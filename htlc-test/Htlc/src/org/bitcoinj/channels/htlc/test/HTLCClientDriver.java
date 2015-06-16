package org.bitcoinj.channels.htlc.test;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.bitcoinj.channels.htlc.HTLCPaymentChannelClientConnection;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.protocols.channels.PaymentChannelClientState.IncrementedPayment;
import org.bitcoinj.utils.Threading;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.Uninterruptibles;

import static org.bitcoinj.core.Coin.CENT;

public class HTLCClientDriver {
	
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCClientDriver.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private final Coin MICROPAYMENT_SIZE = CENT.divide(10);
	
	private CountDownLatch latch;
	
	private WalletAppKit appKit;
	
	public static void main(String[] args) throws Exception {
		new HTLCClientDriver().run();
	}
	
	public void run() throws Exception {
		
		appKit = new WalletAppKit(PARAMS, new File("."), "htlc_client");
        appKit.connectToLocalHost();
        appKit.startAsync();
        appKit.awaitRunning();
		
        System.out.println(appKit.wallet());
        appKit.wallet().allowSpendingUnconfirmedTransactions();
        if (appKit.wallet().getImportedKeys().size() < 2) {
        	// Import new keys
        	appKit.wallet().importKey(new ECKey());
        	appKit.wallet().importKey(new ECKey());
        }
        
        ECKey primaryKey = appKit.wallet().getImportedKeys().get(0);
        ECKey secondaryKey = appKit.wallet().getImportedKeys().get(1);
        
        log.info(
			"Client addresses: {} {}", 
    		primaryKey.toAddress(PARAMS), 
    		secondaryKey.toAddress(PARAMS)
		);

		final int timeoutSecs = 15;
		final InetSocketAddress server = 
			new InetSocketAddress("localhost", 4242);
		// 10 minutes
		final long timeWindow = 300L;
		Coin value = Coin.valueOf(1, 0);
		
		TransactionBroadcastScheduler broadcastScheduler = 
			new TransactionBroadcastScheduler(appKit.peerGroup());
		
		HTLCPaymentChannelClientConnection client = 
			new HTLCPaymentChannelClientConnection(
				server,
				timeoutSecs,
				appKit.wallet(),
				broadcastScheduler,
				primaryKey, 
				secondaryKey,
				value,
				timeWindow
			);
		latch = new CountDownLatch(1);
		Futures.addCallback(
			client.getChannelOpenFuture(), 
			new FutureCallback<HTLCPaymentChannelClientConnection>() {
			    @Override public void onSuccess(
		    		final HTLCPaymentChannelClientConnection client
	    		) {
			    	/*
			    	log.info("Success! Trying to make micropayments");			    	
			    	try {
						paymentIncrementCallback(client);
					} catch (IllegalStateException | ValueOutOfRangeException e) {
						e.printStackTrace();
					}
					*/
			    }
			    @Override public void onFailure(Throwable throwable) {
			    	log.error(throwable.getLocalizedMessage());
			    }
		}, Threading.USER_THREAD);
		latch.await();
	}
	
	private void paymentIncrementCallback(
		final HTLCPaymentChannelClientConnection client
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
			    //	latch.countDown();
				}
				@Override public void onFailure(Throwable throwable) {
					log.error(throwable.getLocalizedMessage());
				//	latch.countDown();
				}
			}
		);
	}
}
 