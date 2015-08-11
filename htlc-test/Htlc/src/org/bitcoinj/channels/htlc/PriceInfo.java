package org.bitcoinj.channels.htlc;

public class PriceInfo {
	private final String deviceId;
	private final String sensor;
	private final Long price;
	
	public PriceInfo(String deviceId, String sensor, long price) {
		this.deviceId = deviceId;
		this.sensor = sensor;
		this.price = price;
	}
	
	public String getDeviceId() {
		return deviceId;
	}
	
	public String getSensor() {
		return sensor;
	}
	
	public Long getPrice() {
		return price;
	} 
}