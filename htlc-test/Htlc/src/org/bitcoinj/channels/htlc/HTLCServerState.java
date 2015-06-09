package org.bitcoinj.channels.htlc;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

public class HTLCServerState extends HTLCState {

	private static final Logger log = 
			LoggerFactory.getLogger(HTLCServerState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	public enum State {
		NEW,
		REFUND_SIGNED,
		SETTLE_RECEIVED,
		FORFEIT_RECEIVED,
		SETTLE_RETRIEVED
	}
	private State state;
	
	private Transaction refundTx;
	
	private Transaction settlementTx;
	private TransactionSignature serverSettlementTxSig;
	private TransactionSignature clientSettlementTxSig;
	
	private Transaction forfeitTx;
	private TransactionSignature clientForfeitTxSig;
	
	private ByteString secret;

	public HTLCServerState(
		Coin value,
		ByteString secret,
		ByteString secretHash,
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		super(secretHash, value, settlementExpiryTime, refundExpiryTime);
		this.secret = secret;
		this.state = State.NEW;
	}
	
	public SignedTransaction getSignedRefund(
		Transaction teardownTx,
		ECKey clientKey,
		ECKey serverKey
	) {
		// TODO: FIX INDEX! It's not always 2!
		TransactionOutput htlcOut = teardownTx.getOutput(2);
		Transaction htlcRefundTx = new Transaction(PARAMS);
		htlcRefundTx.addOutput(htlcOut.getValue(), clientKey.toAddress(PARAMS));
		htlcRefundTx.addInput(htlcOut).setSequenceNumber(0);
		htlcRefundTx.setLockTime(getRefundExpiryTime());
		
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
		Transaction settlementTx,
		TransactionSignature settlementSig
	) {
		this.settlementTx = settlementTx;
		this.clientSettlementTxSig = settlementSig;
		this.state = State.SETTLE_RECEIVED;
	}
	
	public void storeSignedForfeitTx(
		Transaction forfeitTx,
		TransactionSignature clientForfeitTxSig
	) {
		this.forfeitTx = forfeitTx;
		this.clientForfeitTxSig = clientForfeitTxSig;
		this.state = State.FORFEIT_RECEIVED;
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
 