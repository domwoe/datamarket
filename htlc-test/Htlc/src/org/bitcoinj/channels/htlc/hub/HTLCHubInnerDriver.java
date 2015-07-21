package org.bitcoinj.channels.htlc.hub;

import java.io.File;
import java.net.SocketAddress;

import javax.annotation.Nullable;

import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;

public class HTLCHubInnerDriver 
implements HTLCHubInnerServerListener.HandlerFactory {

	private static final Logger log = 
		LoggerFactory.getLogger(HTLCHubInnerDriver.class);
	
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private static final Integer PORT = 4242;
	
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
	
		final long timeWindow = 3000L;
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
			final SocketAddress clientAddress) {
		log.info("New connection initiated");
		
		return new ServerConnectionEventHandler() {
			
			@Override
			@Nullable
			public ListenableFuture<ByteString> paymentIncrease(Coin arg0, Coin arg1,
					ByteString arg2) {
				return null;
			}
			
			@Override
			public void channelOpen(Sha256Hash hash) {
				log.info("Channel open for {}: {}.", clientAddress, hash);
			}
			
			@Override
			public void channelClosed(CloseReason reason) {
				log.info("Client closed channel for reason {}", reason);				
			}
		};
	}
}
