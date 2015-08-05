package org.bitcoinj.channels.htlc.buyer;


import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import net.jcip.annotations.GuardedBy;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoin.paymentchannel.Protos.HTLCFlow.FlowType;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage.MessageType;
import org.bitcoinj.channels.htlc.FlowResponse;
import org.bitcoinj.channels.htlc.HTLCBlockingQueue;
import org.bitcoinj.channels.htlc.HTLCChannelClientState;
import org.bitcoinj.channels.htlc.HTLCClientState;
import org.bitcoinj.channels.htlc.PriceInfo;
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
import org.bitcoinj.protocols.channels.IPaymentChannelClient;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
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
public class HTLCBuyerClient implements IPaymentChannelClient {
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCBuyerClient.class);
	private static final int CLIENT_MAJOR_VERSION = 1;
	private final int CLIENT_MINOR_VERSION = 0;
	private static final int SERVER_MAJOR_VERSION = 1;
	private final int MAX_MESSAGES = 10;
	 
	private final ECKey clientPrimaryKey;
	private final ECKey clientSecondaryKey;
	
	private final long timeWindow;
	@GuardedBy("lock") private long minPayment;
	
	private final ReentrantLock lock = Threading.lock("htlcchannelclient");
	private final HTLCBlockingQueue<Protos.HTLCPayment> blockingQueue;
	private List<Protos.HTLCPayment> currentBatch;

	@GuardedBy("lock") private final ClientConnection conn;
	@GuardedBy("lock") private HTLCChannelClientState state;
	
	@GuardedBy("lock")
	private Map<String, SettableFuture<PaymentIncrementAck>> 
		paymentAckFutureMap;
	
	@GuardedBy("lock")
	private final Map<String, SettableFuture<FlowResponse>> responseFutureMap;
	
	@GuardedBy("lock")
	private final Map<String, SettableFuture<List<PriceInfo>>> 
		paymentInfoFutureMap;
	
	@GuardedBy("lock")
	private Map<String, Coin> paymentValueMap;
	
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
    private final Coin value;
    private Coin missing;
    
    public HTLCBuyerClient(
    	Wallet wallet,
    	TransactionBroadcastScheduler broadcastScheduler,
    	ECKey clientPrimaryKey,
    	ECKey clientSecondaryKey,
    	Coin value,
    	long timeWindow,
    	ClientConnection conn
	) {
    	this.wallet = wallet;
    	this.broadcastScheduler = broadcastScheduler;
    	this.clientPrimaryKey = clientPrimaryKey;
    	this.clientSecondaryKey = clientSecondaryKey;
    	this.value = value;
    	this.timeWindow = timeWindow;
    	this.conn = conn;
    	this.paymentAckFutureMap = 
			new HashMap<String, SettableFuture<PaymentIncrementAck>>();
    	this.paymentValueMap = new HashMap<String, Coin>();
    	this.blockingQueue = 
			new HTLCBlockingQueue<Protos.HTLCPayment>(MAX_MESSAGES);
    	this.currentBatch = new ArrayList<Protos.HTLCPayment>();
    	this.responseFutureMap = 
			new HashMap<String, SettableFuture<FlowResponse>>();
    	this.paymentInfoFutureMap = 
			new HashMap<String, SettableFuture<List<PriceInfo>>>(); 
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
                    .setType(
                		Protos.TwoWayChannelMessage.MessageType.CLIENT_VERSION
            		)
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
	    		case HTLC_ROUND_ACK:
	    			receiveHTLCRoundAck();
	    			return;
	    		case HTLC_ROUND_DONE:
	    			receiveHTLCRoundDone();
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
	    		case HTLC_PAYMENT_ACK:
					receiveHTLCPaymentAck(msg);
	    			return;
	    		case HTLC_FLOW:
	    			receiveHTLCFlowMsg(msg);
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
	    	conn.sendToServer(Protos.TwoWayChannelMessage.newBuilder()
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
    private void receiveHTLCRoundInit() {
    	if (htlcRound == HTLCRound.OFF) {
    		log.info("Received block request for HTLC update from server");
    		// Send the HTLCRound ack back to server to lock in update round
    		Protos.HTLCRoundAck.Builder htlcAck = 
				Protos.HTLCRoundAck.newBuilder();
    		TwoWayChannelMessage ackMsg = TwoWayChannelMessage.newBuilder()
				.setHtlcRoundAck(htlcAck)
				.setType(MessageType.HTLC_ROUND_ACK)
				.build();
    		conn.sendToServer(ackMsg);
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
    	conn.sendToServer(initMsg);
    }
    
    @GuardedBy("lock")
    private void receiveHTLCRoundDone() {
    	log.info("Server finished its round of updates");
    	htlcRound = HTLCRound.OFF;
    	if (!blockingQueue.isEmpty()) {
    		// Start new client update round
    		initializeHTLCRound();
    	}
    }
    
    @GuardedBy("lock")
    private void receiveHTLCInitReply(Protos.TwoWayChannelMessage msg) 
    		throws ValueOutOfRangeException {
    	
    	checkState(step == InitStep.CHANNEL_OPEN);
    	log.info("Received htlc INIT REPLY.");
    	
    	Protos.HTLCInitReply htlcInitReply = msg.getHtlcInitReply();
    	
    	List<ByteString> ids = new ArrayList<ByteString>();
    	List<Integer> idxs = new ArrayList<Integer>();
    	List<Protos.HTLCPaymentReply> paymentsReply = 
			htlcInitReply.getNewPaymentsReplyList();
    	
    	for (Protos.HTLCPaymentReply paymentReply: paymentsReply) {
    		ByteString requestId = paymentReply.getClientRequestId();
    		ByteString hashId = paymentReply.getId();
    		
    		String requestIdString = new String(requestId.toByteArray());
    		String id = new String(hashId.toByteArray());
    		
    		// Update the key in the map to only use one id from now on
        	SettableFuture<PaymentIncrementAck> paymentAckFuture = 
    			paymentAckFutureMap.get(requestIdString);
        	paymentAckFutureMap.remove(requestIdString);
        	paymentAckFutureMap.put(id, paymentAckFuture);
        	
        	Coin storedValue = paymentValueMap.get(requestIdString);
        	paymentValueMap.remove(requestIdString);
        	paymentValueMap.put(id, storedValue);
        
    		int htlcIdx = state.updateTeardownTxWithHTLC(id, storedValue);
    		ids.add(hashId);
    		idxs.add(htlcIdx);
    	}
    	
    	log.info("Done processing HTLC init reply");
    	
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
				.addAllIds(ids)
				.addAllIdx(idxs)
				.setSignedTeardown(signedTeardown);
    	final Protos.TwoWayChannelMessage.Builder channelMsg =
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_SIGNED_TEARDOWN)
				.setHtlcSignedTeardown(teardownMsg);
    	conn.sendToServer(channelMsg.build());
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
    	List<ByteString> allIds = htlcSigRefundMsg.getIdsList();
    	List<Protos.HTLCSignedTransaction> allSignedForfeits =
			new ArrayList<Protos.HTLCSignedTransaction>();
    	List<Protos.HTLCSignedTransaction> allSignedSettles =
			new ArrayList<Protos.HTLCSignedTransaction>();
    	
    	for (int i = 0; i < allSignedRefunds.size(); i++) {
    		Protos.HTLCSignedTransaction signedRefund = allSignedRefunds.get(i);
    		ByteString htlcId = allIds.get(i);
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
    		String id = new String(htlcId.toByteArray());
    		state.finalizeHTLCRefundTx(id, refundTx, refundSig, teardownHash);	
    		
    		SignedTransaction signedForfeit = state.getHTLCForfeitTx(
				id,
				teardownHash
			);
    		Protos.HTLCSignedTransaction signedForfeitProto = 
				Protos.HTLCSignedTransaction.newBuilder()
					.setTx(txToBS(signedForfeit.getTx()))
					.setSignature(sigToBS(signedForfeit.getSig()))
					.build();
    		
    		SignedTransaction signedSettle = state.getHTLCSettlementTx(
				id, 
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
					ByteString.copyFrom(clientSecondaryKey.getPubKey())
				);
    	
    	final Protos.TwoWayChannelMessage.Builder serverMsg = 
			Protos.TwoWayChannelMessage.newBuilder();
    	serverMsg.setHtlcSignedSettleAndForfeit(sigSettleForfeit);
    	serverMsg.setType(
			Protos.TwoWayChannelMessage.MessageType.HTLC_SIGNED_SETTLE_FORFEIT
		);
    	
    	conn.sendToServer(serverMsg.build());
    }
    
    @GuardedBy("lock")
    private void receiveHTLCSetupComplete(Protos.TwoWayChannelMessage msg) 
    		throws ValueOutOfRangeException {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	List<ByteString> allIds = msg.getHtlcSetupComplete().getIdsList();
    	for (ByteString id: allIds) {
        	log.info("received HTLC setup complete for {}", new String(id.toByteArray()));
    		state.makeSettleable(new String(id.toByteArray()));
    	}
    	htlcRound = HTLCRound.OFF;
    	
    	// Client update round is over, signal this to the server
    	Protos.HTLCRoundDone.Builder roundDone = Protos.HTLCRoundDone.newBuilder();
    	final Protos.TwoWayChannelMessage.Builder roundDoneMsg = 
			Protos.TwoWayChannelMessage.newBuilder()
				.setHtlcRoundDone(roundDone)
				.setType(MessageType.HTLC_ROUND_DONE);
    	conn.sendToServer(roundDoneMsg.build());
    }
    
    @GuardedBy("lock")
    private void receiveHTLCServerUpdate(Protos.TwoWayChannelMessage msg) 
    		throws ValueOutOfRangeException {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	Protos.HTLCServerUpdate updateMsg = msg.getHtlcServerUpdate();
    	List<Protos.HTLCRevealSecret> allSecrets = 
			updateMsg.getRevealSecretsList();
    	List<Protos.HTLCBackOff> allBackOffs = updateMsg.getBackOffsList();
    	
    	for (Protos.HTLCRevealSecret secretMsg: allSecrets) {
    		String htlcId = new String(secretMsg.getId().toByteArray());
    		String secret = new String(secretMsg.getSecret().toByteArray());
    		state.attemptSettle(htlcId, secret);
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
    	
    	List<HTLCClientState> allHTLCs = state.getAllActiveHTLCS();
    	List<ByteString> allIds = new ArrayList<ByteString>();
    	List<Integer> allIdxs = new ArrayList<Integer>();
    	for (HTLCClientState htlcState: allHTLCs) {
    		ByteString htlcId = ByteString.copyFrom(htlcState.getId().getBytes());
    		int htlcIdx = htlcState.getIndex();
    		allIds.add(htlcId);
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
    	conn.sendToServer(channelMsg.build());
    }
   
    @GuardedBy("lock")
    private void receiveHTLCPaymentAck(Protos.TwoWayChannelMessage msg) {
    	checkState(step == InitStep.CHANNEL_OPEN);
    	log.info("Received HTLC payment ACK message");
    	Protos.HTLCPaymentAck ack = msg.getHtlcPaymentAck();
    	ByteString htlcId = ack.getId();
    	String id = new String(htlcId.toByteArray());
    	Coin value = paymentValueMap.remove(id);
    	// This will cancel the broadcast of the HTLC refund Tx
    	state.cancelHTLCRefundTxBroadcast(id);
    	// Let's set the future - we are done with this HTLC
    	SettableFuture<PaymentIncrementAck> future = 
			paymentAckFutureMap.get(id);
    	future.set(new PaymentIncrementAck(value, htlcId));
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
			String reqIdString = new String(UUID.randomUUID().toString());
			paymentAckFutureMap.put(reqIdString, incrementPaymentFuture);
			paymentValueMap.put(reqIdString, value);
			
			Protos.HTLCPayment newPayment = Protos.HTLCPayment.newBuilder()
				.setRequestId(ByteString.copyFrom(reqIdString.getBytes()))
				.setValue(value.getValue())
				.build();
			
			if (canInitHTLCRound()) {
				currentBatch.add(newPayment);
				initializeHTLCRound();
			} else {
				blockingQueue.put(newPayment);
			}
			return incrementPaymentFuture;
		} finally {
			lock.unlock();
		}
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
		conn.sendToServer(initMsg);
		htlcRound = HTLCRound.WAITING_FOR_ACK;
	}
	
	@GuardedBy("lock")
	public ListenableFuture<FlowResponse> nodeStats() {
		lock.lock();
		try {
			log.info("Sending nodeStats query to twin hub");
			// We can generate a UUID to identify the token request response
			// This UUID will be mirrored back by the server
			String reqIdString = new String(UUID.randomUUID().toString());
			SettableFuture<FlowResponse> responseFuture = 
				SettableFuture.create();
			responseFutureMap.put(reqIdString, responseFuture);
			
			Protos.HTLCFlow.Builder stats = Protos.HTLCFlow.newBuilder()
				.setId(reqIdString)
				.setType(Protos.HTLCFlow.FlowType.NODE_STATS);
			final TwoWayChannelMessage msg = 
				Protos.TwoWayChannelMessage.newBuilder()
					.setHtlcFlow(stats)
					.setType(MessageType.HTLC_FLOW)
					.build();
			conn.sendToServer(msg);
			
			return responseFuture;
		} finally {
			lock.unlock();
		}
	}
	
	private void receiveHTLCFlowMsg(TwoWayChannelMessage msg) {
		Protos.HTLCFlow flowMsg = msg.getHtlcFlow();
		FlowType type = flowMsg.getType();
		String id = flowMsg.getId();
		switch (type) {
			case NODE_STATS_REPLY:
				receiveNodeStatsReply(id, flowMsg.getNodeStats());
				return;
			case SENSOR_STATS_REPLY:
				//receiveSensorStatsReply(id, flowMsg.getNodeStats());
				return;
			case PAYMENT_INFO:
				receivePaymentInfo(id, flowMsg.getPaymentInfo());
			default:
				return;
		}
	}
	
	private void receiveNodeStatsReply(
		String id, 
		Protos.HTLCNodeStats nodeStats
	) {
		SettableFuture<FlowResponse> flowFuture = responseFutureMap.get(id);
		flowFuture.set(new FlowResponse(nodeStats.getDevicesList()));
	}
/*
	public ListenableFuture<String> sensorStats() {
		log.info("Sending sensorStats query to twin hub");
		Protos.HTLCFlow.Builder stats = Protos.HTLCFlow.newBuilder()
			.setType(Protos.HTLCFlow.FlowType.SENSOR_STATS);
		final TwoWayChannelMessage msg = 
			Protos.TwoWayChannelMessage.newBuilder()
				.setHtlcFlow(stats)
				.setType(MessageType.HTLC_FLOW)
				.build();
		conn.sendToServer(msg);
	}
*/
	@GuardedBy("lock")
	private void receivePaymentInfo(
		String id, 
		Protos.HTLCPaymentInfo paymentInfo
	) {
		SettableFuture<List<PriceInfo>> future = paymentInfoFutureMap.get(id);
		List<PriceInfo> priceInfoList = new ArrayList<PriceInfo>();
		for (int i = 0; i < paymentInfo.getSensorTypesList().size(); i++) {
			priceInfoList.add(
				new PriceInfo(
					paymentInfo.getSensorTypes(i), 
					paymentInfo.getPrices(i)
				)
			);
		}
		future.set(priceInfoList);
	}
	
	@GuardedBy("lock")
	public ListenableFuture<List<PriceInfo>> select(String sensorType) {
		lock.lock();
		try {
			// We can generate a UUID to identify the token request response
			// This UUID will be mirrored back by the server
			String reqIdString = new String(UUID.randomUUID().toString());
			SettableFuture<List<PriceInfo>> responseFuture = 
				SettableFuture.create();
			paymentInfoFutureMap.put(reqIdString, responseFuture);
			
			Protos.HTLCSelectData.Builder selectData = 
				Protos.HTLCSelectData.newBuilder()
					.setSensorType(sensorType);
			Protos.HTLCFlow.Builder stats = Protos.HTLCFlow.newBuilder()
				.setId(reqIdString)
				.setSelectData(selectData)
				.setType(Protos.HTLCFlow.FlowType.SELECT);
			final TwoWayChannelMessage msg = 
				Protos.TwoWayChannelMessage.newBuilder()
					.setHtlcFlow(stats)
					.setType(MessageType.HTLC_FLOW)
					.build();
			conn.sendToServer(msg);
			
			return responseFuture;
		} finally {
			lock.unlock();
		}
	}
	
	@Override
	public void settle() throws IllegalStateException {
				
	}
}
