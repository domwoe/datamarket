# How To Run the entire system

## Hub
1. It needs the regtest bitcoin net running on the localhost.
2. A "hub.wallet" with 3 keys and sufficient funds.
3. Can be run in Eclipse using the HTLCHubDriver main.

## Android App
1. in HTLCService, replace 
```appKit.setPeerNodes(
    new PeerAddress(
        InetAddress.getByName("192.168.0.102"),
        PARAMS.getPort()
    )
);
```
with appropriate IP for the regtest net.

2. In the same file, replace 
```final InetSocketAddress server =
	new InetSocketAddress("192.168.0.102", 4242);
```
with appropriate hub address (port stays).

3. Run with Android Studio, service will be started at the same
time with the app.

## Buyer 
1. It needs the regtest bitcoin net running on the localhost. If you want it to
connect to a remote regtest net, replace:
```appKit = new WalletAppKit(PARAMS, new File("."), "hub");
appKit.connectToLocalHost();
with:
appKit = new WalletAppKit(PARAMS, new File(path), "htlc_client");
try {
	appKit.setPeerNodes(
		new PeerAddress(
			InetAddress.getByName("REGTEST_NET_IP"), 	
			PARAMS.getPort()
		)
	);
} catch (UnknownHostException e1) {
	e1.printStackTrace();
}
```

2. Replace "localhost"
```final InetSocketAddress server = 
	new InetSocketAddress("localhost", BUYER_PORT);
```
with appropriate IP for Hub.

3. Can be run in Eclipse using HTLCBuyerDriver main.

3. Run select queries with:
```select <sensor_name>``` (This must match the sensor's exact name as registered on the Hub and displayed in the app)
This will return the device id and the price

4. Run buy queries with:
```buy <sensor_name> <device_id> <price>```