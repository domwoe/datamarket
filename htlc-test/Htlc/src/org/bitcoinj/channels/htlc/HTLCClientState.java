package org.bitcoinj.channels.htlc;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.GetHeadersMessage;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;

public class HTLCClientState extends HTLCState {
	
	// TODO: ADD STATE VALIDATION FOR EACH STEP
	
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCClientState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	public enum State {
		NEW,
		OUTPUT_INITIATED,
		REFUND_VERIFIED,
		SETTLE_CREATED,
		FORFEIT_CREATED,
		SETTLE_EXPIRED,
		FORFEIT_EXPIRED,
		SETTLEABLE
	}
	private State state;
	
	private TransactionOutput teardownTxHTLCOutput;
	
	private Transaction refundTx;
	private Transaction settlementTx;
	private Transaction forfeitTx;

	public Transaction getRefundTx() {
		return refundTx;
	}

	public Transaction getSettlementTx() {
		return settlementTx;
	}

	public Transaction getForfeitTx() {
		return forfeitTx;
	}

	public HTLCClientState(
		String secretHash, 
		Coin value,
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		super(secretHash, value, settlementExpiryTime, refundExpiryTime);
		this.state = State.NEW;
	}
	
	public Transaction addHTLCOutput(
		Transaction teardownTx, 
		HTLCKeys keys
	) {
		if (this.state != State.NEW) {
			throw new IllegalStateException("HTLC is in invalid state!");
		}
		
		byte[] clientPrimaryPubKey = keys.getClientPrimaryKey().getPubKey();
		byte[] clientSecondaryPubKey = keys.getClientSecondaryKey().getPubKey();
		byte[] serverPubKey = keys.getServerKey().getPubKey();
		
		ScriptBuilder bld = new ScriptBuilder();
		bld.op(ScriptOpCodes.OP_IF);
			bld.op(ScriptOpCodes.OP_2);
			bld.data(clientPrimaryPubKey);
			bld.data(serverPubKey);
			bld.op(ScriptOpCodes.OP_2);
			bld.op(ScriptOpCodes.OP_CHECKMULTISIG);
		bld.op(ScriptOpCodes.OP_ELSE);
			bld.op(ScriptOpCodes.OP_SHA256);
			bld.data(getId().getBytes());
			bld.op(ScriptOpCodes.OP_EQUALVERIFY);
			bld.op(ScriptOpCodes.OP_2);
			bld.data(clientSecondaryPubKey);
			bld.data(serverPubKey);
			bld.op(ScriptOpCodes.OP_2);
			bld.op(ScriptOpCodes.OP_CHECKMULTISIG);
		bld.op(ScriptOpCodes.OP_ENDIF);
		
		this.teardownTxHTLCOutput = 
			teardownTx.addOutput(getValue(), bld.build());
		this.state = State.OUTPUT_INITIATED;
		
		return teardownTx;
	}
	
	public void signAndStoreRefundTx(
		Transaction refundTx,
		TransactionSignature refundSig, 
		ECKey clientPrimaryKey,
		Sha256Hash teardownTxHash
	) {		
		TransactionInput refundInput = refundTx.getInput(0);
		
		log.info("REFUND TX {}", refundTx);
		ECKey.ECDSASignature clientSig = clientPrimaryKey.sign(teardownTxHash);
		TransactionSignature clientTs = 
			new TransactionSignature(clientSig, Transaction.SigHash.ALL, false);
		
		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.data(new byte[]{}); // Null dummy
		bld.data(clientTs.encodeToBitcoin());
		bld.data(refundSig.encodeToBitcoin());
		bld.op(ScriptOpCodes.OP_1);
		Script refundInputScript = bld.build();
		
		refundInput.setScriptSig(refundInputScript);
		
		refundInputScript.correctlySpends(
			refundTx, 
			0,
			teardownTxHTLCOutput.getScriptPubKey()
		);
		
		this.refundTx = refundTx;
		this.state = State.REFUND_VERIFIED;
	}
	
	public SignedTransaction createSettlementTx(
		Sha256Hash teardownTxHash,
		ECKey clientSecondaryKey,
		ECKey serverKey
	) {
		Script htlcPubScript = teardownTxHTLCOutput.getScriptPubKey();
		Transaction settlementTx = new Transaction(PARAMS);
		settlementTx.addOutput(getValue(), serverKey.toAddress(PARAMS));
		settlementTx.addInput(
			teardownTxHash,
			2,
			htlcPubScript
		).setSequenceNumber(0);
		settlementTx.setLockTime(getSettlementExpiryTime());
		
		log.info("Settlement TX: {}", settlementTx);
		log.info("Settlement htlcPubScript: {}", htlcPubScript);
		log.info("Settlement TX HASH: {}", settlementTx.hashForSignature(
			0, 
			htlcPubScript, 
			Transaction.SigHash.ALL, 
			false
		));
		
		TransactionSignature clientSig = settlementTx.calculateSignature(
			0,
			clientSecondaryKey, 
			htlcPubScript, 
			SigHash.ALL, 
			false
		);
		
		this.settlementTx = settlementTx;
		this.state = State.SETTLE_CREATED;
		return new SignedTransaction(settlementTx, clientSig);
	}
	
	public SignedTransaction createForfeitTx(
		Sha256Hash teardownTxHash,
		ECKey clientPrimaryKey
	) {
		Script htlcPubScript = teardownTxHTLCOutput.getScriptPubKey();
		Transaction forfeitTx = new Transaction(PARAMS);
		forfeitTx.addOutput(getValue(), clientPrimaryKey.toAddress(PARAMS));
		forfeitTx.addInput(
			teardownTxHash, 
			2,
			htlcPubScript
		);
		
		TransactionSignature clientSig = forfeitTx.calculateSignature(
			0,
			clientPrimaryKey, 
			htlcPubScript, 
			SigHash.ALL, 
			false
		);
		
		this.forfeitTx = forfeitTx;
		this.state = State.FORFEIT_CREATED;
		return new SignedTransaction(forfeitTx, clientSig);
	}
	
	public boolean verifySecret(String secret) {
		final String hashedSecret = Hashing.sha256()
	        .hashString(secret, Charsets.UTF_8)
	        .toString();
		return hashedSecret.equals(getId());
	}
}
