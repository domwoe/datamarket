import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.net.InetAddress;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.crypto.TransactionSignature;

public class Htlc {

	private static final String secret = "hashme";

	public static Wallet loadWallet(File f, NetworkParameters params) {

		Wallet wallet = null;
		ECKey key = null;

		if (f.exists()) {
			try {
				wallet = Wallet.loadFromFile(f);
			} catch (UnreadableWalletException e) {
				e.printStackTrace();
				
			}
		} else {
			wallet = new Wallet(params);
		}

		if (wallet.getImportedKeys().size() == 0) {
			// No key, create one
			key = new ECKey();
			byte[] publicKey = key.getPubKey();
			Address addr = key.toAddress(params);
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
			Address addr = firstKey.toAddress(params);
			System.out.println("CURRENT ADDRESS: " + addr.toString());
		}
		return wallet;
	}

	public static void createHTLC(NetworkParameters params) {

		Wallet myWallet = Htlc.loadWallet(new File("test.wallet"), params);
		ECKey myKey = myWallet.getImportedKeys().get(0);
		byte[] myPubKey = myKey.getPubKey();

		Wallet peerWallet = Htlc.loadWallet(new File("test1.wallet"), params);
		ECKey peerKey = peerWallet.getImportedKeys().get(0);
		byte[] peerPubKey = peerKey.getPubKey();

		/* CLIENT SIDE */
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
			bld.data(digest);
			bld.op(ScriptOpCodes.OP_SHA256);
			bld.op(ScriptOpCodes.OP_EQUAL);
			bld.data(peerPubKey);
			bld.op(ScriptOpCodes.OP_CHECKSIGVERIFY);
		bld.op(ScriptOpCodes.OP_ENDIF);
		Script htlcScript = bld.build();

		Coin amount = Coin.valueOf(1, 0);
		htlc.addOutput(amount, htlcScript);

		System.out.println("WALLET: " + myWallet);

		Set<Transaction> transactions = myWallet.getTransactions(false);
		for (Transaction t: transactions) {

			TransactionOutput sigOutput = t.getOutput(1);
			TransactionInput input = htlc.addInput(sigOutput);

			Script pubScript = sigOutput.getScriptPubKey();

			Sha256Hash sighash = htlc.hashForSignature(0, pubScript, Transaction.SigHash.ALL, false);
			ECKey.ECDSASignature mySignature = myKey.sign(sighash);
			TransactionSignature ts = new TransactionSignature(mySignature, Transaction.SigHash.ALL, false);
			Script inputScript = ScriptBuilder.createInputScript(ts, myKey);

			System.out.println("TX PUBSCRIPT: " + sigOutput.getScriptPubKey());
			System.out.println("TX SIGSCRIPT: " + inputScript);

			System.out.println("TX: " + t);
			System.out.println("HTLC TX: " + htlc);

			input.setScriptSig(inputScript);
			input.verify(sigOutput);
			
			break;
		}

		/****************/

		/* SERVER SIDE */
		// Send htlc over the network, simulate locally for now
/*		Transaction peerHtlc = htlc;
		TransactionOutput multisigOut = peerHtlc.getOutput(0);
		Script multisigScript = multisigOut.getScriptPubKey();

		Transaction peerRefund = new Transaction(params);
		peerRefund.addOutput(amount, myKey);
		peerRefund.addInput(multisigOut);
		// Lock it for 10 minutes
		long lockTime = Date.getTime() / 1000l + 10*60;
		peerRefund.setLockTime(lockTime);
		// Sign it
		Sha256Hash sighash = spendTx.hashTransactionForSignature(0, multisigScript, Transaction.SIGHASH_ALL, false);
		ECKey.ECDSASignature signature = peerKey.sign(sighash);*/
		/****************/
	}

	public static void main(String[] args) throws Exception {

		NetworkParameters params = RegTestParams.get();

		Htlc.createHTLC(params);
		
		//Wallet wallet = Htlc.loadWallet(new File("test.wallet"), params);

/*
		BlockStore blockStore = new MemoryBlockStore(params);
        BlockChain chain = new BlockChain(params, wallet, blockStore);

        final PeerGroup peerGroup = new PeerGroup(params, chain);
        peerGroup.addAddress(new PeerAddress(InetAddress.getLocalHost()));
        peerGroup.startAsync();
        // Now download and process the block chain.
        peerGroup.downloadBlockChain();
        peerGroup.stopAsync();

        final File walletFile = new File("test.wallet");
        wallet.saveToFile(walletFile);
        System.out.println("Wallet: " + wallet);
*/		
	}
} 