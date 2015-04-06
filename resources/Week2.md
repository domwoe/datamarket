# Week 2 Progress

##Resources: 
1. https://bitcoin.org/en/developer-guide
2. https://bitcoinj.github.io/working-with-contracts
3. https://en.bitcoin.it/wiki/Atomic_cross-chain_trading
4. https://en.bitcoin.it/wiki/Zero_Knowledge_Contingent_Payment
5. https://en.bitcoin.it/wiki/Contracts#Example_7:_Rapidly-adjusted_.28micro.29payments_to_a_pre-determined_party
6. http://rusty.ozlabs.org/?p=450
7. Bitcoin Transaction Malleability and MtGox (http://www.tik.ee.ethz.ch/file/7e4a7f3f2991784786037285f4876f5c/malleability.pdf)
8. http://socket.io/get-started/chat/

## Specification

### System components
The system architecture comprises 3 major components: one component consists of several sensor data providers (Android phones running a specialized app), one central hub (important for discovery and relaying the sensor and payments data), and a third component, consisting of data buyers.

#### Android App
The app's interface consists of a main view that allows the user to toggle each sensor between sharing or not its data for Bitcoins. The available sensors are detected at startup and only these are displayed in the main view.

The bitcoin wallet is created on the first start and stored on the device (possibly encrypted with a user-chosen key).
At the top of the view, it displays the value in the wallet.

#### Central Hub
Runs a Java Spring application that lands the buyers on a simple website allowing queries for data in exchange for bitcoins. The buyers are able to create accounts to:
* setup their Bitcoin wallet
* establish a 30-days timeLocked micropayment channel with the central hub (allow choosing value to be locked in)
* view stats such as: remaining money, number of connected devices, types of sensor data available and prices for these
* query and receive data by spending from their Bitcoin wallet (allow filtering by location if this data is shared by device); buyer can then download the set of data to his personal device

It also runs a Socket Server that allows the Android apps to connect to it. This subcomponent keeps track of all connected devices, is in charge of broadcasting queries for sensor data, and receiving the raw data to be forwarded back to the HTTP Server and given to the buyer.

#### Buyers
This component consists of the client browser accessing the website which allows queries on the available data in exchange for bitcoins.

### Establishing the connections

#### Between data providers and Hub
In order to support connectivity, the Hub will run a TCP Socket Server (eg. SocketIO to allow broadcasting to all Android phones when queries are coming through).
The Android app first established a TCP connection to the Hub (fixed address). Once the TCP handshake is successful, the app will announce the offered sensor data to the central component through the established connection.

Message is in JSON format:
{"data": [{"id": "location"},
		  {"id": "signal"},
		  {"id": "humidity"},
		  {"id": "temperature"},
		  {"id": "magnetic_field"}
		 ]
}
Note: if user switches off the data of one of the sensors, a new, updated message is sent to the hub, informing it of the change.

### Running a query
When a buyer runs a query, this is received by the Spring component in the central Hub as a POST request. The hub will forward the type of data that the buyer is interested in to the Socket Server. The Socket Server will then broadcast to all connected (and location matching) Android devices a message requesting to send the data back on the existing TCP connection. 

Message is in JSON format, for eg.:
{"query": [{"id": "location"},
		   {"id": "signal"},
		  ]
}

The connection listener on each Android device analyzes the request it received from the Hub, reads the raw data from the required sensors, and forwards these as a JSON message back to the hub.

The Server Socket subcomponent receives the raw data and relays it to the HTTP Server subcomponent, which will serve it to the buyer on the website.

### Payment Protocol  
![HTLC with Microchannel Payments](/resources/img/HTLC.png)

The first part of the diagram explains the micropayment channel setup between the 3 major components. 

At the heart of the system lays the central hub. The hub establishes micropayment channels with all data providers and all buyers. On the other hand, each of the other components keeps only one micropayment channel open, with the central hub.

When a new data provider connects to the central Hub or an existing one has an expired channel, it establishes a TCP connection and sets up a new micropayment channel between the two. The central Hub has to lock in a certain amount for the next 30 days. Assuming numerous data providers, the Hub must have a high amount of Bitcoins alocated to run the entire system. Initially, the app component has a fully signed commit TX that sends him 0 and X to the Hub.

When a buyer queries the Hub for the first time, he is required to lock in a certain amount of Bitcoins in the micropayment channel that is established between the him and the Hub. Initially, the Hub component has a fully signed commit TX that sends him 0 and Y to the Buyer.

Both the Hub (in the Hub-App channel) and the Buyer (in the Buyer-Hub channel), get a signed timelocked (for 30 days) full refund TX, in case their counter-party vanishes. 

When a query is served, a new HTLC flow is created between the Buyer and the Hub, and the Hub and the Apps. This is displayed in the second part of the diagram. Basically, starting from the Android app, a hash-chain is formed backwards to the Buyer:
* The App creates random data R and distributes its hash
* Between the App and the Hub. A new multisig output is added to the teardown TX and double spent: one TX with a timelock of 1 day to send the coin back to the Hub if the App does not reveal R in time, and the second commit TX that uses R to transfer the coin to the App. If R is given within 1 day, the two can agree to update the teardown TX by removing the HTLC and transfer the coin to the App. If it is not, the Hub is able to get back the coin after 1 day.
* Same process goes on between the Buyer and the Hub, only difference is that in this case, the timelock is set to 2 days, so no participant can reveal R so late that the next components do not have the opportunity to claim their coins.