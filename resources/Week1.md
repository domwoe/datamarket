# Week 1 Progress

## Papers
1. ** Bitcoin: A Peer-to-Peer Electronic Cash System ** - describes the solution to double-spending in a peer-to-peer network protocol using proof of work.
2. ** Homomorphic Payment Addresses and the Pay-to-Contract Protocol ** - describes an e-payment protocol for customer-merchant relations w/o requireing a trusted payment descriptor (created destination account number on client side -> no need for encrypted/authenticated communication; secure even with merchant compromise).
3. ** Inter-channel payments (Impulse) ** - models a wallet as a set of N always-open payment channels. Payment is made through N payment channels and are closed immediately. Individual payment channels are thought of as unspent outputs.
4. ** Micropaymants Revisited ** - describes 3 new probabilistic micropayment schemes that are more efficient, user-friendly, and reduce processing costs.
5. ** Mobile Sensor Data Collector using Android Smartphone ** - describes a system
that using an Android app, collects, displays and streams sensor data (for BSN - Body Sensor Networks developed for medical healthcare apps) to a central server.
6. ** Paying your Internet, One Byte at a Time ** - proof-of-concept allowing an access point to provide Internet access to untrusted users in exchange for bitcoins through a micropayment channel.
7. ** PayWord and MicroMint - Two simple micropayment schemes ** - describes two lightweight micropayments schemes: PayWord is designed to authenticate a complete chain to the vendor, then uses each payword to make micropayments; MicroMint is designed for increased speed (eliminates pubkey ops, replaces them with hash fcts).
8. ** RNAP: A Resource Negotiation and Pricing Protocol ** - describes a protocol through which a user and a network can negotiate network services. The service provider can communicate availability of services and delivers charging info to the user.
9. ** The Architecture of Coupon-Based, Semi-off-Line Anonymous Micropayment System for Internet of Things ** - describes an anonymous micropayment system for transactions in IoT. Uses the idea of hash chains to produce divisible coins.
10. ** The Bitcoin Lightning Network ** - describes a solution to Bitcoin's scalability problem by using timelocks on a network of micropayment channels. *Hashed Timelock Contract (HTLC) * - recipient generates random data R, hashes it to produce H and sends H to the sender of funds, together with its bitcoin address. Sender routes their payment to the receiver and when an updated transaction is received, the recipient may elect to redeem the transaction by disclosing the random data R (which pulls the funds from the sender).
	http://www.coindesk.com/could-the-bitcoin-lightning-network-solve-blockchain-scalability/

## URLS
	1. ** https://en.bitcoin.it/wiki/Transaction ** - transactions in Bitcoin are transfers of Bitcoin values that are broadcast to the network and collected into blocks (in the blockchain); a transaction references the previous transaction's outputs and dedicates all input Bitcoin value to new outputs; not encrypted -> possible to browse and view every transaction ever collected into a block. General format:
		* Version no
		* In-counter
		* List of inputs
		* Out-counter
		* List of outputs
		* Lock-time
	2. ** https://en.bitcoin.it/wiki/Script ** - list of instructions recorder with each transaction that describes how the next person wanting to spend the bictoins being transferred can gain access to them; spender must provide:
		* a pubk that, when hashed, yields the destination address D embedded into the script
		* a signature to show evidence of the private key corresponding to the pubkey just provided
	The party who originally sent the Bitcoins now being spent, dictates script operations that will occur last in order to release them for use in another transaction. The party wanting to spend them must provide inputs to the previously recorded script that results in those ops occuring last, leaving behind true.
	3. **https://en.bitcoin.it/wiki/Contracts#Example_7:_Rapidly-adjusted_.28micro.29payments_to_a_pre-determined_party **
	4. ** https://bitcoinj.github.io/working-with-micropayments **
	5. ** Alex Akselrod. Extensible Scalable Coopetitive High Availability Trade Optimization Network (ESCHATON) ** 
		* Friend-to-friend, instant, off-blockchain, trustless payments of arbitrary size with optional escrow, composable into distributed inter-blockchain instant payments and exchanges between arbitrary parties
		* Friend-to-friend, instant, off-blockchain, trustless bets composable into distributed derivatives and prediction markets, crowdfunding with performance bonds, insurance
		* Metered network services bought using micropayments, composable into meshnets, mixnets, microgrids, social networks, grid computing networks
	Proof-of-concept: https://github.com/aakselrod/libtxchain-java
	6. ** Peter Todd. Near-zero fee transactions with hub-and-spoke micropayments. ** http://sourceforge.net/p/bitcoin/mailman/message/33144746/ 
	Hub-and-Spoke Payments: Using a nearly completely untrusted hub, allow any number of parties to mutually send and receive Bitcoins instantly with near-zero
	transaction fees. Each participant creates one or two micropayment channels with the hub; for Alice to send Bob some funds Alice first sends the funds to the hub in some small increment, the hub sends the funds to Bob, and finally the hub gives proof of that send to Alice. The incremental amount of Bitcoins sent can be set arbitrarily low, limited only by bandwidth and CPU time, and Bob does not necessarily need to actually be online. The worst that the hub can do is leave user's funds locked until the timeout expires.
	7. ** http://developer.android.com/guide/topics/sensors/sensors_overview.html **

## Apps that read sensor data: 
	1. https://play.google.com/store/apps/details?id=com.fivasim.androsensor&hl=en
	2. https://play.google.com/store/apps/details?id=imoblife.androidsensorbox&hl=en
	3. https://play.google.com/store/apps/details?id=com.calintat.sensors&hl=en
	4. https://play.google.com/store/apps/details?id=com.mtorres.phonetester&hl=en
	5. https://play.google.com/store/apps/details?id=com.innoventions.sensorkinetics&hl=en

## Projects
	1. ** Sensor Ex ** (https://play.google.com/store/apps/details?id=com.tarco.tarsensorics&hl=en)
	Logs data directly to your PC via WiFi for real-time remote data acquisition 
	or plots phone sensors and saves them for extended analyses later on 

	2. ** Collecting Sensor Data from Smart Devices ** (http://web.engr.illinois.edu/~das17/smartphone-data-collection.html) Collects sensor data through an app and periodically uploads it to a remote server when WIFI is available. Contains source code as well.

	3. ** Funf ** (http://www.funf.org/about.html) The Funf Open Sensing Framework is an extensible sensing and data processing framework for mobile devices, supported and maintained by Behavio. The core concept is to provide an open source, reusable set of functionalities, enabling the collection, uploading, and configuration of a wide range of data signals accessible via mobile phones.

	4. ** sensor-data-collection-library ** A simple library for logging sensor data to a file. (https://code.google.com/p/sensor-data-collection-library/)