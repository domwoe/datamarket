package org.bitcoinj.channels.htlc;

import org.bitcoinj.core.Coin;

import com.google.common.primitives.Bytes;


public class HTLCPaymentReceipt {
	private final Coin value;
	private final Bytes data;

    public HTLCPaymentReceipt(Coin value, Bytes data) {
	    this.value = value;
        this.data = data;
    }

    public Coin getValue() {
        return value;
    }

    public Bytes getData() {
        return data;
    }
}
