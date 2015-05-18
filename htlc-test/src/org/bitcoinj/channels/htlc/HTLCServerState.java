package org.bitcoinj.channels.htlc;

import org.bitcoinj.channels.htlc.HTLCClientState.State;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
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
		INITIATED
	}
	private State state;
	
	public HTLCServerState(
		String id,
		Coin value, 
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		super(value, settlementExpiryTime, refundExpiryTime);
		this.state = State.INITIATED;
	}
	
	public byte[] provideHtlcRefundTransaction(
		Transaction htlcRefundTx,
		byte[] clientMultisigPubKey,
		Coin value,
		HTLCKeys keys
	) {
		if (state != State.INITIATED) {
			throw new IllegalStateException("Server was provided with HTLC refund tx in an invalid state: " + state);
		}
		log.info("Provided with refund transaction: {}", htlcRefundTx);
		// Do basic sanity check
		htlcRefundTx.verify();
		// Verify that the refundTx has a single input
		if (htlcRefundTx.getInputs().size() != 1) {
			throw new VerificationException(
				"HTLC refund transaction does not have exactly one input!"
			);
		}
		// Verify that the refundTx has a proper time-lock
		if (htlcRefundTx.getLockTime() < getRefundExpiryTime()) {
			throw new VerificationException(
				"HTLC refund tx has a lock time that is too early!"
			);
		}
		if (htlcRefundTx.getOutputs().size() != 1) {
			throw new VerificationException(
				"Htlc refund tx does not have exactly one output!"
			);
		}
		if (htlcRefundTx.getOutput(0).getValue().compareTo(value) != 0) {
			throw new VerificationException(
				"Htlc refund tx has an invalid value locked in!"
			);
		}
		// Sign the HTLC refund tx
		//clientKey = ECKey.fromPublicOnly(clientMultisigPubKey);
		Script multisigPubKey = ScriptBuilder.createMultiSigOutputScript(
			2, 
			ImmutableList.of(keys.getClientPrimaryKey(), keys.getServerKey())
		);
		TransactionSignature sig = htlcRefundTx.calculateSignature(
			0, 
			keys.getServerKey(), 
			multisigPubKey, 
			Transaction.SigHash.ALL, 
			false
		);
		log.info("Signed HTLC refund transaction.");
		state = State.WAITING_FOR_HTLC;
		return sig.encodeToBitcoin();
	}
}
 