package org.bitcoinj.channels.htlc.android;

import java.util.List;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException;

public interface IPaymentAndroidChannelClient {
	void receiveMessage(Protos.TwoWayChannelMessage msg);
	
	interface ClientConnection {
		void connectionOpen(Sha256Hash hash);
		void connectionClosed();
		void sendToHub(Protos.TwoWayChannelMessage msg);
		void paymentIncrease(Coin from, Coin to);
		void destroyConnection(PaymentChannelCloseException.CloseReason reason);
		public List<String> getDataFromSensor(String sensorType);
	}
}
