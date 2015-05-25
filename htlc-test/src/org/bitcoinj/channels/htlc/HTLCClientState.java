package org.bitcoinj.channels.htlc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTLCClientState extends HTLCState {
	
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
		FORFEIT_EXPIRED
	}
	private State state;
	
	private final String secret;
	
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
		String secret, 
		Coin value,
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		super(value, settlementExpiryTime, refundExpiryTime);
		this.secret = secret;
		this.state = State.NEW;
	}
	
	public Transaction addHTLCOutput(
		Transaction teardownTx, 
		HTLCKeys keys
	) {
		if (this.state != State.NEW) {
			throw new IllegalStateException("HTLC is in invalid state!");
		}
		/* Hash the secret */
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		md.update(secret.getBytes());
		byte[] digest = md.digest();
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
			bld.data(digest);
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
		SignedTransactionWithHash signedTxWithHash, 
		ECKey clientPrimaryKey
	) {
		Transaction refundTx = signedTxWithHash.getTx();
		TransactionInput refundInput = refundTx.getInput(0);
		TransactionSignature serverRefundSig = signedTxWithHash.getSig();
		TransactionSignature clientSig = refundTx.calculateSignature(
			0,
			clientPrimaryKey,
			teardownTxHTLCOutput.getScriptPubKey(),
			Transaction.SigHash.ALL,
			false
		);
		
		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.data(new byte[]{}); // Null dummy
		bld.data(clientSig.encodeToBitcoin());
		bld.data(serverRefundSig.encodeToBitcoin());
		bld.op(ScriptOpCodes.OP_1);
		Script refundInputScript = bld.build();
		
		refundInput.setScriptSig(refundInputScript);
		refundInput.verify(teardownTxHTLCOutput);
		
		this.refundTx = refundTx;
		this.state = State.REFUND_VERIFIED;
	}
	
	public SignedTransaction createSettlementTx(
		Sha256Hash teardownTxHash,
		ECKey clientPrimaryKey,
		ECKey serverKey
	) {
		Transaction settlementTx = new Transaction(PARAMS);
		settlementTx.setLockTime(getSettlementExpiryTime());
		settlementTx.addOutput(getValue(), serverKey.toAddress(PARAMS));
		settlementTx.addInput(
			teardownTxHash,
			3,
			teardownTxHTLCOutput.getScriptPubKey()
		);
		
		TransactionSignature clientSig = settlementTx.calculateSignature(
			0,
			clientPrimaryKey, 
			teardownTxHTLCOutput.getScriptPubKey(), 
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
		Transaction forfeitTx = new Transaction(PARAMS);
		forfeitTx.addOutput(getValue(), clientPrimaryKey.toAddress(PARAMS));
		forfeitTx.addInput(
			teardownTxHash, 
			3,
			teardownTxHTLCOutput.getScriptPubKey()
		);
		
		TransactionSignature clientSig = forfeitTx.calculateSignature(
			0,
			clientPrimaryKey, 
			teardownTxHTLCOutput.getScriptPubKey(), 
			SigHash.ALL, 
			false
		);
		
		this.forfeitTx = forfeitTx;
		this.state = State.FORFEIT_CREATED;
		return new SignedTransaction(forfeitTx, clientSig);
	}
}
