package org.bitcoinj.channels.htlc;

import java.util.List;
import org.bitcoinj.core.Coin;
import com.google.common.primitives.Bytes;


public class HTLCPaymentReceipt {
	private final Coin value;
	private final List<String> data;

    public HTLCPaymentReceipt(Coin value, List<String> data) {
	    this.value = value;
        this.data = data;
    }

    public Coin getValue() {
        return value;
    }

    public List<String> getData() {
        return data;
    }
}
