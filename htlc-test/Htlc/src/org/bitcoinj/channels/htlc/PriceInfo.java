package org.bitcoinj.channels.htlc;

public class PriceInfo {
	private String sensor;
	private Long price;
	
	public PriceInfo(String sensor, long price) {
		this.sensor = sensor;
		this.price = price;
	}
	
	public String getSensor() {
		return sensor;
	}
	
	public Long getPrice() {
		return price;
	}
}