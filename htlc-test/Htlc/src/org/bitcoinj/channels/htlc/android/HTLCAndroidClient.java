package org.bitcoinj.channels.htlc.android;

import static com.google.common.base.Preconditions.checkState;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import net.jcip.annotations.GuardedBy;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.HTLCData;
import org.bitcoin.paymentchannel.Protos.HTLCFlow;
import org.bitcoin.paymentchannel.Protos.HTLCRegisterSensors;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoin.paymentchannel.Protos.HTLCFlow.FlowType;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage.MessageType;
import org.bitcoinj.channels.htlc.HTLCBlockingQueue;
import org.bitcoinj.channels.htlc.HTLCChannelServerState;
import org.bitcoinj.channels.htlc.HTLCServerState;
import org.bitcoinj.channels.htlc.SignedTransactionWithHash;
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
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;

public class HTLCAndroidClient implements IPaymentAndroidChannelClient {
	
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCAndroidClient.class);
	
	private final ReentrantLock lock = Threading.lock("htlcchannelserver");
	
	private final int SERVER_MAJOR_VERSION = 1;
    private final int SERVER_MINOR_VERSION = 0;
	private final int MAX_MESSAGES = 100;

	private enum InitStep {
        WAITING_ON_CLIENT_VERSION,
        WAITING_ON_UNSIGNED_REFUND,
        WAITING_ON_CONTRACT,
        WAITING_ON_MULTISIG_ACCEPTANCE,
        CHANNEL_OPEN
    }
    @GuardedBy("lock")
    private InitStep step = InitStep.WAITING_ON_CLIENT_VERSION;
    
    private enum HTLCRound {
		OFF,
		WAITING_FOR_ACK,
		CONFIRMED,
		CLIENT
	}
	@GuardedBy("lock") HTLCRound htlcRound = HTLCRound.OFF;
	
	@GuardedBy("lock")
	private final HTLCBlockingQueue<Object> blockingQueue;
	
	private final Wallet wallet;
	
	private final ECKey serverKey;
	 // The minimum accepted channel value
    private final Coin minAcceptedChannelSize;
	
	private final TransactionBroadcastScheduler broadcastScheduler;
	
	@GuardedBy("lock") private final ClientConnection conn;
	
	// Used to keep track of whether or not the "socket" ie connection is open 
    // and we can generate messages
    @GuardedBy("lock") private boolean connectionOpen = false;
    // Indicates that no further messages should be sent and we intend to 
    // settle the connection
    @GuardedBy("lock") private boolean channelSettling = false;
	
	 // The state manager for this channel
    @GuardedBy("lock") private HTLCChannelServerState state;
	
    // The time this channel expires (ie the refund transaction's locktime)
    @GuardedBy("lock") private long expireTime;
    
    // The list of HTLC ids that are being closed in this update round
    @GuardedBy("lock") private final List<String> closingHTLCIds;
    // The set of HTLC ids that are being created in this client update round
    @GuardedBy("lock") private final Set<String> newHTLCIds;
	
	HTLCAndroidClient(
		Wallet wallet,
		TransactionBroadcastScheduler broadcaster,
		ECKey key,
		Coin minPayment,
		ClientConnection conn
	) {
		this.wallet = wallet;
		this.broadcastScheduler = broadcaster;
		this.serverKey = key;
		this.minAcceptedChannelSize = minPayment;
		this.conn = conn;
		this.blockingQueue = new HTLCBlockingQueue<Object>(MAX_MESSAGES);
		this.closingHTLCIds = new ArrayList<String>();
		this.newHTLCIds = new HashSet<String>();
	}

	@Override
	public void receiveMessage(TwoWayChannelMessage msg) {
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
            			receiveRefundMessage(msg);
            			return;
            		case HTLC_PROVIDE_CONTRACT:
            			receiveContractMessage(msg);
            			return;
            		case HTLC_ROUND_INIT:
            			receiveHTLCRoundInitMessage(msg);
            			return;
            		case HTLC_ROUND_ACK:
            			receiveHTLCRoundAckMessage(msg);
            			return;
            		case HTLC_ROUND_DONE:
            			receiveHTLCRoundDoneMessage(msg);
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
        conn.sendToHub(
    		Protos.TwoWayChannelMessage.newBuilder()
    			.setType(Protos.TwoWayChannelMessage.MessageType.SERVER_VERSION)
                .setServerVersion(versionNegotiationBuilder)
                .build()
        );
   //     log.info(
    //		"Got initial version message, responding with VERSIONS and " +
   // 		"INITIATE: min value={}", minAcceptedChannelSize.value
//		);
        
        expireTime = 
        	Utils.currentTimeSeconds() + clientVersion.getTimeWindowSecs();
		
		step = InitStep.WAITING_ON_UNSIGNED_REFUND;
        
        Protos.Initiate.Builder initiateBuilder = Protos.Initiate.newBuilder()
            .setMultisigKey(ByteString.copyFrom(serverKey.getPubKey()))
            .setExpireTimeSecs(expireTime)
            .setMinAcceptedChannelSize(minAcceptedChannelSize.value)
            .setMinPayment(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.value);

        conn.sendToHub(Protos.TwoWayChannelMessage.newBuilder()
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
			broadcastScheduler
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
    	conn.sendToHub(Protos.TwoWayChannelMessage.newBuilder()
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
    		conn.sendToHub(Protos.TwoWayChannelMessage.newBuilder()
    				.setType(Protos.TwoWayChannelMessage.MessageType.CHANNEL_OPEN)
    				.build());
    		step = InitStep.CHANNEL_OPEN;
    		conn.connectionOpen(contractHash);
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
				bestPaymentChange 
			);
    	}
    	if (sendAck) {
	    	// Send the ACK for the payment
	    	final Protos.TwoWayChannelMessage.Builder ack = 
				Protos.TwoWayChannelMessage.newBuilder();
	    	ack.setType(Protos.TwoWayChannelMessage.MessageType.PAYMENT_ACK);
	    	conn.sendToHub(ack.build());
    	}    	
    }
	
	@GuardedBy("lock") 
    private void receiveHTLCRoundInitMessage(TwoWayChannelMessage msg) {
    	if (canAckHTLCRound()) {
    		log.info("Received block request for HTLC update from client");
    		// Send the HTLCRound ack back to the client
    		Protos.HTLCRoundAck.Builder htlcAck = Protos.HTLCRoundAck.newBuilder();
    		TwoWayChannelMessage ackMsg = TwoWayChannelMessage.newBuilder()
				.setHtlcRoundAck(htlcAck)
				.setType(MessageType.HTLC_ROUND_ACK)
				.build();
    		conn.sendToHub(ackMsg);
    		htlcRound = HTLCRound.CLIENT;
    	}
    }
	
	@GuardedBy("lock") 
    private void receiveHTLCRoundAckMessage(TwoWayChannelMessage msg) {
    	log.info("We can now push our batched updates to the hub");
    	htlcRound = HTLCRound.CONFIRMED;
    	// Retrieve all queued up updates
    	List<Object> allUpdates = blockingQueue.getAll();
    	log.info("UPDATE SIZE: {}", allUpdates.size());
    	List<Protos.HTLCRevealSecret> allSecrets = 
			new ArrayList<Protos.HTLCRevealSecret>();
    	List<Protos.HTLCBackOff> allBackoffs = 
			new ArrayList<Protos.HTLCBackOff>();
    	// Store the HTLC ids that are being closed this round
    	closingHTLCIds.clear();
    	
    	for (Object update: allUpdates) {
    		log.info("Processing an update {}", update.getClass());
    		if (update instanceof Protos.HTLCRevealSecret) {
    			Protos.HTLCRevealSecret secretMsg = 
					(Protos.HTLCRevealSecret) update;
    			allSecrets.add(secretMsg);
    			closingHTLCIds.add(secretMsg.getId());
    			log.error("HTLC_SET2 {} {}", secretMsg.getId(), new Date().getTime());
    			conn.paymentIncrease(state.getValueOfHTLC(secretMsg.getId()));
    		} else if (update instanceof Protos.HTLCBackOff) {
    			allBackoffs.add((Protos.HTLCBackOff) update);
    		}
    	}
    	
    	Protos.HTLCServerUpdate.Builder update = 
			Protos.HTLCServerUpdate.newBuilder()
				.addAllRevealSecrets(allSecrets)
				.addAllBackOffs(allBackoffs);
    	TwoWayChannelMessage.Builder updateMsg = 
			TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_SERVER_UPDATE)
				.setHtlcServerUpdate(update);
    	conn.sendToHub(updateMsg.build());
    }
	
	@GuardedBy("lock") 
    private void receiveHTLCRoundDoneMessage(TwoWayChannelMessage msg) {
    	// Set the htlcRound state
		log.info("Hub finished update round.");
    	htlcRound = HTLCRound.OFF;
    	if (!blockingQueue.isEmpty()) {
    		log.info("Initiating new update round from device");
    		// Start new server update round  
    		initializeHTLCRound();
    	}
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
		conn.sendToHub(initMsg);
		htlcRound = HTLCRound.WAITING_FOR_ACK;
    }
	
	@GuardedBy("lock")
    private void receiveHtlcInitMessage(TwoWayChannelMessage msg) 
    		throws UnsupportedEncodingException {
    	log.info("Received HTLC INIT msg, replying with HTLC_INIT_REPLY");
    	
    	newHTLCIds.clear();
    	
    	final Protos.HTLCInit htlcInit = msg.getHtlcInit();
    	List<Protos.HTLCPayment> newPayments = htlcInit.getNewPaymentsList();
    	List<Protos.HTLCPaymentReply> paymentsReply = 
			new ArrayList<Protos.HTLCPaymentReply>();
    	
    	for (Protos.HTLCPayment payment: newPayments) {
    		long value = payment.getValue();
    		HTLCServerState newHTLCState = 
				state.createNewHTLC(Coin.valueOf(value));
    		newHTLCIds.add(newHTLCState.getId());
    		Protos.HTLCPaymentReply paymentReply = 
				Protos.HTLCPaymentReply.newBuilder()
					.setId(newHTLCState.getId())
					.setClientRequestId(payment.getRequestId())
					.build();
    		paymentsReply.add(paymentReply);
    		// Store the data and when we confirm this HTLC we can 
    		// schedule a new async msg that gives the data to the buyer
    		newHTLCState.setData(
				conn.getDataFromSensor(payment.getSensorType())
			);
    	}
    	Protos.HTLCInitReply.Builder initReply = 
			Protos.HTLCInitReply.newBuilder()
				.addAllNewPaymentsReply(paymentsReply);
		final TwoWayChannelMessage replyMsg = TwoWayChannelMessage.newBuilder()
			.setType(MessageType.HTLC_INIT_REPLY)
			.setHtlcInitReply(initReply)
			.build();
		conn.sendToHub(replyMsg);
    }
	
	@GuardedBy("lock")
    private void receiveSignedTeardownMessage(TwoWayChannelMessage msg) {
    	//log.info("Received signed teardown message");
    	    	
    	final Protos.HTLCProvideSignedTeardown teardownMsg = 
			msg.getHtlcSignedTeardown();
    	final Protos.HTLCSignedTransaction signedTeardown = 
			teardownMsg.getSignedTeardown();
    	
    	List<String> htlcIds = teardownMsg.getIdsList();
    	List<Integer> htlcIdxs = teardownMsg.getIdxList();
    	Transaction teardownTx = new Transaction(
			wallet.getParams(), 
			signedTeardown.getTx().toByteArray()
		);
    	
    	TransactionSignature teardownSig = 
			TransactionSignature.decodeFromBitcoin(
				signedTeardown.getSignature().toByteArray(), true
			);
    	//log.info("Signing refund and teardown hash");
    	
    	List<Protos.HTLCSignedTransaction> allSignedRefunds = 
    		new ArrayList<Protos.HTLCSignedTransaction>();
    	
    	for (int i = 0; i < htlcIds.size(); i++) {
    		String htlcId = htlcIds.get(i);
    		int htlcIdx = htlcIdxs.get(i);
    		SignedTransactionWithHash sigTxWithHash = 
				state.getSignedRefundAndTeardownHash(
					htlcId, 
					htlcIdx, 
					teardownTx, 
					teardownSig
				);
    		Protos.HTLCSignedTransaction signedRefund = 
				Protos.HTLCSignedTransaction.newBuilder()
					.setTx(txToBS(sigTxWithHash.getTx()))
					.setSignature(sigToBS(sigTxWithHash.getSig()))
					.setTxHash(ByteString.copyFrom(
						sigTxWithHash.getSpentTxHash().getBytes()
					))
					.build();
    		allSignedRefunds.add(signedRefund);
    	}
    	
    	//log.info("Done signing refund and teardown hash");
    	//log.info("Sending Signed refunds and teardown hashes back to client");
    	
    	Protos.HTLCSignedRefundWithHash.Builder htlcSigRefund = 
			Protos.HTLCSignedRefundWithHash.newBuilder()
				.addAllIds(htlcIds)
				.addAllSignedRefund(allSignedRefunds);
    	
    	final TwoWayChannelMessage channelMsg = TwoWayChannelMessage.newBuilder()
			.setHtlcSignedRefundWithHash(htlcSigRefund)
			.setType(MessageType.HTLC_SIGNED_REFUND)
			.build();
    	
    	conn.sendToHub(channelMsg);
    }
	
	@GuardedBy("lock")
    private void receiveSignedSettleAndForfeitMsg(TwoWayChannelMessage msg) {
		
    	Protos.HTLCSignedSettleAndForfeit htlcMsg = 
			msg.getHtlcSignedSettleAndForfeit();
    	List<String> allIds = htlcMsg.getIdsList();
    	List<Protos.HTLCSignedTransaction> allForfeits = 
			htlcMsg.getSignedForfeitList();
    	List<Protos.HTLCSignedTransaction> allSettles = 
			htlcMsg.getSignedSettleList();
    	ECKey clientSecondaryKey = ECKey.fromPublicOnly(
			htlcMsg.getClientSecondaryKey().toByteArray()
		);
    	
    	// Store the client's secondary key
    	state.setClientSecondaryKey(clientSecondaryKey);
    	
    	for (int i = 0; i < allIds.size(); i++) {
    		String htlcId = allIds.get(i);
    		Protos.HTLCSignedTransaction signedForfeit = allForfeits.get(i);
    		Protos.HTLCSignedTransaction signedSettle = allSettles.get(i);
    		
    		Transaction settleTx = new Transaction(
				wallet.getParams(), 
				signedSettle.getTx().toByteArray()
			);
	    	TransactionSignature settleSig = 
    			TransactionSignature.decodeFromBitcoin(
					signedSettle.getSignature().toByteArray(), 
					true
				);
	    	state.finalizeHTLCSettlementTx(
    			htlcId, 
    			settleTx,
    			settleSig
    		);
	    	
	    	Transaction forfeitTx = new Transaction(
    			wallet.getParams(),
    			signedForfeit.getTx().toByteArray()
    		);
        	TransactionSignature forfeitSig = 
    			TransactionSignature.decodeFromBitcoin(
	    			signedForfeit.getSignature().toByteArray(), 
	    			true
	    		);
        	state.finalizeHTLCForfeitTx(
    			htlcId,
        		forfeitTx,
        		forfeitSig
    		);
        	// If we already have some secrets, queue them up for the next
        	// server update round; only do this if we're in a hub update round
        	// else it will keep retrieving the same secrets over and over again
        	if (htlcRound ==  HTLCRound.CLIENT && newHTLCIds.contains(htlcId)) {
	        	queueUpSecret(htlcId);
        	}
    	}
    	
    	if (htlcRound == HTLCRound.CONFIRMED) { 
    		// It's the server's round, send a round done msg instead of setup
    		// complete
    		
    		// The data msgs are sent async. They will not break the transaction
    		// signature process so it is safe to do it;
    		List<HTLCData> allData = new ArrayList<>();
    		for (String htlcId: closingHTLCIds) {
    			log.error("HTLC_COMP2 {} {}", htlcId, new Date().getTime());
    			List<String> sensorData = state.getDataForHTLC(htlcId);
    			if (sensorData == null) {
    				log.error(
						"Retrieving data but failed with NULL for htlcID {}. " +
						"This should not happen.", htlcId
					);
    			} else {
    				//log.info("Retrieving data for HTLCId {}", htlcId);
    				//log.info("Data is: {}", sensorData);
	    			HTLCData.Builder dataMsg = HTLCData.newBuilder()
	    				.setId(htlcId)
						.addAllData(state.getDataForHTLC(htlcId));
	    			allData.add(dataMsg.build());
    			}
    			// Remove from the HTLCMap
    			state.removeHTLC(htlcId);
    		}
    		// Send the data msgs to the Hub so it can forward them correctly
    		Protos.HTLCFlow.Builder flowMsg = Protos.HTLCFlow.newBuilder()
				.addAllData(allData)
				.setType(FlowType.DATA);
    		Protos.TwoWayChannelMessage dataMsg = 
				Protos.TwoWayChannelMessage.newBuilder()
					.setHtlcFlow(flowMsg)
					.setType(MessageType.HTLC_FLOW)
					.build();
    		conn.sendToHub(dataMsg);
    		
    		//log.info("Device Round done!");
    		
        	Protos.HTLCRoundDone.Builder roundDone =
    			Protos.HTLCRoundDone.newBuilder();
        	final Protos.TwoWayChannelMessage.Builder roundDoneMsg = 
    			Protos.TwoWayChannelMessage.newBuilder()
    				.setHtlcRoundDone(roundDone)
    				.setType(MessageType.HTLC_ROUND_DONE);
        	conn.sendToHub(roundDoneMsg.build()); 
        	htlcRound = HTLCRound.OFF;
    	} else {
	    	// Let's ACK the successful setup
	    	Protos.HTLCSetupComplete.Builder setupMsg = 
				Protos.HTLCSetupComplete.newBuilder()
					.addAllIds(allIds);
	    	final Protos.TwoWayChannelMessage.Builder htlcSetupMsg =
				Protos.TwoWayChannelMessage.newBuilder()
					.setHtlcSetupComplete(setupMsg)
					.setType(
						Protos.TwoWayChannelMessage.
							MessageType.HTLC_SETUP_COMPLETE
					);
	    	conn.sendToHub(htlcSetupMsg.build());
    	}
    }
	
	@GuardedBy("lock") 
    private void queueUpSecret(String htlcId) {
    	String secret = state.getSecretForHTLC(htlcId);
		//log.info("Retrieveing HTLC secret for htlc id {}: {}", htlcId, secret);
    	if (secret != null) {
    		Protos.HTLCRevealSecret revealMsg = 
				Protos.HTLCRevealSecret.newBuilder()
					.setId(htlcId)
					.setSecret(secret)
					.build();
    		blockingQueue.put(revealMsg);
    		log.info("Queueing up secret for next SERVER round");
    		log.info("QUEUE SIZE: {}", blockingQueue.size());
    	}
    }

	@GuardedBy("lock")
	public void updateSensors(List<String> sensors, long price) {
		List<Long> prices = new ArrayList<>();
		for (int i = 0; i < sensors.size(); i++) {
			prices.add(price);
		}
		HTLCRegisterSensors.Builder registerMsg = 
			HTLCRegisterSensors.newBuilder()
				.addAllSensors(sensors)
				.addAllPrices(prices);
		HTLCFlow.Builder flowMsg = HTLCFlow.newBuilder()
			.setType(FlowType.REGISTER_SENSORS)
			.setRegisterSensors(registerMsg);
		final TwoWayChannelMessage channelMsg = 
			TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_FLOW)
				.setHtlcFlow(flowMsg)
				.build();
		conn.sendToHub(channelMsg);
	}
	
	@GuardedBy("lock")
    private void receiveCloseMessage() throws InsufficientMoneyException {
      //  log.info("Got CLOSE message, closing channel");
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
                conn.sendToHub(msg.build());
                conn.destroyConnection(clientRequestedClose);
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to broadcast settlement tx", t);
                conn.destroyConnection(clientRequestedClose);
            }
        });
    }
	
	private boolean canAckHTLCRound() {
    	return (
			htlcRound == HTLCRound.OFF || 
			htlcRound == HTLCRound.WAITING_FOR_ACK
		);
    }
	
	private static ByteString txToBS(Transaction tx) {
    	return ByteString.copyFrom(tx.bitcoinSerialize());
    }
    
    private static ByteString sigToBS(TransactionSignature sig) {
    	return ByteString.copyFrom(sig.encodeToBitcoin());
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
        conn.sendToHub(Protos.TwoWayChannelMessage.newBuilder()
                .setError(errorBuilder)
                .setType(Protos.TwoWayChannelMessage.MessageType.ERROR)
                .build());
        conn.destroyConnection(closeReason);
    }
}
