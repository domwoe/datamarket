package org.bitcoinj.channels.htlc;

import static com.google.common.base.Preconditions.checkState;
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
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptOpCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;

public class HTLCClientState extends HTLCState {
	
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCClientState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	public enum State {
		NEW,
		OUTPUT_INITIATED,
		REFUND_VERIFIED,
		FORFEIT_CREATED,
		SETTLE_CREATED,
		SETTLEABLE
	}
	private State state;
	
	private TransactionOutput teardownTxHTLCOutput;
	
	private Transaction refundTx;
	private Transaction settlementTx;
	
	private Transaction forfeitTx;
	private TransactionSignature forfeitTxSig;
	
	public boolean isSettleable() {
		return state == State.SETTLEABLE;
	}

	public Transaction getRefundTx() {
		return refundTx;
	}

	public Transaction getSettlementTx() {
		return settlementTx;
	}

	public Transaction getForfeitTx() {
		return forfeitTx;
	}
	
	public void setHTLCOutput(TransactionOutput htlcOutput) {
		this.teardownTxHTLCOutput = htlcOutput;
	}
	
	public TransactionOutput getHTLCOutput() {
		return teardownTxHTLCOutput;
	}
	
	public int getIndex() {
		return teardownTxHTLCOutput.getIndex();
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
		checkState(state == State.NEW);
		
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
		
		ECKey.ECDSASignature clientSig = clientPrimaryKey.sign(teardownTxHash);
		TransactionSignature clientTs = 
			new TransactionSignature(clientSig, Transaction.SigHash.ALL, false);
		
		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.smallNum(0);
//		bld.data(new byte[]{}); // Null dummy
		bld.data(clientTs.encodeToBitcoin());
		bld.data(refundSig.encodeToBitcoin());
		bld.op(ScriptOpCodes.OP_1);
		Script refundInputScript = bld.build();
		
		refundInput.setScriptSig(refundInputScript);
		
		refundInput.getScriptSig().correctlySpends(
			refundTx, 
			0,
			teardownTxHTLCOutput.getScriptPubKey()
		);
		
		log.error("REFUND TX: {}", refundTx);
		log.error("REFUND TX  OUTPUT {}", refundTx.getOutput(0).getScriptPubKey().toString());
		log.error("REFUND INPUT: {}", refundTx.getInput(0).getScriptSig().toString());
		
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
			getIndex(),
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
		log.error("Settlement TX OUTPUT: {}", settlementTx.getOutput(0).getScriptPubKey().toString());
		log.error("SETTLEMENT INPUT: {}", settlementTx.getInput(0).getScriptSig().toString());
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
		
		log.error("FORFEIT TX: {}", forfeitTx);
		log.error("FORFEIT TX OUTPUT: {}", forfeitTx.getOutput(0).getScriptPubKey().toString());
		log.error("FORFEIT TX INPUT: {}", forfeitTx.getInput(0).getScriptSig().toString());
		
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
	
	public Transaction verifyAndFinalizeForfeit(
		Transaction forfeitTx,
		TransactionSignature forfeitServerSig
	) {
				
		ScriptBuilder bld = new ScriptBuilder();
		bld.smallNum(0);
//		bld.data(new byte[]{});
		bld.data(forfeitTxSig.encodeToBitcoin());
		bld.data(forfeitServerSig.encodeToBitcoin());
		bld.op(ScriptOpCodes.OP_1);
		Script forfeitInputScript = bld.build();
		
		TransactionInput forfeitInput = forfeitTx.getInput(0);
		forfeitInput.setScriptSig(forfeitInputScript);
		
		forfeitInput.getScriptSig().correctlySpends(
			refundTx, 
			0,
			teardownTxHTLCOutput.getScriptPubKey()
		);
		return forfeitTx;
	}
	
	public void makeSettleable() {
		log.error("MADE {} SETTLEABLE", getId());
		this.state = State.SETTLEABLE;
	}
	
	public boolean verifySecret(String secret) {
		log.error("STATE: {}", state.toString());
		checkState(state == State.SETTLEABLE);
		final String hashedSecret = Hashing.sha256()
	        .hashString(secret, Charsets.UTF_8)
	        .toString();
		return hashedSecret.equals(getId());
	}
}
