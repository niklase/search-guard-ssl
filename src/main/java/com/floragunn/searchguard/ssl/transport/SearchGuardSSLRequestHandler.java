/*
 * Copyright 2015 floragunn UG (haftungsbeschränkt)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.ssl.transport;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLPeerUnverifiedException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.DelegatingTransportChannel;
import org.elasticsearch.transport.TcpTransportChannel;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;

import com.floragunn.searchguard.ssl.util.ExceptionUtils;
import com.floragunn.searchguard.ssl.util.SSLRequestHelper;

public class SearchGuardSSLRequestHandler<T extends TransportRequest>
implements TransportRequestHandler<T> {
    
    private final String action;
    private final TransportRequestHandler<T> actualHandler;
    private final ThreadPool threadPool;
    protected final Logger log = LogManager.getLogger(this.getClass());
    private final PrincipalExtractor principalExtractor;

    public SearchGuardSSLRequestHandler(String action, TransportRequestHandler<T> actualHandler, ThreadPool threadPool, final PrincipalExtractor principalExtractor) {
        super();
        this.action = action;
        this.actualHandler = actualHandler;
        this.threadPool = threadPool;
        this.principalExtractor = principalExtractor;
    }
    
    protected ThreadContext getThreadContext() {
        if(threadPool == null) {
            return null;
        }
        
        return threadPool.getThreadContext();
    }

    @Override
    public final void messageReceived(T request, TransportChannel channel) throws Exception {
        messageReceived(request, channel, null);
    }

    @Override
    public final void messageReceived(T request, TransportChannel channel, Task task) throws Exception {
        ThreadContext threadContext = getThreadContext() ;
      
        if(SSLRequestHelper.containsBadHeader(threadContext, "_sg_ssl_")) {
            final Exception exception = ExceptionUtils.createBadHeaderException();
            channel.sendResponse(exception);
            throw exception;
        }
        
        if("netty3".equals(channel.getChannelType()) || "netty4".equals(channel.getChannelType())) {
            final Exception exception = new ElasticsearchException(channel.getChannelType()+" not supported");
            channel.sendResponse(exception);
            throw exception;
        }
        
        if (!"netty".equals(channel.getChannelType())) { //netty4
            messageReceivedDecorate(request, actualHandler, channel, task);
            return;
        }
        
        try {
            
            Channel nettyChannel = null;
            
            if(channel instanceof DelegatingTransportChannel) {
                nettyChannel = (Channel) ((TcpTransportChannel<?>)((DelegatingTransportChannel) channel).getChannel()).getChannel();
            } else if (channel instanceof TcpTransportChannel) {
                nettyChannel = (Channel) ((TcpTransportChannel<?>) channel).getChannel();
            } else {
                throw new Exception("Invalid channel of type "+channel.getClass());
            }
            
            final SslHandler sslhandler = (SslHandler) nettyChannel.pipeline().get("ssl_server");

            if (sslhandler == null) {
                final String msg = "No ssl handler found (SG 11)";
                //log.error(msg);
                final Exception exception = new ElasticsearchException(msg);
                channel.sendResponse(exception);
                throw exception;
            }


            final Certificate[] peerCerts = sslhandler.engine().getSession().getPeerCertificates();
            final Certificate[] localCerts = sslhandler.engine().getSession().getLocalCertificates();
            
            if (peerCerts != null 
                    && peerCerts.length > 0 
                    && peerCerts[0] instanceof X509Certificate 
                    && localCerts != null && localCerts.length > 0 
                    && localCerts[0] instanceof X509Certificate) {
                final X509Certificate[] x509PeerCerts = Arrays.copyOf(peerCerts, peerCerts.length, X509Certificate[].class);
                final X509Certificate[] x509LocalCerts = Arrays.copyOf(localCerts, localCerts.length, X509Certificate[].class);
                final String principal = principalExtractor==null?null:principalExtractor.extractPrincipal(x509PeerCerts[0], PrincipalExtractor.Type.TRANSPORT);
                addAdditionalContextValues(action, request, x509LocalCerts, x509PeerCerts, principal);
                if(threadContext != null) {
                    //in the case of ssl plugin only: threadContext and principalExtractor are null
                    threadContext.putTransient("_sg_ssl_transport_principal", principal);
                    threadContext.putTransient("_sg_ssl_transport_peer_certificates", x509PeerCerts);
                    threadContext.putTransient("_sg_ssl_transport_local_certificates", x509LocalCerts);
                    threadContext.putTransient("_sg_ssl_transport_protocol", sslhandler.engine().getSession().getProtocol());
                    threadContext.putTransient("_sg_ssl_transport_cipher", sslhandler.engine().getSession().getCipherSuite());
                }
                messageReceivedDecorate(request, actualHandler, channel, task);
            } else {
                final String msg = "No X509 transport client certificates found (SG 12)";
                //log.error(msg);
                final Exception exception = new ElasticsearchException(msg);
                errorThrown(exception, request, action);
                channel.sendResponse(exception);
                throw exception;
            }

        } catch (final SSLPeerUnverifiedException e) {
            errorThrown(e, request, action);
            final Exception exception = ExceptionsHelper.convertToElastic(e);
            channel.sendResponse(exception);
            throw exception;
        } catch (final Exception e) {
            errorThrown(e, request, action);
            throw e;
        }
        
    }
    
    protected void addAdditionalContextValues(final String action, final TransportRequest request, final X509Certificate[] localCerts, final X509Certificate[] peerCerts, final String principal)
            throws Exception {
        // no-op
    }
    
    protected void messageReceivedDecorate(final T request, final TransportRequestHandler<T> actualHandler, final TransportChannel transportChannel, Task task) throws Exception {
        actualHandler.messageReceived(request, transportChannel, task);
    }
    
    protected void errorThrown(Throwable t, final TransportRequest request, String action) {
        // no-op
    }
}
