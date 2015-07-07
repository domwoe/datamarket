package org.bitcoinj.channels.htlc;

import static com.google.common.base.Preconditions.checkState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bitcoinj.core.AbstractWalletEventListener;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.AllowUnconfirmedCoinSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

public class HTLCChannelClientState {
	
	public enum State {
		NEW,
		INITIATED,
		WAITING_FOR_SIGNED_REFUND,
		SCHEDULE_BROADCAST,
		PROVIDE_MULTISIG_CONTRACT_TO_SERVER,
		READY,
		EXPIRED
    }
    private State state;
    
    private TransactionBroadcastScheduler broadcastScheduler;
    
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCChannelClientState.class);
	
	private final int SERVER_OUT_IDX = 0;
	private final int CLIENT_OUT_IDX = 1;
	
	private final int MAX_HTLCS = 5;
	
	private Wallet wallet;
	
	private final ECKey clientPrimaryKey;
	private final ECKey clientSecondaryKey;
	private final ECKey serverMultisigKey;
	
	private Transaction multisigContract;
	private Transaction refundTx;
	private Transaction teardownTx;
	private Transaction teardownTxSnapshot; // Keep tx from before update round
	
	private final Coin totalValue;
	private Coin valueToMe;
	private Coin refundFees;
	private Coin totalValueInHTLCs;
	
	private Script multisigScript;

	/**
	 * Time when to broadcast the teardownTx before the channel refundTx
	 * becomes valid and can be used by the sending counterpart (in minutes
	 * before refundTx timelock)
	 */
	private final long TIME_DELTA = 2;
	private final long expireTime;
	private final long htlcSettlementExpiryTime;
	private final long htlcRefundExpiryTime;
	
	private Map<String, HTLCClientState> htlcMap;
	private Set<HTLCClientState> htlcSet;

	/**
	 * This stores the hash of the previously seen teardown transactions;
	 * It maps to the HTLCs' forfeiture transactions
	 */
	private Map<Sha256Hash, List<Transaction> > teardownForfeitureTxMap;
	
	public HTLCChannelClientState(
		Wallet wallet, 
		TransactionBroadcastScheduler broadcastScheduler,
		ECKey myPrimaryKey,
		ECKey mySecondaryKey,
		ECKey serverMultisigKey,
		Coin value,
		long expiryTimeInSec
	) {
		this.clientPrimaryKey = myPrimaryKey;
		this.clientSecondaryKey = mySecondaryKey;
		this.serverMultisigKey = serverMultisigKey;
		this.wallet = wallet;
		this.expireTime = expiryTimeInSec;
		this.htlcSettlementExpiryTime = expiryTimeInSec + TIME_DELTA*60;
		this.htlcRefundExpiryTime = expiryTimeInSec + 2*TIME_DELTA*60;
		this.totalValue = this.valueToMe = value;
		this.htlcMap = new HashMap<String, HTLCClientState>();
		this.htlcSet = new HashSet<HTLCClientState>();
		this.teardownForfeitureTxMap = 
			new LinkedHashMap<Sha256Hash, List<Transaction> >();
		this.totalValueInHTLCs = Coin.valueOf(0L);
		this.broadcastScheduler = broadcastScheduler;
		setState(State.NEW);
	}
	
	/**
     * Returns true if the tx is a valid settlement transaction.
     */
    public synchronized boolean isSettlementTransaction(Transaction tx) {
        try {
            tx.verify();
            tx.getInput(0).verify(multisigContract.getOutput(0));
            return true;
        } catch (VerificationException e) {
            return false;
        }
    }
    
    AbstractWalletEventListener walletListener = 
		new AbstractWalletEventListener() {
	    	 @Override
	    	 public void onCoinsReceived(
				 Wallet w, 
				 Transaction tx, 
				 Coin prevBalance, 
				 Coin newBalance
			 ) {
	    		 Sha256Hash sighash = tx.hashForSignature(
					 0, 
					 multisigScript, 
					 SigHash.ALL,
					 false
				 );
	    		 if (teardownForfeitureTxMap.containsKey(sighash)) {
	    			 // Let's broadcast all found forfeiture transactions
	    			 // immediately
	    			 for (Transaction forfeiture: 
	    				 	teardownForfeitureTxMap.get(sighash)) {
	    				 
	    				 log.info("Found forfeiture: {}. Broadcasting it", forfeiture);
	    				 broadcastScheduler.broadcastTransaction(forfeiture);
	    			 }
	    		 }
	    	 }
    };
	
	/** 
	 * Creates the initial contract tx 
	 * Links the timelocked refund to its output
	 * @throws ValueOutOfRangeException
	 * @throws InsufficientMoneyException
	 */
	public synchronized void initiate() 
			throws ValueOutOfRangeException, InsufficientMoneyException {
		
		/*
		 * Let's register an event listener for the wallet 
		 */
		wallet.addEventListener(walletListener);		
		
		/*
		 * Create the new contract tx, get it completed by the wallet
		 */
		Transaction template = new Transaction(wallet.getParams());
		TransactionOutput multisigOutput = template.addOutput(
			totalValue, 
			ScriptBuilder.createMultiSigOutputScript(
				2,
				Lists.newArrayList(clientPrimaryKey, serverMultisigKey)
			)
		);
		if (multisigOutput.getMinNonDustValue().compareTo(totalValue) > 0) {
			throw new ValueOutOfRangeException("totalValue too small to use!");
		}
		Wallet.SendRequest req = Wallet.SendRequest.forTx(template);
		
		req.coinSelector = AllowUnconfirmedCoinSelector.get();
		req.shuffleOutputs = false;

		wallet.completeTx(req);
		Coin multisigFee = req.tx.getFee();
		multisigContract = req.tx;
		
		log.info("MULTISIG CONTRACT READY: {}", multisigContract);
		
		/* 
		 * Create a refund tx that protects the client in case the 
		 * server vanishes
		 */
		refundTx = new Transaction(wallet.getParams());
		// Allow replacement
		refundTx.addInput(multisigOutput).setSequenceNumber(0);
		refundTx.setLockTime(expireTime);
		if (totalValue.compareTo(Coin.CENT) < 0) {
			// Must pay min fee
			final Coin valueAfterFee = 
				totalValue.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
			if (Transaction.MIN_NONDUST_OUTPUT.compareTo(valueAfterFee) > 0) {
				throw new 
					ValueOutOfRangeException("totalValue too small to use!");
			}
			refundTx.addOutput(
				valueAfterFee, 
				clientPrimaryKey.toAddress(wallet.getParams())
			);
            refundFees = multisigFee.add(
        		Transaction.REFERENCE_DEFAULT_MIN_TX_FEE
    		);
		} else {
			refundTx.addOutput(
				totalValue, 
				clientPrimaryKey.toAddress(wallet.getParams())
			);
			refundFees = multisigFee;
		}
		log.info("initiated channel with multi-sig contract {}, refund {}", 
			multisigContract.getHashAsString(),
			refundTx.getHashAsString()
        );
        setState(State.INITIATED);
	}
	
	/**
	 * Got the server's signature on the client refund tx
	 * Now sign it with the client key
	 * @param serverSignature
	 */
	public synchronized void provideRefundSignature(byte[] serverSignature) 
			throws VerificationException {
		checkState(state == State.WAITING_FOR_SIGNED_REFUND);
		TransactionSignature serverSig = 
				TransactionSignature.decodeFromBitcoin(serverSignature, true);
		if (
			serverSig.sigHashMode() != Transaction.SigHash.ALL || 
			serverSig.anyoneCanPay()
		) {
			throw new VerificationException(
				"Refund signature was not SIGHASH_ALL"
			);
		}
		// Sign the refund transaction
		final TransactionOutput multisigContractOutput =
			multisigContract.getOutput(0);
		multisigScript = multisigContractOutput.getScriptPubKey();
		TransactionSignature mySignature = refundTx.calculateSignature(
			0,
			clientPrimaryKey,
			multisigScript,
			Transaction.SigHash.ALL,
			false
		);
		Script scriptSig = ScriptBuilder.createMultiSigInputScript(
			mySignature, 
			serverSig
		);
		log.info("Refund scriptSig: {}", scriptSig);
        log.info("Multi-sig contract scriptPubKey: {}", multisigScript);
        TransactionInput refundInput = refundTx.getInput(0);
        refundInput.setScriptSig(scriptSig);
        refundInput.verify(multisigContractOutput);
        
        setState(State.SCHEDULE_BROADCAST);
	}
	
	/**
	 * Method to be called after completing the channel refundTx to schedule
	 * broadcasting of contract and refund
	 */
	public synchronized void scheduleRefundTxBroadcast() {
		checkState(state == State.SCHEDULE_BROADCAST);
		broadcastScheduler.scheduleTransaction(refundTx, refundTx.getLockTime());
		try {
			wallet.commitTx(multisigContract);
		} catch (VerificationException e) {
			throw new RuntimeException(e);
		}
		setState(State.PROVIDE_MULTISIG_CONTRACT_TO_SERVER);
	}
	
	public synchronized SignedTransaction getInitialSignedTeardownTx(
		Coin amount
	) throws ValueOutOfRangeException {
		
		if (amount.signum() < 0) {
		    throw new ValueOutOfRangeException("Tried to decrement payment");
		}
		Coin newValueToMe = valueToMe.subtract(amount);
		if (newValueToMe.signum() < 0) {
		    throw new ValueOutOfRangeException(
	    		"Channel has too little money to pay " + amount + " satoshis"
    		);
		}
		valueToMe = newValueToMe;
		
		// This will update the teardown Tx field
		teardownTx = new Transaction(wallet.getParams());
		teardownTx.addInput(multisigContract.getOutput(0)).setSequenceNumber(0);
		teardownTx.addOutput(totalValue.subtract(valueToMe), serverMultisigKey);
		teardownTx.addOutput(valueToMe, clientPrimaryKey);
		
		log.info("Teardown hash: {}", teardownTx.getHash());
		// Now construct the signature on the teardown Tx
		TransactionSignature teardownSig = teardownTx.calculateSignature(
			0,
			clientPrimaryKey,
			multisigScript,
			Transaction.SigHash.ALL,
			false
		);
		log.info("Teardown sig: {}", new BigInteger(teardownSig.encodeToBitcoin()));
		log.info("Teardown connected output: {}", multisigScript);
		log.info("Teardown input hash: {}", teardownTx.getInput(0).hashCode());
		return new SignedTransaction(teardownTx, teardownSig);
	}
	
	/**
	 * @throws ValueOutOfRangeException
	 */
	public synchronized int updateTeardownTxWithHTLC(
		String hashId,
		Coin amount
	) throws ValueOutOfRangeException {
		checkNotExpired();
		log.info("Amount: {}", amount);
		if (amount.signum() < 0) {
		    throw new ValueOutOfRangeException("Tried to decrement payment");
		}
		Coin newValueToMe = valueToMe.subtract(amount);
		if (newValueToMe.compareTo(Transaction.MIN_NONDUST_OUTPUT) < 0 && 
				newValueToMe.signum() > 0) {
		    log.info(
	    		"New value being sent back as change was smaller " +
		    	"than minimum nondust output, sending all"
			);
		    amount = valueToMe;
		    newValueToMe = Coin.ZERO;
		}
		if (newValueToMe.signum() < 0) {
		    throw new ValueOutOfRangeException(
	    		"Channel has too little money to pay " + amount + " satoshis"
    		);
		}
		valueToMe = newValueToMe;
		totalValueInHTLCs = totalValueInHTLCs.add(amount);
		
		// Create new HTLC
		HTLCClientState htlcState = new HTLCClientState(
			hashId,
			amount,
			htlcSettlementExpiryTime,
			htlcRefundExpiryTime
		);
		final Coin valueAfterFee = 	
			newValueToMe.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
		if (Transaction.MIN_NONDUST_OUTPUT.compareTo(valueAfterFee) > 0) {
			throw new ValueOutOfRangeException(
				"Value after fee too small to use!"
			);
		}
		// Update client and server values
		teardownTx.getOutput(CLIENT_OUT_IDX).setValue(valueAfterFee);
		teardownTx.getOutput(SERVER_OUT_IDX).setValue(
			totalValue.subtract(newValueToMe).subtract(totalValueInHTLCs)
		);
		teardownTx = htlcState.addHTLCOutput(
			teardownTx,
			new HTLCKeys(
				clientPrimaryKey, 
				clientSecondaryKey, 
				serverMultisigKey
			)
		);
		
		htlcSet.add(htlcState);
		htlcMap.put(hashId, htlcState);		
		log.info("Created fresh teardown tx {}", teardownTx);
		
		return htlcState.getHTLCOutput().getIndex();
	}

	/**
	 * First method to be called when making a new micropayment
	 * @param amount
	 * @return
	 * @throws ValueOutOfRangeException
	 */
	public synchronized SignedTransaction getSignedTeardownTx(
		String hashId,
		Coin amount
	) throws ValueOutOfRangeException {
		
		checkNotExpired();
		log.info("Amount: {}", amount);
		if (amount.signum() < 0) {
		    throw new ValueOutOfRangeException("Tried to decrement payment");
		}
		Coin newValueToMe = valueToMe.subtract(amount);
		if (newValueToMe.compareTo(Transaction.MIN_NONDUST_OUTPUT) < 0 && 
				newValueToMe.signum() > 0) {
		    log.info(
	    		"New value being sent back as change was smaller " +
		    	"than minimum nondust output, sending all"
			);
		    amount = valueToMe;
		    newValueToMe = Coin.ZERO;
		}
		if (newValueToMe.signum() < 0) {
		    throw new ValueOutOfRangeException(
	    		"Channel has too little money to pay " + amount + " satoshis"
    		);
		}
		valueToMe = newValueToMe;
		totalValueInHTLCs = totalValueInHTLCs.add(amount);
		
		// This will update the teardown Tx field
		updateTeardownTx(newValueToMe);
		
		// Create new HTLC
		HTLCClientState htlcState = new HTLCClientState(
			hashId,
			amount,
			htlcSettlementExpiryTime,
			htlcRefundExpiryTime
		);
		teardownTx = htlcState.addHTLCOutput(
			teardownTx,
			new HTLCKeys(clientPrimaryKey, clientSecondaryKey, serverMultisigKey)
		);
		
		htlcSet.add(htlcState);
		htlcMap.put(hashId, htlcState);		
		log.info("Created fresh teardown tx {}", teardownTx);
		// Now construct the signature on the teardown Tx
		TransactionSignature teardownSig = teardownTx.calculateSignature(
			0,
			clientPrimaryKey,
			multisigScript,
			Transaction.SigHash.ALL,
			false
		);
		
		log.info(
			"HTLCCHANNEL STATE Teardown input: {}", 
			teardownTx.getInput(0).getConnectedOutput().getScriptPubKey()
		);
		
		return new SignedTransaction(teardownTx, teardownSig);
    }
	
	/**
	 * Called when the server has provided the HTLC Signed RefundTx and the
	 * teardownTx hash.
	 */
	public synchronized void finalizeHTLCRefundTx(
		String htlcId,
		Transaction refundTx,
		TransactionSignature refundSig,
		Sha256Hash teardownHash
	) {
		log.info("Client teardown hash: {}", teardownTx.getHash());
		
		HTLCClientState htlcState = htlcMap.get(htlcId);
		htlcState.signAndStoreRefundTx(
			refundTx,
			refundSig,
			clientPrimaryKey,
			teardownHash
		);
		// And now schedule the HTLC refund broadcast, but cancel it 
		// if the HTLC gets settled in the meanwhile
		log.info("SCHEDULING BROADCAST HTLC REFUND TX: {}", htlcState.getRefundTx());
		broadcastScheduler.scheduleTransaction(
			htlcState.getRefundTx(), 
			htlcRefundExpiryTime
		);
	}
	
	/**
	 * Called after finalizeHTLCRefundTx() to create the signed settlement Tx
	 */
	public synchronized SignedTransaction getHTLCSettlementTx(
		String htlcId,
		Sha256Hash spentTxHash
	) {
		HTLCClientState htlcState = htlcMap.get(htlcId);
		return htlcState.createSettlementTx(
			spentTxHash,
			clientSecondaryKey,
			serverMultisigKey
		);
	}
	
	/**
	 * Called immediately after the settlementTx was created.
	 * Creates the signed forfeitTx 
	 * @return
	 */
	public synchronized SignedTransaction getHTLCForfeitTx(
		String htlcId,
		Sha256Hash spentTxHash
	) {
		HTLCClientState htlcState = htlcMap.get(htlcId);
		return htlcState.createForfeitTx(
			spentTxHash,
			clientPrimaryKey
		);
	}
	
	public synchronized void makeSettleable(String htlcId) {
		HTLCClientState htlcState = htlcMap.get(htlcId);
		htlcState.makeSettleable();
		htlcSet.remove(htlcState);
	}
	
	public synchronized void cancelHTLCRefundTxBroadcast(String htlcId) {
		HTLCClientState htlcState = htlcMap.get(htlcId);
		log.info("ATTEMPTING TO CANCEL BROADCAST HTLC REFUND TX {}", htlcState.getRefundTx());
		broadcastScheduler.removeTransaction(htlcState.getRefundTx());
	}

	public synchronized List<HTLCClientState> getAllActiveHTLCS() {
		return new ArrayList<HTLCClientState>(htlcMap.values());
	}
	
	/**
	 * Called when the server ACK's that the HTLC can be settled at any time now
	 * @throws ValueOutOfRangeException 
	 */
	public synchronized boolean attemptSettle(
		String htlcId,
		String secret
	) throws ValueOutOfRangeException {
		HTLCClientState htlcState = htlcMap.get(htlcId);
		if (htlcState.verifySecret(secret)) {
			log.info("Secret verified");
			// Hash matches, we can remove the HTLC and update the teardownTx
			closeHTLC(htlcState);
			return true;
		} else {
			log.info("Secret verification failed");
			return false;
		}
	}
	
	public synchronized void attemptBackoff(
		String htlcId,
		Transaction forfeitTx,
		TransactionSignature forfeitServerSig
	) {
		HTLCClientState htlcState = htlcMap.get(htlcId);
		Transaction completeForfeitTx = 
			htlcState.verifyAndFinalizeForfeit(forfeitTx, forfeitServerSig);
		Sha256Hash sighash = teardownTxSnapshot.hashForSignature(
			 0,
			 multisigScript,
			 SigHash.ALL,
			 false
		);
		List<Transaction> currentForfeits = teardownForfeitureTxMap.get(sighash);
		currentForfeits.add(completeForfeitTx);
		teardownForfeitureTxMap.put(sighash, currentForfeits);
		// Update the teardown
		refundHTLC(htlcState);
	}
	
	public synchronized SignedTransaction getSignedTeardownTx() {
		return new SignedTransaction(
			teardownTx,
			teardownTx.calculateSignature(
				0,
				clientPrimaryKey,
				multisigScript,
				Transaction.SigHash.ALL,
				false
			)
		);
	}
	
	public synchronized void takeTeardownTxSnapshot() {
		teardownTxSnapshot = teardownTx;
	}
	
	private synchronized void refundHTLC(HTLCClientState htlcState) {
		checkState(htlcState.isSettleable());
		removeHTLC(htlcState);
		Coin htlcValue = htlcState.getValue();
		valueToMe = valueToMe.add(htlcValue);
		totalValueInHTLCs = totalValueInHTLCs.subtract(htlcValue);
		// Update client value
		teardownTx.getOutput(CLIENT_OUT_IDX).setValue(valueToMe);
	}
	
	private synchronized void closeHTLC(HTLCClientState htlcState) 
			throws ValueOutOfRangeException {
		checkState(htlcState.isSettleable());
		removeHTLC(htlcState);
		Coin htlcValue = htlcState.getValue();
		totalValueInHTLCs = totalValueInHTLCs.subtract(htlcValue);
		// Update server value
		teardownTx.getOutput(SERVER_OUT_IDX).setValue(
			totalValue.subtract(valueToMe).subtract(totalValueInHTLCs)
		);
	}
	
	private synchronized void removeHTLC(HTLCClientState htlcState) {
		checkState(htlcState.isSettleable());
		
		List<TransactionOutput> allOutputs = teardownTx.getOutputs();
		Iterator<TransactionOutput> itr = allOutputs.listIterator();
		while (itr.hasNext()) {
			TransactionOutput output = itr.next();
			if (output.equals(htlcState.getHTLCOutput())) {
				// Remove this element
				itr.remove();
			}
		}
		teardownTx = new Transaction(wallet.getParams());
		teardownTx.addInput(multisigContract.getOutput(0)).setSequenceNumber(0);
		
		// Re-add all previous outputs
		for (TransactionOutput output: allOutputs) {
			// Make sure we update the output in the state
			htlcState.setHTLCOutput(teardownTx.addOutput(output));
		}
		htlcMap.remove(htlcState.getId());
	}
		
    public synchronized Transaction getIncompleteRefundTransaction() {
    	checkState(state == State.INITIATED);
    	setState(State.WAITING_FOR_SIGNED_REFUND);
        return refundTx;
    }
    
    public synchronized Transaction getMultisigContract() {
        if (getState() == State.PROVIDE_MULTISIG_CONTRACT_TO_SERVER)
            setState(State.READY);
        return multisigContract;
    }
    
    /**
	 * Called when the teardown Tx needs to be updated with new values
     * @throws ValueOutOfRangeException 
	 */
	private synchronized void updateTeardownTx(Coin newValueToMe) 
			throws ValueOutOfRangeException {
		
		List<TransactionOutput> allOutputs = teardownTx.getOutputs();
		
		teardownTx = new Transaction(wallet.getParams());
		teardownTx.addInput(multisigContract.getOutput(0)).setSequenceNumber(0);
		final Coin valueAfterFee = 
			newValueToMe.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
		if (Transaction.MIN_NONDUST_OUTPUT.compareTo(valueAfterFee) > 0) {
			throw new ValueOutOfRangeException(
				"Value after fee too small to use!"
			);
		}
		
		// Re-add all previous outputs
		for (TransactionOutput output: allOutputs) {
			teardownTx.addOutput(output);
		}
		
		// Update client and server values
		teardownTx.getOutput(CLIENT_OUT_IDX).setValue(valueAfterFee);
		teardownTx.getOutput(SERVER_OUT_IDX).setValue(
			totalValue.subtract(newValueToMe).subtract(totalValueInHTLCs)
		);
		
		log.info("newValuetoMe: {}", newValueToMe);
		log.info("valueafterfee: {}", valueAfterFee);
		log.info("Created TEARDOWN: {}", teardownTx);
	}
	
	private synchronized void checkNotExpired() {
        if (Utils.currentTimeSeconds() > expireTime) {
            setState(State.EXPIRED);
            throw new IllegalStateException("Channel expired");
        }
    }

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}
}
