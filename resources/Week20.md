# Progress Week 20

## Completed steps
1. Added identifier on the Hub that is used by the clients to select the device they want to buy from.
2. Split the buying process into indexing the available data with quotas and actual payment and data exchange.
3. Almost (see current issues) completed forwarding of flow messages between the Android and Buyer Hub subcomponents. 
4. Fixed ordering of HTLC setups on the path. Flow is as follows:
	* Buyer initiates payment. Hub forwards initiating message to device
	* Device creates new HTLC, gives hash back to Hub, which forwards it to the buyer
	* Buyer does a full HTLC setup with the Hub
	* When the setup is complete, the Hub is signaled and the HTLC setup between the Hub and the device is resumed

## Current issues
1. Do I need synchronous rounds of updates between the 3 components or can it be split up in 2?
2. Problem with batching: have to do msg analysis every time to check which buyer/android device to forward to. 
Same big msg contains payments for several devices in a batch. Currently sorting by deviceId for the device. 
Not yet sure about the buyers.
Idea: use htlcIds to identify the connection, but then a lot of msg processing will take place on the Hub superclass
to inspect which payment is whose and will need to recreate the batch messages.
3. When revealing a secret, do we first update all transactions between the hub and the device, then forward it
to the client?  

## Next steps
1. Fix current issues.
2. Test the system.
3. Start report.