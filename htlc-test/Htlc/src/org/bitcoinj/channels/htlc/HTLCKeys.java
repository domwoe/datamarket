package org.bitcoinj.channels.htlc;

import org.bitcoinj.core.ECKey;

public class HTLCKeys {
	private final ECKey clientPrimaryKey;
	private final ECKey clientSecondaryKey;
	private final ECKey serverKey;
	
	public HTLCKeys(
		ECKey clientPrimaryKey,
		ECKey clientSecondaryKey,
		ECKey serverKey
	) {
		this.clientPrimaryKey = clientPrimaryKey;
		this.clientSecondaryKey = clientSecondaryKey;
		this.serverKey = serverKey;
	}

	public ECKey getClientPrimaryKey() {
		return clientPrimaryKey;
	}

	public ECKey getClientSecondaryKey() {
		return clientSecondaryKey;
	}

	public ECKey getServerKey() {
		return serverKey;
	}
}
