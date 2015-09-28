package org.bitcoinj.channels.htlc.hub;


import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage.MessageType;
import org.bitcoinj.channels.htlc.HTLCBlockingQueue;
import org.bitcoinj.channels.htlc.HTLCChannelClientState;
import org.bitcoinj.channels.htlc.HTLCClientState;
import org.bitcoinj.channels.htlc.SignedTransaction;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;


/**
 * Handler class that is in charge of most complexity of creating an HTLC
 * payment channel connection
 * @author frabu
 *
 */
public class HTLCHubAndroidServer {
	private static final org.slf4j.Logger log = 
			LoggerFactory.getLogger(HTLCHubAndroidServer.class);
	private static final int SERVER_MAJOR_VERSION = 1;
	private static final int CLIENT_MAJOR_VERSION = 1;
	private final int CLIENT_MINOR_VERSION = 0;
	private final int MAX_MESSAGES = 100;
	
	@GuardedBy("lock") private long minPayment;
	
	private final ReentrantLock lock = new ReentrantLock();
	private final HTLCBlockingQueue<Protos.HTLCPayment> blockingQueue;
	private List<Protos.HTLCPayment> currentBatch;

	@GuardedBy("lock") private final ServerConnection conn;
	@GuardedBy("lock") private HTLCChannelClientState state;
	
	@GuardedBy("lock")
	private Map<String, SettableFuture<PaymentIncrementAck> > 
		paymentAckFutureMap;
	
	@GuardedBy("lock")
	private Map<String, Coin> paymentValueMap;
	
	@GuardedBy("lock")
	private Map<String, Long> htlcInitTimeMap;
	
	private final String deviceId;
	
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
	
	private enum HTLCRound {
		OFF,
		WAITING_FOR_ACK,
		CONFIRMED,
		SERVER
	}
	@GuardedBy("lock") 
	private HTLCRound htlcRound = HTLCRound.OFF;
	
	private TransactionBroadcastScheduler broadcastScheduler;
	 
	// The wallet associated with this channel
    private final Wallet wallet;
    private final ECKey primaryKey;
    private final ECKey secondaryKey;
    
    private final Coin value;
    private final long timeWindow;
    
    public interface ServerConnection {
    	public void sendToDevice(Protos.TwoWayChannelMessage msg);
    	public void registerSensors(
			List<String> sensors, 
			List<Long> prices
		);
    	public void unregisterDevice(HTLCHubAndroidServer server);
    	public void destroyConnection(CloseReason reason);
    	public void channelOpen(Sha256Hash contractHash);
    	public boolean acceptExpireTime(long expireTime);
    	public void forwardToBuyerServers(
    		HTLCHubAndroidServer fromAndroidServer,
			TwoWayChannelMessage msg
		);
    }
    
    public HTLCHubAndroidServer(
    	String deviceId,
		TransactionBroadcastScheduler broadcaster,
		Wallet wallet,
		ECKey primaryKey,
		ECKey secondaryKey,
		Coin value,
		long timeWindow,
		ServerConnection conn
	) {
    	this.deviceId = deviceId;
    	this.wallet = wallet;
    	this.primaryKey = primaryKey;
    	this.secondaryKey = secondaryKey;
    	this.value = value;
    	this.timeWindow = timeWindow;
    	this.broadcastScheduler = broadcaster;
    	this.conn = conn;
    	this.paymentAckFutureMap = 
			new HashMap<String, SettableFuture<PaymentIncrementAck>>();
    	this.paymentValueMap = new HashMap<String, Coin>();
    	this.blockingQueue = 
			new HTLCBlockingQueue<Protos.HTLCPayment>(MAX_MESSAGES);
    	this.currentBatch = new ArrayList<Protos.HTLCPayment>();
    	this.htlcInitTimeMap = new HashMap<String, Long>();
    }
    
    public String getDeviceId() {
    	return deviceId;
    }
	    
    public void receiveMessage(Protos.TwoWayChannelMessage msg) {
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
	    			if (
						msg.getServerVersion().getMajor() != 
						SERVER_MAJOR_VERSION
					) {
	    				errorBuilder = Protos.Error.newBuilder()
							.setCode(
								Protos.Error.ErrorCode.NO_ACCEPTABLE_VERSION
							);
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
	    			closeReason = 
						receiveInitiate(initiate, value, errorBuilder);
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
	    		case HTLC_ROUND_INIT:
	    			receiveHTLCRoundInit();
	    			return;
	    		case HTLC_ROUND_ACK:
	    			receiveHTLCRoundAck();
	    			return;
	    		case HTLC_ROUND_DONE:
	    			receiveHTLCRoundDone(msg);
	    			return;
	    		case HTLC_INIT:
	    			// This must come from the buyer hub server
	    			receiveHTLCInit(msg);
	    			return;
	    		case HTLC_INIT_REPLY:
	    			receiveHTLCInitReply(msg);
	    			return;
	    		case HTLC_SIGNED_REFUND:
	    			receiveHTLCSignedRefundWithHash(msg);
	    			return;
	    		case HTLC_SETUP_COMPLETE:
	    			receiveHTLCSetupComplete(msg);
	    			return;
	    		case HTLC_SERVER_UPDATE:
	    			receiveHTLCServerUpdate(msg);
	    			return;
	    		case HTLC_FLOW:
	    			receiveHTLCFlow(msg);
	    			return;
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
	    	conn.sendToDevice(Protos.TwoWayChannelMessage.newBuilder()
	    			.setError(errorBuilder)
	                .setType(Protos.TwoWayChannelMessage.MessageType.ERROR)
	                .build());
        	conn.destroyConnection(closeReason);
    	} catch (ValueOutOfRangeException e) {
			e.printStackTrace();
		} finally {
    		lock.unlock();
    	}
    }
    
    @GuardedBy("lock")
    public void select(String id, String sensorType) {
    	lock.lock();
    	try {
    		final Protos.HTLCSelectData selectData = 
				Protos.HTLCSelectData.newBuilder()
					.setSensorType(sensorType)
					.build();
    		Protos.HTLCFlow htlcFlow = Protos.HTLCFlow.newBuilder()
				.setId(id)
				.setSelectData(selectData)
				.setType(Protos.HTLCFlow.FlowType.SELECT)
				.build();
    		conn.sendToDevice(Protos.TwoWayChannelMessage.newBuilder()
					.setType(MessageType.HTLC_FLOW)
					.setHtlcFlow(htlcFlow)
					.build());
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
    		primaryKey,
    		secondaryKey,
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
					ByteString.copyFrom(primaryKey.getPubKey())
				)
				.setTx(ByteString.copyFrom(
					state.getIncompleteRefundTransaction().bitcoinSerialize()
				));
    	conn.sendToDevice(Protos.TwoWayChannelMessage.newBuilder()
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
    	
    	// Schedule the broadcast of the channel refund;
    	// Ensure the tx is safely stored in the wallet
    	state.scheduleRefundTxBroadcast();
    	
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
    	conn.sendToDevice(msg.build());
    }
    
    @GuardedBy("lock")
    private void receiveChannelOpen() throws VerificationException {
    	checkState(step == InitStep.WAITING_FOR_CHANNEL_OPEN);
    	log.info("Got CHANNEL_OPEN message, ready to pay");
    	step = InitStep.CHANNEL_OPEN;
    }
    
    @GuardedBy("lock")
    private void receiveHTLCRoundInit() {
    	if (htlcRound == HTLCRound.OFF) {
    		log.info("Received block request for HTLC update from device");
    		// Send the HTLCRound ack back to server to lock in update round
    		Protos.HTLCRoundAck.Builder htlcAck = 
				Protos.HTLCRoundAck.newBuilder();
    		TwoWayChannelMessage ackMsg = TwoWayChannelMessage.newBuilder()
				.setHtlcRoundAck(htlcAck)
				.setType(MessageType.HTLC_ROUND_ACK)
				.build();
    		conn.sendToDevice(ackMsg);
    		htlcRound = HTLCRound.SERVER;
    		// Take snapshot of teardown to be used when verifying forfeit tx
    		// signatures
    		state.takeTeardownTxSnapshot();
    	}
    }
    
    @GuardedBy("lock")
    private void receiveHTLCRoundAck() {
    	log.info("We can now push our batched updates to the server");
    	htlcRound = HTLCRound.CONFIRMED;
    	// Retrieve all queued up updates
    	currentBatch = blockingQueue.getAll();
    	Protos.HTLCInit.Builder htlcInit = Protos.HTLCInit.newBuilder()
			.addAllNewPayments(currentBatch);
    	final TwoWayChannelMessage initMsg = TwoWayChannelMessage.newBuilder()
			.setType(MessageType.HTLC_INIT)
			.setHtlcInit(htlcInit)
			.build();
    	conn.sendToDevice(initMsg);
    }
    
    @GuardedBy("lock")
    private void receiveHTLCRoundDone(Protos.TwoWayChannelMessage msg) {
    	log.info("Device finished its round of updates");
    	// Mark HTLCs as settleable 
    	state.makeAllSettleable();
    	htlcRound = HTLCRound.OFF;
    	if (!blockingQueue.isEmpty()) {
    		// Start new client update round
    		initializeHTLCRound();
    	}
    }
    
    @GuardedBy("lock")
    public void receiveHTLCInit(Protos.TwoWayChannelMessage msg) {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	log.info("Received htlc INIT forwarded from Buyer server");
    	Protos.HTLCInit htlcInit = msg.getHtlcInit();
    	
    	for (Protos.HTLCPayment payment: htlcInit.getNewPaymentsList()) {
    		blockingQueue.put(payment);
    		paymentValueMap.put(
				payment.getRequestId(), 
				Coin.valueOf(payment.getValue())
			);
    		htlcInitTimeMap.put(payment.getRequestId(), new Date().getTime());
    	}
    	if (canInitHTLCRound()) {
			initializeHTLCRound();
		}
    }
    
    @GuardedBy("lock")
    private void receiveHTLCInitReply(Protos.TwoWayChannelMessage msg) {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	log.info("Received HTLC INIT Reply from Android client. " +
    			"Forwarding it to the Buyer Hub server");
    	
    	Protos.HTLCInitReply htlcInitReply = msg.getHtlcInitReply();

    	for (
			Protos.HTLCPaymentReply paymentReply: 
				htlcInitReply.getNewPaymentsReplyList()
		) {
    		String requestId = paymentReply.getClientRequestId();
    		String htlcId = paymentReply.getId();
    		log.info("Received Android reply with htlcId {}", htlcId);
    		
    		// Update the map
    		Coin storedValue = paymentValueMap.get(requestId);
    		paymentValueMap.remove(requestId);
    		paymentValueMap.put(htlcId, storedValue);
    		
    		long htlcInitTime = htlcInitTimeMap.get(requestId);
    		log.error("HTLC_INIT2 {} {}", htlcId, htlcInitTime);
    		htlcInitTimeMap.remove(requestId);
    	}
    	
    	conn.forwardToBuyerServers(this, msg);
    }
    
    private static ByteString txToBS(Transaction tx) {
    	return ByteString.copyFrom(tx.bitcoinSerialize());
    }
    
    private static ByteString sigToBS(TransactionSignature sig) {
    	return ByteString.copyFrom(sig.encodeToBitcoin());
    }
	    
    @GuardedBy("lock")
    private void receiveHTLCSignedRefundWithHash(
		Protos.TwoWayChannelMessage msg
	) {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	Protos.HTLCSignedRefundWithHash htlcSigRefundMsg = 
			msg.getHtlcSignedRefundWithHash();
    	
    	List<Protos.HTLCSignedTransaction> allSignedRefunds = 
			htlcSigRefundMsg.getSignedRefundList();
    	List<String> allIds = htlcSigRefundMsg.getIdsList();
    	List<Protos.HTLCSignedTransaction> allSignedForfeits =
			new ArrayList<Protos.HTLCSignedTransaction>();
    	List<Protos.HTLCSignedTransaction> allSignedSettles =
			new ArrayList<Protos.HTLCSignedTransaction>();
    	
    	for (int i = 0; i < allSignedRefunds.size(); i++) {
    		Protos.HTLCSignedTransaction signedRefund = allSignedRefunds.get(i);
    		String htlcId = allIds.get(i);
    		Sha256Hash teardownHash = new Sha256Hash(
				signedRefund.getTxHash().toByteArray()
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
    		state.finalizeHTLCRefundTx(htlcId, refundTx, refundSig, teardownHash);	
    		
    		SignedTransaction signedForfeit = state.getHTLCForfeitTx(
				htlcId,
				teardownHash
			);
    		Protos.HTLCSignedTransaction signedForfeitProto = 
				Protos.HTLCSignedTransaction.newBuilder()
					.setTx(txToBS(signedForfeit.getTx()))
					.setSignature(sigToBS(signedForfeit.getSig()))
					.build();
    		
    		SignedTransaction signedSettle = state.getHTLCSettlementTx(
				htlcId, 
				teardownHash
			);
    		Protos.HTLCSignedTransaction signedSettleProto =
				Protos.HTLCSignedTransaction.newBuilder()
					.setTx(txToBS(signedSettle.getTx()))
					.setSignature(sigToBS(signedSettle.getSig()))
					.build();
    		
    		allSignedForfeits.add(signedForfeitProto);
    		allSignedSettles.add(signedSettleProto);
    	}    
    	
    	Protos.HTLCSignedSettleAndForfeit.Builder sigSettleForfeit = 
			Protos.HTLCSignedSettleAndForfeit.newBuilder()
				.addAllIds(allIds)
				.addAllSignedForfeit(allSignedForfeits)
				.addAllSignedSettle(allSignedSettles)
				.setClientSecondaryKey(
					ByteString.copyFrom(secondaryKey.getPubKey())
				);
    	
    	final Protos.TwoWayChannelMessage.Builder serverMsg = 
			Protos.TwoWayChannelMessage.newBuilder();
    	serverMsg.setHtlcSignedSettleAndForfeit(sigSettleForfeit);
    	serverMsg.setType(
			Protos.TwoWayChannelMessage.MessageType.HTLC_SIGNED_SETTLE_FORFEIT
		);
    	
    	conn.sendToDevice(serverMsg.build());
    }
    
    @GuardedBy("lock")
    private void receiveHTLCSetupComplete(Protos.TwoWayChannelMessage msg) 
    		throws ValueOutOfRangeException {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	List<String> allIds = msg.getHtlcSetupComplete().getIdsList();
    	for (String id: allIds) {
    		log.error("HTLC_SETUP2 {} {}", id, new Date().getTime());
    		state.makeSettleable(id);
    	}
    	htlcRound = HTLCRound.OFF;
    	
    	// Client update round is over, signal this to the server
    	Protos.HTLCRoundDone.Builder roundDone = 
			Protos.HTLCRoundDone.newBuilder();
    	final Protos.TwoWayChannelMessage.Builder roundDoneMsg = 
			Protos.TwoWayChannelMessage.newBuilder()
				.setHtlcRoundDone(roundDone)
				.setType(MessageType.HTLC_ROUND_DONE);
    	conn.sendToDevice(roundDoneMsg.build());
    }
    
    @GuardedBy("lock")
    private void receiveHTLCServerUpdate(Protos.TwoWayChannelMessage msg) 
    		throws ValueOutOfRangeException {
    	log.info("Received HTLC Server Update. Processing request");
    	checkState(step == InitStep.CHANNEL_OPEN);
    	Protos.HTLCServerUpdate updateMsg = msg.getHtlcServerUpdate();
    	List<Protos.HTLCRevealSecret> allSecrets = 
			updateMsg.getRevealSecretsList();
    	log.info("NUMBER OF SECRETS: {}", allSecrets.size());
    	List<Protos.HTLCBackOff> allBackOffs = updateMsg.getBackOffsList();
    	
    	for (Protos.HTLCRevealSecret secretMsg: allSecrets) {
    		String secret = secretMsg.getSecret();
    		log.info("Validating secret: {}", secret);
    		// Remove all incorrect secrets; do no propagate them on the path
    		if (!state.attemptSettle(secretMsg.getId(), secret)) {
    			log.info(
					"Received incorrect secret for HTLC id: {} {}", 
					secretMsg.getId(), secretMsg.getSecret()
				);
    			allSecrets.remove(secretMsg);
    		}
    	}
    	
    	for (Protos.HTLCBackOff backOffMsg: allBackOffs) {
    		String htlcId = new String(backOffMsg.toByteArray());
    		Protos.HTLCSignedTransaction signedForfeitMsg = 
				backOffMsg.getSignedForfeit();
    		Transaction forfeitTx = new Transaction(
				wallet.getParams(),
				signedForfeitMsg.getTx().toByteArray()
			);
    		TransactionSignature forfeitSig = 
				TransactionSignature.decodeFromBitcoin(
					signedForfeitMsg.toByteArray(),
					true
				);
    		state.attemptBackoff(htlcId, forfeitTx, forfeitSig);
    	}
    	
    	List<HTLCClientState> allHTLCs = state.getAllActiveHTLCs();
    	List<String> allIds = new ArrayList<String>();
    	List<Integer> allIdxs = new ArrayList<Integer>();
    	for (HTLCClientState htlcState: allHTLCs) {
    		int htlcIdx = htlcState.getIndex();
    		allIds.add(htlcState.getId());
    		allIdxs.add(htlcIdx);
    	}

    	SignedTransaction signedTx = state.getSignedTeardownTx();
    	Protos.HTLCSignedTransaction.Builder signedTeardown =
			Protos.HTLCSignedTransaction.newBuilder()
				.setTx(ByteString.copyFrom(signedTx.getTx().bitcoinSerialize()))
				.setSignature(ByteString.copyFrom(
					signedTx.getSig().encodeToBitcoin()
				));
    	Protos.HTLCProvideSignedTeardown.Builder teardownMsg = 
			Protos.HTLCProvideSignedTeardown.newBuilder()
				.addAllIds(allIds)
				.addAllIdx(allIdxs)
				.setSignedTeardown(signedTeardown);
    	final Protos.TwoWayChannelMessage.Builder channelMsg =
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_SIGNED_TEARDOWN)
				.setHtlcSignedTeardown(teardownMsg);
    	conn.sendToDevice(channelMsg.build());
    	
    	// After checking everything, we can forward the secrets and backoffs
    	// to the buyer hub
    	Protos.HTLCServerUpdate.Builder newHTLCServerUpdate = 
			Protos.HTLCServerUpdate.newBuilder()
				.addAllRevealSecrets(allSecrets)
				.addAllBackOffs(allBackOffs);
    	final Protos.TwoWayChannelMessage forwardMsg = 
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_SERVER_UPDATE)
				.setHtlcServerUpdate(newHTLCServerUpdate)
				.build();
    	log.info("Processed HTLC Server update and forwarding correct secrets to buyer servers");
    	conn.forwardToBuyerServers(this, forwardMsg);
    }
    
    @GuardedBy("lock")
    private void receiveHTLCFlow(Protos.TwoWayChannelMessage msg) 
    		throws ValueOutOfRangeException {
    	Protos.HTLCFlow flowMsg = msg.getHtlcFlow();
    	switch(flowMsg.getType()) {
    		case REGISTER_SENSORS:
    			conn.registerSensors(
					flowMsg.getRegisterSensors().getSensorsList(), 
					flowMsg.getRegisterSensors().getPricesList()
				);
    			break;
    		case RESUME_SETUP:
    			receiveResumeSetup(flowMsg.getResumeSetup());
    			break;
    		case DATA:
    			log.info("RECEIVED DATA ON ANDROID SERVEr; Forwarding");
    			conn.forwardToBuyerServers(this, msg);
    			break;
    		default:
    			break;
    	}
    }
    
    @GuardedBy("lock")
    private void receiveResumeSetup(Protos.HTLCResumeSetup setupMsg) 
    		throws ValueOutOfRangeException {
    	
    	log.info("Received Resume Setup");
    	
    	checkState(htlcRound == HTLCRound.CONFIRMED);
    	List<String> idList = setupMsg.getHtlcIdList();
    	List<Integer> idxList = new ArrayList<Integer>();
    	
    	for (String htlcId: idList) {
    		Coin value = paymentValueMap.get(htlcId);
    		int htlcIdx = state.updateTeardownTxWithHTLC(htlcId, value);
    		idxList.add(htlcIdx);
    	}
    	
    	log.info("Sending back Signed teardown with updated ids");
    	
    	SignedTransaction signedTx = state.getSignedTeardownTx();
    	Protos.HTLCSignedTransaction.Builder signedTeardown =
			Protos.HTLCSignedTransaction.newBuilder()
				.setTx(ByteString.copyFrom(signedTx.getTx().bitcoinSerialize()))
				.setSignature(ByteString.copyFrom(
					signedTx.getSig().encodeToBitcoin()
				));
    	Protos.HTLCProvideSignedTeardown.Builder teardownMsg = 
			Protos.HTLCProvideSignedTeardown.newBuilder()
				.addAllIds(idList)
				.addAllIdx(idxList)
				.setSignedTeardown(signedTeardown);
    	final Protos.TwoWayChannelMessage.Builder channelMsg =
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_SIGNED_TEARDOWN)
				.setHtlcSignedTeardown(teardownMsg);
    	conn.sendToDevice(channelMsg.build());
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
	
	private boolean canInitHTLCRound() {
		return (
			htlcRound == HTLCRound.OFF || 
			htlcRound == HTLCRound.WAITING_FOR_ACK
		);
	}
	
	private void initializeHTLCRound() {
		log.info("Sending block request for HTLC update round!");
		Protos.HTLCRoundInit.Builder initRound = 
			Protos.HTLCRoundInit.newBuilder();
		final TwoWayChannelMessage initMsg = 
			Protos.TwoWayChannelMessage.newBuilder()
				.setHtlcRoundInit(initRound)
				.setType(MessageType.HTLC_ROUND_INIT)
				.build();
		conn.sendToDevice(initMsg);
		htlcRound = HTLCRound.WAITING_FOR_ACK;
	}
    
    /**
     * Called to indicate the connection has been opened and 
     * messages can now be generated for the client.
     */
    public void connectionOpen() {
    	log.info(
			"New server channel active. Sending version negotiation" +
			"Android device"
		);
    	lock.lock();
    	try {
    		step = InitStep.WAITING_FOR_VERSION_NEGOTIATION;
    		log.info("Sending version negotiation to server");
    		Protos.ClientVersion.Builder versionNegotiationBuilder = 
				Protos.ClientVersion.newBuilder()
                    .setMajor(CLIENT_MAJOR_VERSION)
                    .setMinor(CLIENT_MINOR_VERSION)
                    .setTimeWindowSecs(timeWindow);
    		conn.sendToDevice(Protos.TwoWayChannelMessage.newBuilder()
	                .setType(
	            		Protos.TwoWayChannelMessage.MessageType.CLIENT_VERSION
	        		)
	                .setClientVersion(versionNegotiationBuilder)
	                .build());
    	} finally {
    		lock.unlock();
    	}
    }
    
    public void connectionClosed() {
   		log.info("Server channel closed. Unregistering device");
   		conn.unregisterDevice(this);
    }
}
