package org.bitcoinj.channels.htlc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HtlcClientState {
	
	private static final Logger log = 
		LoggerFactory.getLogger(HtlcClientState.class);
	
	public enum State {
		NEW, 
		INITIATED
	}
	private State state;
	
	
	private final long settlementExpiryTime;
	private final long refundExpiryTime;

	public HtlcClientState(
		long settlementExpiryTime,
		long refundExpiryTime
	) {
		this.settlementExpiryTime = settlementExpiryTime;
		this.refundExpiryTime = refundExpiryTime;
		this.state = State.NEW;
	}
	
	public void initiate() {
		
	}
}
