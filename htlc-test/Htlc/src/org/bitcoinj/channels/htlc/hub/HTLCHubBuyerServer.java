package org.bitcoinj.channels.htlc.hub;


import static com.google.common.base.Preconditions.checkState;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.HTLCData;
import org.bitcoin.paymentchannel.Protos.HTLCFlow.FlowType;
import org.bitcoin.paymentchannel.Protos.HTLCBackOff;
import org.bitcoin.paymentchannel.Protos.HTLCFlow;
import org.bitcoin.paymentchannel.Protos.HTLCPayment;
import org.bitcoin.paymentchannel.Protos.HTLCPaymentReply;
import org.bitcoin.paymentchannel.Protos.HTLCRevealSecret;
import org.bitcoin.paymentchannel.Protos.HTLCServerUpdate;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage.MessageType;
import org.bitcoinj.channels.htlc.HTLCBlockingQueue;
import org.bitcoinj.channels.htlc.HTLCChannelServerState;
import org.bitcoinj.channels.htlc.HTLCServerState;
import org.bitcoinj.channels.htlc.SignedTransaction;
import org.bitcoinj.channels.htlc.SignedTransactionWithHash;
import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
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
public class HTLCHubBuyerServer {
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCHubBuyerServer.class);
	
	private final ReentrantLock lock = new ReentrantLock();
	private final int MAX_MESSAGES = 100;
	
	private final int SERVER_MAJOR_VERSION = 1;
    private final int SERVER_MINOR_VERSION = 0;
    
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
	
	@GuardedBy("lock")
	private final Map<String, HTLCPayment> htlcPaymentMap;
	 
	// The list of HTLC ids that are being closed in this server update round 
    @GuardedBy("lock")
    private final List<String> closingHTLCIds;
    // The set of HTLC ids that are being created in this client update round
    @GuardedBy("lock") private final Set<String> newHTLCIds;
    
    /**
     * Implements the connection between this server and the client, providing 
     * an interface which allows messages to be
     * sent to the client, requests for the connection to the client to be 
     * closed, and callbacks which occur when the
     * channel is fully open or the client completes a payment.
     */
    public interface ServerConnection {
    	
    	public void addAddress(Address addr);
    	
    	public List<String> nodeStats();
    	
    	public Set<String> sensorStats();
    	
    	public void select(
			String id,
			String sensorType, 
			HTLCHubBuyerServer server
		);
    	
    	public void sendToBuyer(TwoWayChannelMessage msg);
    	
    	public void forwardToAndroidServers(
			HTLCHubBuyerServer server,
			TwoWayChannelMessage msg			
		);
    	
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
    
    // The time this channel expires (ie the refund transaction's locktime)
    @GuardedBy("lock") private long expireTime;
    
    public HTLCHubBuyerServer(
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
    	this.blockingQueue = new HTLCBlockingQueue<Object>(MAX_MESSAGES);
    	this.htlcPaymentMap = new HashMap<String, HTLCPayment>();
    	this.closingHTLCIds = new ArrayList<String>();
    	this.newHTLCIds = new HashSet<String>();
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
            			receiveHTLCInitMessage(msg);
            			return;
            		case HTLC_INIT_REPLY:
            			// This must have been forwarded from the android server!
            			receiveHTLCInitReplyMessage(msg);
            			return;
            		case HTLC_SIGNED_TEARDOWN:
            			receiveSignedTeardownMessage(msg);
            			return;
            		case HTLC_SIGNED_SETTLE_FORFEIT:
            			receiveSignedSettleAndForfeitMsg(msg);
            			return;
            		case HTLC_SERVER_UPDATE:
            			// This must have been forwarded from the android server!
            			receiveServerUpdateMsg(msg);
            			return;
            		case HTLC_FLOW:
            			receiveHTLCFlowMsg(msg);
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
        		InsufficientMoneyException e
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
    	
    	ECKey clientKey = ECKey.fromPublicOnly(
			clientVersion.getClientKey().toByteArray()
		);
    	
    	// TODO: REMOVE THIS: ONLY FOR TESTING PURPOSES
    	conn.addAddress(clientKey.toAddress(wallet.getParams()));
    	
    	Protos.ServerVersion.Builder versionNegotiationBuilder =
			Protos.ServerVersion.newBuilder()
				.setMajor(SERVER_MAJOR_VERSION)
				.setMinor(SERVER_MINOR_VERSION);
        conn.sendToBuyer(
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

        conn.sendToBuyer(Protos.TwoWayChannelMessage.newBuilder()
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
    	conn.sendToBuyer(Protos.TwoWayChannelMessage.newBuilder()
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
							multisigContractPropagated(
								providedContract, 
								multisigContract.getHash()
							);
						} catch (ValueOutOfRangeException e) {
							e.printStackTrace();
						}
    				}
    			}, Threading.SAME_THREAD);
    }
    
    private void multisigContractPropagated(
		Protos.HTLCProvideContract providedContract,
		Sha256Hash contractHash
	) throws ValueOutOfRangeException {
    	lock.lock();
    	try {
    		receiveInitialPaymentMessage(
				providedContract.getSignedInitialTeardown(),
				false
			);
    		conn.sendToBuyer(Protos.TwoWayChannelMessage.newBuilder()
    				.setType(
						Protos.TwoWayChannelMessage.MessageType.CHANNEL_OPEN
					)
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
    	
    	if (sendAck) {
	    	// Send the ACK for the payment
	    	final Protos.TwoWayChannelMessage.Builder ack = 
				Protos.TwoWayChannelMessage.newBuilder();
	    	ack.setType(Protos.TwoWayChannelMessage.MessageType.PAYMENT_ACK);
	    	conn.sendToBuyer(ack.build());
    	}    	
    }
    
    @GuardedBy("lock") 
    private void receiveHTLCRoundInitMessage(TwoWayChannelMessage msg) {
    	log.info("RECEIVED HTLCINIT ROUND IN STATE: {}", htlcRound.toString());
    	if (canAckHTLCRound()) {
    		log.info("Received block request for HTLC update from client");
    		// Send the HTLCRound ack back to the client
    		Protos.HTLCRoundAck.Builder htlcAck = Protos.HTLCRoundAck.newBuilder();
    		TwoWayChannelMessage ackMsg = TwoWayChannelMessage.newBuilder()
				.setHtlcRoundAck(htlcAck)
				.setType(MessageType.HTLC_ROUND_ACK)
				.build();
    		conn.sendToBuyer(ackMsg);
    		htlcRound = HTLCRound.CLIENT;
    	}
    }
    
    @GuardedBy("lock") 
    private void receiveHTLCRoundAckMessage(TwoWayChannelMessage msg) {
    	log.info("We can now push our batched updates to the client");
    	htlcRound = HTLCRound.CONFIRMED;
    	// Retrieve all queued up updates
    	List<Object> allUpdates = blockingQueue.getAll();
    	List<Protos.HTLCRevealSecret> allSecrets = 
			new ArrayList<Protos.HTLCRevealSecret>();
    	List<Protos.HTLCBackOff> allBackoffs = 
			new ArrayList<Protos.HTLCBackOff>();
    	
    	// Store the HTLC ids that are being closed
    	closingHTLCIds.clear();
    	
    	for (Object update: allUpdates) {
    		if (update instanceof Protos.HTLCRevealSecret) {
    			Protos.HTLCRevealSecret revealMsg = 
					(Protos.HTLCRevealSecret) update;
    			allSecrets.add(revealMsg);
    			closingHTLCIds.add(revealMsg.getId());
    			log.error("HTLC_SET1 {} {}", revealMsg.getId(), new Date().getTime());
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
    	conn.sendToBuyer(updateMsg.build());
    }
    
    @GuardedBy("lock") 
    private void receiveHTLCRoundDoneMessage(TwoWayChannelMessage msg) {
    	// Set the htlcRound state
    	log.info("Received HTLCRound Done. Closing round");
    	htlcRound = HTLCRound.OFF;
    	if (!blockingQueue.isEmpty()) {
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
		conn.sendToBuyer(initMsg);
		htlcRound = HTLCRound.WAITING_FOR_ACK;
    }
       
    /**
     * This method is on the outter hub, thus at this point we don't create
     * the HTLC but forward all the payments created to the devices so we receive
     * an HTLC hash-id for each.
     * @param msg
     */
    @GuardedBy("lock")
    private void receiveHTLCInitMessage(TwoWayChannelMessage msg) {
    	log.info("Received HTLC INIT msg, forwarding to devices");
    	
    	final Protos.HTLCInit htlcInit = msg.getHtlcInit();
    	List<Protos.HTLCPayment> paymentList = htlcInit.getNewPaymentsList();
    	
    	for (Protos.HTLCPayment payment: paymentList) {
    		String deviceId = payment.getDeviceId();
    		log.info("Received HTLCINIT for {}", deviceId);
    		String requestId = payment.getRequestId();
    		// Store these payments in a map
    		htlcPaymentMap.put(requestId, payment);
       	}
    	
    	conn.forwardToAndroidServers(this, msg);
    }
    
    @GuardedBy("lock") 
    private void receiveHTLCInitReplyMessage(TwoWayChannelMessage msg) {
    	log.info("Received HTLC_INIT_REPLY from Android server. " +
    			"Processing it. Creating HTLCs and Sending it " +
    			"to the buyer client");

    	final Protos.HTLCInitReply htlcReply = msg.getHtlcInitReply();
    	
    	List<Protos.HTLCPaymentReply> paymentReplies = 
			htlcReply.getNewPaymentsReplyList();
    	
    	newHTLCIds.clear();
    	
    	for (Protos.HTLCPaymentReply reply: paymentReplies) {
    		String clientRequestId = reply.getClientRequestId();
    		String htlcId = reply.getId();
    		log.info("Received android reply htlcid {}", htlcId);
    		newHTLCIds.add(htlcId);
    		HTLCPayment payment = htlcPaymentMap.get(clientRequestId);
			htlcPaymentMap.remove(clientRequestId);
			htlcPaymentMap.put(htlcId, payment);
    		
    		// Update the map
    		long value = payment.getValue();
			state.createPreinitializedHTLC(Coin.valueOf(value), htlcId);
    	}
    	
    	// Forward the message to the buyer client
    	conn.sendToBuyer(msg);
    }
      
    private static ByteString txToBS(Transaction tx) {
    	return ByteString.copyFrom(tx.bitcoinSerialize());
    }
    
    private static ByteString sigToBS(TransactionSignature sig) {
    	return ByteString.copyFrom(sig.encodeToBitcoin());
    }
    
    @GuardedBy("lock")
    private void receiveSignedTeardownMessage(TwoWayChannelMessage msg) {
    	log.info("Received signed teardown message");
    	    	
    	final Protos.HTLCProvideSignedTeardown teardownMsg = 
			msg.getHtlcSignedTeardown();
    	final Protos.HTLCSignedTransaction signedTeardown = 
			teardownMsg.getSignedTeardown();
    	List<String> htlcIds = teardownMsg.getIdsList();
    	log.info("BUYER SERVER IDS: {}", Arrays.toString(htlcIds.toArray()));
    	
    	List<Integer> htlcIdxs = teardownMsg.getIdxList();
    	Transaction teardownTx = new Transaction(
			wallet.getParams(), 
			signedTeardown.getTx().toByteArray()
		);
    	
    	log.info("RECEIVED TEARDOWN: {}", teardownTx);
    	
    	TransactionSignature teardownSig = 
			TransactionSignature.decodeFromBitcoin(
				signedTeardown.getSignature().toByteArray(), true
			);
    	log.info("Signing refund and teardown hash");
    	
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
    	
    	log.info("Done signing refund and teardown hash");
    	log.info("Sending Signed refunds and teardown hashes back to client");
    	
    	Protos.HTLCSignedRefundWithHash.Builder htlcSigRefund = 
			Protos.HTLCSignedRefundWithHash.newBuilder()
				.addAllSignedRefund(allSignedRefunds)
				.addAllIds(htlcIds);
    	
    	final TwoWayChannelMessage channelMsg = TwoWayChannelMessage.newBuilder()
			.setHtlcSignedRefundWithHash(htlcSigRefund)
			.setType(MessageType.HTLC_SIGNED_REFUND)
			.build();
    	
    	conn.sendToBuyer(channelMsg);
    }
    
    @GuardedBy("lock")
    private void receiveSignedSettleAndForfeitMsg(TwoWayChannelMessage msg) {
    	log.info("Received Signed Settle and Forfeit");
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
        	// server update round
        	if (htlcRound == HTLCRound.CLIENT && newHTLCIds.contains(htlcId)) {
        		log.info("Attempt to queue up secret for HTLC ID {}", htlcId);
        		queueUpSecret(htlcId);
        	}
    	}
    	
    	if (htlcRound == HTLCRound.CONFIRMED) { 
    		// The data msgs are sent async. They will not break the transaction
    		// signature process so it is safe to do it;
    		List<HTLCData> allData = new ArrayList<>();
    		for (String htlcId: closingHTLCIds) {
    			log.error("HTLC_COMP1 {} {}", htlcId, new Date().getTime());
    			List<String> sensorData = state.getDataForHTLC(htlcId);
    			if (sensorData == null) {
    				log.error(
						"Retrieving data but failed with NULL for htlcID {}. " +
						"Data is late.", htlcId
					);
    			} else {
	    			HTLCData.Builder dataMsg = HTLCData.newBuilder()
	    				.setId(htlcId)
						.addAllData(state.getDataForHTLC(htlcId));
	    			allData.add(dataMsg.build());
    			}
    			// Remove from the HTLCMap
    			state.removeHTLC(htlcId);
    		}
    		// Send the data msgs to the Buyer
    		Protos.HTLCFlow.Builder flowMsg = Protos.HTLCFlow.newBuilder()
				.addAllData(allData)
				.setType(FlowType.DATA);
    		Protos.TwoWayChannelMessage dataMsg = 
				Protos.TwoWayChannelMessage.newBuilder()
					.setHtlcFlow(flowMsg)
					.setType(MessageType.HTLC_FLOW)
					.build();
    		conn.sendToBuyer(dataMsg);
    		
        	Protos.HTLCRoundDone.Builder roundDone = 
    			Protos.HTLCRoundDone.newBuilder();
        	final Protos.TwoWayChannelMessage.Builder roundDoneMsg = 
    			Protos.TwoWayChannelMessage.newBuilder()
    				.setHtlcRoundDone(roundDone)
    				.setType(MessageType.HTLC_ROUND_DONE);
        	conn.sendToBuyer(roundDoneMsg.build());
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
						Protos.TwoWayChannelMessage
							.MessageType.HTLC_SETUP_COMPLETE
					);
	    	conn.sendToBuyer(htlcSetupMsg.build());
	    	
	    	// Call all the Android handlers to create the corresponding HTLCs
	    	// on the path; ONLY DO IT FOR THE NEWLY CREATED HTLCs
	    	log.info("Sending resume setup for {}", Arrays.toString(newHTLCIds.toArray()));
	    	Protos.HTLCResumeSetup.Builder htlcResume = 	
				Protos.HTLCResumeSetup.newBuilder()
					.addAllHtlcId(newHTLCIds);
	    	Protos.HTLCFlow flow = Protos.HTLCFlow.newBuilder()
				.setType(FlowType.RESUME_SETUP)
				.setResumeSetup(htlcResume)
				.build();
    		final Protos.TwoWayChannelMessage channelMsg = 
    			Protos.TwoWayChannelMessage.newBuilder()
    				.setType(MessageType.HTLC_FLOW)
    				.setHtlcFlow(flow)
    				.build();
    		conn.forwardToAndroidServers(this, channelMsg);
    	}
    }
    
    @GuardedBy("lock")
    private void receiveServerUpdateMsg(TwoWayChannelMessage msg) {
    	log.info("Received Server Update msg");
    	checkState(step == InitStep.CHANNEL_OPEN);
    	HTLCServerUpdate updateMsg = msg.getHtlcServerUpdate();
    	List<Protos.HTLCRevealSecret> allSecrets = 
			updateMsg.getRevealSecretsList();
    	List<Protos.HTLCBackOff> allBackOffs = updateMsg.getBackOffsList();
    	for (HTLCRevealSecret secret: allSecrets) {
    		log.info("QUEUE SIZE: {}", blockingQueue.size());
    		blockingQueue.put(secret);
    	}
    	for (HTLCBackOff backoff: allBackOffs) {
    		blockingQueue.put(backoff);
    	}
    	if (canInitHTLCRound()) {
			initializeHTLCRound();
		}
    }
    
    private boolean canInitHTLCRound() {
		return (htlcRound == HTLCRound.OFF);
	}
    
    @GuardedBy("lock") 
    private void queueUpSecret(String htlcId) {
    	String secret = state.getSecretForHTLC(htlcId);
    	if (secret != null) {
    		log.info("Queueing up secret for next Hub Buyer round");
    		Protos.HTLCRevealSecret revealMsg = 
				Protos.HTLCRevealSecret.newBuilder()
					.setId(htlcId)
					.setSecret(secret)
					.build();
    		log.info("QUEUE SIZE: {}", blockingQueue.size());
    		blockingQueue.put(revealMsg);
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
                conn.sendToBuyer(msg.build());
                conn.destroyConnection(clientRequestedClose);
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to broadcast settlement tx", t);
                conn.destroyConnection(clientRequestedClose);
            }
        });
    }
    
    @GuardedBy("lock")
    private void receiveHTLCFlowMsg(TwoWayChannelMessage msg) {
    	Protos.HTLCFlow flowMsg = msg.getHtlcFlow();
    	Protos.HTLCFlow.FlowType type = flowMsg.getType();
    	switch (type) {
    		case NODE_STATS:
    			receiveNodeStatsMsg(flowMsg.getId());
    			return;
    		case REGISTER_SENSORS:
    			return;
    		case SENSOR_STATS:
    			receiveSensorStatsMsg(flowMsg.getId());
    			return;
    		case SELECT:
    			receiveSelectMsg(flowMsg.getId(), flowMsg.getSelectData());
    			return;
    		case DATA:
    			receiveDataMsg(flowMsg.getDataList());
    			return;
    		default:
    			return;
    	}
    }
    
    @GuardedBy("lock")
    private void receiveNodeStatsMsg(String id) {
    	List<String> nodeStats = conn.nodeStats();
    	Protos.HTLCNodeStats.Builder nodeMsg = 
			Protos.HTLCNodeStats.newBuilder()
				.addAllDevices(nodeStats);
    	Protos.HTLCFlow.Builder flowMsg = Protos.HTLCFlow.newBuilder()
			.setId(id)
			.setNodeStats(nodeMsg)
			.setType(FlowType.NODE_STATS_REPLY); 
    	conn.sendToBuyer(
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_FLOW)
				.setHtlcFlow(flowMsg).build()
		);			
    }
    
    @GuardedBy("lock")
    private void receiveSensorStatsMsg(String id) {
    	Set<String> sensorStats = conn.sensorStats();
    	Protos.HTLCSensorStats.Builder sensorMsg =
			Protos.HTLCSensorStats.newBuilder()
				.addAllSensors(sensorStats);
    	Protos.HTLCFlow.Builder flowMsg = Protos.HTLCFlow.newBuilder()
			.setId(id)
			.setSensorStats(sensorMsg)
			.setType(FlowType.SENSOR_STATS_REPLY); 
    	conn.sendToBuyer(
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_FLOW)
				.setHtlcFlow(flowMsg).build()
		);
    }
    
    @GuardedBy("lock")
    private void receiveSelectMsg(String id, Protos.HTLCSelectData selectData) {
    	conn.select(id, selectData.getSensorType(), this);
    }
    
    @GuardedBy("lock")
    private void receiveDataMsg(List<HTLCData> dataList) {
    	List<HTLCData> dataToRelease = new ArrayList<HTLCData>();
    	for (HTLCData data: dataList) {
        	log.info("Received DATA FLOW MSG. Processing it");
    		String htlcId = data.getId();
    		List<String> sensorData = data.getDataList();
    		log.info("DATA IS: {}", Arrays.toString(sensorData.toArray()));
    		if (state.isHTLCActive(htlcId)) {
    			// This means the transaction that pays for this data has 
    			// not been closed yet; Store the data
        		state.setDataForHTLC(htlcId, sensorData);
    		} else {
    			// Transaction has been closed; We can release the data
    			dataToRelease.add(data);
    		}
    	}
    	Protos.HTLCFlow.Builder flowMsg = Protos.HTLCFlow.newBuilder()
			.setType(FlowType.DATA)
			.addAllData(dataToRelease);
    	final Protos.TwoWayChannelMessage channelMsg = 
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_FLOW)
				.setHtlcFlow(flowMsg)
				.build();
    	conn.sendToBuyer(channelMsg);
    }
    
    @GuardedBy("lock")
    public void sendSelectResult(
		String id,
		List<String> deviceIds,
		List<String> sensors,
		List<Long> prices
	) {
    	Protos.HTLCPaymentInfo.Builder payInfo = 
			Protos.HTLCPaymentInfo.newBuilder(); 
    	for (int i = 0; i < sensors.size(); i++) {
    		payInfo.addAllDeviceIds(deviceIds);
    		payInfo.addSensorTypes(sensors.get(i));
    		payInfo.addPrices(prices.get(i));
    	}
    	
    	Protos.HTLCFlow flow = Protos.HTLCFlow.newBuilder()
    		.setType(HTLCFlow.FlowType.PAYMENT_INFO)
			.setId(id)
			.setPaymentInfo(payInfo)
			.build();
    	conn.sendToBuyer(
			Protos.TwoWayChannelMessage.newBuilder()
				.setType(MessageType.HTLC_FLOW)
				.setHtlcFlow(flow).build()
		);
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
                conn.sendToBuyer(msg.build());
                conn.destroyConnection(CloseReason.SERVER_REQUESTED_CLOSE);
            }
        } finally {
            lock.unlock();
        }
    }
    
    private boolean canAckHTLCRound() {
    	return (
			htlcRound == HTLCRound.OFF || 
			htlcRound == HTLCRound.WAITING_FOR_ACK
		);
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
        conn.sendToBuyer(Protos.TwoWayChannelMessage.newBuilder()
                .setError(errorBuilder)
                .setType(Protos.TwoWayChannelMessage.MessageType.ERROR)
                .build());
        conn.destroyConnection(closeReason);
    }
}
