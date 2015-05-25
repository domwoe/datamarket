package org.bitcoinj.channels.htlc.test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import org.bitcoinj.channels.htlc.HTLCPaymentChannelClient;
import org.bitcoinj.channels.htlc.HTLCPaymentChannelClientConnection;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.store.UnreadableWalletException;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

public class HTLCClientDriver {
	
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCClientDriver.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	public static Wallet loadWallet(File f) {

		Wallet wallet = null;

		if (f.exists()) {
			try {
				wallet = Wallet.loadFromFile(f);
			} catch (UnreadableWalletException e) {
				e.printStackTrace();
				
			}
		} else {
			wallet = new Wallet(PARAMS);
		}

		if (wallet.getImportedKeys().size() < 2) {
			// No key, create two
			ECKey primaryKey = new ECKey();
			ECKey secondaryKey = new ECKey();
			
			Address addr = primaryKey.toAddress(PARAMS);
			System.out.println("NEW ADDRESS: " + addr.toString());
			wallet.importKey(primaryKey);
			wallet.importKey(secondaryKey);
			try {	
				wallet.saveToFile(f);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else { 
			// Fetch first address and print it
			ECKey firstKey = wallet.getImportedKeys().get(0);
			byte[] publicKey = firstKey.getPubKey();
			Address addr = firstKey.toAddress(PARAMS);
			System.out.println("CURRENT ADDRESS: " + addr.toString());
		}
		return wallet;
	}
	
	public static void main(String args[]) 
			throws IOException, ValueOutOfRangeException {
		
		Wallet wallet = HTLCClientDriver.loadWallet(new File("test.wallet"));
		ECKey primaryKey = wallet.getImportedKeys().get(0);
		ECKey secondaryKey = wallet.getImportedKeys().get(1);
		
		final int timeoutSecs = 15;
		final InetSocketAddress server = 
			new InetSocketAddress("localhost", 4242);
		// 10 minutes
		final long timeWindow = 600L;
		Coin value = Coin.valueOf(5, 0);
		
		HTLCPaymentChannelClientConnection client = 
			new HTLCPaymentChannelClientConnection(
				server, 
				timeoutSecs,
				wallet,
				primaryKey, 
				secondaryKey,
				value,
				timeWindow
			);
		Futures.addCallback(
			client.getChannelOpenFuture(), 
			new FutureCallback<HTLCPaymentChannelClientConnection>() {
			    @Override public void onSuccess(
		    		HTLCPaymentChannelClientConnection client
	    		) {
			    	System.out.println("Micropayment channel successfully open!");
			    }
			    @Override public void onFailure(Throwable throwable) {
			    	System.out.println(throwable.getLocalizedMessage());
			    }
		});
		
		log.info("Closing channel");
		client.settle();
	}
}
 