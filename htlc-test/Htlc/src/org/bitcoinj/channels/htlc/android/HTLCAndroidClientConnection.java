package org.bitcoinj.channels.htlc.android;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.NioClient;
import org.bitcoinj.net.ProtobufParser;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class HTLCAndroidClientConnection extends Thread {
	
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCAndroidClientConnection.class);

	private HTLCAndroidClient channelClient;
	private ProtobufParser<Protos.TwoWayChannelMessage> wireParser;
	
	private final SettableFuture<HTLCAndroidClientConnection> channelOpenFuture = 
		SettableFuture.create();
	
	private final InetSocketAddress server;
	private final Integer timeoutSeconds;
	private final Wallet wallet;
	private final TransactionBroadcastScheduler broadcastScheduler;
	private final ECKey key;
	private final Coin minPayment;
	private final AppConnection appConn;
	
	public HTLCAndroidClientConnection(
		InetSocketAddress server,
		int timeoutSeconds,
		Wallet wallet,
		TransactionBroadcastScheduler broadcastScheduler,
		ECKey key,
		Coin minPayment,
		AppConnection appConn
	)  {
		this.server = server;
		this.timeoutSeconds = timeoutSeconds;
		this.wallet = wallet;
		this.broadcastScheduler = broadcastScheduler;
		this.key = key;
		this.minPayment = minPayment;
		this.appConn = appConn;
	}
	
	public interface AppConnection {
		List<String> getDataFromSensor(String sensorType);
	}
	
	@Override 
	public void run() {
		/*
    	 * Glue the object which vends/ingests protobuf messages in order 
    	 * to manage state to the network object which
    	 * reads/writes them to the wire in length prefixed form.
    	 */
    	channelClient = new HTLCAndroidClient(
			wallet,
			broadcastScheduler,
			key,
			minPayment,
			new HTLCAndroidClient.ClientConnection() {
				
				@Override
				public List<String> getDataFromSensor(String sensorType) {
					return appConn.getDataFromSensor(sensorType);
				}
				
				@Override
	            public void sendToHub(Protos.TwoWayChannelMessage msg) {
	                wireParser.write(msg);
	            }
				
				@Override
	            public void destroyConnection(
            		PaymentChannelCloseException.CloseReason reason
        		) {
	                channelOpenFuture.setException(
                		new PaymentChannelCloseException("" +
            				"Hub requested he connection be closed: " 
    						+ reason, reason
        				)
            		);
	                wireParser.closeConnection();
	            }

				@Override
				public void connectionOpen(Sha256Hash hash) {
					log.info("Connection opened {}", hash);
					channelOpenFuture.set(HTLCAndroidClientConnection.this);
				}

				@Override
				public void connectionClosed() {
					log.info("Connection closed");					
				}

				@Override
				public void paymentIncrease(Coin from, Coin to) {
					log.info("Client {} increased payment by {}", to.toString());
					
				}
			}
		);
    	
    	log.info("timeoutSeconds*1000: {}", timeoutSeconds*1000);
    	
    	 // And glue back in the opposite direction - network to the channelClient.
        wireParser = new ProtobufParser<Protos.TwoWayChannelMessage>(
    		new ProtobufParser.Listener<Protos.TwoWayChannelMessage>() {
	            @Override
	            public void messageReceived(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler, 
            		Protos.TwoWayChannelMessage msg
        		) {
                	log.info("Message received from server");
                    channelClient.receiveMessage(msg);
	            }

	            @Override
	            public void connectionOpen(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler
        		) {
	            	log.info("Connection opened");
	            }

	            @Override
	            public void connectionClosed(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler
        		) {
	                channelOpenFuture.setException(
                		new PaymentChannelCloseException(
            				"The TCP socket died",
	                        PaymentChannelCloseException.CloseReason.CONNECTION_CLOSED
                        )
            		);
	            }
    		}, 
    		Protos.TwoWayChannelMessage.getDefaultInstance(), 
    		Short.MAX_VALUE, 
    		timeoutSeconds*1000
		);

        // Initiate the outbound network connection. We don't need to keep this 
        // around. The wireParser object will handle things from here on out.
        try {
			new NioClient(server, wireParser, timeoutSeconds * 1000);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public ListenableFuture<HTLCAndroidClientConnection> getChannelOpenFuture() {
		return channelOpenFuture;
	}
	
	public void updateSensors(List<String> sensors, List<Long> prices) {
		channelClient.updateSensors(sensors, prices);
	}
}
