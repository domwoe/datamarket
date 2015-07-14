package org.bitcoinj.channels.htlc.hub;

import java.io.File;
import java.net.SocketAddress;

import javax.annotation.Nullable;

import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.channels.htlc.test.HTLCServerDriver;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.ServerConnectionEventHandler;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;

public class HTLCHubOutterDriver 
implements HTLCHubOutterServerListener.HandlerFactory {

	private static final Logger log = 
			LoggerFactory.getLogger(HTLCServerDriver.class);
		private static final NetworkParameters PARAMS = RegTestParams.get();
	
	private static final Integer PORT = 4243;
	private static final Integer TWIN_PORT = 4242;
		
	private WalletAppKit appKit;
	
	public static void main(String[] args) throws Exception {
		new HTLCHubOutterDriver().run();
	}
	
	public void run() throws Exception {
		
		appKit = new WalletAppKit(PARAMS, new File("."), "outter_hub");
        appKit.connectToLocalHost();
        appKit.startAsync();
        appKit.awaitRunning();
        
        log.info("Wallet: {}", appKit.wallet().toString());
        
        if (appKit.wallet().getImportedKeys().size() == 0) {
        	// Import new key
        	appKit.wallet().importKey(new ECKey());
        }
        
        ECKey serverKey = appKit.wallet().getImportedKeys().get(0);
        log.info("Server address: {}", serverKey.toAddress(PARAMS));
        
        TransactionBroadcastScheduler broadcastScheduler = 
    		new TransactionBroadcastScheduler(appKit.peerGroup());
        
        new HTLCHubOutterServerListener(
    		broadcastScheduler, 
    		appKit.wallet(), 
    		serverKey, 
    		15, 
    		Coin.valueOf(100000), 
    		this
		).bindAndStart(PORT, TWIN_PORT);
	}
	
	@Override
	public ServerConnectionEventHandler onNewConnection(final SocketAddress clientAddress) {
		log.info("New connection initiated");
		return new ServerConnectionEventHandler() {
			
			@Override
			public void channelOpen(Sha256Hash channelId) {
				log.info("Channel open for {}: {}.", clientAddress, channelId);
			}
			
			@Override
			@Nullable
			public ListenableFuture<ByteString> paymentIncrease(
				Coin from,
				Coin to,
				ByteString info
			) {
				log.info(
					"Client {} paid increased payment by {} for a total of " + 
					to.toString(), clientAddress, from
				);
                return null;
			}
			
			@Override
			public void channelClosed(CloseReason reason) {
				log.info(
					"Client {} closed channel for reason {}", 
					clientAddress, 
					reason
				);
			}
		};
	}
}
