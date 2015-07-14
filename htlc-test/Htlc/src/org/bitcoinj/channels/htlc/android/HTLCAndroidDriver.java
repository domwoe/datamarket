package org.bitcoinj.channels.htlc.android;

import static org.bitcoinj.core.Coin.CENT;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

import org.bitcoinj.channels.htlc.HTLCBuyerClientConnection;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.channels.htlc.test.HTLCClientDriver;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

public class HTLCAndroidDriver implements Runnable {
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCClientDriver.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private final Coin MICROPAYMENT_SIZE = CENT;
	
	private CountDownLatch latch;
	
	private WalletAppKit appKit;
	
	public static void main(String[] args) throws Exception {
		new HTLCClientDriver().run();
	}
	
	@Override
	public void run() {
		
		appKit = new WalletAppKit(PARAMS, new File("."), "htlc_client");
        appKit.connectToLocalHost();
        appKit.startAsync();
        appKit.awaitRunning();
		
        System.out.println(appKit.wallet());
        appKit.wallet().allowSpendingUnconfirmedTransactions();
        if (appKit.wallet().getImportedKeys().size() == 0) {
        	// Import new keys
        	appKit.wallet().importKey(new ECKey());
        }
        
        ECKey key = appKit.wallet().getImportedKeys().get(0);
        
        log.info("Android address: {}", key.toAddress(PARAMS));

		final int timeoutSecs = 15;
		final InetSocketAddress server = 
			new InetSocketAddress("localhost", 4242);

		Coin minPayment = Coin.valueOf(0, 1);
		
		TransactionBroadcastScheduler broadcastScheduler = 
			new TransactionBroadcastScheduler(appKit.peerGroup());
		
		try {
			HTLCAndroidClientConnection client = 
				new HTLCAndroidClientConnection(
					server,
					timeoutSecs,
					appKit.wallet(),
					broadcastScheduler,
					key,
					minPayment
				);
		} catch (IOException | ValueOutOfRangeException e) {
			e.printStackTrace();
		}
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
	
	private void paymentIncrementCallback(
		final HTLCBuyerClientConnection client
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
	}
}
