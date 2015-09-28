package org.bitcoinj.channels.htlc.buyer;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.bitcoinj.core.Coin;
import org.slf4j.LoggerFactory;

public class HTLCBuyerDriver {
	
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCBuyerDriver.class);
	private static final Integer BUYERS = 1;
	
	private List<HTLCBuyerInstance> buyersList = new ArrayList<>();
	
	public static void main(String[] args) throws Exception {
		
		HTLCBuyerDriver driver = new HTLCBuyerDriver();
		for (int i = 0; i < BUYERS; i++) {
			log.info("Spawing buyer {}", i);
			HTLCBuyerInstance buyerInstance = new HTLCBuyerInstance("buyer" + i);
			driver.addBuyer(buyerInstance);
			new Thread(buyerInstance).start();
		}
		
		driver.readQuery();
	}
	
	public void readQuery() throws InterruptedException {
		Scanner input = new Scanner(System.in);
		
		while (input.hasNext()) {
			String query = input.nextLine();
			// Remove quotes
			query = query.replace("\"", "");
			String delims = "[ ]+";
			String[] tokens = query.split(delims);
			if (tokens[0].equalsIgnoreCase("stats")) {
				if (tokens[1].equalsIgnoreCase("nodes")) {
					nodeStats();
				} else if (tokens[1].equalsIgnoreCase("sensors")) {
					sensorStats();
				} else {
					error("Invalid stats query.");
				}
			} else if (tokens[0].equalsIgnoreCase("select")) {
				String sensorType = 
					query.substring(query.indexOf('<') + 1, query.indexOf('>'));
				select(sensorType);
			} else if (tokens[0].equalsIgnoreCase("buy")) {
				String sensorType = 
					query.substring(query.indexOf('<') + 1, query.indexOf('>'));
				query = query.replaceAll("<.*?>", "");
				tokens = query.split(delims);
				String deviceId = tokens[1];
				Long value = Long.parseLong(tokens[2]);
				
				buy(sensorType, deviceId, Coin.valueOf(value));
			} else if (tokens[0].equalsIgnoreCase("close")) {
				close();
			} else {
				error("Invalid query.");
			}
		}
		input.close();
	}
	
	private void close() {
		for (int i = 0; i < buyersList.size(); i++) {
			try {
				buyersList.get(i).close();
			} catch (Exception e) {
				e.printStackTrace();
				continue;
			}
		}
	}
	
	private void nodeStats() {
		for (int i = 0; i < buyersList.size(); i++) {
			buyersList.get(i).nodeStats();
		}
	}
	
	private void sensorStats() {
		for (int i = 0; i < buyersList.size(); i++) {
			buyersList.get(i).sensorStats();
		}
	}
	
	private void select(String sensorType) {
		for (int i = 0; i < buyersList.size(); i++) {
			buyersList.get(i).select(sensorType);
		}
	}
	
	private void buy(String sensorType, String deviceId, Coin value) {
		for (int i = 0; i < buyersList.size(); i++) {
			buyersList.get(i).buy(sensorType, deviceId, value);
		}
	}
	
	public void addBuyer(HTLCBuyerInstance buyer) {
		buyersList.add(buyer);
	}
	
	private void error(String error) {
		log.error("Input error has occured: {}", error);
	}
}
 