package org.bitcoinj.channels.htlc.test;


import java.io.File;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bitcoinj.store.UnreadableWalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

/**
 * 
 */

/**
 * @author cdecker
 *
 */
public class Test {
	private static final Logger logger = LoggerFactory.getLogger(Test.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();
	private static final String SECRET = "Very secret string";
	/**
	 * @param args
	 * @throws UnreadableWalletException 
	 */
	public static void main(String[] args) throws UnreadableWalletException {
		Wallet walletA = Wallet.loadFromFile(new File("buyer0.wallet"));
		Wallet walletB = Wallet.loadFromFile(new File("hub.wallet"));
		
		// 0.1 Select addresses to send multisigs to
		ECKey privkeyA = walletA.getImportedKeys().get(0);
		ECKey privkeyB = walletB.getImportedKeys().get(0);
		byte[] pubkeyA = privkeyA.getPubKey();
		byte[] pubkeyB = privkeyB.getPubKey();
		logger.info("Pubkey A: " + new String(Hex.encode(pubkeyA)));
		logger.info("Pubkey B: " + new String(Hex.encode(pubkeyB)));
		
		// 0.1 Select the root output on which to build the HTLC setup transaction
		// Output 0840a8ae83599b889d1b9bf2b794b74bf0f46a28a74f90e29ee79668a1d6f783:0 seems a nice candidate
		Transaction prevTx = walletA.getTransactions(false).iterator().next();
		int prevOutIndex = 0;
		
		// 1. Create a transaction that creates an HTLC output
		Transaction setupTx = createSetupTransaction(prevTx, prevOutIndex, pubkeyA, pubkeyB, privkeyA, SECRET);
		logger.info(setupTx.toString());
		
		// 2 Create a refund transaction spending the setupTX's output
		Transaction refundTx = createRefundTransaction(setupTx, 0, privkeyA, privkeyB);
		logger.info(refundTx.toString());
		
		// 3 Create settlement transaction using the secret
		Transaction settlementTx = createSettlementTransaction(setupTx, 0, privkeyB, SECRET);
		logger.info(settlementTx.toString());
		
		// 4 Create forfeiture transaction, omitted since it's just a refundTx without a timelock.
	}
	
	public static Transaction createSetupTransaction(Transaction prevTx,
			int outputIndex, byte[] pubkeyA, byte[] pubkeyB, ECKey privkeyA, String secret){
		Transaction setupTx = new Transaction(PARAMS);
		
		// 1.1 Add inputs
		TransactionOutput prevOut = prevTx.getOutput(outputIndex);
		setupTx.addInput(prevOut);
		
		// 1.2 Add outputs
		// 1.2.1 Create HTLC script
		ScriptBuilder bld = new ScriptBuilder();
		bld.op(ScriptOpCodes.OP_IF);
			bld.op(ScriptOpCodes.OP_2);
			bld.data(pubkeyA);
			bld.data(pubkeyB);
			bld.op(ScriptOpCodes.OP_2);
			bld.op(ScriptOpCodes.OP_CHECKMULTISIG);
		bld.op(ScriptOpCodes.OP_ELSE);
		    bld.op(ScriptOpCodes.OP_HASH160);
			bld.data(Utils.sha256hash160(secret.getBytes()));
			bld.op(ScriptOpCodes.OP_EQUALVERIFY);
			bld.op(ScriptOpCodes.OP_2);
			bld.data(pubkeyA);
			bld.data(pubkeyB);
			bld.op(ScriptOpCodes.OP_2);
			bld.op(ScriptOpCodes.OP_CHECKMULTISIG);
		bld.op(ScriptOpCodes.OP_ENDIF);
		Script htlcScript = bld.build();
		
		TransactionOutput htlcOutput = new TransactionOutput(PARAMS, setupTx, Coin.valueOf(1, 0), htlcScript.getProgram());
		setupTx.addOutput(htlcOutput);
		
		// 1.3 Sign the one input
		// We need to give the scriptPubKey from the output we'd like to spend
		Sha256Hash sighash = setupTx.hashForSignature(outputIndex, prevOut.getScriptPubKey(), Transaction.SigHash.ALL, false);
		ECKey.ECDSASignature mySignature = privkeyA.sign(sighash);
		TransactionSignature ts = new TransactionSignature(mySignature, Transaction.SigHash.ALL, false);
		Script scriptSig = ScriptBuilder.createInputScript(ts, privkeyA);
		setupTx.getInput(0).setScriptSig(scriptSig);
		
		// 1.4 Verify that the scripts match up, throws exception if they don't
		scriptSig.correctlySpends(setupTx, 0, prevOut.getScriptPubKey());
		return setupTx;
	}
	
	public static Transaction createRefundTransaction(Transaction prevTx, int outputIndex, ECKey privkeyA, ECKey privkeyB){
		Transaction refundTx = new Transaction(PARAMS);
		refundTx.setLockTime(3500000);
		
		// 2.1 Add inputs
		TransactionOutput prevOut = prevTx.getOutput(outputIndex);
		refundTx.addInput(prevOut);
		
		// 2.2 Add output
		Script scriptPubKey = ScriptBuilder.createOutputScript(privkeyA);
		TransactionOutput refundOutput = new TransactionOutput(PARAMS, refundTx, Coin.valueOf(1, 0), scriptPubKey.getProgram());
		refundTx.addOutput(refundOutput);
		
		// 2.3 Build the scriptSig selecting the correct branch and providing all signatures
		// Build signatures
		Sha256Hash sighash = refundTx.hashForSignature(outputIndex, prevOut.getScriptPubKey(), Transaction.SigHash.ALL, false);
		ECKey.ECDSASignature sigA = privkeyA.sign(sighash);
		ECKey.ECDSASignature sigB = privkeyB.sign(sighash);
		TransactionSignature txSigA = new TransactionSignature(sigA, Transaction.SigHash.ALL, false);
		TransactionSignature txSigB = new TransactionSignature(sigB, Transaction.SigHash.ALL, false);
				
		ScriptBuilder bld = new ScriptBuilder();
		
		// Null dummy
		bld.data(new byte[]{});
		
		// The two signatures
		bld.data(txSigA.encodeToBitcoin());
		bld.data(txSigB.encodeToBitcoin());
		
		// Select upper branch of if-statement
		bld.op(ScriptOpCodes.OP_1);
		Script scriptSig = bld.build();
		refundTx.getInput(0).setScriptSig(scriptSig);
		
		// 2.4 Verify that the scripts match up
		scriptSig.correctlySpends(refundTx, 0, prevOut.getScriptPubKey());
		return refundTx;
	}
	
	public static Transaction createSettlementTransaction(Transaction setupTx, int outputIndex, ECKey privkeyB, String secret){
		Transaction settlementTx = new Transaction(PARAMS);
		settlementTx.setLockTime(3450000);
		
		// 3.1 Add inputs
		TransactionOutput prevOut = setupTx.getOutput(outputIndex);
		settlementTx.addInput(prevOut);
		
		// 3.2 Add output
		Script scriptPubKey = ScriptBuilder.createOutputScript(privkeyB);
		TransactionOutput refundOutput = new TransactionOutput(PARAMS, settlementTx, Coin.valueOf(1, 0), scriptPubKey.getProgram());
		settlementTx.addOutput(refundOutput);
		
		// 3.3 Build the scriptSig selecting the correct branch and providing all signatures
		// Build signatures
		Sha256Hash sighash = settlementTx.hashForSignature(outputIndex, prevOut.getScriptPubKey(), Transaction.SigHash.ALL, false);
		ECKey.ECDSASignature sigB = privkeyB.sign(sighash);
		TransactionSignature txSigB = new TransactionSignature(sigB, Transaction.SigHash.ALL, false);
				
		ScriptBuilder bld = new ScriptBuilder();
		
		// The signature from B
		bld.data(txSigB.encodeToBitcoin());
		
		// Provide the secret that hashed to the hash
		bld.data(secret.getBytes());
		
		// Select upper branch of if-statement
		bld.data(new byte[]{});
		Script scriptSig = bld.build();
		settlementTx.getInput(0).setScriptSig(scriptSig);
		
		// 3.4 Verify that the scripts match up
		scriptSig.correctlySpends(settlementTx, 0, prevOut.getScriptPubKey());
		return settlementTx;
	}
}
