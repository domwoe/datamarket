package org.bitcoinj.channels.htlc.android;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;

public class HTLCAndroidDriver {
	private static final Logger log = 
		Logger.getLogger("HTLCClientDriver");
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private static final Integer NETWORK_TIMEOUT = 6000;
	
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
        
		final InetSocketAddress server = 
			new InetSocketAddress("192.168.0.101", 4242);

		Coin minPayment = Coin.valueOf(0, 1);
		
		TransactionBroadcastScheduler broadcastScheduler = 
			new TransactionBroadcastScheduler(appKit.peerGroup());
		
		HTLCAndroidClientConnection client = 
			new HTLCAndroidClientConnection(
				server,
				NETWORK_TIMEOUT,
				appKit.wallet(),
				broadcastScheduler,
				key,
				minPayment, 
				null
			);
	}
}
