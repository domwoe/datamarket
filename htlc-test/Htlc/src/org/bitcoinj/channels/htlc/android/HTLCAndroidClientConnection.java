package org.bitcoinj.channels.htlc.android;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.NioClient;
import org.bitcoinj.net.ProtobufParser;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTLCAndroidClientConnection {
	
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCAndroidClientConnection.class);

	private final HTLCAndroidClient channelClient;
	private final ProtobufParser<Protos.TwoWayChannelMessage> wireParser;
	
	public HTLCAndroidClientConnection(
		InetSocketAddress server,
		int timeoutSeconds,
		Wallet wallet,
		TransactionBroadcastScheduler broadcastScheduler,
		ECKey key,
		Coin minPayment
	) throws IOException, ValueOutOfRangeException {
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
	            public void sendToHub(Protos.TwoWayChannelMessage msg) {
	                wireParser.write(msg);
	            }
				
				@Override
	            public void destroyConnection(
            		PaymentChannelCloseException.CloseReason reason
        		) {
					// TODO
	            /*    channelOpenFuture.setException(
                		new PaymentChannelCloseException("" +
            				"Payment channel client requested that the " +
            				"connection be closed: " + reason, reason
        				)
            		);*/
	                wireParser.closeConnection();
	            }

				@Override
				public void connectionOpen(Sha256Hash hash) {
					log.info("Connection opened {}", hash);
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
    	 // And glue back in the opposite direction - network to the channelClient.
        wireParser = new ProtobufParser<Protos.TwoWayChannelMessage>(
    		new ProtobufParser.Listener<Protos.TwoWayChannelMessage>() {
	            @Override
	            public void messageReceived(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler, 
            		Protos.TwoWayChannelMessage msg
        		) {
	             //   try {
	                	System.out.println("Message received from server");
	                    channelClient.receiveMessage(msg);
	               // } catch (InsufficientMoneyException e) {
	                    // We should only get this exception during INITIATE, 
	                	// so channelOpen wasn't called yet.
// TODO:	                    channelOpenFuture.setException(e);
	           //     }
	            }

	            @Override
	            public void connectionOpen(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler
        		) {
	            	log.info("Connection opened");
	            	// TODO:
	             //   channelClient.connectionOpen();
	            }

	            @Override
	            public void connectionClosed(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler
        		) {
	            	// TODO
	             //   channelClient.connectionClosed();
	             /*   channelOpenFuture.setException(
                		new PaymentChannelCloseException(
            				"The TCP socket died",
	                        PaymentChannelCloseException.CloseReason.CONNECTION_CLOSED
                        )
            		);*/
	            }
    		}, 
    		Protos.TwoWayChannelMessage.getDefaultInstance(), 
    		Short.MAX_VALUE, 
    		timeoutSeconds*1000
		);

        // Initiate the outbound network connection. We don't need to keep this 
        // around. The wireParser object will handle things from here on out.
        new NioClient(server, wireParser, timeoutSeconds * 1000);
    }	
}
