package org.bitcoinj.channels.htlc;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;

import com.google.common.util.concurrent.ListenableFuture;


public interface IPaymentBuyerChannelClient {

	void receiveMessage(Protos.TwoWayChannelMessage msg) 
		throws InsufficientMoneyException;
	
	void connectionOpen();
	
	void connectionClosed();
	
	void settle() throws IllegalStateException;

	ListenableFuture<HTLCPaymentReceipt> buy(
		String sensorType, 
		String deviceId, 
		Coin value
	);
	
	interface ClientConnection {
		void sendToServer(Protos.TwoWayChannelMessage msg);
		void destroyConnection(PaymentChannelCloseException.CloseReason reason);
		public boolean acceptExpireTime(long expireTime);
		void channelOpen(boolean wasInitiated);
	}
}
