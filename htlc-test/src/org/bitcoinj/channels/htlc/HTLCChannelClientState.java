package org.bitcoinj.channels.htlc;

import java.io.File;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
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
import org.bitcoinj.script.ScriptOpCodes;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.wallet.AllowUnconfirmedCoinSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

public class HTLCChannelClientState {
	
	public enum State {
		NEW,
		INITIATED,
		READY,
        CHANNEL_NEW,
        CHANNEL_INITIATED,
        CHANNEL_WAITING_FOR_SIGNED_REFUND,
        CHANNEL_PROVIDE_CONTRACT_TO_SERVER,
        CHANNEL_READY,
        CHANNEL_EXPIRED,
        CHANNEL_CLOSED,
    }
    private State state;
    
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCChannelClientState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	private Wallet wallet;
	
	private final ECKey myPrimaryKey;
	private final ECKey mySecondaryKey;
	private final ECKey serverMultisigKey;
	
	private Transaction multisigContract;
	private Transaction refundTx;
	private Transaction teardownTx;
	private Transaction htlcSetupTx;
	private Transaction htlcRefundTx;
	
	private String currentHtlcSecret;
	
	private final Coin totalValue;
	private Coin valueInHtlc;
	private Coin valueToMe;
	private Coin refundFees;
	
	private Script multisigScript;
	
	private static final long DAILY_MINUTES = 1440;
	private final long expiryTime;
	private final long htlcSettlementExpiryTime;
	private final long htlcRefundExpiryTime;
	
	private Map<String, HTLCClientState> htlcMap;
	
	/** Container for a transaction and its signature. */
    public static class SignedTransaction {
    	public Transaction tx;
        public TransactionSignature sig;
    }
	
	public HTLCChannelClientState(
			Wallet wallet, 
			ECKey myPrimaryKey,
			ECKey mySecondaryKey,
			ECKey serverMultisigKey, 
			Coin value, 
			long expiryTimeInMins
	) {
		this.myPrimaryKey = myPrimaryKey;
		this.mySecondaryKey = mySecondaryKey;
		this.serverMultisigKey = serverMultisigKey;
		this.wallet = wallet;
		this.expiryTime = new Date().getTime() / 1000l + expiryTimeInMins*60;
		this.htlcSettlementExpiryTime = 
			new Date().getTime()/1000l + (DAILY_MINUTES + expiryTimeInMins)*60;
		this.htlcRefundExpiryTime = 
			new Date().getTime()/1000l + (2*DAILY_MINUTES + expiryTimeInMins)*60;
		this.totalValue = this.valueToMe = value;
		this.setState(State.CHANNEL_NEW);
		this.htlcMap = new HashMap<String, HTLCClientState>();
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
				Lists.newArrayList(myPrimaryKey, serverMultisigKey)
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
		refundTx.setLockTime(expiryTime);
		if (totalValue.compareTo(Coin.CENT) < 0) {
			// Must pay min fee
			final Coin valueAfterFee = 
				totalValue.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
			if (Transaction.MIN_NONDUST_OUTPUT.compareTo(valueAfterFee) > 0) {
				throw new 
					ValueOutOfRangeException("totalValue too small to use!");
			}
			refundTx.addOutput(valueAfterFee, myPrimaryKey.toAddress(PARAMS));
            refundFees = multisigFee.add(
        		Transaction.REFERENCE_DEFAULT_MIN_TX_FEE
    		);
		} else {
			refundTx.addOutput(totalValue, myPrimaryKey.toAddress(PARAMS));
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
	public synchronized void provideRefundSignature(byte[] serverSignature) {
		TransactionSignature serverSig = 
				TransactionSignature.decodeFromBitcoin(serverSignature, true);
		if (serverSig.sigHashMode() != Transaction.SigHash.ALL || 
			serverSig.anyoneCanPay()) {
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
			myPrimaryKey,
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
        
        // Skip storing in wallet for now
        
        setState(State.CHANNEL_PROVIDE_CONTRACT_TO_SERVER);
	}

	/**
	 * First method to be called when making a new micropayment
	 * @param amount
	 * @return
	 * @throws ValueOutOfRangeException
	 */
	public synchronized SignedTransaction getTeardownTx(
		Coin amount, 
		String secret
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
		
		this.teardownTx = updateTeardownTx(newValueToMe);
		// Create new HTLC
		HTLCClientState htlcState = new HTLCClientState(
			secret,
			amount, 
			htlcSettlementExpiryTime,
			htlcRefundExpiryTime
		);
		this.teardownTx = htlcState.addHTLCOutput(
			teardownTx, 
			secret, 
			new HTLCKeys(myPrimaryKey, mySecondaryKey, serverMultisigKey)
		);
		htlcMap.put(htlcState.getId(), htlcState);		
		
		log.info("Created fresh teardown tx {}", teardownTx);
		
		// Now construct the signature on the teardown Tx
		TransactionSignature teardownSig = teardownTx.calculateSignature(
			0,
			myPrimaryKey,
			multisigContract.getOutput(0).getScriptPubKey(),
			Transaction.SigHash.ALL,
			false
		);
		
		SignedTransaction signedTx = new SignedTransaction();
		signedTx.tx = teardownTx;
		signedTx.sig = teardownSig;
		
		return signedTx;
    }
	
	/**
	 * Called when the teardown Tx needs to be updated with new values
	 */
	private synchronized Transaction updateTeardownTx(Coin newValueToMe) {
		Transaction newTeardownTx = new Transaction(PARAMS);
		newTeardownTx.addInput(multisigContract.getOutput(0));
		newTeardownTx.addOutput(newValueToMe, myPrimaryKey.toAddress(PARAMS));
		newTeardownTx.addOutput(
			totalValue.subtract(newValueToMe), 
			serverMultisigKey.toAddress(PARAMS)
		);
		return newTeardownTx;
	}
	
	public synchronized Transaction getHTLCSettlementTx(String htlcId) {
		HTLCClientState htlcState = htlcMap.get(htlcId); 
		return htlcState.createSettlementTx(serverMultisigKey);
	}
	
	 public synchronized void checkNotExpired() {
        if (Utils.currentTimeSeconds() > expiryTime) {
            setState(State.CHANNEL_EXPIRED);
            throw new IllegalStateException("Channel expired");
        }
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

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}
}
