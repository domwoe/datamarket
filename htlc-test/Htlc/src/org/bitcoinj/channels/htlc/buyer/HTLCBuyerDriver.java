package org.bitcoinj.channels.htlc.buyer;

import static org.bitcoinj.core.Coin.CENT;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

import org.bitcoinj.channels.htlc.FlowResponse;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.utils.Threading;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class HTLCBuyerDriver {
	
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCBuyerDriver.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private static final Integer BUYER_PORT = 4243;
	private final Coin MICROPAYMENT_SIZE = CENT; 
	
	private CountDownLatch latch;
	private HTLCBuyerClientConnection client;
	private WalletAppKit appKit;
	
	public static void main(String[] args) throws Exception {
		new HTLCBuyerDriver().run();
	}
	
	public void run() throws Exception {
		
		appKit = new WalletAppKit(PARAMS, new File("."), "buyer");
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
			new InetSocketAddress("localhost", BUYER_PORT);
		// 10 minutes
		final long timeWindow = 300L;
		Coin value = Coin.valueOf(1, 0);
		
		TransactionBroadcastScheduler broadcastScheduler = 
			new TransactionBroadcastScheduler(appKit.peerGroup());
		
		client = new HTLCBuyerClientConnection(
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
			new FutureCallback<HTLCBuyerClientConnection>() {
			    @Override public void onSuccess(
		    		final HTLCBuyerClientConnection client
	    		) {
			    	log.info(
		    			"Channel open! We're now connected and " +
		    			"we can make queries"
					);	
			    	readQuery();
			    }
			    @Override public void onFailure(Throwable throwable) {
			    	log.error(throwable.getLocalizedMessage());
			    }
		}, Threading.USER_THREAD);
		latch.await();
	}
	
	private void readQuery() {
		System.out.println("Connected to hub. Please enter a query:");
		
		Scanner input = new Scanner(System.in);
		
		while (input.hasNext()) {
			String query = input.nextLine();
			// Remove quotes
			query = query.replace("\"", "");
			String delims = "[ ]+";
			String[] tokens = query.split(delims);
			if (tokens.length < 2) {
				error("Invalid query length.");
			} else if (tokens[0].equalsIgnoreCase("stats")) {
				if (tokens[1].equalsIgnoreCase("nodes")) {
					waitForFuture(client.nodeStats());
				} else if (tokens[1].equalsIgnoreCase("")) {
					//client.sensorStats();
				} else {
					error("Invalid stats query.");
				}
			} else if (tokens[0].equalsIgnoreCase("select")) {
				String sensorType = tokens[1];
				client.select(sensorType);
			} else {
				error("Invalid query.");
			}
		}
	}
	
	private void error(String error) {
		log.error("Input error has occured: {}", error);
	}
	
	private void waitForFuture(ListenableFuture<FlowResponse> future) {
		Futures.addCallback(
			future,
			new FutureCallback<FlowResponse>() {
				@Override public void onSuccess(FlowResponse response) {
					
				}
				
				@Override public void onFailure(Throwable throwable) {
					log.error(throwable.getLocalizedMessage());
				}
			}
		);	
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
 