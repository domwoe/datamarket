package org.bitcoinj.channels.htlc;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;

public class HTLCServerState extends HTLCState {

	private static final Logger log = 
			LoggerFactory.getLogger(HTLCServerState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	
	public enum State {
		NEW,
		REFUND_SIGNED,
		SETTLE_RECEIVED,
		FORFEIT_RECEIVED,
		FORFEIT_RETRIEVED,
		SETTLE_RETRIEVED
	}
	private State state;
	
	private Transaction refundTx;
	
	private Transaction settlementTx;
	private TransactionSignature serverSettlementTxSig;
	private TransactionSignature clientSettlementTxSig;
	
	private Transaction forfeitTx;
	private TransactionSignature clientForfeitTxSig;
	
	private String secret;

	public HTLCServerState(
		Coin value,
		String secret,
		String secretHash,
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		super(secretHash, value, settlementExpiryTime, refundExpiryTime);
		this.secret = secret;
		this.state = State.NEW;
	}
	
	public SignedTransactionWithHash getSignedRefund(
		Transaction teardownTx,
		int idx,
		ECKey clientKey,
		ECKey serverKey
	) {
		checkState(state == State.NEW);
		TransactionOutput htlcOut = teardownTx.getOutput(idx);
		refundTx = new Transaction(PARAMS);
		refundTx.addOutput(htlcOut.getValue(), clientKey.toAddress(PARAMS));
		refundTx.addInput(htlcOut).setSequenceNumber(0);
		refundTx.setLockTime(getRefundExpiryTime());
		
		TransactionSignature serverRefundSig = refundTx.calculateSignature(
			0,
			serverKey,
			htlcOut.getScriptPubKey(),
			Transaction.SigHash.ALL,
			false
		);
		Sha256Hash sighash = refundTx.hashForSignature(
			0, 
			htlcOut.getScriptPubKey(), 
			Transaction.SigHash.ALL, 
			false
		);
		this.state = State.REFUND_SIGNED;
		return new SignedTransactionWithHash(refundTx, serverRefundSig, sighash);
	}
	
	public void verifyAndStoreSignedSettlementTx(
		ECKey clientSecondaryKey,
		Script htlcPubScript,
		Transaction settlementTx,
		TransactionSignature settlementClientSig
	) {
		checkState(state == State.REFUND_SIGNED);
		// Verify that the client's signature is correct
		Sha256Hash sighash = settlementTx.hashForSignature(
			0, 
			htlcPubScript, 
			Transaction.SigHash.ALL, 
			false
		);
		
		if (!clientSecondaryKey.verify(sighash, settlementClientSig)) {
			throw new VerificationException(
				"Client signature does not verify settlement Tx " +
				settlementTx
			);
		}
		
		this.settlementTx = settlementTx;
		this.clientSettlementTxSig = settlementClientSig;
		this.state = State.SETTLE_RECEIVED;
	}
	
	public void verifyAndStoreSignedForfeitTx(
		ECKey clientPrimaryKey,
		Script htlcPubScript,
		Transaction forfeitTx,
		TransactionSignature clientForfeitTxSig
	) {
		checkState(state == State.SETTLE_RECEIVED);
		// Verify that the client's signature is correct
		Sha256Hash sighash = forfeitTx.hashForSignature(
			0, 
			htlcPubScript, 
			Transaction.SigHash.ALL, 
			false
		);
		if (!clientPrimaryKey.verify(sighash, clientForfeitTxSig)) {
			throw new VerificationException(
				"Client signature does not verify forfeiture Tx " +	forfeitTx
			);
		}
		this.forfeitTx = forfeitTx;
		this.clientForfeitTxSig = clientForfeitTxSig;
		this.state = State.FORFEIT_RECEIVED;
	} 
	
	public SignedTransaction getFullForfeitTx(
		Transaction teardownTx,
		ECKey serverKey
	) {
		checkState(state == State.FORFEIT_RECEIVED);
		TransactionSignature serverSig = forfeitTx.calculateSignature(
			0, 
			serverKey, 
			teardownTx.getOutput(2).getScriptPubKey(), 
			SigHash.ALL,
			false
		);
		this.state = State.FORFEIT_RETRIEVED;
		return new SignedTransaction(forfeitTx, serverSig);
	}
	
	public Transaction getFullSettlementTx(
		Transaction teardownTx,
		ECKey serverKey
	) {
		checkState(state == State.FORFEIT_RETRIEVED);
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
		
		// Create the script that spends the multisig output.
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
	
	public Transaction getSettlementTx() {
		return settlementTx;
	}
	
	public String getSecret() {
		return secret;
	}
}
  