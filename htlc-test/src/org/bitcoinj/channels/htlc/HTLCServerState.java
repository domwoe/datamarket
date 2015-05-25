package org.bitcoinj.channels.htlc;

import java.util.Date;

import org.bitcoinj.channels.htlc.HTLCClientState.State;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

public class HTLCServerState extends HTLCState {

	private static final Logger log = 
			LoggerFactory.getLogger(HTLCServerState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	public enum State {
		NEW,
		REFUND_SIGNED,
		SETTLE_RECEIVED,
		SETTLE_RETRIEVED
	}
	private State state;
	
	private Transaction refundTx;
	
	private TransactionSignature serverSettlementTxSig;
	private TransactionSignature clientSettlementTxSig;
	private Transaction settlementTx;
	
	public HTLCServerState(
		String id,
		Coin value, 
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		super(id, value, settlementExpiryTime, refundExpiryTime);
		this.state = State.NEW;
	}
	
	public SignedTransaction getSignedRefund(
		Transaction teardownTx,
		ECKey clientKey,
		ECKey serverKey
	) {
		TransactionOutput htlcOut = teardownTx.getOutput(2);
		Transaction htlcRefundTx = new Transaction(PARAMS);
		htlcRefundTx.setLockTime(getRefundExpiryTime());
		htlcRefundTx.addOutput(htlcOut.getValue(), clientKey.toAddress(PARAMS));
		htlcRefundTx.addInput(htlcOut);
		
		TransactionSignature serverRefundSig = htlcRefundTx.calculateSignature(
			0,
			serverKey,
			htlcOut.getScriptPubKey(),
			Transaction.SigHash.ALL,
			false
		);
		this.state = State.REFUND_SIGNED;
		return new SignedTransaction(htlcRefundTx, serverRefundSig);
	}
	
	public void storeSignedSettlementTx(
		Transaction teardownTx,
		SignedTransaction signedSettleTx
	) {
		this.settlementTx = signedSettleTx.getTx();
		this.clientSettlementTxSig = signedSettleTx.getSig();
		this.state = State.SETTLE_RECEIVED;
	}
	
	public Transaction getFullSettlementTx(
		Transaction teardownTx,
		ECKey serverKey,
		String secret
	) {
		TransactionInput settleTxIn = settlementTx.getInput(0);
		TransactionOutput htlcOutput = teardownTx.getOutput(2);
		TransactionSignature serverSig = settlementTx.calculateSignature(
			0,
			serverKey, 
			htlcOutput.getScriptPubKey(), 
			SigHash.ALL, 
			false
		);
		this.serverSettlementTxSig = serverSig;
		
		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.data(serverSettlementTxSig.encodeToBitcoin());
		bld.data(clientSettlementTxSig.encodeToBitcoin());
		bld.data(secret.getBytes());
		bld.data(new byte[]{});
		Script inputScript = bld.build();

		settleTxIn.setScriptSig(inputScript);
		settleTxIn.verify(htlcOutput);
	
		this.state = State.SETTLE_RETRIEVED;
		return settlementTx;
	}
}
 