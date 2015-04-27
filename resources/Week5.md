# Week 5 Progress

## Resources
1. https://bitcoinj.github.io/working-with-contracts
2. https://en.bitcoin.it/wiki/Atomic_cross-chain_trading
3. https://bitcointalk.org/index.php?topic=193281.msg3315031#msg3315031
4. https://bitcoinj.github.io/javadoc/0.12
5. https://en.bitcoin.it/wiki/Script
6. https://en.bitcoin.it/wiki/Transaction
7. https://bitcoinj.github.io/testing
8. https://bitcoinj.github.io/working-with-the-wallet
9. https://en.bitcoin.it/wiki/Bitcoind

## Completed steps
### HTLC
1. Read on the bitcoin testnet, decided to setup regression test mode locally:
	* Installed Bitcoind (program that implements the Bitcoin protocol for command line and remote procedure call (RPC) use)
	* Setup a simple configuration for it to run locally
	* Created new blocks in local blockchain
2. Wrote a simple Java app that creates a Bitcoin wallet, stores it locally.
	* Managed to sent 10 BTC to app's address from the local bitcoin daemon
	* Mined the transaction locally to it is available in the wallet immediately
3. Continued work on HTLC. Found a very useful post (Resource 3) that contains "socrates1024" post about Altcoins: detailed protocol, includes scripts.
	* Dwelved into the bitcoinJ documentation, looking into Transaction, Script, ScriptBuilder classes
	* Built the main scriptPubKey:
	IF 
		2 PUSHDATA(33)[2d7aebadf772f8987f92d3ebe175ac8a78ace8a89609629e03afd3ac12	679b4b8] PUSHDATA(33)[2af680b7d20620ce435a0d95c5c20c70a88db5e7752a8d6d		f4963e59a1aa14c] 2 CHECKMULTISIGVERIFY 
	ELSE 
		SHA256 PUSHDATA(32)[2208b9403a87df9f4ed6b2ee2657efaa589026b4cce9accc8e8a	 5bf3d693c86] EQUAL 
	ENDIF

### Android app
1. Added location services
2. Only decimal numbers allowed for pricing

## Next steps
1. Create another timelocked Transaction for the refund linked to the output of the Tx above, get it signed by peer.
2. Then broadcast it to lock in the money. 
3. Try to claim the second branch by using the secret's hash.
4. Build finite state machine