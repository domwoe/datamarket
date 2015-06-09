package org.bitcoinj.channels.htlc;


import static com.google.common.base.Preconditions.checkState;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import net.jcip.annotations.GuardedBy;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.protocols.channels.IPaymentChannelClient;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.protocols.payments.PaymentProtocol.Ack;
import org.bitcoinj.utils.Threading;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;

/**
 * This class handles the creation of an HTLC payment channel.
 * Implements the IPaymentChannelClient interface
 * @author frabu
 *
 */
public class HTLCPaymentChannelClient implements IPaymentChannelClient {
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCPaymentChannelClient.class);
	private static final int CLIENT_MAJOR_VERSION = 1;
	private final int CLIENT_MINOR_VERSION = 0;
	private static final int SERVER_MAJOR_VERSION = 1;
	 
	private final ECKey clientPrimaryKey;
	private final ECKey clientSecondaryKey;
	
	private final long timeWindow;
	@GuardedBy("lock") private long minPayment;
	
	protected final ReentrantLock lock = Threading.lock("htlcchannelclient");
	 
	@GuardedBy("lock") private final ClientConnection conn;
	@GuardedBy("lock") private HTLCChannelClientState state;
	
	@GuardedBy("lock")
	private Map<ByteString, SettableFuture<PaymentIncrementAck> > 
		paymentAckFutureMap;
	
	@GuardedBy("lock")
	private Map<ByteString, Coin> paymentValueMap; 
	
	private enum InitStep {
		WAITING_FOR_CONNECTION_OPEN,
        WAITING_FOR_VERSION_NEGOTIATION,
        WAITING_FOR_INITIATE,
        WAITING_FOR_REFUND_RETURN,
        WAITING_FOR_CHANNEL_OPEN,
        CHANNEL_OPEN,
        WAITING_FOR_HTLC_INIT_REPLY,
        WAITING_FOR_CHANNEL_CLOSE,
        CHANNEL_CLOSED,
	}
	@GuardedBy("lock") 
	private InitStep step = InitStep.WAITING_FOR_CONNECTION_OPEN;
	
	private TransactionBroadcastScheduler broadcastScheduler;
	 
	// The wallet associated with this channel
    private final Wallet wallet;
    private final Coin value;
    private Coin missing;
    
    public HTLCPaymentChannelClient(
    	Wallet wallet,
    	TransactionBroadcastScheduler broadcastScheduler,
    	ECKey clientPrimaryKey,
    	ECKey clientSecondaryKey,
    	Coin value,
    	long timeWindow,
    	ClientConnection  conn
	) {
    	this.wallet = wallet;
    	this.broadcastScheduler = broadcastScheduler;
    	this.clientPrimaryKey = clientPrimaryKey;
    	this.clientSecondaryKey = clientSecondaryKey;
    	this.value = value;
    	this.timeWindow = timeWindow;
    	this.conn = conn;
    	this.paymentAckFutureMap = 
			new HashMap<ByteString, SettableFuture<PaymentIncrementAck>>();
    	this.paymentValueMap = new HashMap<ByteString, Coin>();
    }
    
    /**
     * Called to indicate the connection has been opened and messages can 
     * now be generated for the server.
     * Generates a CLIENT_VERSION message for the server. Server responds
     * with its version
     */
    @Override
    public void connectionOpen() {
    	lock.lock();
    	try {
    		step = InitStep.WAITING_FOR_VERSION_NEGOTIATION;
    		log.info("Sending version negotiation to server");
    		Protos.ClientVersion.Builder versionNegotiationBuilder = 
				Protos.ClientVersion.newBuilder()
                    .setMajor(CLIENT_MAJOR_VERSION)
                    .setMinor(CLIENT_MINOR_VERSION)
                    .setTimeWindowSecs(timeWindow);
    		conn.sendToServer(Protos.TwoWayChannelMessage.newBuilder()
                    .setType(Protos.TwoWayChannelMessage.MessageType.CLIENT_VERSION)
                    .setClientVersion(versionNegotiationBuilder)
                    .build());
    	} finally {
    		lock.unlock();
    	}
    }
    
    @Override
    public void receiveMessage(Protos.TwoWayChannelMessage msg) 
    		throws InsufficientMoneyException {
    	lock.lock();
        try {
        	Protos.Error.Builder errorBuilder;
            CloseReason closeReason;
        	switch (msg.getType()) {
        		case SERVER_VERSION:
        			checkState(
        				step == InitStep.WAITING_FOR_VERSION_NEGOTIATION && 
        				msg.hasServerVersion()
    				);
        			if (msg.getServerVersion().getMajor() != SERVER_MAJOR_VERSION) {
        				errorBuilder = Protos.Error.newBuilder()
    						.setCode(Protos.Error.ErrorCode.NO_ACCEPTABLE_VERSION);
        				closeReason = CloseReason.NO_ACCEPTABLE_VERSION;
        				break;
        			}
        			log.info("Got version handshake, awaiting INITIATE");
        			step = InitStep.WAITING_FOR_INITIATE;
                    return;
        		case INITIATE:
        			checkState(
    					step == InitStep.WAITING_FOR_INITIATE && 
    					msg.hasInitiate()
					);
        			Protos.Initiate initiate = msg.getInitiate();
        			errorBuilder = Protos.Error.newBuilder();
        			closeReason = receiveInitiate(initiate, value, errorBuilder);
        			if (closeReason == null) {
        				log.error("Refund sent to server");
        				return;
        			}
        			log.error(
    					"Initiate failed with error: {}", 
    					errorBuilder.build().toString()
					);
        			break;
        		case RETURN_REFUND:
        			receiveRefund(msg);
        			return;
        		case CHANNEL_OPEN:
        			receiveChannelOpen();
        			return;
        		case HTLC_INIT_REPLY:
        			receiveHtlcInitReply(msg);
        			return;
        		case HTLC_SIGNED_REFUND:
        			receiveHtlcSignedRefundWithHash(msg);
        		case CLOSE:
        			receiveClose(msg);
        			return;
        		case ERROR:
        			checkState(msg.hasError());
        			 log.error(
    					 "Server sent ERROR {} with explanation {}", 
    					 msg.getError().getCode().name(),
                         msg.getError().hasExplanation() ? 
                    		 msg.getError().getExplanation() : ""
        			 );
                     conn.destroyConnection(CloseReason.REMOTE_SENT_ERROR);
                     return;
        		default:
                    log.error(
                		"Got unknown message type or type that " +
                		"doesn't apply to clients."
    				);
                    errorBuilder = Protos.Error.newBuilder()
                		.setCode(Protos.Error.ErrorCode.SYNTAX_ERROR);
                    closeReason = CloseReason.REMOTE_SENT_INVALID_MESSAGE;
                    break;
        	}
        	conn.sendToServer(Protos.TwoWayChannelMessage.newBuilder()
    			.setError(errorBuilder)
                .setType(Protos.TwoWayChannelMessage.MessageType.ERROR)
                .build());
        	conn.destroyConnection(closeReason);
        } finally {
        	lock.unlock();
        }
    }
    
    @Nullable
    @GuardedBy("lock")
    private CloseReason receiveInitiate(
		Protos.Initiate initiate,
		Coin contractValue, 
		Protos.Error.Builder errorBuilder
	) {
    	log.info("Got initiate message");
    	final long expireTime = initiate.getExpireTimeSecs();
    	checkState(expireTime >= 0 && initiate.getMinAcceptedChannelSize() >= 0);
    	
    	if (!conn.acceptExpireTime(expireTime)) {
    		log.error(
				"Server suggested expire time was out of our " +
				"allowed bounds: {} ({} s)", 
				Utils.dateTimeFormat(expireTime * 1000), 
				expireTime
			);
            errorBuilder.setCode(Protos.Error.ErrorCode.TIME_WINDOW_UNACCEPTABLE);
            return CloseReason.TIME_WINDOW_UNACCEPTABLE;
    	}  	
    	Coin minChannelSize = Coin.valueOf(initiate.getMinAcceptedChannelSize());
    	if (contractValue.compareTo(minChannelSize) < 0) {
    		log.error("Server requested too much value");
    		errorBuilder.setCode(Protos.Error.ErrorCode.CHANNEL_VALUE_TOO_LARGE);
    		missing = minChannelSize.subtract(contractValue);
    		return CloseReason.SERVER_REQUESTED_TOO_MUCH_VALUE;
    	}
        final long MIN_PAYMENT = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.value;
        if (initiate.getMinPayment() != MIN_PAYMENT) {
            log.error(
        		"Server requested a min payment of {} but we expected {}", 
        		initiate.getMinPayment(), 
        		MIN_PAYMENT
    		);
            errorBuilder.setCode(Protos.Error.ErrorCode.MIN_PAYMENT_TOO_LARGE);
            errorBuilder.setExpectedValue(MIN_PAYMENT);
            missing = Coin.valueOf(initiate.getMinPayment() - MIN_PAYMENT);
            return CloseReason.SERVER_REQUESTED_TOO_MUCH_VALUE;
        }
        final byte[] pubKeyBytes = initiate.getMultisigKey().toByteArray();
        if (!ECKey.isPubKeyCanonical(pubKeyBytes)) {
        	throw new VerificationException(
    			"Server gave us a non-canonical public key, protocol error."
			);
        }
        state = new HTLCChannelClientState(
    		wallet,
    		broadcastScheduler,
    		clientPrimaryKey,
    		clientSecondaryKey,
    		ECKey.fromPublicOnly(pubKeyBytes), 
    		contractValue,
    		expireTime
		);
        
        try {
        	state.initiate();
        } catch (InsufficientMoneyException e) {
        	log.error("Insufficient money in wallet when trying to initiate", e);
        	errorBuilder.setCode(Protos.Error.ErrorCode.CHANNEL_VALUE_TOO_LARGE);
        	return CloseReason.SERVER_REQUESTED_TOO_MUCH_VALUE;
        } catch (ValueOutOfRangeException e) {
        	log.error("Value out of range when trying to initiate", e);
        	errorBuilder.setCode(Protos.Error.ErrorCode.CHANNEL_VALUE_TOO_LARGE);
        	return CloseReason.SERVER_REQUESTED_TOO_MUCH_VALUE;
        }
        
        minPayment = initiate.getMinPayment();
    	step = InitStep.WAITING_FOR_REFUND_RETURN;
    	
    	log.info("Sending refund transaction");
    	
    	Protos.ProvideRefund.Builder provideRefundBuilder = 
			Protos.ProvideRefund.newBuilder()
				.setMultisigKey(
					ByteString.copyFrom(clientPrimaryKey.getPubKey())
				)
				.setTx(ByteString.copyFrom(
					state.getIncompleteRefundTransaction().bitcoinSerialize()
				));
    	conn.sendToServer(Protos.TwoWayChannelMessage.newBuilder()
			.setProvideRefund(provideRefundBuilder)
			.setType(Protos.TwoWayChannelMessage.MessageType.PROVIDE_REFUND)
			.build());
    	return null;
    }
    
    @GuardedBy("lock")
    private void receiveRefund(Protos.TwoWayChannelMessage refundMsg) {
    	checkState(
			step == InitStep.WAITING_FOR_REFUND_RETURN && 
			refundMsg.hasReturnRefund()
		);
    	log.info("Got RETURN_REFUND message, providing signed contract");
    	Protos.ReturnRefund returnedRefund = refundMsg.getReturnRefund();
    	state.provideRefundSignature(returnedRefund.getSignature().toByteArray());
    	step = InitStep.WAITING_FOR_CHANNEL_OPEN;
    	
    	// Schedule the broadcast of the contract and the refund;
    	// Ensure the tx is safely stored in the wallet
    	state.scheduleBroadcast();
    	
    	Protos.HTLCProvideContract.Builder contractMsg = 
			Protos.HTLCProvideContract.newBuilder()
				.setTx(ByteString.copyFrom(
					state.getMultisigContract().bitcoinSerialize())
				);
    	
    	// Get an updated teardown tx with signature from the client, so the
    	// server will have a fully signed teardown tx with dust value when
    	// the channel will be opened
    	try {
    		// Make initial payment of dust limit, put it into this msg.
    		SignedTransaction signedTx = 
				state.getInitialSignedTeardownTx(Coin.valueOf(minPayment));
    		Protos.HTLCSignedTransaction.Builder signedTeardown = 
				Protos.HTLCSignedTransaction.newBuilder()
					.setTx(
						ByteString.copyFrom(signedTx.getTx().bitcoinSerialize())
					)
					.setSignature(
						ByteString.copyFrom(signedTx.getSig().encodeToBitcoin())
					);
    		Transaction tst = new Transaction(wallet.getParams(),
    				signedTeardown.getTx().toByteArray());
    		contractMsg.setSignedInitialTeardown(signedTeardown);
    	} catch (ValueOutOfRangeException e) {
    		throw new IllegalStateException(e);
    	}
    	
    	final Protos.TwoWayChannelMessage.Builder msg = 
			Protos.TwoWayChannelMessage.newBuilder();
    	msg.setHtlcProvideContract(contractMsg);
    	msg.setType(
			Protos.TwoWayChannelMessage.MessageType.HTLC_PROVIDE_CONTRACT
		);
    	conn.sendToServer(msg.build());
    }
    
    @GuardedBy("lock")
    private void receiveChannelOpen() throws VerificationException {
    	checkState(step == InitStep.WAITING_FOR_CHANNEL_OPEN);
    	log.info("Got CHANNEL_OPEN message, ready to pay");
    	step = InitStep.CHANNEL_OPEN;
    	conn.channelOpen(false); // dummy bool to keep interface
    }
    
    @GuardedBy("lock")
    private void receiveHtlcInitReply(Protos.TwoWayChannelMessage msg) {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	Protos.HTLCInitReply htlcInitReply = msg.getHtlcInitReply();
    	ByteString requestId = htlcInitReply.getClientRequestId();
    	ByteString hashId = htlcInitReply.getId();
    	
    	// Update the key in the map to only use one id from now on
    	SettableFuture<PaymentIncrementAck> paymentAckFuture = 
			paymentAckFutureMap.get(requestId);
    	paymentAckFutureMap.remove(requestId);
    	paymentAckFutureMap.put(htlcInitReply.getId(), paymentAckFuture);
    	// Let's set the future to return early in the protocol
    	
    	Coin storedValue = paymentValueMap.get(requestId);
    	paymentValueMap.remove(requestId);
    	
    	log.info("Received htlc INIT REPLY.");
    	
    	try {
    		SignedTransaction signedTx = state.getSignedTeardownTx(
    			hashId, 
				storedValue
			);
    		Protos.HTLCSignedTransaction.Builder signedTeardown =
				Protos.HTLCSignedTransaction.newBuilder()
					.setTx(ByteString.copyFrom(
						signedTx.getTx().bitcoinSerialize()
					))
					.setSignature(ByteString.copyFrom(
						signedTx.getSig().encodeToBitcoin()
					));
    		log.info ("Sending signed teardown to server");
    		
    		Protos.HTLCProvideSignedTeardown.Builder teardownMsg = 
				Protos.HTLCProvideSignedTeardown.newBuilder()
					.setId(hashId)
					.setSignedTeardown(signedTeardown);
    		final Protos.TwoWayChannelMessage.Builder channelMsg = 
				Protos.TwoWayChannelMessage.newBuilder();
    		channelMsg.setHtlcSignedTeardown(teardownMsg);
    		channelMsg.setType(
				Protos.TwoWayChannelMessage.MessageType.HTLC_SIGNED_TEARDOWN
			);
    		conn.sendToServer(channelMsg.build());
    		
		} catch (ValueOutOfRangeException e) {
			e.printStackTrace();
		}
    } 
    
    @GuardedBy("lock")
    private void receiveHtlcSignedRefundWithHash(
		Protos.TwoWayChannelMessage msg
	) {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	Protos.HTLCSignedRefundWithHash htlcSigRefundMsg = 
			msg.getHtlcSignedRefundWithHash();
    	Protos.HTLCSignedTransaction signedRefund = 
			htlcSigRefundMsg.getSignedRefund();
    	ByteString htlcId = htlcSigRefundMsg.getId();
    	Sha256Hash teardownHash = new Sha256Hash(
			htlcSigRefundMsg.getTeardownHash().toByteArray()
		);
    	Transaction refundTx = new Transaction(
			wallet.getParams(), 
			signedRefund.getTx().toByteArray()
		);
    	TransactionSignature refundSig = 
			TransactionSignature.decodeFromBitcoin(
				signedRefund.getSignature().toByteArray(), 
				true
			);
    	
    	log.info("Server teardown hash: {}", teardownHash);
    	
    	// finalize the HTLC refund and store it in the state
    	state.finalizeHTLCRefundTx(htlcId, refundTx, refundSig, teardownHash);
    	
    	SignedTransaction signedForfeit = state.getHTLCForfeitTx(
			htlcId, 
			teardownHash
		);
    	
    	SignedTransaction signedSettlement = state.getHTLCSettlementTx(
			htlcId, 
			teardownHash
		);
    	
    	Protos.HTLCSignedTransaction.Builder signedForfeitMsg =
			Protos.HTLCSignedTransaction.newBuilder()
				.setTx(ByteString.copyFrom(
					signedForfeit.getTx().bitcoinSerialize()
				))
				.setSignature(ByteString.copyFrom(
					signedForfeit.getSig().encodeToBitcoin()
				));
    	
    	Protos.HTLCSignedTransaction.Builder signedSettleMsg =
			Protos.HTLCSignedTransaction.newBuilder()
				.setTx(ByteString.copyFrom(
					signedSettlement.getTx().bitcoinSerialize()
				))
				.setSignature(ByteString.copyFrom(
					signedSettlement.getSig().encodeToBitcoin()
				));
    	
    	Protos.HTLCSignedSettleAndForfeit.Builder sigSettleForfeit = 
			Protos.HTLCSignedSettleAndForfeit.newBuilder()
				.setId(htlcId)
				.setSignedForfeit(signedForfeitMsg)
				.setSignedSettle(signedSettleMsg);
    	
    	final Protos.TwoWayChannelMessage.Builder serverMsg = 
			Protos.TwoWayChannelMessage.newBuilder();
    	serverMsg.setHtlcSignedSettleAndForfeit(sigSettleForfeit);
    	serverMsg.setType(
			Protos.TwoWayChannelMessage.MessageType.HTLC_SIGNED_SETTLE_FORFEIT
		);
    	
    	conn.sendToServer(serverMsg.build());
    }
    
    @GuardedBy("lock")
    private void receiveClose(Protos.TwoWayChannelMessage msg)
    		throws VerificationException {
    	checkState(lock.isHeldByCurrentThread());
    	if (msg.hasSettlement()) {
    		Transaction settleTx = new Transaction(
				wallet.getParams(), 
				msg.getSettlement().getTx().toByteArray()
			);
            log.info(
        		"CLOSE message received with settlement tx {}", 
        		settleTx.getHash()
    		);
            if (state != null && state.isSettlementTransaction(settleTx)) {
                // The wallet has a listener on it that the state object will 
            	// use to do the right thing at this point (like watching it 
            	// for confirmations). The tx has been checked by now for 
            	// syntactical validity and that it correctly spends the 
            	// multisig contract.
                wallet.receivePending(settleTx, null);
            }
    	} else {
            log.info("CLOSE message received without settlement tx");
        }
        if (step == InitStep.WAITING_FOR_CHANNEL_CLOSE)
            conn.destroyConnection(CloseReason.CLIENT_REQUESTED_CLOSE);
        else
            conn.destroyConnection(CloseReason.SERVER_REQUESTED_CLOSE);
        step = InitStep.CHANNEL_CLOSED;
    }

	@Override
	public void connectionClosed() {
		// Empty; no need for this
	}

	@Override
	public ListenableFuture<PaymentIncrementAck> incrementPayment(
		Coin value,
		@Nullable ByteString arg1,
		@Nullable KeyParameter arg2
	) throws ValueOutOfRangeException, IllegalStateException {
		lock.lock();
		try {
			checkState(step == InitStep.CHANNEL_OPEN);
			final SettableFuture<PaymentIncrementAck> incrementPaymentFuture = 
				SettableFuture.create();
			
			incrementPaymentFuture.addListener(new Runnable() {
	            @Override
	            public void run() {
	                lock.lock();
	                paymentAckFutureMap.values().remove(incrementPaymentFuture);
	                lock.unlock();
	            }
	        }, MoreExecutors.sameThreadExecutor());
			
			// We can generate a UUID to identify the token request response
			// This UUID will be mirrored back by the server
			ByteString requestId = 
				ByteString.copyFrom(UUID.randomUUID().toString().getBytes());
			paymentAckFutureMap.put(requestId, incrementPaymentFuture);
			paymentValueMap.put(requestId, value);
			
			Protos.HTLCInit.Builder htlcInitMsg = Protos.HTLCInit.newBuilder()
					.setRequestId(requestId)
					.setValue(value.getValue());
			
			// At this step we have to first generate a message to the server
			// to request an HTLC id and secret hash
			conn.sendToServer(Protos.TwoWayChannelMessage.newBuilder()
					.setHtlcInit(htlcInitMsg)
					.setType(Protos.TwoWayChannelMessage.MessageType.HTLC_INIT)
					.build());

			return incrementPaymentFuture;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void settle() throws IllegalStateException {
				
	}
}
