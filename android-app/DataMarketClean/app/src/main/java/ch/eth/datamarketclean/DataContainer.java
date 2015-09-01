package ch.eth.datamarketclean;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by frabu on 26.08.2015.
 */
public class DataContainer<E> {

    private final Map<String, E> sensorDataMap;

    public DataContainer() {
        this.sensorDataMap = new HashMap<>();
    }

    public void put(String key, E element) {
        sensorDataMap.put(key, element);
    }

    public E get(String key) {
        return sensorDataMap.get(key);
    }
}
