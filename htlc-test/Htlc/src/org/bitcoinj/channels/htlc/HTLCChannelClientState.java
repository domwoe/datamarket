package org.bitcoinj.channels.htlc;

import static com.google.common.base.Preconditions.checkState;

import java.util.HashMap;
import java.util.Map;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
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
		
        CHANNEL_INITIATED,
        CHANNEL_WAITING_FOR_SIGNED_REFUND,
        CHANNEL_PROVIDE_CONTRACT_TO_SERVER,
        CHANNEL_READY,
        CHANNEL_EXPIRED,
        CHANNEL_CLOSED,
    }
    private State state;
    
    private TransactionBroadcastScheduler broadcastScheduler;
    
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCChannelClientState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	private Wallet wallet;
	
	private final ECKey clientPrimaryKey;
	private final ECKey clientSecondaryKey;
	private final ECKey serverMultisigKey;
	
	private Transaction multisigContract;
	private Transaction refundTx;
	private Transaction teardownTx;
	
	private final Coin totalValue;
	private Coin valueToMe;
	private Coin refundFees;
	private Coin totalValueInHTLCs;
	
	private Script multisigScript;

	private final long DAILY_SECONDS = 86400; 
	private final long expireTime;
	private final long htlcSettlementExpiryTime;
	private final long htlcRefundExpiryTime;
	
	private Map<String, HTLCClientState> htlcMap;
	
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
		this.htlcSettlementExpiryTime = expiryTimeInSec + DAILY_SECONDS;
		this.htlcRefundExpiryTime = expiryTimeInSec + 2*DAILY_SECONDS;
		this.totalValue = this.valueToMe = value;
		this.htlcMap = new HashMap<String, HTLCClientState>();
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
	
	/** 
	 * Creates the initial contract tx 
	 * Links the timelocked refund to its output
	 * @throws ValueOutOfRangeException
	 * @throws InsufficientMoneyException
	 */
	public synchronized void initiate() 
			throws ValueOutOfRangeException, InsufficientMoneyException {
		/*
		 * Create the new contract tx, get it completed by the wallet
		 */
		Transaction template = new Transaction(PARAMS);
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
		
		/* 
		 * Create a refund tx that protects the client in case the 
		 * server vanishes
		 */
		refundTx = new Transaction(PARAMS);
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
			refundTx.addOutput(valueAfterFee, clientPrimaryKey.toAddress(PARAMS));
            refundFees = multisigFee.add(
        		Transaction.REFERENCE_DEFAULT_MIN_TX_FEE
    		);
		} else {
			refundTx.addOutput(totalValue, clientPrimaryKey.toAddress(PARAMS));
			refundFees = multisigFee;
		}
		log.info("initiated channel with multi-sig contract {}, refund {}", 
			multisigContract.getHashAsString(),
			refundTx.getHashAsString()
        );
        setState(State.CHANNEL_INITIATED);
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
	 * Method to be called after completing the refundTx to schedule
	 * broadcasting of contract and refund
	 */
	public synchronized void scheduleBroadcast() {
		checkState(state == State.SCHEDULE_BROADCAST);
		broadcastScheduler.scheduleRefund(multisigContract, refundTx);
		try {
			wallet.commitTx(multisigContract);
		} catch (VerificationException e) {
			throw new RuntimeException(e);
		}
		setState(State.PROVIDE_MULTISIG_CONTRACT_TO_SERVER);
	}

	/**
	 * First method to be called when making a new micropayment
	 * @param amount
	 * @return
	 * @throws ValueOutOfRangeException
	 */
	public synchronized SignedTransaction getSignedTeardownTx(
		String id,
		Coin amount, 
		byte[] secretHash
	) throws ValueOutOfRangeException {
		
		checkNotExpired();
		if (amount.signum() < 0)
		    throw new ValueOutOfRangeException("Tried to decrement payment");
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
		if (newValueToMe.signum() < 0)
		    throw new ValueOutOfRangeException(
	    		"Channel has too little money to pay " + amount + " satoshis"
    		);
		valueToMe = newValueToMe;
		totalValueInHTLCs = totalValueInHTLCs.add(amount);
		this.teardownTx = updateTeardownTx(newValueToMe);
		
		// Create new HTLC
		HTLCClientState htlcState = new HTLCClientState(
			id,
			secretHash,
			amount, 
			htlcSettlementExpiryTime,
			htlcRefundExpiryTime
		);
		this.teardownTx = htlcState.addHTLCOutput(
			teardownTx,
			new HTLCKeys(clientPrimaryKey, clientSecondaryKey, serverMultisigKey)
		);
		htlcMap.put(htlcState.getId(), htlcState);		
		log.info("Created fresh teardown tx {}", teardownTx);
		// Now construct the signature on the teardown Tx
		TransactionSignature teardownSig = teardownTx.calculateSignature(
			0,
			clientPrimaryKey,
			multisigContract.getOutput(0).getScriptPubKey(),
			Transaction.SigHash.ALL,
			false
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
		TransactionSignature refundSig
	) {
		HTLCClientState htlcState = htlcMap.get(htlcId);
		htlcState.signAndStoreRefundTx(
			refundTx,
			refundSig,
			clientPrimaryKey
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
			clientPrimaryKey,
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
	
	/**
	 * Call this method when server sends us a secret; if it matches the 
	 * stored secret, create a new teardownTx, sign it and send it to the
	 * server
	 */
	public synchronized SignedTransaction removeHTLCAndUpdateTeardownTx(
		String htlcId
	) {
		// Add index to be stored in the HTLCState so we know which output to remove
		// USE 2 for now
		// TODO: ADD HTLCState validation
		TransactionOutput serverOutput = teardownTx.getOutput(1);
		TransactionOutput htlcOutput = teardownTx.getOutput(2);
		teardownTx.clearOutputs();
		teardownTx.addOutput(valueToMe, clientPrimaryKey.toAddress(PARAMS));
		teardownTx.addOutput(
			serverOutput.getValue().add(htlcOutput.getValue()),
			serverMultisigKey.toAddress(PARAMS)
		);
		totalValueInHTLCs = totalValueInHTLCs.subtract(htlcOutput.getValue());
		TransactionSignature teardownSig = teardownTx.calculateSignature(
			0,
			clientPrimaryKey,
			multisigContract.getOutput(0).getScriptPubKey(),
			Transaction.SigHash.ALL,
			false
		);
		// Remove HTLC from map
		htlcMap.remove(htlcId);
		return new SignedTransaction(teardownTx, teardownSig);
	}
		
    public synchronized Transaction getIncompleteRefundTransaction() {
        if (getState() == State.CHANNEL_INITIATED)
            setState(State.CHANNEL_WAITING_FOR_SIGNED_REFUND);
        return teardownTx;
    }
    
    public synchronized Transaction getMultisigContract() {
        if (getState() == State.CHANNEL_PROVIDE_CONTRACT_TO_SERVER)
            setState(State.CHANNEL_READY);
        return multisigContract;
    }
    
    /**
	 * Called when the teardown Tx needs to be updated with new values
	 */
	private synchronized Transaction updateTeardownTx(Coin newValueToMe) {
		Transaction newTeardownTx = new Transaction(PARAMS);
		newTeardownTx.addInput(multisigContract.getOutput(0));
		newTeardownTx.addOutput(newValueToMe, clientPrimaryKey.toAddress(PARAMS));
		newTeardownTx.addOutput(
			totalValue.subtract(newValueToMe).subtract(totalValueInHTLCs), 
			serverMultisigKey.toAddress(PARAMS)
		);
		return newTeardownTx;
	}
	
	private synchronized void checkNotExpired() {
        if (Utils.currentTimeSeconds() > expireTime) {
            setState(State.CHANNEL_EXPIRED);
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
