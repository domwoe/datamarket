// HTLCServiceListener.aidl
package ch.eth.datamarketclean;

// Declare any non-default types here with import statements

interface HTLCServiceListener {
   List<String> getDataFromSensor(String sensorType);
   void handleWalletUpdate(long value);
   void channelEstablished();
}
