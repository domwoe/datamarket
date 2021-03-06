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
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;

public class HTLCHubDriver 
implements HTLCHubServerListener.BuyerHandlerFactory, 
	HTLCHubServerListener.AndroidHandlerFactory {

	private static final Logger log = 
		LoggerFactory.getLogger(HTLCHubDriver.class);
	
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private static final Integer ANDROID_PORT = 4242;
	private static final Integer BUYER_PORT = 4243;
	private static final Integer NETWORK_TIMEOUT = 60000;
	private static final Long CHANNEL_TIME_WINDOW = 60000L;
	
	private WalletAppKit appKit;
	private HTLCHubServerListener server;
	
	public static void main(String[] args) throws Exception {
		new HTLCHubDriver().run();
	}
	
	public void run() throws Exception {
		appKit = new WalletAppKit(PARAMS, new File("."), "hub");
		appKit.connectToLocalHost(); // Not necessary on testnet
		try {
			appKit.startAsync();
		} catch (IllegalStateException e) {
			log.error("ERROR: {}", e.getCause());
		}
		appKit.awaitRunning();
		
		log.info(appKit.wallet().toString());
		
		if (appKit.wallet().getImportedKeys().size() < 3) {
        	// Import new key
        	appKit.wallet().importKey(new ECKey());        	
        	appKit.wallet().importKey(new ECKey());
           	appKit.wallet().importKey(new ECKey());
        }
	
		ECKey primaryKey = appKit.wallet().getImportedKeys().get(0);
		ECKey secondaryKey = appKit.wallet().getImportedKeys().get(1);
		ECKey receivingKey = appKit.wallet().getImportedKeys().get(2);
	
		Coin value = Coin.valueOf(10000L);
		
		log.info(
			"Hub payer addresses: {} {}", 
    		primaryKey.toAddress(PARAMS), 
    		secondaryKey.toAddress(PARAMS)
		);

        TransactionBroadcastScheduler broadcastScheduler = 
    		new TransactionBroadcastScheduler(appKit.peerGroup());
        
        server = new HTLCHubServerListener(
    		broadcastScheduler, 
    		appKit.wallet(),
    		primaryKey,
    		secondaryKey,
    		receivingKey,
    		value,
    		CHANNEL_TIME_WINDOW,
    		primaryKey, 
    		NETWORK_TIMEOUT, 
    		Coin.valueOf(1000L), 
    		this,
    		this
		);
        server.bindAndStart(BUYER_PORT, ANDROID_PORT);
	}

	@Override
	@Nullable
	public ServerConnectionEventHandler onNewConnection(
			final SocketAddress clientAddress) {
		return new ServerConnectionEventHandler() {
			
			@Override
			@Nullable
			public ListenableFuture<ByteString> paymentIncrease(
				Coin arg0, 
				Coin arg1,
				ByteString arg2
			) {
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
