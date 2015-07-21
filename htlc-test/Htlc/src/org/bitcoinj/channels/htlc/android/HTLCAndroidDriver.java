package org.bitcoinj.channels.htlc.android;

import static org.bitcoinj.core.Coin.CENT;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.channels.htlc.test.HTLCClientDriver;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;

public class HTLCAndroidDriver {
	private static final Logger log = 
		Logger.getLogger("HTLCClientDriver");
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private final Coin MICROPAYMENT_SIZE = CENT;
	
	private CountDownLatch latch;
	
	private WalletAppKit appKit;
	
	private final String path;
	
	public HTLCAndroidDriver(String path) {
		this.path = path;
	}
	
	public void run() {
		appKit = new WalletAppKit(PARAMS, new File(path), "htlc_client");
        try {
			appKit.setPeerNodes(
				new PeerAddress(
					InetAddress.getByName("192.168.0.101"), 	
					PARAMS.getPort()
				)
			);
		} catch (UnknownHostException e1) {
			e1.printStackTrace();
		}
        appKit.startAsync();
        appKit.awaitRunning();
		
        System.out.println(appKit.wallet());
        appKit.wallet().allowSpendingUnconfirmedTransactions();
        if (appKit.wallet().getImportedKeys().size() == 0) {
        	// Import new keys
        	appKit.wallet().importKey(new ECKey());
        }
        
        ECKey key = appKit.wallet().getImportedKeys().get(0);
        
        log.log(Level.INFO, "Android address: {}", key.toAddress(PARAMS));
        
		final int timeoutSecs = 15;
		final InetSocketAddress server = 
			new InetSocketAddress("192.168.0.101", 4242);

		Coin minPayment = Coin.valueOf(0, 1);
		
		TransactionBroadcastScheduler broadcastScheduler = 
			new TransactionBroadcastScheduler(appKit.peerGroup());
		
		HTLCAndroidClientConnection client = 
			new HTLCAndroidClientConnection(
				server,
				timeoutSecs,
				appKit.wallet(),
				broadcastScheduler,
				key,
				minPayment
			);

		/*
		latch = new CountDownLatch(1);
		
		Futures.addCallback(
			client.getChannelOpenFuture(), 
			new FutureCallback<HTLCPaymentChannelClientConnection>() {
			    @Override public void onSuccess(
		    		final HTLCPaymentChannelClientConnection client
	    		) {
			    	log.info("Channel open! Trying to make micropayments");			    	
			    	try {
						paymentIncrementCallback(client);
					} catch (
						IllegalStateException | 
						ValueOutOfRangeException e
					) {
						e.printStackTrace();
					}
			    }
			    @Override public void onFailure(Throwable throwable) {
			    	log.error(throwable.getLocalizedMessage());
			    }
		}, Threading.USER_THREAD);
		latch.await();*/
	}
	/*
	private void paymentIncrementCallback(
		final HTLCAndroidClientConnection client
	) throws IllegalStateException, ValueOutOfRangeException {
		Futures.addCallback(
			client.incrementPayment(MICROPAYMENT_SIZE), 
			new FutureCallback<PaymentIncrementAck>() {
				@Override public void onSuccess(PaymentIncrementAck ack) {
					try {
						log.info(
							"Successfully made payment {} {}", 
							new String(ack.getInfo().toByteArray(), "UTF-8"), 
							ack.getValue()
						);
					} catch (UnsupportedEncodingException e) {
						e.printStackTrace();
					}
					log.info("Closing channel");
			    	client.settle();
			    	latch.countDown();
				}
				@Override public void onFailure(Throwable throwable) {
					log.error(throwable.getLocalizedMessage());
					latch.countDown();
				}
			}
		);
	}*/
}
