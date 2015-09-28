// HTLCServiceApi.aidl
package ch.eth.datamarketclean;

// Declare any non-default types here with import statements
import ch.eth.datamarketclean.HTLCServiceListener;

interface HTLCServiceApi {
   void updateSensors(in List<String> sensors, long price);
   void addListener(HTLCServiceListener listener);
   void removeListener(HTLCServiceListener listener);
}
