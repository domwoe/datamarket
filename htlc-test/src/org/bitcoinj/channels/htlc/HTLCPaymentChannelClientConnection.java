package org.bitcoinj.channels.htlc;


import java.io.IOException;
import java.net.InetSocketAddress;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.NioClient;
import org.bitcoinj.net.ProtobufParser;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;


public class HTLCPaymentChannelClientConnection {

	private final SettableFuture<HTLCPaymentChannelClientConnection> 
		channelOpenFuture = SettableFuture.create();
	private final HTLCPaymentChannelClient channelClient;
    private final ProtobufParser<Protos.TwoWayChannelMessage> wireParser;
    
    public HTLCPaymentChannelClientConnection(
		InetSocketAddress server,
		int timeoutSeconds,
		Wallet wallet,
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
    	channelClient = new HTLCPaymentChannelClient(
			wallet, 
			clientPrimaryKey, 
			clientSecondaryKey,
			value,
			timeWindow,
			new HTLCPaymentChannelClient.ClientConnection() {
				
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
				public boolean acceptExpireTime(long arg0) {
					return true;
				}

				@Override
				public void channelOpen(boolean arg0) {
					wireParser.setSocketTimeout(0);
		            // Inform the API user that we're done and ready to roll.
	                channelOpenFuture.set(
                		HTLCPaymentChannelClientConnection.this
            		);	
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
	                try {
	                	System.out.println("Received msg from server");
	                    channelClient.receiveMessage(msg);
	                } catch (InsufficientMoneyException e) {
	                    // We should only get this exception during INITIATE, 
	                	// so channelOpen wasn't called yet.
	                    channelOpenFuture.setException(e);
	                }
	            }

	            @Override
	            public void connectionOpen(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler
        		) {
	            	System.out.println("Connection opened");
	                channelClient.connectionOpen();
	            }

	            @Override
	            public void connectionClosed(
            		ProtobufParser<Protos.TwoWayChannelMessage> handler
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
    public ListenableFuture<HTLCPaymentChannelClientConnection> 
			getChannelOpenFuture() {
        return channelOpenFuture;
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
}
