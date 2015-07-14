package org.bitcoinj.channels.htlc.hub;


import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import javax.annotation.Nullable;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.NioServer;
import org.bitcoinj.net.ProtobufParser;
import org.bitcoinj.net.StreamParserFactory;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.bitcoinj.protocols.channels.StoredPaymentChannelServerStates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements a listening TCP server that can accept connections from payment 
 * channel clients, and invokes the provided event listeners when new channels 
 * are opened or payments arrive. This is the highest level class in the payment
 * channels API. Internally, sends protobuf messages to/from a newly created 
 * {@link HTLCHubInnerServer}.
 */
public class HTLCHubInnerServerListener {

	private static final Logger log = 
			LoggerFactory.getLogger(HTLCHubInnerServerListener.class);
	private final Wallet wallet;
	private final Coin value;
	private final long timeWindow;
	private final ECKey primaryKey;
	private final ECKey secondaryKey;
	private final TransactionBroadcastScheduler broadcaster;
	
	// The event handler factory which creates new 
	// ServerConnectionEventHandler per connection
    private final HandlerFactory eventHandlerFactory;
    private final Coin minAcceptedChannelSize;

    private NioServer server;
    private final int timeoutSeconds;
	
    /**
     * A factory which generates connection-specific event handlers.
     */
    public static interface HandlerFactory {
        /**
         * Called when a new connection completes version handshake to 
         * get a new connection-specific listener.
         * If null is returned, the connection is immediately closed.
         */
        @Nullable public ServerConnectionEventHandler onNewConnection(
    		SocketAddress clientAddress
		);
    }

    private class ServerHandler {
        public ServerHandler(
    		final SocketAddress address, 
    		final int timeoutSeconds
		) {
            paymentChannelManager = new HTLCHubInnerServer(
        		broadcaster, 
        		wallet,
        		primaryKey,
        		secondaryKey,
        		value,
        		timeWindow,
        		new HTLCHubInnerServer.ServerConnection() {
        			@Override public void sendToDevice(
    					Protos.TwoWayChannelMessage msg
					) {
    					socketProtobufHandler.write(msg);
    				}

        			@Override public void destroyConnection(
    					PaymentChannelCloseException.CloseReason reason
					) {
        				if (closeReason != null) {
        					closeReason = reason;
        				}
        				socketProtobufHandler.closeConnection();
        			}

	                @Override public void channelOpen(Sha256Hash contractHash) {
	                    socketProtobufHandler.setSocketTimeout(0);
	                    eventHandler.channelOpen(contractHash);
	                }
	                
	                @Override
					public boolean acceptExpireTime(long expireTime) {
						// One extra minute to compensate for time skew and latency
						return expireTime <= (
							timeWindow + Utils.currentTimeSeconds() + 60
						);  
					}
	            });

            protobufHandlerListener = 
        		new ProtobufParser.Listener<Protos.TwoWayChannelMessage>() {
            		@Override
            		public synchronized void messageReceived(
        				ProtobufParser<Protos.TwoWayChannelMessage> handler, 
        				Protos.TwoWayChannelMessage msg
    				) {
            			paymentChannelManager.receiveMessage(msg);
            		}

	                @Override
	                public synchronized void connectionClosed(
                		ProtobufParser<Protos.TwoWayChannelMessage> handler
            		) {
	                    paymentChannelManager.connectionClosed();
	                    if (closeReason != null) {
	                        eventHandler.channelClosed(closeReason);
	                    } else {
	                        eventHandler.channelClosed(
                        		PaymentChannelCloseException.CloseReason
                        			.CONNECTION_CLOSED
                			);
	                    }
	                }

	                @Override
	                public synchronized void connectionOpen(
                		ProtobufParser<Protos.TwoWayChannelMessage> handler
            		) {
	                    ServerConnectionEventHandler eventHandler = 
                    		eventHandlerFactory.onNewConnection(address);
	                    if (eventHandler == null) {
	                        handler.closeConnection();
	                    } else {
	                        ServerHandler.this.eventHandler = eventHandler;
	                        paymentChannelManager.connectionOpen();
	                    }
	                }
        	};

            socketProtobufHandler = 
        		new ProtobufParser<Protos.TwoWayChannelMessage>(
    				protobufHandlerListener, 
    				Protos.TwoWayChannelMessage.getDefaultInstance(), 
    				Short.MAX_VALUE, 
    				timeoutSeconds*1000
        	);
        }
        
        private PaymentChannelCloseException.CloseReason closeReason;

        // The user-provided event handler
        private ServerConnectionEventHandler eventHandler;

        // The payment channel server which does the actual payment channel handling
        private final HTLCHubInnerServer paymentChannelManager;

        // The connection handler which puts/gets protobufs from the TCP socket
        private final ProtobufParser<Protos.TwoWayChannelMessage> 
        	socketProtobufHandler;

        // The listener which connects to socketProtobufHandler
        private final ProtobufParser.Listener<Protos.TwoWayChannelMessage> 
        	protobufHandlerListener;
    }
    
    public HTLCHubInnerServerListener(
		TransactionBroadcastScheduler broadcaster,
		Wallet wallet,
		ECKey primaryKey,
		ECKey secondaryKey,
		Coin value,
		long timeWindow,
		ECKey serverKey,
		final int timeoutSeconds,
		Coin minAcceptedChannelSize,
		HandlerFactory eventHandlerFactory
	) {
    	this.broadcaster = broadcaster;
    	this.wallet = wallet;
    	this.value = value;
    	this.timeWindow = timeWindow;
    	this.primaryKey = primaryKey;
    	this.secondaryKey = secondaryKey;
    	this.minAcceptedChannelSize = minAcceptedChannelSize;
    	this.eventHandlerFactory = eventHandlerFactory;
    	this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Binds to the given port and starts accepting new client connections.
     * @throws Exception If binding to the given port fails (eg SocketException: 
     * Permission denied for privileged ports)
     */
    public void bindAndStart(int port) throws Exception {
        server = new NioServer(
    		new StreamParserFactory() {
	            @Override
	            public ProtobufParser<Protos.TwoWayChannelMessage> getNewParser(
	        		InetAddress inetAddress, 
	        		int port
	    		) {
	                return new ServerHandler(
                		new InetSocketAddress(inetAddress, port), 
                		timeoutSeconds
            		).socketProtobufHandler;
	            }
        	}, 
        	new InetSocketAddress(port)
    	);
        server.startAsync();
        server.awaitRunning();
    }
    
    /**
     * <p>Closes all client connections currently connected gracefully.</p>
     *
     * <p>Note that this does <i>not</i> settle the actual payment channels 
     * (and broadcast payment transactions), which
     * must be done using the {@link StoredPaymentChannelServerStates}
     * which manages the states for the associated wallet.</p>
     */
    public void close() {
        server.stopAsync();
        server.awaitTerminated();
    }
}
