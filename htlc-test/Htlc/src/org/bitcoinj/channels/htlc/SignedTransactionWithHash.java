package org.bitcoinj.channels.htlc;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.TransactionSignature;

public class SignedTransactionWithHash extends SignedTransaction {
	
	private Sha256Hash spentTxHash;
	
	public SignedTransactionWithHash(
		Transaction tx, 
		TransactionSignature sig, 
		Sha256Hash spentTxHash
	) {
		super(tx, sig);
		this.spentTxHash = spentTxHash;
	}
	
	public Sha256Hash getSpentTxHash() {
		return spentTxHash;
	}
}
