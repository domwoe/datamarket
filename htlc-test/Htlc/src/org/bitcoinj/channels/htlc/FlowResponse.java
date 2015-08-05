package org.bitcoinj.channels.htlc;

import java.util.List;

public class FlowResponse {
	
	private final List<String> stats;
	
	public FlowResponse(List<String> stats) {
		this.stats = stats;
	}
	
	public List<String> getStats() {
		return stats;
	}
}
