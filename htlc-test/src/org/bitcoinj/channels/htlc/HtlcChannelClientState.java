package org.bitcoinj.channels.htlc;

import java.io.File;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Scanner;

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

public class HtlcChannelClientState {
	
	public enum State {
        CHANNEL_NEW,
        CHANNEL_INITIATED,
        CHANNEL_WAITING_FOR_SIGNED_REFUND,
        CHANNEL_PROVIDE_CONTRACT_TO_SERVER,
        CHANNEL_READY,
        HTLC_INITIATED,
        PROVIDE_HTLC_TO_SERVER,
        HTLC_SETTLED,
        HTLC_EXPIRED,
        CHANNEL_EXPIRED,
        CHANNEL_CLOSED,
    }
    private State state;
    
	private static final Logger log = 
		LoggerFactory.getLogger(HtlcChannelClientState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	private Wallet wallet;
	
	private final ECKey myKey;
	private final ECKey serverMultisigKey;
	
	private Transaction multisigContract;
	private Transaction refundTx;
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
	
	/** Container for a signature and its signature. */
    public static class SignedSignature {
    	public Transaction tx;
        public TransactionSignature signature;
    }
	
	public HtlcChannelClientState(
			Wallet wallet, 
			ECKey myKey, 
			ECKey serverMultisigKey, 
			Coin value, 
			long expiryTimeInMins
	) {
		this.myKey = myKey;
		this.serverMultisigKey = serverMultisigKey;
		this.wallet = wallet;
		this.expiryTime = new Date().getTime() / 1000l + expiryTimeInMins*60;
		this.htlcSettlementExpiryTime = 
			new Date().getTime()/1000l + (DAILY_MINUTES + expiryTimeInMins)*60;
		this.htlcRefundExpiryTime = 
			new Date().getTime()/1000l + (2*DAILY_MINUTES + expiryTimeInMins)*60;
		this.totalValue = this.valueToMe = value;
		this.setState(State.CHANNEL_NEW);
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
				Lists.newArrayList(myKey, serverMultisigKey)
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
			refundTx.addOutput(valueAfterFee, myKey.toAddress(PARAMS));
			refundTx.addOutput(
				Coin.valueOf(0), 
				serverMultisigKey.toAddress(PARAMS)
			);
            refundFees = multisigFee.add(
        		Transaction.REFERENCE_DEFAULT_MIN_TX_FEE
    		);
		} else {
			refundTx.addOutput(totalValue, myKey.toAddress(PARAMS));
			refundTx.addOutput(
				Coin.valueOf(0), 
				serverMultisigKey.toAddress(PARAMS)
			);
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
			myKey,
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
	 * Adds a new multisig output for 'size' coins to the contract
	 * Creates refund for HTLC value
	 * @param size
	 * @throws ValueOutOfRangeException 
	 */
	public synchronized void initiateHtlc(
		String secret,
		Coin amount
	) throws ValueOutOfRangeException {
		
		checkNotExpired();
		if (amount.signum() < 0) {
			throw new ValueOutOfRangeException("Tried to decrement payment!");
		}
		Coin newValueToMe = valueToMe.subtract(amount);
		if (newValueToMe.compareTo(Transaction.MIN_NONDUST_OUTPUT) < 0 &&
			newValueToMe.signum() > 0
		) {
			log.info(
				"New value being sent back as change was smaller " +
				"than minimum nondust output, sending all"
			);
			amount = valueToMe;
            newValueToMe = Coin.ZERO;
		}
		this.valueInHtlc = amount;
		if (newValueToMe.signum() < 0) {
			throw new ValueOutOfRangeException(
				"Channel has too little money to pay " + amount + " satoshis!"
			);
		}
		
		this.htlcSetupTx = createHtlcSetupTx(amount, secret);
		this.htlcRefundTx = createHtlcRefundTx();
		this.currentHtlcSecret = secret;
		log.info(
			"initiated HTLC with contract {}, refund {}", 
			htlcSetupTx, htlcRefundTx
		);
		setState(State.HTLC_INITIATED);
	}
	
	/**
	 * Got the server's signature on the HTLC refund tx
	 * Now sign it with the client key
	 * @param serverSignature
	 */
	public synchronized void provideHtlcRefundSignature(byte[] serverSignature) {
		TransactionSignature serverSig = 
				TransactionSignature.decodeFromBitcoin(serverSignature, true);
		if (serverSig.sigHashMode() != Transaction.SigHash.ALL || 
			serverSig.anyoneCanPay()) {
			throw new VerificationException(
				"Refund signature was not SIGHASH_ALL"
			);
		}
		// Sign the HTLC refund transaction
		final TransactionOutput multisigContractOutput = 
			htlcSetupTx.getOutput(0);
		TransactionSignature mySignature = htlcRefundTx.calculateSignature(
			0,
			myKey,
			htlcSetupTx.getOutput(0).getScriptPubKey(),
			Transaction.SigHash.ALL,
			false
		);
		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.data(new byte[]{}); // Null dummy
		bld.data(mySignature.encodeToBitcoin());
		bld.data(serverSignature);
		bld.op(ScriptOpCodes.OP_1);
		Script refundInputScript = bld.build();
		
		log.info("HTLC Refund scriptSig: {}", refundInputScript);
        TransactionInput refundInput = refundTx.getInput(0);
        refundInput.setScriptSig(refundInputScript);
        refundInput.verify(multisigContractOutput);
        
        setState(State.PROVIDE_HTLC_TO_SERVER);
	}
	
	/**
	 * Called when the HTLC secret is correctly revealed by the receiver;
	 * Drops the HTLC output of the contract and updates the channel refundTx
	 * @return
	 * @throws ValueOutOfRangeException 
	 */
	public synchronized SignedSignature incrementPayment() 
			throws ValueOutOfRangeException {
		checkNotExpired();
        Coin newValueToMe = valueToMe.subtract(valueInHtlc);
        Transaction tx = makeUnsignedTx(newValueToMe);
        TransactionSignature sig = tx.calculateSignature(
    		0, 
    		myKey, 
    		multisigScript, 
    		SigHash.ALL, 
    		false
		);
        valueToMe = newValueToMe;
        SignedSignature payment = new SignedSignature();
        payment.signature = sig;
        payment.tx = tx;
        this.setState(State.HTLC_SETTLED);
        return payment;
	}
	
	private Transaction createHtlcSetupTx(
		Coin amount,
		String secret
	) {
		TransactionOutput prevTxOut = multisigContract.getOutput(1);
		/* Hash the secret */
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		md.update(secret.getBytes());
		byte[] digest = md.digest();
		byte[] myPubKey = myKey.getPubKey();
		byte[] peerPubKey = serverMultisigKey.getPubKey();
		
		ScriptBuilder bld = new ScriptBuilder();
		bld.op(ScriptOpCodes.OP_IF);
			bld.op(ScriptOpCodes.OP_2);
			bld.data(myPubKey);
			bld.data(peerPubKey);
			bld.op(ScriptOpCodes.OP_2);
			bld.op(ScriptOpCodes.OP_CHECKMULTISIG);
		bld.op(ScriptOpCodes.OP_ELSE);
			bld.op(ScriptOpCodes.OP_SHA256);
			bld.data(digest);
			bld.op(ScriptOpCodes.OP_EQUALVERIFY);
			bld.data(peerPubKey);
			bld.op(ScriptOpCodes.OP_CHECKSIG);
		bld.op(ScriptOpCodes.OP_ENDIF);
		Script htlcScript = bld.build();
		
		/* Construct the Main HTLC Transaction */
		Transaction htlcTx = new Transaction(PARAMS);
		htlcTx.addOutput(amount, htlcScript);
		/* Add inputs to the HTLC transaction pointing to the spent Tx */
		htlcTx.addInput(prevTxOut);
		return htlcTx;
	}
	
	public Transaction createHtlcRefundTx() {
		Transaction refundTx = new Transaction(PARAMS);
		refundTx.setLockTime(htlcRefundExpiryTime);
		refundTx.addOutput(valueInHtlc, myKey.toAddress(PARAMS));
		refundTx.addInput(htlcSetupTx.getOutput(0));
		return refundTx;
	}
	
	/**
	 * Clears the contract outputs and creates an updated refundTx
	 * @param valueToMe
	 * @return
	 * @throws ValueOutOfRangeException
	 */
	private synchronized Transaction makeUnsignedTx(Coin valueToMe) 
			throws ValueOutOfRangeException {
		multisigContract.clearOutputs();
        Transaction tx = new Transaction(PARAMS);
        tx.addInput(multisigContract.getOutput(0));
	    tx.addOutput(valueToMe, myKey.toAddress(PARAMS));
	    tx.addOutput(
    		totalValue.subtract(valueToMe), 
    		serverMultisigKey.toAddress(PARAMS)
		);
	    return tx;
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
        return refundTx;
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
