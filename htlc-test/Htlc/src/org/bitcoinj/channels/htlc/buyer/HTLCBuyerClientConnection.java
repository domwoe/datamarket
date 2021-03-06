package org.bitcoinj.channels.htlc.buyer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.channels.htlc.FlowResponse;
import org.bitcoinj.channels.htlc.HTLCPaymentReceipt;
import org.bitcoinj.channels.htlc.PriceInfo;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.NioClient;
import org.bitcoinj.net.ProtobufConnection;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;

import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class HTLCBuyerClientConnection {

	private final SettableFuture<HTLCBuyerClientConnection> 
		channelOpenFuture = SettableFuture.create();
	private final HTLCBuyerClient channelClient;
	private final ProtobufConnection<Protos.TwoWayChannelMessage> wireParser;
	
	public HTLCBuyerClientConnection(
		InetSocketAddress server,
		int timeoutSeconds,
		Wallet wallet,
		TransactionBroadcastScheduler broadcastScheduler,
		ECKey clientPrimaryKey,
		ECKey clientSecondaryKey,
		Coin value,
		final long timeWindow
	) throws IOException, ValueOutOfRangeException {
		/*
		 * Glue the object which vends/ingests protobuf messages in order 
		 * to manage state to the network object which
		 * reads/writes them to the wire in length prefixed form.
		 */
		channelClient = new HTLCBuyerClient(
			wallet, 
			broadcastScheduler,
			clientPrimaryKey, 
			clientSecondaryKey,
			value,
			timeWindow,
			new HTLCBuyerClient.ClientConnection() {
				
				@Override
	            public void sendToServer(Protos.TwoWayChannelMessage msg) {
	                wireParser.write(msg);
	            }
				
				@Override
	            public void destroyConnection(
	        		PaymentChannelCloseException.CloseReason reason
	    		) {
	                channelOpenFuture.setException(
	            		new PaymentChannelCloseException("" +
	        				"Payment channel client requested that the " +
	        				"connection be closed: " + reason, reason
	    				)
	        		);
	                wireParser.closeConnection();
	            }
	
				@Override
				public boolean acceptExpireTime(long expireTime) {
					// One extra minute to compensate for time skew and latency
					return expireTime <= (
						timeWindow + Utils.currentTimeSeconds() + 60
					);  
				}
	
				@Override
				public void channelOpen(boolean wasInitiated) {
					wireParser.setSocketTimeout(0);
		            // Inform the API user that we're done and ready to roll.
	                channelOpenFuture.set(
                		HTLCBuyerClientConnection.this
	        		);	
				}
			}
		);
		 // And glue back in the opposite direction - network to the channelClient.
	    wireParser = new ProtobufConnection<Protos.TwoWayChannelMessage>(
			new ProtobufConnection.Listener<Protos.TwoWayChannelMessage>() {
	            @Override
	            public void messageReceived(
            		ProtobufConnection<Protos.TwoWayChannelMessage> handler, 
	        		Protos.TwoWayChannelMessage msg
	    		) {
	                try {
	                    channelClient.receiveMessage(msg);
	                } catch (InsufficientMoneyException e) {
	                    // We should only get this exception during INITIATE, 
	                	// so channelOpen wasn't called yet.
	                    channelOpenFuture.setException(e);
	                }
	            }
	
	            @Override
	            public void connectionOpen(
            		ProtobufConnection<Protos.TwoWayChannelMessage> handler
	    		) {
	            	System.out.println("Connection opened");
	                channelClient.connectionOpen();
	            }
	
	            @Override
	            public void connectionClosed(
            		ProtobufConnection<Protos.TwoWayChannelMessage> handler
	    		) {
	                channelClient.connectionClosed();
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
	    new NioClient(server, wireParser, timeoutSeconds * 1000);
	}
	
	/**
	 * Gets a future which returns this when the channel is successfully 
	 * open, or throws an exception if there is an error before the channel 
	 * has reached the open state
	 */
	public ListenableFuture<HTLCBuyerClientConnection> 
			getChannelOpenFuture() {
	    return channelOpenFuture;
	}
	
	public ListenableFuture<FlowResponse> nodeStats() {
		return channelClient.nodeStats();
	}
	
	
	public ListenableFuture<FlowResponse> sensorStats() {
		return channelClient.sensorStats();
	}
	
	
	public ListenableFuture<List<PriceInfo>> select(String sensorType) {
		return channelClient.select(sensorType);
	}
	
	public ListenableFuture<HTLCPaymentReceipt> buy(
		String sensorType, 
		String deviceId, 
		Coin value
	) {
		return channelClient.buy(sensorType, deviceId, value);
	}
	
	/**
	 * Closes the connection, notifying the server it should settle the channel
	 * by broadcasting the most recent payment transaction.
	 */
	public void settle() {
	    try {
	        channelClient.settle();
	    } catch (IllegalStateException e) {
	        // Already closed...oh well
	    }
	}
	
   public void disconnectWithoutSettlement() {
        wireParser.closeConnection();
    }
}
