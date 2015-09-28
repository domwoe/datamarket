package org.bitcoinj.channels.htlc.hub;


import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoinj.channels.htlc.FlowResponse;
import org.bitcoinj.channels.htlc.PriceInfo;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.NioServer;
import org.bitcoinj.net.ProtobufConnection;
import org.bitcoinj.net.StreamConnectionFactory;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.bitcoinj.protocols.channels.StoredPaymentChannelServerStates;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.pqc.math.ntru.polynomial.SparseTernaryPolynomial;

import com.google.common.collect.Lists;
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
	protected final ReentrantLock lock = new ReentrantLock();
	
	private final Wallet wallet;
	private final Coin value;
	private final long timeWindow;
	private final ECKey primaryKey;
	private final ECKey secondaryKey;
	private final ECKey receivingKey;
	private final TransactionBroadcastScheduler broadcaster;
	
	private Integer counter = 1;
	private Transaction transaction;
	
	// The event handler factory which creates new 
	// ServerConnectionEventHandler per connection
    private final AndroidHandlerFactory androidEventHandlerFactory;
    private final BuyerHandlerFactory buyerEventHandlerFactory;
    private final Coin minAcceptedChannelSize;

    private NioServer androidServer;
    private NioServer buyerServer;
    private final int timeoutSeconds;
    
    private final Map<HTLCHubAndroidServer, List<PriceInfo>> deviceMap;
    
    private final HTLCHubMessageFilter messageFilter;
    
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
						lock.lock();
						try {
							List<String> nodes = new ArrayList<String>();
							for (
								Map.Entry<HTLCHubAndroidServer, List<PriceInfo>> 
									entry: deviceMap.entrySet()
							) {
								nodes.add(entry.getKey().getDeviceId());
							}
							return nodes;
						} finally {
							lock.unlock();
						}
					}

					@Override
					public Set<String> sensorStats() {
						Set<String> sensors = new HashSet<String>();
						for (
							Map.Entry<HTLCHubAndroidServer, List<PriceInfo>> 
								entry: deviceMap.entrySet()
						) {
							for (PriceInfo price: entry.getValue()) {
								sensors.add(price.getSensor());
							}
						}
						return sensors;
					}

					@Override
					public void select(
						String id, 
						String sensorType, 
						HTLCHubBuyerServer buyerServer
					) {
						lock.lock();
						try {
							log.info("SensorType selected: {}", sensorType);
							List<String> deviceIdList = new ArrayList<String>();
							List<String> sensorList = new ArrayList<String>();
							List<Long> priceList = new ArrayList<Long>();
							for (
								Map.Entry<HTLCHubAndroidServer, List<PriceInfo>> 
									entry: deviceMap.entrySet() 
							) {
								List<PriceInfo> priceInfoList = entry.getValue();
								for (PriceInfo price: priceInfoList) {
									if (price.getSensor().equals(sensorType)) {
										log.info("Sensor back: {} {}", price.getSensor(), price.getPrice());
										deviceIdList.add(price.getDeviceId());
										sensorList.add(price.getSensor());
    									priceList.add(price.getPrice());
									}
								}
							}
							
							buyerServer.sendSelectResult(
								id, 
								deviceIdList, 
								sensorList, 
								priceList
							);
						} finally {
							lock.unlock();
						}
					}

					@Override
					public void forwardToAndroidServers(
						HTLCHubBuyerServer fromBuyerServer,
						TwoWayChannelMessage msg
					) {
						lock.lock();
						try {
							messageFilter.putAndroidMsg(fromBuyerServer, msg);
						} finally {
							lock.unlock();
						}
					}

					@Override
					public void addAddress(Address addr) {
						lock.lock();
						try {
							log.error("COUNTER: {}", counter);
					//		if (counter > 0) {
								transaction.addOutput(Coin.valueOf(100, 0), addr);
								counter--;
				//			} else {
								Wallet.SendRequest req = Wallet.SendRequest.forTx(transaction);
								wallet.completeTx(req);
								log.warn("Broadcasting spread tx {}", req.tx);
								broadcaster.broadcastTransaction(req.tx);
					//		}							
						} catch (InsufficientMoneyException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} finally {
							lock.unlock();
						}
					}
				}
			);
    		
    		protobufHandlerListener = 
				new ProtobufConnection.Listener<Protos.TwoWayChannelMessage>() {
					@Override
					public void connectionClosed(
						ProtobufConnection<TwoWayChannelMessage> handler
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
						ProtobufConnection<TwoWayChannelMessage> handler
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
						ProtobufConnection<TwoWayChannelMessage> handler,
						TwoWayChannelMessage msg
					) {	
						paymentChannelManager.receiveMessage(msg);
					}
    			
				};
				
			log.info("timeoutSeconds*1000: {}", timeoutSeconds*1000);
    		
			socketProtobufHandler = 
				new ProtobufConnection<Protos.TwoWayChannelMessage>(
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
        private final ProtobufConnection<Protos.TwoWayChannelMessage> 
        	socketProtobufHandler;

        // The listener which connects to socketProtobufHandler
        private final ProtobufConnection.Listener<Protos.TwoWayChannelMessage> 
        	protobufHandlerListener;
    }

    private class AndroidServerHandler {
        public AndroidServerHandler(
        	final String deviceId,
    		final SocketAddress address, 
    		final int timeoutSeconds
		) {
            paymentChannelManager = new HTLCHubAndroidServer(
            	deviceId,
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
    					List<Long> prices
					) {
        				log.info("Registered sensors: {}", Arrays.toString(sensors.toArray()));
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

					@Override
					public void forwardToBuyerServers(
						HTLCHubAndroidServer fromAndroidServer,
						TwoWayChannelMessage msg
					) {
						lock.lock();
						try {
							messageFilter.putBuyerMsg(fromAndroidServer, msg);
						} finally {
							lock.unlock();
						}
					}

					@Override
					public void unregisterDevice(HTLCHubAndroidServer server) {
						messageFilter.unregisterDevice(server.getDeviceId());
						lock.lock();
						try {
							deviceMap.remove(server);
						} finally { 
							lock.unlock();
						}
					}
	            });

            protobufHandlerListener = 
        		new ProtobufConnection.Listener<Protos.TwoWayChannelMessage>() {
            		@Override
            		public synchronized void messageReceived(
        				ProtobufConnection<Protos.TwoWayChannelMessage> handler, 
        				Protos.TwoWayChannelMessage msg
    				) {
            			paymentChannelManager.receiveMessage(msg);
            		}

	                @Override
	                public synchronized void connectionClosed(
                		ProtobufConnection<Protos.TwoWayChannelMessage> handler
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
                		ProtobufConnection<Protos.TwoWayChannelMessage> handler
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
        	
        	log.info("timeoutSeconds*1000: {}", timeoutSeconds*1000);

            socketProtobufHandler = 
        		new ProtobufConnection<Protos.TwoWayChannelMessage>(
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
        private final ProtobufConnection<Protos.TwoWayChannelMessage> 
        	socketProtobufHandler;

        // The listener which connects to socketProtobufHandler
        private final ProtobufConnection.Listener<Protos.TwoWayChannelMessage> 
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
		int timeoutSeconds,
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
    	this.deviceMap = new HashMap<HTLCHubAndroidServer, List<PriceInfo>>();
    	this.messageFilter = new HTLCHubMessageFilter();
    	this.transaction = new Transaction(wallet.getParams());
    }

    /**
     * Binds to the given port and starts accepting new client connections.
     * @throws Exception If binding to the given port fails (eg SocketException: 
     * Permission denied for privileged ports)
     */
    public void bindAndStart(int buyerPort, int androidPort) throws Exception {
    	buyerServer = new NioServer(
			new StreamConnectionFactory() {
				@Override
				public ProtobufConnection<Protos.TwoWayChannelMessage> getNewConnection(
					InetAddress inetAddress,
					int port
				) {
					return new BuyerServerHandler(
						new InetSocketAddress(inetAddress, port),
						timeoutSeconds
					).socketProtobufHandler;
				}
			},
			new InetSocketAddress(buyerPort)
		);
    	
        androidServer = new NioServer(
    		new StreamConnectionFactory() {
	            @Override
	            public ProtobufConnection<Protos.TwoWayChannelMessage> getNewConnection(
	        		InetAddress inetAddress, 
	        		int port
	    		) {
	            	String deviceId = UUID.randomUUID().toString();
	            	// TODO: HERE WE HARDCODE THE DEVICE ID FOR TESTING. REMOVE
	            	deviceId = new String("SAMSUNG");
	            	log.info("Launched new AndroidServer with id {}", deviceId);
	            	AndroidServerHandler handler = new AndroidServerHandler(
	            		deviceId,
                		new InetSocketAddress(inetAddress, port), 
                		timeoutSeconds
            		);
	            	messageFilter.registerDevice(
            			deviceId, 
            			handler.paymentChannelManager
        			);
	                return handler.socketProtobufHandler;
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
		List<Long> prices
	) {
    	lock.lock();
    	try {
    		List<PriceInfo> updatedSensors = new ArrayList<PriceInfo>();
    		for (int i = 0; i < sensors.size(); i++) {
    			PriceInfo priceInfo = new PriceInfo(
					server.getDeviceId(), 
					sensors.get(i), 
					prices.get(i)
				);
    			// Update map
    			updatedSensors.add(priceInfo);
    		}
    		deviceMap.put(server, updatedSensors);
    		log.info("Received new update on sensors");
    	} finally {
    		lock.unlock();
    	}
    }
}
