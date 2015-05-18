package org.bitcoinj.channels.htlc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
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
		OUTPUT_INIT,
		SETTLE_CREATED,
		SETTLE_EXPIRED,
		FORFEIT_EXPIRED
	}
	private State state;

/*	private Transaction setupTx;*/
/*	private Transaction teardownTx;*/
	
	private final String secret;
	
	private TransactionOutput teardownTxHTLCOutput;

	public HTLCClientState(
/*		Transaction teardownTx,*/
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
		String secret, 
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
		this.state = State.OUTPUT_INIT;
		
		return teardownTx;
	}
	
	public Transaction createSettlementTx(ECKey serverKey) {
		Transaction settlementTx = new Transaction(PARAMS);
		settlementTx.setLockTime(getSettlementExpiryTime());
		settlementTx.addOutput(getValue(), serverKey.toAddress(PARAMS));
		settlementTx.addInput(teardownTxHTLCOutput);
		this.state = State.SETTLE_CREATED;
		return settlementTx;
	}
}
