package org.bitcoinj.channels.htlc;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.TransactionSignature;

public class SignedTransaction {
	
	private Transaction tx;
    private TransactionSignature sig;
    
    public SignedTransaction(Transaction tx, TransactionSignature sig) {
    	this.tx = tx;
    	this.sig = sig;
    }

	public Transaction getTx() {
		return tx;
	}

	public TransactionSignature getSig() {
		return sig;
	}
}
