import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;
import java.util.Date;

import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.crypto.TransactionSignature;

public class Htlc {

	private static final String secret = "hashme";
	private static final NetworkParameters PARAMS = RegTestParams.get();

	public static Wallet loadWallet(File f) {

		Wallet wallet = null;
		ECKey key = null;

		if (f.exists()) {
			try {
				wallet = Wallet.loadFromFile(f);
			} catch (UnreadableWalletException e) {
				e.printStackTrace();
				
			}
		} else {
			wallet = new Wallet(PARAMS);
		}

		if (wallet.getImportedKeys().size() == 0) {
			// No key, create one
			key = new ECKey();
			byte[] publicKey = key.getPubKey();
			Address addr = key.toAddress(PARAMS);
			System.out.println("NEW ADDRESS: " + addr.toString());
			wallet.importKey(key);
			try {	
				wallet.saveToFile(f);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else { 
			// Fetch first address and print it
			ECKey firstKey = wallet.getImportedKeys().get(0);
			byte[] publicKey = firstKey.getPubKey();
			Address addr = firstKey.toAddress(PARAMS);
			System.out.println("CURRENT ADDRESS: " + addr.toString());
		}
		return wallet;
	}

	public static TransactionOutput selectTxOutFromWallet(Wallet wallet)
			throws Exception {
		Set<Transaction> transactions = wallet.getTransactions(false);
		TransactionOutput sigOutput = null;

		for (Transaction t: transactions) {

			List<TransactionOutput> outputs = t.getOutputs();
			for (TransactionOutput tOut: outputs) {
				if (tOut.isMine(wallet)) {
					sigOutput = tOut;
					break;
				}
			}
			if (sigOutput == null) {
				continue;
			} else {
				return sigOutput;
			}
		}
		throw new Exception("No valid output found in wallet!");
	}

	public static TransactionOutput createHTLCTransaction(
		TransactionOutput prevTxOut,
		ECKey myKey,
		ECKey peerKey
	) {

		/* Hash the secret */
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		md.update(Htlc.secret.getBytes());
		byte[] digest = md.digest();

		byte[] myPubKey = myKey.getPubKey();
		byte[] peerPubKey = peerKey.getPubKey();

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
		Transaction htlc = new Transaction(PARAMS);
		Coin amount = Coin.valueOf(1, 0);
		TransactionOutput htlcOutput = htlc.addOutput(amount, htlcScript); 
		Coin change = prevTxOut.getValue().subtract(amount);
		htlc.addOutput(change, myKey.toAddress(PARAMS));

		/* Add inputs to the HTLC transaction pointing to the Tx we're spending */
		TransactionInput input = htlc.addInput(prevTxOut);

		/* Sign the HTLC  */
		Sha256Hash sighash = htlc.hashForSignature(0, prevTxOut.getScriptPubKey(), Transaction.SigHash.ALL, false);
		ECKey.ECDSASignature mySignature = myKey.sign(sighash);
		TransactionSignature ts = new TransactionSignature(mySignature, Transaction.SigHash.ALL, false);
		Script inputScript = ScriptBuilder.createInputScript(ts, myKey);

		// Verify the redeem to the previous TX
		input.setScriptSig(inputScript);
		input.verify(prevTxOut);
		return htlcOutput;
	}

	public static Transaction createHtlcRefundTx(
		TransactionOutput prevTxOut,
		ECKey myKey,
		ECKey peerKey
	) {

		Transaction refundTx = new Transaction(PARAMS);
		// Lock it for 10 minutes
		int minutes = 10;
		long lockTime = new Date().getTime() / 1000l + minutes*60;
		refundTx.setLockTime(lockTime);

		refundTx.addOutput(Coin.valueOf(1, 0), myKey.toAddress(PARAMS));
		TransactionInput refundInput = refundTx.addInput(prevTxOut);

		// "Hand" this refundTx over to the server to get it signed
		Sha256Hash sigHash = refundTx.hashForSignature(0, prevTxOut.getScriptPubKey(), Transaction.SigHash.ALL, false);

		ECKey.ECDSASignature peerRefundSig = peerKey.sign(sigHash);
		TransactionSignature peerTS = new TransactionSignature(peerRefundSig, Transaction.SigHash.ALL, false);

		ECKey.ECDSASignature myRefundSig = myKey.sign(sigHash);
		TransactionSignature myTS = new TransactionSignature(myRefundSig, Transaction.SigHash.ALL, false);

		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.data(new byte[]{}); // Null dummy
		bld.data(myTS.encodeToBitcoin());
		bld.data(peerTS.encodeToBitcoin());
		bld.op(ScriptOpCodes.OP_1);
		Script refundInputScript = bld.build();

		// Add it to the refundInput.
		refundInput.setScriptSig(refundInputScript);
		refundInput.verify(prevTxOut);

		return refundTx;
	}

	public static Transaction createHtlcSettlementTx(
		TransactionOutput prevTxOut,
		ECKey myKey,
		ECKey peerKey
	) {
		Transaction settlementTx = new Transaction(PARAMS);
		// Lock it for 9 minutes
		int minutes = 9;
		long lockTime = new Date().getTime() / 1000l + minutes*60;
		settlementTx.setLockTime(lockTime);

		settlementTx.addOutput(Coin.valueOf(1, 0), peerKey.toAddress(PARAMS));
		TransactionInput settleInput = settlementTx.addInput(prevTxOut);

		Sha256Hash sigHash = settlementTx.hashForSignature(0, prevTxOut.getScriptPubKey(), Transaction.SigHash.ALL, false);
		ECKey.ECDSASignature sigB = peerKey.sign(sigHash);
		TransactionSignature sigTS = new TransactionSignature(sigB, Transaction.SigHash.ALL, false);

		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.data(sigTS.encodeToBitcoin());
		bld.data(Htlc.secret.getBytes());
		bld.data(new byte[]{});
		Script inputScript = bld.build();

		settleInput.setScriptSig(inputScript);
		settleInput.verify(prevTxOut);

		return settlementTx;
	}


/*
	public static Transaction createHTLC(NetworkParameters params) {

		Wallet myWallet = Htlc.loadWallet(new File("test.wallet"), params);
		ECKey myKey = myWallet.getImportedKeys().get(0);
		byte[] myPubKey = myKey.getPubKey();
		Address myAddr = myKey.toAddress(params);

		Wallet peerWallet = Htlc.loadWallet(new File("test1.wallet"), params);
		ECKey peerKey = peerWallet.getImportedKeys().get(0);
		byte[] peerPubKey = peerKey.getPubKey();
		Address peerAddr = peerKey.toAddress(params);

		Transaction htlc = new Transaction(params);
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		md.update(Htlc.secret.getBytes());
		byte[] digest = md.digest();

		ScriptBuilder bld = new ScriptBuilder();
		bld.op(ScriptOpCodes.OP_IF);
			bld.op(ScriptOpCodes.OP_2);
			bld.data(myPubKey);
			bld.data(peerPubKey);
			bld.op(ScriptOpCodes.OP_2);
			bld.op(ScriptOpCodes.OP_CHECKMULTISIGVERIFY);
		bld.op(ScriptOpCodes.OP_ELSE);
			bld.op(ScriptOpCodes.OP_SHA256);
			bld.data(digest);
			bld.op(ScriptOpCodes.OP_EQUAL);
			bld.data(peerPubKey);
			bld.op(ScriptOpCodes.OP_CHECKSIGVERIFY);
		bld.op(ScriptOpCodes.OP_ENDIF);
		Script htlcScript = bld.build();

		System.out.println("WALLET: " + myWallet);

		Set<Transaction> transactions = myWallet.getTransactions(false);
		for (Transaction t: transactions) {

			List<TransactionOutput> outputs = t.getOutputs();
			TransactionOutput sigOutput = null;

			for (TransactionOutput tOut: outputs) {
				if (tOut.isMine(myWallet)) {
					sigOutput = tOut;
					break;
				}
			}

			if (sigOutput == null) {
				System.out.println("Couldn't find bitcoins to spend");
			} else {
				Coin amount = Coin.valueOf(1, 0);
				TransactionOutput htlcOutput = htlc.addOutput(amount, htlcScript); // Payment output 1 BTC
				Coin change = sigOutput.getValue().subtract(amount);
				htlc.addOutput(change, myAddr); // Change for rest THROWS DUPLICATE OUTPOINT

				TransactionInput input = htlc.addInput(sigOutput);

				// Sign the Main Transaction but don't broadcast just yet 
				Script pubScript = sigOutput.getScriptPubKey();
				Sha256Hash sighash = htlc.hashForSignature(0, pubScript, Transaction.SigHash.ALL, false);
				ECKey.ECDSASignature mySignature = myKey.sign(sighash);
				TransactionSignature ts = new TransactionSignature(mySignature, Transaction.SigHash.ALL, false);
				Script inputScript = ScriptBuilder.createInputScript(ts, myKey);

			//	System.out.println("TX PUBSCRIPT: " + sigOutput.getScriptPubKey());
		//		System.out.println("TX SIGSCRIPT: " + inputScript);

			//	System.out.println("TX: " + t);
				System.out.println("HTLC TX: " + htlc);

				// Verify the redeem to the previous TX
				input.setScriptSig(inputScript);
				input.verify(sigOutput);


				Transaction refundTx = new Transaction(params);
				refundTx.addOutput(amount, myAddr);
				TransactionInput refundInput = refundTx.addInput(htlcOutput);
				// Lock it for 10 minutes
				int minutes = 10;
				long lockTime = new Date().getTime() / 1000l + minutes*60;
				refundTx.setLockTime(lockTime);

				// "Hand" this refundTx over to the server to get it signed
				Script refundPubScript = htlcOutput.getScriptPubKey();
				Sha256Hash serverRefundSighash = refundTx.hashForSignature(0, refundPubScript, Transaction.SigHash.ALL, false);
				ECKey.ECDSASignature serverRefundSig = myKey.sign(serverRefundSighash);
				TransactionSignature serverTS = new TransactionSignature(serverRefundSig, Transaction.SigHash.ALL, false);

				Sha256Hash myRefundSighash = refundTx.hashForSignature(0, refundPubScript, Transaction.SigHash.ALL, false);
				ECKey.ECDSASignature myRefundSig = myKey.sign(myRefundSighash);
				TransactionSignature myTS = new TransactionSignature(myRefundSig, Transaction.SigHash.ALL, false);

				// Create the script that spends the multi-sig output.
				bld = new ScriptBuilder();
				bld.data(serverTS.encodeToBitcoin());
				bld.data(myTS.encodeToBitcoin());
				bld.data(peerPubKey);
				bld.data(myPubKey);
				bld.op(ScriptOpCodes.OP_1); // One dummy op for OP_CHECKMULTISIGVERIFY bug

				System.out.println("refundPubScript: " + refundPubScript);
				
				// Build it to a Script
				Script refundInputScript = bld.build();
				System.out.println("RefundInput: " + refundInputScript);
				// Add it to the refundInput.
				refundInput.setScriptSig(refundInputScript);
				refundInput.verify(htlcOutput);

				try {
				  	
					BlockStore blockStore = new MemoryBlockStore(params);
			        BlockChain chain = new BlockChain(params, myWallet, blockStore);

			        final PeerGroup peerGroup = new PeerGroup(params, chain);
        			peerGroup.addAddress(new PeerAddress(InetAddress.getLocalHost()));
		    	    peerGroup.start();
				  	peerGroup.broadcastTransaction(htlc); 
				  	peerGroup.stop();
				  	System.out.println("WALLET: " + myWallet);
				  	//myWallet.commitTx(htlc);

				} catch (VerificationException | BlockStoreException | UnknownHostException e) {
					System.out.println(e.getMessage());
					e.printStackTrace();
				}
			}

			break;
		}
	}

	public static void claimWithSecret(NetworkParameters params) throws Exception {

		Wallet peerWallet = Htlc.loadWallet(new File("test.wallet"), params);
		BlockStore blockStore = new MemoryBlockStore(params);
        BlockChain chain = new BlockChain(params, peerWallet, blockStore);

        final PeerGroup peerGroup = new PeerGroup(params, chain);
		peerGroup.addAddress(new PeerAddress(InetAddress.getLocalHost()));
	    peerGroup.startAsync();
	    peerGroup.downloadBlockChain();
	    System.out.println("PEER WALLET: " + peerWallet);
	    peerWallet.saveToFile(new File("test.wallet"));
	    peerGroup.stopAsync();
	}
*/
	public static void main(String[] args) throws Exception {

		NetworkParameters params = RegTestParams.get();

		Wallet myWallet = Htlc.loadWallet(new File("test.wallet"));
		Wallet peerWallet = Htlc.loadWallet(new File("test1.wallet"));

		ECKey myKey = myWallet.getImportedKeys().get(0);
		ECKey peerKey = peerWallet.getImportedKeys().get(0);

		TransactionOutput prevTxOut = Htlc.selectTxOutFromWallet(myWallet);
		TransactionOutput htlcTxOut = Htlc.createHTLCTransaction(prevTxOut, myKey, peerKey);
		Transaction refundTx = Htlc.createHtlcRefundTx(htlcTxOut, myKey, peerKey);
		Transaction settlemeneTx = Htlc.createHtlcSettlementTx(htlcTxOut, myKey, peerKey);
	}
} 