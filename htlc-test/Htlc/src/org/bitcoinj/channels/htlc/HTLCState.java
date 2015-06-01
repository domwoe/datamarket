package org.bitcoinj.channels.htlc;

import java.util.UUID;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.RegTestParams;

public abstract class HTLCState {
	
	private static NetworkParameters PARAMS = RegTestParams.get();
	
	private final Coin value;

	private final long settlementExpiryTime;
	private final long refundExpiryTime;
	
	private final String id;
	
	protected HTLCState(
		String id,
		Coin value,
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		this.id = id;
		this.value = value;
		this.settlementExpiryTime = settlementExpiryTime;
		this.refundExpiryTime = refundExpiryTime;
	}
	
	protected HTLCState(
		Coin value,
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		this(UUID.randomUUID().toString(), value, 
			settlementExpiryTime, refundExpiryTime);
	}
	
	public static NetworkParameters getParams() {
		return PARAMS;
	}
	
	public Coin getValue() {
		return value;
	}

	public long getSettlementExpiryTime() {
		return settlementExpiryTime;
	}

	public long getRefundExpiryTime() {
		return refundExpiryTime;
	}

	public String getId() {
		return id;
	}
}
