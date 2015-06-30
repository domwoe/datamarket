package org.bitcoinj.channels.htlc;


import static com.google.common.base.Preconditions.checkState;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.PaymentChannelServer;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
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
	
	protected final ReentrantLock lock = Threading.lock("htlcchannelserver");
	
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
     * Implements the connection between this server and the client, providing 
     * an interface which allows messages to be
     * sent to the client, requests for the connection to the client to be 
     * closed, and callbacks which occur when the
     * channel is fully open or the client completes a payment.
     */
    public interface ServerConnection {
        /**
         * <p>Requests that the given message be sent to the client. There are 
         * no blocking requirements for this method,
         * however the order of messages must be preserved.</p>
         *
         * <p>If the send fails, no exception should be thrown, however
         * {@link PaymentChannelServer#connectionClosed()} should be called 
         * immediately.</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} 
         * object - be careful about reentrancy</p>
         */
        public void sendToClient(Protos.TwoWayChannelMessage msg);

        /**
         * <p>Requests that the connection to the client be closed</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} 
         * object - be careful about reentrancy</p>
         *
         * @param reason The reason for the closure, see the individual values 
         * for more details.
         *               It is usually safe to ignore this value.
         */
        public void destroyConnection(CloseReason reason);

        /**
         * <p>Triggered when the channel is opened and payments can begin</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} 
         * object - be careful about reentrancy</p>
         *
         * @param contractHash A unique identifier which represents this channel
         *  (actually the hash of the multisig contract)
         */
        public void channelOpen(Sha256Hash contractHash);

        /**
         * <p>Called when the payment in this channel was successfully 
         * incremented by the client</p>
         *
         * <p>Called while holding a lock on the {@link PaymentChannelServer} 
         * object - be careful about reentrancy</p>
         *
         * @param by The increase in total payment
         * @param to The new total payment to us (not including fees which may 
         * be required to claim the payment)
         * @param info Information about this payment increase, used to extend 
         * this protocol.
         * @return A future that completes with the ack message that will be 
         * included in the PaymentAck message to the client. Use null for no ack message.
         */
        @Nullable
        public ListenableFuture<ByteString> paymentIncrease(
    		Coin by, Coin to, @Nullable ByteString info
		);
    }
    private final ServerConnection conn;
    
    // Used to keep track of whether or not the "socket" ie connection is open 
    // and we can generate messages
    @GuardedBy("lock") private boolean connectionOpen = false;
    // Indicates that no further messages should be sent and we intend to 
    // settle the connection
    @GuardedBy("lock") private boolean channelSettling = false;
    
    // The wallet and peergroup which are used to complete/broadcast transactions
    private final Wallet wallet;
    private final TransactionBroadcastScheduler broadcaster;
    
    // The key used for multisig in this channel
    @GuardedBy("lock") private final ECKey serverKey;
    
    // The minimum accepted channel value
    private final Coin minAcceptedChannelSize;
    
    // The state manager for this channel
    @GuardedBy("lock") private HTLCChannelServerState state;
    
    private final long DAILY_SECONDS = 86400;
    // The time this channel expires (ie the refund transaction's locktime)
    @GuardedBy("lock") private long expireTime;
    
    public HTLCPaymentChannelServer(
		TransactionBroadcastScheduler broadcaster,
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
    		log.info("Server channel closed.");
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
            			return;
            		case PROVIDE_REFUND:
            			log.info("Received PROVIDE_REFUND");
            			receiveRefundMessage(msg);
            			return;
            		case HTLC_PROVIDE_CONTRACT:
            			receiveContractMessage(msg);
            			return;
            		case HTLC_INIT:
            			receiveHtlcInitMessage(msg);
            			return;
            		case HTLC_SIGNED_TEARDOWN:
            			receiveSignedTeardownMessage(msg);
            			return;
            		case HTLC_SIGNED_SETTLE_FORFEIT:
            			receiveSignedSettleAndForfeitMsg(msg);
            			return;
            		case HTLC_UPDATE_TEARDOWN:
            			receiveUpdatedTeardownMsg(msg);
            			return;
            		case CLOSE:
            			receiveCloseMessage();
            			return;
            		case ERROR:
            			 checkState(msg.hasError());
                         log.error(
                    		 "Client sent ERROR {} with explanation {}", 
                    		 msg.getError().getCode().name(),
                             msg.getError().hasExplanation() ? 
                        		 msg.getError().getExplanation() : ""
            			 );
                         conn.destroyConnection(CloseReason.REMOTE_SENT_ERROR);
                         return;
            		default:
            			final String errorText = "Got unknown message type " +
        					"or type that doesn't apply to servers.";
                        error(
                    		errorText, 
                    		Protos.Error.ErrorCode.SYNTAX_ERROR, 
                    		CloseReason.REMOTE_SENT_INVALID_MESSAGE
                		);
            	}
            } catch (
        		IllegalStateException | 
        		InsufficientMoneyException | 
        		UnsupportedEncodingException e
    		) {
            	log.error(e.toString());
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
		
		step = InitStep.WAITING_ON_UNSIGNED_REFUND;
        
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
    
    @GuardedBy("lock")
    private void receiveRefundMessage(Protos.TwoWayChannelMessage msg) 	
    		throws VerificationException {
    	
    	checkState(step == InitStep.WAITING_ON_UNSIGNED_REFUND);
    	log.info("Got refund transaction, returning signature");
    	
    	Protos.ProvideRefund providedRefund = msg.getProvideRefund();
    	state = new HTLCChannelServerState(
			wallet, 
			serverKey, 
			expireTime, 
			broadcaster
		);
    	byte[] signature = state.provideRefundTransaction(
			new Transaction(
				wallet.getParams(), 
				providedRefund.getTx().toByteArray()
			),  
			providedRefund.getMultisigKey().toByteArray()
		);
    	
    	step = InitStep.WAITING_ON_CONTRACT;
    	
    	Protos.ReturnRefund.Builder returnRefundBuilder = 
			Protos.ReturnRefund.newBuilder()
				.setSignature(ByteString.copyFrom(signature));
    	conn.sendToClient(Protos.TwoWayChannelMessage.newBuilder()
				.setReturnRefund(returnRefundBuilder)
				.setType(Protos.TwoWayChannelMessage.MessageType.RETURN_REFUND)
				.build());
    }
    
    @GuardedBy("lock")
    private void receiveContractMessage(Protos.TwoWayChannelMessage msg) 
    		throws VerificationException {
    	checkState(
			step == InitStep.WAITING_ON_CONTRACT && 
			msg.hasHtlcProvideContract()
		);
    	log.info("Got contract, broadcasting and responding with CHANNEL_OPEN");
    	final Protos.HTLCProvideContract providedContract = 
			msg.getHtlcProvideContract();
    	final Transaction multisigContract = new Transaction(
			wallet.getParams(), 
			providedContract.getTx().toByteArray()
		);
    	this.step = InitStep.WAITING_ON_MULTISIG_ACCEPTANCE;
    	state.provideMultisigContract(multisigContract)
    			.addListener(new Runnable() {
    				@Override
    				public void run() {
    					try {
							multisigContractPropogated(
								providedContract, 
								multisigContract.getHash()
							);
						} catch (ValueOutOfRangeException e) {
							e.printStackTrace();
						}
    				}
    			}, Threading.SAME_THREAD);
    }
    
    private void multisigContractPropogated(
		Protos.HTLCProvideContract providedContract,
		Sha256Hash contractHash
	) throws ValueOutOfRangeException {
    	lock.lock();
    	try {
    		receiveInitialPaymentMessage(
				providedContract.getSignedInitialTeardown(),
				false
			);
    		conn.sendToClient(Protos.TwoWayChannelMessage.newBuilder()
    				.setType(Protos.TwoWayChannelMessage.MessageType.CHANNEL_OPEN)
    				.build());
    		step = InitStep.CHANNEL_OPEN;
    		conn.channelOpen(contractHash);
    	} finally {
    		lock.unlock();
    	}
    }
    
    @GuardedBy("lock")
    private void receiveInitialPaymentMessage(
		Protos.HTLCSignedTransaction msg,
		boolean sendAck
	) throws ValueOutOfRangeException {
    	log.info("Got a payment message");
    	
    	Coin lastBestPayment = state.getBestValueToMe();
    	Transaction teardownTx = new Transaction(
			wallet.getParams(), 
			msg.getTx().toByteArray()
		);
    	TransactionSignature teardownSig = 
			TransactionSignature.decodeFromBitcoin(
				msg.getSignature().toByteArray(), 
				true
			);
    	state.receiveInitialTeardown(teardownTx, teardownSig);
    	Coin bestPaymentChange = 
			state.getBestValueToMe().subtract(lastBestPayment);
    	
    	if (bestPaymentChange.signum() > 0) {
    		conn.paymentIncrease(
				bestPaymentChange, 
				state.getBestValueToMe(), 
				null
			);
    	}
    	if (sendAck) {
	    	// Send the ACK for the payment
	    	final Protos.TwoWayChannelMessage.Builder ack = 
				Protos.TwoWayChannelMessage.newBuilder();
	    	ack.setType(Protos.TwoWayChannelMessage.MessageType.PAYMENT_ACK);
	    	conn.sendToClient(ack.build());
    	}    	
    }
       
    @GuardedBy("lock")
    private void receiveHtlcInitMessage(TwoWayChannelMessage msg) 
    		throws UnsupportedEncodingException {
    	log.info("Received HTLC INIT msg, replying with HTLC_INIT_REPLY");
    	final Protos.HTLCInit htlcInitMsg = msg.getHtlcInit();
    	final ByteString clientRequestId = htlcInitMsg.getRequestId();
    	final long value = htlcInitMsg.getValue();
    	
    	// Call into the HTLCChannelServerState method
    	HTLCServerState newHtlcState = state.createNewHTLC(Coin.valueOf(value));
    	
    	Protos.HTLCInitReply.Builder htlcInitReply =
			Protos.HTLCInitReply.newBuilder()
				.setId(ByteString.copyFrom(newHtlcState.getId().getBytes()))
				.setClientRequestId(clientRequestId);
    	conn.sendToClient(Protos.TwoWayChannelMessage.newBuilder()
    			.setHtlcInitReply(htlcInitReply)
    			.setType(Protos.TwoWayChannelMessage.MessageType.HTLC_INIT_REPLY)
    			.build());	
    }
    
    @GuardedBy("lock")
    private void receiveSignedTeardownMessage(TwoWayChannelMessage msg) {
    	log.info("Received signed teardown message");
    	
    	final Protos.HTLCProvideSignedTeardown teardownMsg = 
			msg.getHtlcSignedTeardown();
    	final Protos.HTLCSignedTransaction signedTeardown = 
			teardownMsg.getSignedTeardown();
    	ByteString htlcId = teardownMsg.getId();
    	String id = new String(htlcId.toByteArray());
    	Transaction teardownTx = new Transaction(
			wallet.getParams(), 
			signedTeardown.getTx().toByteArray()
		);
    	
    	TransactionSignature teardownSig = 
			TransactionSignature.decodeFromBitcoin(
				signedTeardown.getSignature().toByteArray(), true
			);
    	log.info("Signing refund and teardown hash");
    	SignedTransactionWithHash sigTxWithHash = 
			state.getSignedRefundAndTeardownHash(
				id, 
				teardownTx,
				teardownSig
			);
    	log.info("Done signing refund and teardown hash");
    	Protos.HTLCSignedTransaction.Builder signedRefund = 
			Protos.HTLCSignedTransaction.newBuilder()
				.setTx(ByteString.copyFrom(
					sigTxWithHash.getTx().bitcoinSerialize()
				))
				.setSignature(ByteString.copyFrom(
					sigTxWithHash.getSig().encodeToBitcoin()
				));
    	Protos.HTLCSignedRefundWithHash.Builder htlcSigRefund = 
			Protos.HTLCSignedRefundWithHash.newBuilder()
				.setId(htlcId)
				.setSignedRefund(signedRefund)
				.setTeardownHash(ByteString.copyFrom(
					sigTxWithHash.getSpentTxHash().getBytes()
				));
    	
    	log.info("Replying with signed refund");
    	
    	conn.sendToClient(Protos.TwoWayChannelMessage.newBuilder()
    			.setHtlcSignedRefundWithHash(htlcSigRefund)
    			.setType(
					Protos.TwoWayChannelMessage.MessageType.HTLC_SIGNED_REFUND
				)
    			.build());	
    }
    
    @GuardedBy("lock")
    private void receiveSignedSettleAndForfeitMsg(TwoWayChannelMessage msg) {
    	Protos.HTLCSignedSettleAndForfeit htlcMsg = 
			msg.getHtlcSignedSettleAndForfeit();
    	ByteString htlcId = htlcMsg.getId();
    	String id = new String(htlcId.toByteArray());
    	Protos.HTLCSignedTransaction signedSettle = htlcMsg.getSignedSettle();
    	
    	ECKey clientSecondaryKey = ECKey.fromPublicOnly(
			htlcMsg.getClientSecondaryKey().toByteArray()
		);
    	state.setClientSecondaryKey(clientSecondaryKey);
    	
    	Transaction settleTx = new Transaction(
			wallet.getParams(), 
			signedSettle.getTx().toByteArray()
		);
    	TransactionSignature settleSig = TransactionSignature.decodeFromBitcoin(
			signedSettle.getSignature().toByteArray(), 
			true
		);
    	
    	state.finalizeHTLCSettlementTx(
			id, 
			settleTx,
			settleSig
		);
    	
    	Protos.HTLCSignedTransaction signedForfeit = htlcMsg.getSignedForfeit();
    	Transaction forfeitTx = new Transaction(
			wallet.getParams(),
			signedForfeit.getTx().toByteArray()
		);
    	TransactionSignature forfeitSig = TransactionSignature.decodeFromBitcoin(
			signedForfeit.getSignature().toByteArray(), 
			true
		);
    	state.finalizeHTLCForfeitTx(
    		id,
    		forfeitTx,
    		forfeitSig
		);
    	
    	// Let's ACK the successful setup
    	Protos.HTLCSetupComplete.Builder setupMsg = 
			Protos.HTLCSetupComplete.newBuilder()
				.setId(htlcId);
    	final Protos.TwoWayChannelMessage.Builder htlcSetupMsg =
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(
					Protos.TwoWayChannelMessage.MessageType.HTLC_SETUP_COMPLETE
				);
    	conn.sendToClient(htlcSetupMsg.build());
  	
    	// TODO: MOVE this to a separate method
    	// If we already have the secret for this HTLC, let's settle it early;
    	// We also send the client the forfeitTx, in case later the server
    	// wants to use an older teardown
    	String secret = state.getSecretForHTLC(id);
    	if (secret != null) {
    		log.info("SERVER Attempt to settle");
    		SignedTransaction fullySignedForfeit = state.getFullForfeitTx(id);
    		Protos.HTLCSignedTransaction.Builder signedForfeitMsg = 
				Protos.HTLCSignedTransaction.newBuilder()
					.setTx(ByteString.copyFrom(
						fullySignedForfeit.getTx().bitcoinSerialize()
					))
					.setSignature(ByteString.copyFrom(
						fullySignedForfeit.getSig().encodeToBitcoin()
					));
    		
    		Protos.HTLCRevealSecret.Builder revealMsg = 
				Protos.HTLCRevealSecret.newBuilder()
					.setId(htlcId)
					.setSecret(ByteString.copyFrom(secret.getBytes()))
					.setSignedForfeit(signedForfeitMsg);
    		final Protos.TwoWayChannelMessage.Builder channelMsg = 
				Protos.TwoWayChannelMessage.newBuilder();
    		channelMsg.setType(
				Protos.TwoWayChannelMessage.MessageType.HTLC_REVEAL_SECRET
			);
    		channelMsg.setHtlcRevealSecret(revealMsg);
    		conn.sendToClient(channelMsg.build());
    	}
    }
    
    @GuardedBy("lock")
    private void receiveUpdatedTeardownMsg(TwoWayChannelMessage msg) {
    	Protos.HTLCUpdateTeardown updatedTeardownMsg = 
			msg.getHtlcUpdateTeardown();
    	Protos.HTLCSignedTransaction signedTeardown = 
			updatedTeardownMsg.getSignedTeardown();
    	ByteString htlcId = updatedTeardownMsg.getId();
    	String id = new String(htlcId.toByteArray());
    	Transaction teardownTx = new Transaction(
			wallet.getParams(),
			signedTeardown.getTx().toByteArray()
		);
    	TransactionSignature teardownSig = 
			TransactionSignature.decodeFromBitcoin(
				signedTeardown.getSignature().toByteArray(), 
				true
			);
    	if (state.removeHTLCAndUpdateTeardownTx(id, teardownTx, teardownSig)) {
    		// We ACK the payment
    		Protos.HTLCPaymentAck.Builder ackMsg = 
				Protos.HTLCPaymentAck.newBuilder()
					.setId(htlcId);
    		final Protos.TwoWayChannelMessage.Builder ack = 
				Protos.TwoWayChannelMessage.newBuilder();
    		ack.setType(Protos.TwoWayChannelMessage.MessageType.HTLC_PAYMENT_ACK);
    		ack.setHtlcPaymentAck(ackMsg);
    		conn.sendToClient(ack.build());    		
    	}
    }
    
    @GuardedBy("lock")
    private void receiveCloseMessage() throws InsufficientMoneyException {
        log.info("Got CLOSE message, closing channel");
        if (state != null) {
            settlePayment(CloseReason.CLIENT_REQUESTED_CLOSE);
        } else {
            conn.destroyConnection(CloseReason.CLIENT_REQUESTED_CLOSE);
        }
    }
    
    @GuardedBy("lock")
    private void settlePayment(final CloseReason clientRequestedClose) 
    		throws InsufficientMoneyException {
        channelSettling = true;
        Futures.addCallback(state.close(), new FutureCallback<Transaction>() {
            @Override
            public void onSuccess(Transaction result) {
                // Send the successfully accepted transaction back to the client.
                final Protos.TwoWayChannelMessage.Builder msg = 
            		Protos.TwoWayChannelMessage.newBuilder();
                msg.setType(Protos.TwoWayChannelMessage.MessageType.CLOSE);
                if (result != null) {
                    // Result can be null on various error paths, like 
                	// if we never actually opened properly and so on.
                    msg.getSettlementBuilder().setTx(
                		ByteString.copyFrom(result.bitcoinSerialize())
            		);
                    log.info("Sending CLOSE back with broadcast settlement tx.");
                } else {
                    log.info(
                		"Sending CLOSE back without broadcast settlement tx."
            		);
                }
                conn.sendToClient(msg.build());
                conn.destroyConnection(clientRequestedClose);
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to broadcast settlement tx", t);
                conn.destroyConnection(clientRequestedClose);
            }
        });
    }
    
    /**
     * <p>Closes the connection by generating a settle message for the client and calls
     * {@link ServerConnection#destroyConnection(CloseReason)}. 
     * Note that this does not broadcast the payment transaction and the client 
     * may still resume the same channel if they reconnect</p>
     * <p>
     * <p>Note that {@link PaymentChannelServer#connectionClosed()} must still 
     * be called after the connection fully closes.</p>
     */
    public void close() {
        lock.lock();
        try {
            if (connectionOpen && !channelSettling) {
                final Protos.TwoWayChannelMessage.Builder msg = 
            		Protos.TwoWayChannelMessage.newBuilder();
                msg.setType(Protos.TwoWayChannelMessage.MessageType.CLOSE);
                conn.sendToClient(msg.build());
                conn.destroyConnection(CloseReason.SERVER_REQUESTED_CLOSE);
            }
        } finally {
            lock.unlock();
        }
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
