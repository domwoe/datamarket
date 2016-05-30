package org.bitcoinj.channels.htlc.buyer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.bitcoinj.channels.htlc.FlowResponse;
import org.bitcoinj.channels.htlc.HTLCPaymentReceipt;
import org.bitcoinj.channels.htlc.PriceInfo;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.channels.htlc.buyer.HTLCBuyerClientConnection;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class HTLCBuyerInstance implements Runnable {
	
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCBuyerInstance.class);
	
	private WalletAppKit appKit;
	
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private static final Integer BUYER_PORT = 4243;
	private static final Long CHANNEL_TIME_WINDOW = 60000L;
	private static final Integer NETWORK_TIMEOUT = 60000;
	SecureRandom random = new SecureRandom();
	
	private HTLCBuyerClientConnection client;
	
	private String walletName;
	private Address key;
	private volatile boolean running;
	private volatile boolean channelOpen;
	
	public HTLCBuyerInstance(String walletName) {
		this.walletName = walletName;
		this.running = true;
		this.channelOpen = false;
	}
	
	public void close() {
		running = false;
		if (!channelOpen) {
			System.out.println("Stopping " + walletName);
			appKit.stopAsync();
			appKit.awaitTerminated();
			if (client != null) {
				client.disconnectWithoutSettlement();
			}
		}
	}

	@Override
	public void run() {
		try {
			appKit = new WalletAppKit(PARAMS, new File("."), "htlc_client");
			try {
				appKit.setPeerNodes(
						new PeerAddress(
							InetAddress.getByName("82.165.25.152"), 	
							PARAMS.getPort()
						)
					);
			} catch (UnknownHostException e1) {
			    e1.printStackTrace();
			}
	        appKit.startAsync();
	        appKit.awaitRunning();
		} catch (IllegalStateException e) {
			e.printStackTrace();
			return;
		}
		
        System.out.println(appKit.wallet());
        
        appKit.wallet().allowSpendingUnconfirmedTransactions();
        if (appKit.wallet().getImportedKeys().size() < 2) {
        	// Import new keys
        	appKit.wallet().importKey(new ECKey());
        	appKit.wallet().importKey(new ECKey());
        }
        
        ECKey primaryKey = appKit.wallet().getImportedKeys().get(0);
        ECKey secondaryKey = appKit.wallet().getImportedKeys().get(1);
        
        log.error(
			"Buyer {} addresses: {} {}",
			walletName,
    		primaryKey.toAddress(PARAMS), 
    		secondaryKey.toAddress(PARAMS)
		);
        
        key = primaryKey.toAddress(PARAMS);

		final InetSocketAddress server = 
			new InetSocketAddress("82.165.25.152", BUYER_PORT);
		Coin value = Coin.valueOf(100000L);
		
		TransactionBroadcastScheduler broadcastScheduler = 
			new TransactionBroadcastScheduler(appKit.peerGroup());
		
		try {
			client = new HTLCBuyerClientConnection(
				server,
				NETWORK_TIMEOUT,
				appKit.wallet(),
				broadcastScheduler,
				primaryKey, 
				secondaryKey,
				value,
				CHANNEL_TIME_WINDOW
			);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ValueOutOfRangeException e) {
			e.printStackTrace();
		}
		
		Futures.addCallback(
			client.getChannelOpenFuture(), 
			new FutureCallback<HTLCBuyerClientConnection>() {
			    @Override public void onSuccess(
		    		final HTLCBuyerClientConnection client
	    		) {
			    	/*
			    	channelOpen = true;
			    	
			    	while (running) {
			    		try {
							Thread.sleep(2000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
			    		
			    		String sensorType = "";
			    		
			    		switch (random.nextInt(Integer.MAX_VALUE) % 2) {
			    			case 0:
			    				sensorType = "LIGHT";
			    				break;
			    			case 1:
			    				sensorType = "PROXIMITY";
			    				break;
			    		}
			    		switch (random.nextInt(Integer.MAX_VALUE) % 4) {
			    			case 0:
			    				log.warn("Running SELECT " + sensorType);
			    				select(sensorType);
			    				break;
				    		case 1:
				    			log.warn("Running BUY " + sensorType);
				    			buy(sensorType, "SAMSUNG", Coin.valueOf(10L));
				    			break;
				    		case 2:
				    			log.warn("Running Node stats");
				    			nodeStats();
				    			break;
				    		case 3:
				    			log.warn("Running Sensor Stats");
				    			sensorStats();
				    			break;
			    		}
			    	}
			    	
			    	appKit.stopAsync();
					appKit.awaitTerminated();
					if (client != null) {
						client.disconnectWithoutSettlement();
					}*/
			    }
			    @Override public void onFailure(Throwable throwable) {
			    	log.error(throwable.getLocalizedMessage());
			    }
		}, Threading.USER_THREAD);
	}
	
	public void nodeStats() {
		registerForStats(client.nodeStats());
	}
	
	public void sensorStats() {
		registerForStats(client.sensorStats());
	}
	
	public void select(String sensorType) {
		registerForSelect(client.select(sensorType));
	}
	
	public void buy(String sensorType, String deviceId, Coin value) {
		registerForData(client.buy(sensorType, deviceId, value));
	}
	
	private void registerForSelect(ListenableFuture<List<PriceInfo>> future) {
		Futures.addCallback(
			future, 
			new FutureCallback<List<PriceInfo>>() {
				@Override
				public void onSuccess(List<PriceInfo> pInfoList) {
					System.out.println("Received query result");
					for (PriceInfo pInfo: pInfoList) {
						System.out.println("\n" +
							pInfo.getDeviceId() + ": " + 
							pInfo.getSensor() + " " + 
							pInfo.getPrice() + " Satoshis"
						);
					}
				}
				
				@Override 
				public void onFailure(Throwable throwable) {
					
				}
			}
		);
	}
	
	private void registerForData(
		ListenableFuture<HTLCPaymentReceipt> future
	) {
		Futures.addCallback(
			future, 
			new FutureCallback<HTLCPaymentReceipt>() {
				@Override
				public void onSuccess(HTLCPaymentReceipt receiptWithData) {
					log.warn("Received data {}",  Arrays.toString(receiptWithData.getData().toArray()));
				}
				
				@Override
				public void onFailure(Throwable throwable) {
					log.error("An exception has occured during the payment");
				}
			}
		);
	}
		
	private void registerForStats(ListenableFuture<FlowResponse> future) {
		Futures.addCallback(
			future,
			new FutureCallback<FlowResponse>() {
				@Override public void onSuccess(FlowResponse response) {
					for (String stat: response.getStats()) {
						System.out.println("\n" + stat);
					}
				}
				
				@Override public void onFailure(Throwable throwable) {
					log.error(throwable.getLocalizedMessage());
				}
			}
		);	
	}
}