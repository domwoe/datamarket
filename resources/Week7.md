# Week 7 Progress

## Resources
1. https://bitcoinj.github.io/javadoc/0.12.3
2. https://bitcoin.org/en/developer-guide#micropayment-channel

## Completed steps
1. Went through the BitcoinJ micropayment channels code.
2. Wrote a simplified and cleaner version of it
	* Creates an initial Multisig transaction
	* Attaches a refund transaction with an output to the client with the locked in value
	* Gets it signed by the server
	* Then the client gives the server the Multisig tx, which in turn broadcasts it and locks in the money of the client
	* When a new payment is made, the client updates the multisig output to pay an additional amount to the server and subtract that from the refund to its address 
3. Integrated the HTLC workflow in the modified micropayment channels code:
	* When a new payment is made, a new HTLC flow is constructed: the HTLC setup transaction is set up on the client side that locks in the amount the client wants to pay to the server.
	* A refund tx for that amount is passed to the server, which signs and returns it to the client
	* Then the HTLC setup TX can be safely given to the server 
	* If the secret is produced in time, the two will simply call the increment method which wipes the HTLC output and updates the refund accordingly

## Next steps
1. Build driver-classes that make the transition of the built finite state machines between states.
2. Build classes that allow running the protocol on the network.
3. Test on the local Bitcoin network.
