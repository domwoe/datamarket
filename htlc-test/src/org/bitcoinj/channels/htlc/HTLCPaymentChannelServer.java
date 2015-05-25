package org.bitcoinj.channels.htlc;


import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;


/**
 * Handler class that is in charge of most complexity of creating an HTLC
 * payment channel connection
 * @author frabu
 *
 */
public class HTLCPaymentChannelServer {
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCPaymentChannelServer.class);
	
	protected final ReentrantLock lock = Threading.lock("channelserver");
	
	public final int SERVER_MAJOR_VERSION = 1;
    public final int SERVER_MINOR_VERSION = 0;
    
    private enum InitStep {
        WAITING_ON_CLIENT_VERSION,
        WAITING_ON_UNSIGNED_REFUND,
        WAITING_ON_CONTRACT,
        WAITING_ON_MULTISIG_ACCEPTANCE,
        CHANNEL_OPEN
    }
    @GuardedBy("lock") 
    private InitStep step = InitStep.WAITING_ON_CLIENT_VERSION;
    
    /**
     * Implements the connection between this server and the client, providing an interface which allows messages to be
     * sent to the client, requests for the connection to the client to be closed, and callbacks which occur when the
     * channel is fully open or the client completes a payment.
     */
    public interface ServerConnection {
        /**
         * <p>Requests that the given message be sent to the client. There are no blocking requirements for this method,
         * however the order of messages must be preserved.</p>
         *
         * <p>If the send fails, no exception should be thrown, however
         * {@link PaymentChannelServer#connectionClosed()} should be called immediately.</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} object - be careful about reentrancy</p>
         */
        public void sendToClient(Protos.TwoWayChannelMessage msg);

        /**
         * <p>Requests that the connection to the client be closed</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} object - be careful about reentrancy</p>
         *
         * @param reason The reason for the closure, see the individual values for more details.
         *               It is usually safe to ignore this value.
         */
        public void destroyConnection(CloseReason reason);

        /**
         * <p>Triggered when the channel is opened and payments can begin</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} object - be careful about reentrancy</p>
         *
         * @param contractHash A unique identifier which represents this channel (actually the hash of the multisig contract)
         */
        public void channelOpen(Sha256Hash contractHash);

        /**
         * <p>Called when the payment in this channel was successfully incremented by the client</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} object - be careful about reentrancy</p>
         *
         * @param by The increase in total payment
         * @param to The new total payment to us (not including fees which may be required to claim the payment)
         * @param info Information about this payment increase, used to extend this protocol.
         * @return A future that completes with the ack message that will be included in the PaymentAck message to the client. Use null for no ack message.
         */
        @Nullable
        public ListenableFuture<ByteString> paymentIncrease(
    		Coin by, Coin to, @Nullable ByteString info
		);
    }
    private final ServerConnection conn;
    
    // Used to keep track of whether or not the "socket" ie connection is open and we can generate messages
    @GuardedBy("lock") private boolean connectionOpen = false;
    // Indicates that no further messages should be sent and we intend to settle the connection
    @GuardedBy("lock") private boolean channelSettling = false;
    
    // The wallet and peergroup which are used to complete/broadcast transactions
    private final Wallet wallet;
    private final TransactionBroadcaster broadcaster;
    
    // The key used for multisig in this channel
    @GuardedBy("lock") private final ECKey serverKey;
    
    // The minimum accepted channel value
    private final Coin minAcceptedChannelSize;
    
    // The state manager for this channel
    @GuardedBy("lock") private HTLCChannelServerState state;
    
    // The time this channel expires (ie the refund transaction's locktime)
    @GuardedBy("lock") private long expireTime;
    
    public HTLCPaymentChannelServer(
		TransactionBroadcaster broadcaster,
		Wallet wallet,
		ECKey serverKey,
		Coin minAcceptedChannelSize,
		ServerConnection conn
	) {
    	this.broadcaster = broadcaster;
    	this.wallet = wallet;
    	this.serverKey = serverKey;
    	this.minAcceptedChannelSize = minAcceptedChannelSize;
    	this.conn = conn;
    }
    
    /**
     * Called to indicate the connection has been opened and 
     * messages can now be generated for the client.
     */
    public void connectionOpen() {
        lock.lock();
        try {
            log.info("New server channel active.");
            connectionOpen = true;
        } finally {
            lock.unlock();
        }
    }
    
    public void connectionClosed() {
    	lock.lock();
    	try {
    		
    	} finally {
    		lock.unlock();
    	}
    }
    
    public void receiveMessage(Protos.TwoWayChannelMessage msg) {
    	lock.lock();
    	try {
    		Protos.Error.Builder errorBuilder;
            CloseReason closeReason;
            try {
            	switch (msg.getType()) {
            		case CLIENT_VERSION:
            			receiveVersionMessage(msg);
            			break;
            		default:
            			final String errorText = "Got unknown message type " +
        					"or type that doesn't apply to servers.";
                        error(
                    		errorText, 
                    		Protos.Error.ErrorCode.SYNTAX_ERROR, 
                    		CloseReason.REMOTE_SENT_INVALID_MESSAGE
                		);
            	}
            } catch (Exception e) {
            	
            }
    	} finally {
    		lock.unlock();
    	}
    }
    
    @GuardedBy("lock")
    private void receiveVersionMessage(Protos.TwoWayChannelMessage msg) {
    	final Protos.ClientVersion clientVersion = msg.getClientVersion();
    	final int major = clientVersion.getMajor();
    	
    	if (major != SERVER_MAJOR_VERSION) {
    		error(
				"This server needs protocol version " + SERVER_MAJOR_VERSION + 
				" , client offered " + major, 
				Protos.Error.ErrorCode.NO_ACCEPTABLE_VERSION, 
				CloseReason.NO_ACCEPTABLE_VERSION
			);
            return;
    	}
    	
    	Protos.ServerVersion.Builder versionNegotiationBuilder =
			Protos.ServerVersion.newBuilder()
				.setMajor(SERVER_MAJOR_VERSION)
				.setMinor(SERVER_MINOR_VERSION);
        conn.sendToClient(
    		Protos.TwoWayChannelMessage.newBuilder()
    			.setType(Protos.TwoWayChannelMessage.MessageType.SERVER_VERSION)
                .setServerVersion(versionNegotiationBuilder)
                .build()
        );
        log.info(
    		"Got initial version message, responding with VERSIONS and " +
    		"INITIATE: min value={}", minAcceptedChannelSize.value
		);
        
        expireTime = 
        	Utils.currentTimeSeconds() + clientVersion.getTimeWindowSecs();
        
        Protos.Initiate.Builder initiateBuilder = Protos.Initiate.newBuilder()
            .setMultisigKey(ByteString.copyFrom(serverKey.getPubKey()))
            .setExpireTimeSecs(expireTime)
            .setMinAcceptedChannelSize(minAcceptedChannelSize.value)
            .setMinPayment(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.value);

        conn.sendToClient(Protos.TwoWayChannelMessage.newBuilder()
            .setInitiate(initiateBuilder)
            .setType(Protos.TwoWayChannelMessage.MessageType.INITIATE)
            .build());
    }
    
    private void error(
		String message, 
		Protos.Error.ErrorCode errorCode, 
		CloseReason closeReason
	) {
        log.error(message);
        Protos.Error.Builder errorBuilder;
        errorBuilder = Protos.Error.newBuilder()
                .setCode(errorCode)
                .setExplanation(message);
        conn.sendToClient(Protos.TwoWayChannelMessage.newBuilder()
                .setError(errorBuilder)
                .setType(Protos.TwoWayChannelMessage.MessageType.ERROR)
                .build());
        conn.destroyConnection(closeReason);
    }
}
