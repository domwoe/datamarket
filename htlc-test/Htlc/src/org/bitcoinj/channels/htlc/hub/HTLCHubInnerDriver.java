package org.bitcoinj.channels.htlc.hub;

import static org.bitcoinj.core.Coin.CENT;

import java.io.File;
import java.net.SocketAddress;

import javax.annotation.Nullable;

import org.bitcoinj.channels.htlc.HTLCPaymentChannelServerListener;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTLCHubInnerDriver 
implements HTLCHubInnerServerListener.HandlerFactory {

	private static final Logger log = 
		LoggerFactory.getLogger(HTLCHubInnerDriver.class);
	
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private static final Integer PORT = 4242;
	private final Coin MICROPAYMENT_SIZE = CENT;
	
	private WalletAppKit appKit;
	
	public static void main(String[] args) throws Exception {
		new HTLCHubInnerDriver().run();
	}
	
	public void run() throws Exception {
		appKit = new WalletAppKit(PARAMS, new File("."), "inner_hub");
		appKit.connectToLocalHost();
		appKit.startAsync();
		appKit.awaitRunning();
		
		log.info(appKit.wallet().toString());
		
		if (appKit.wallet().getImportedKeys().size() < 2) {
        	// Import new key
        	appKit.wallet().importKey(new ECKey());        	
        	appKit.wallet().importKey(new ECKey());
        }
	
		ECKey primaryKey = appKit.wallet().getImportedKeys().get(0);
		ECKey secondaryKey = appKit.wallet().getImportedKeys().get(1);
	
		final long timeWindow = 300L;
		Coin value = Coin.valueOf(5, 0);
		
		log.info(
			"Hub payer addresses: {} {}", 
    		primaryKey.toAddress(PARAMS), 
    		secondaryKey.toAddress(PARAMS)
		);

        TransactionBroadcastScheduler broadcastScheduler = 
    		new TransactionBroadcastScheduler(appKit.peerGroup());
        
        new HTLCHubInnerServerListener(
    		broadcastScheduler, 
    		appKit.wallet(),
    		primaryKey,
    		secondaryKey,
    		value,
    		timeWindow,
    		primaryKey, 
    		15, 
    		Coin.valueOf(100000), 
    		this
		).bindAndStart(PORT);
	}

	@Override
	@Nullable
	public ServerConnectionEventHandler onNewConnection(
			SocketAddress clientAddress) {
		return null;
	}
}
