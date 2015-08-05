package org.bitcoinj.channels.htlc.hub;


import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoinj.channels.htlc.FlowResponse;
import org.bitcoinj.channels.htlc.PriceInfo;
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
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.bitcoinj.protocols.channels.StoredPaymentChannelServerStates;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;

/**
 * Implements a listening TCP server that can accept connections from payment 
 * channel clients, and invokes the provided event listeners when new channels 
 * are opened or payments arrive. This is the highest level class in the payment
 * channels API. Internally, sends protobuf messages to/from a newly created 
 * {@link HTLCHubAndroidServer}.
 */
public class HTLCHubServerListener {

	private static final Logger log = 
		LoggerFactory.getLogger(HTLCHubServerListener.class);
	protected final ReentrantLock lock = 
		Threading.lock("HTLCHubServerListener");
	
	private final Wallet wallet;
	private final Coin value;
	private final long timeWindow;
	private final ECKey primaryKey;
	private final ECKey secondaryKey;
	private final ECKey receivingKey;
	private final TransactionBroadcastScheduler broadcaster;
	
	// The event handler factory which creates new 
	// ServerConnectionEventHandler per connection
    private final AndroidHandlerFactory androidEventHandlerFactory;
    private final BuyerHandlerFactory buyerEventHandlerFactory;
    private final Coin minAcceptedChannelSize;

    private NioServer androidServer;
    private NioServer buyerServer;
    private final int timeoutSeconds;
    
    private final List<HTLCHubBuyerServer> buyers;
    private final Map<HTLCHubAndroidServer, List<PriceInfo>> deviceMap;
    private final Map<String, List<AndroidData>> sensorToDeviceMap;
    private final Map<HTLCHubBuyerServer, RequestResponse> responseMap;
    
    class AndroidData {
    	private List<PriceInfo> priceInfoList;
    	private HTLCHubAndroidServer server;
    	
    	public List<PriceInfo> getPriceInfoList() {
    		return priceInfoList;
    	}
    }
    
    class RequestResponse {
    	private List<FlowResponse> responseList;
    	private final Integer counter;
    	
    	RequestResponse(Integer counter) {
    		this.counter = counter;
    	}
    	
    	public void addResponse(FlowResponse response) {
    		responseList.add(response);
    	}
    	
    	public boolean isReady() {
    		return responseList.size() == counter;
    	}
    }
	
    /**
     * A factory which generates connection-specific event handlers.
     */
    public static interface AndroidHandlerFactory {
        /**
         * Called when a new connection completes version handshake to 
         * get a new connection-specific listener.
         * If null is returned, the connection is immediately closed.
         */
        @Nullable public ServerConnectionEventHandler onNewConnection(
    		SocketAddress clientAddress
		);
    }
    
    public static interface BuyerHandlerFactory {
    	@Nullable public ServerConnectionEventHandler onNewConnection(
    		SocketAddress clientAddress 
		);
    }
    
    private class BuyerServerHandler {
    	public BuyerServerHandler(
    		final SocketAddress address,
    		final int timeoutSeconds
		) {
    		paymentChannelManager = new HTLCHubBuyerServer(
				broadcaster,
				wallet,
				receivingKey,
				minAcceptedChannelSize,
				new HTLCHubBuyerServer.ServerConnection() {
					
					@Override
					public void sendToBuyer(TwoWayChannelMessage msg) {
						socketProtobufHandler.write(msg);
					}
					
					@Override
					@Nullable
					public ListenableFuture<ByteString> paymentIncrease(
						Coin by, 
						Coin to,
						@Nullable ByteString info
					) {
						return eventHandler.paymentIncrease(by, to, info);	
					}
					
					@Override
					public void destroyConnection(CloseReason reason) {
						if (reason != null) {						
							closeReason = reason;
						}
					}
					
					@Override
					public void channelOpen(Sha256Hash contractHash) {
						socketProtobufHandler.setSocketTimeout(0);
						eventHandler.channelOpen(contractHash);
					}

					@Override
					public List<String> nodeStats() {
						List<String> nodes = new ArrayList<String>();
						nodes.add("device1");
						nodes.add("device2");
						return nodes;
					}

					@Override
					public Set<String> sensorStats() {
						return sensorToDeviceMap.keySet();
					}

					@Override
					public void select(
						String id, 
						String sensorType, 
						HTLCHubBuyerServer buyerServer
					) {
						lock.lock();
						try {
							List<AndroidData> androidDataList = 
								sensorToDeviceMap.get(sensorType);
							List<Long> priceList = new ArrayList<Long>();
							List<String> sensorList = new ArrayList<String>();
							
							for (AndroidData data: androidDataList) {
								List<PriceInfo> priceInfoList = 
									data.getPriceInfoList();
								for (PriceInfo priceInfo: priceInfoList) {
									priceList.add(priceInfo.getPrice());
									sensorList.add(priceInfo.getSensor());
								}
							}
							buyerServer.sendSelectResult(
								id,
								sensorList,
								priceList
							);			
						} finally {
							lock.unlock();
						}
					}
				}
			);
    		
    		protobufHandlerListener = 
				new ProtobufParser.Listener<Protos.TwoWayChannelMessage>() {
					@Override
					public void connectionClosed(
						ProtobufParser<TwoWayChannelMessage> handler
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
					public void connectionOpen(
							ProtobufParser<TwoWayChannelMessage> handler
					) {
						log.info("New buyer connection open!");
						ServerConnectionEventHandler eventHandler = 
                    		buyerEventHandlerFactory.onNewConnection(address);
						if (eventHandler == null) {
	                        handler.closeConnection();
	                    } else {
	                    	BuyerServerHandler.this.eventHandler = eventHandler;
	                        paymentChannelManager.connectionOpen();
	                    }
					}

					@Override
					public void messageReceived(
						ProtobufParser<TwoWayChannelMessage> handler,
						TwoWayChannelMessage msg
					) {	
						paymentChannelManager.receiveMessage(msg);
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
        private final HTLCHubBuyerServer paymentChannelManager;

        // The connection handler which puts/gets protobufs from the TCP socket
        private final ProtobufParser<Protos.TwoWayChannelMessage> 
        	socketProtobufHandler;

        // The listener which connects to socketProtobufHandler
        private final ProtobufParser.Listener<Protos.TwoWayChannelMessage> 
        	protobufHandlerListener;
    }

    private class AndroidServerHandler {
        public AndroidServerHandler(
    		final SocketAddress address, 
    		final int timeoutSeconds
		) {
            paymentChannelManager = new HTLCHubAndroidServer(
        		broadcaster, 
        		wallet,
        		primaryKey,
        		secondaryKey,
        		value,
        		timeWindow,
        		new HTLCHubAndroidServer.ServerConnection() {
        			@Override public void sendToDevice(
    					Protos.TwoWayChannelMessage msg
					) {
    					socketProtobufHandler.write(msg);
    				}
        			
        			@Override public void registerSensors(
    					List<String> sensors,
    					List<Integer> prices
					) {
        				HTLCHubServerListener.this.registerSensors(
        					AndroidServerHandler.this.paymentChannelManager, 
    						sensors,
    						prices
						);
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
	                	log.info("New android connection open!");
	                    ServerConnectionEventHandler eventHandler = 
                    		androidEventHandlerFactory.onNewConnection(address);
	                    if (eventHandler == null) {
	                        handler.closeConnection();
	                    } else {
	                        AndroidServerHandler.this.eventHandler = eventHandler;
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
        private final HTLCHubAndroidServer paymentChannelManager;

        // The connection handler which puts/gets protobufs from the TCP socket
        private final ProtobufParser<Protos.TwoWayChannelMessage> 
        	socketProtobufHandler;

        // The listener which connects to socketProtobufHandler
        private final ProtobufParser.Listener<Protos.TwoWayChannelMessage> 
        	protobufHandlerListener;
    }
    
    public HTLCHubServerListener(
		TransactionBroadcastScheduler broadcaster,
		Wallet wallet,
		ECKey primaryKey,
		ECKey secondaryKey,
		ECKey receiveingKey,
		Coin value,
		long timeWindow,
		ECKey serverKey,
		final int timeoutSeconds,
		Coin minAcceptedChannelSize,
		AndroidHandlerFactory androidHandlerFactory,
		BuyerHandlerFactory buyerHandlerFactory
	) {
    	this.broadcaster = broadcaster;
    	this.wallet = wallet;
    	this.value = value;
    	this.timeWindow = timeWindow;
    	this.primaryKey = primaryKey;
    	this.secondaryKey = secondaryKey;
    	this.receivingKey = receiveingKey;
    	this.minAcceptedChannelSize = minAcceptedChannelSize;
    	this.androidEventHandlerFactory = androidHandlerFactory;
    	this.buyerEventHandlerFactory = buyerHandlerFactory;
    	this.timeoutSeconds = timeoutSeconds;
    	this.buyers = new ArrayList<HTLCHubBuyerServer>();
    	this.deviceMap = new HashMap<HTLCHubAndroidServer, List<PriceInfo>>();
    	this.responseMap = new HashMap<HTLCHubBuyerServer, RequestResponse>();
    	this.sensorToDeviceMap = new HashMap<String, List<AndroidData>>();
    }

    /**
     * Binds to the given port and starts accepting new client connections.
     * @throws Exception If binding to the given port fails (eg SocketException: 
     * Permission denied for privileged ports)
     */
    public void bindAndStart(int buyerPort, int androidPort) throws Exception {
    	buyerServer = new NioServer(
			new StreamParserFactory() {
				@Override
				public ProtobufParser<Protos.TwoWayChannelMessage> getNewParser(
					InetAddress inetAddress,
					int port
				) {
					BuyerServerHandler handler = new BuyerServerHandler(
						new InetSocketAddress(inetAddress, port),
						timeoutSeconds
					);
					buyers.add(handler.paymentChannelManager);
					return handler.socketProtobufHandler;
				}
			},
			new InetSocketAddress(buyerPort)
		);
    	
        androidServer = new NioServer(
    		new StreamParserFactory() {
	            @Override
	            public ProtobufParser<Protos.TwoWayChannelMessage> getNewParser(
	        		InetAddress inetAddress, 
	        		int port
	    		) {
	                return new AndroidServerHandler(
                		new InetSocketAddress(inetAddress, port), 
                		timeoutSeconds
            		).socketProtobufHandler;
	            }
        	}, 
        	new InetSocketAddress(androidPort)
    	);
        androidServer.startAsync();
        androidServer.awaitRunning();
        
        buyerServer.startAsync();
        buyerServer.awaitRunning();
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
        androidServer.stopAsync();
        androidServer.awaitTerminated();
        buyerServer.stopAsync();
        buyerServer.awaitTerminated();
    }
    
    private void registerSensors(
		HTLCHubAndroidServer server, 
		List<String> sensors,
		List<Integer> prices
	) {
    	lock.lock();
    	try {
    		List<PriceInfo> currentSensors = deviceMap.get(sensors);
    		if (currentSensors == null) {
    			log.info("Hit null get on deviceMap"); 
    			return;
    		}
    		List<PriceInfo> updatedSensors = new ArrayList<PriceInfo>();
    		for (int i = 0; i < sensors.size(); i++) {
    			updatedSensors.add(new PriceInfo(sensors.get(i), prices.get(i)));
    		}
    		deviceMap.put(server, updatedSensors);
    	} finally {
    		lock.unlock();
    	}
    }
}
