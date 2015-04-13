#Week 3 Progress

## Resources:

1. https://developers.google.com/protocol-buffers/docs/javatutorial
2. http://www.javaworld.com/article/2078482/java-web-development/bitcoin-for-beginners--part-3--the-bitcoinj-api.html

## Protocol

The communication protocol is based on Google's protobuf messages. These are exchanged through TCP between the buyers and the Hub, and between the Hub and the Android apps, on the established micropayment channels (by extending it with payloads).

### Android connecting to Hub

On startup, the app checks available sensors and displays them.
A TCP connection is established to the Central Hub (dedicated address). The available and shared sensors are communicated.

enum SensorType {
	LOCATION = 0;
	SIGNAL_STRENGTH = 1;
	TEMPERATURE = 2;
	MAGNETIC_FIELD = 3;
}

message Connect {
	repeated SensorType availableSensors = 1;
}

Upon communicating this, the Hub replies with a nonce that identifies the device and needs to be included in every upcoming message:

message Acknowledge {
	required string nonce = 1;
}

### Buyer application connecting to Hub

First, the buyer has to select a file that represents his/her Bitcoin wallet. After this, the amount to be locked into the channel is given as input, and the microchannel between the buyer and the central Hub is established. On top of this TCP connection, the buyer can run queries in exchange for small payments.

#### Querying the Hub

The query language is simple, allowing the buyer to select the sensor data he is interested in and run simple queries to see available data and number of connected devices:
##### Select
	SELECT sensor="LOCATION, SIGNAL_STRENGTH" will retrieve all location and signal strength data
	SELECT sensor="TEMPERATURE" filterBy LOCATION="CH" will return all temperature data available for devices currently in Switzerland
##### Stats
	STATS NODES will display number of connected Android devices
	STATS SENSORS will display the types of sensor data that are available and their quotes

The select queries are passed down through protobuf.

message Pair {
	optional string key = 1;
	optional string value = 2;
}

message Query {
	repeated SensorType sensorsToQuery = 1;
	repeated Pair filterPairs = 2; // Allow filtering for eg. "location"="CH"
}

The central Hub keeps an array of ServerConnectionEventHandlers that maintain the connections to all Android devices. When a query comes through from a buyer, the hub first does a filterQuery, which establishes whether a certain device's data conforms to the buyer's filter.

message CoarseQuery {
	repeated SensorType query = 1;
}

The device then answers with a coarse result (eg. coarse Location).

message ResultEntry {
	required SensorType sensor = 1;
	required bytes sensorData = 2;
}

message CoarseResult {
	repeated ResultEntry results = 1;
}

Once the conforming devices are found, the buyer's query is broadcast to all of them, and then the hub waits for the results to come back:

message QueryResult {
	repeated ResultEntry results = 1;
}

To allow thread inter-communication (between the connection to the buyer and the connections with the Android apps), the central Hub maintains a result BlockingQueue. The subcomponent in charge for the buyer connections calls into the broadcast function of the subcomponent in charge for the Android connections, then polls the results queue until all results are received. Once the filtering is done, the full query is broadcasted to a subset of the devices, the results are received and then sent back to the buyer.