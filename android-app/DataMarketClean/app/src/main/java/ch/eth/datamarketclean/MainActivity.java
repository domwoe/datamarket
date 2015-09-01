package ch.eth.datamarketclean;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.multidex.MultiDex;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.Switch;

import com.google.common.collect.EvictingQueue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import ch.eth.datamarketclean.services.HTLCService;

import static android.widget.CompoundButton.OnCheckedChangeListener;


public class MainActivity extends Activity {

    private static final String SIGNAL_STRENGTH_LABEL = "Signal Strength GSM";
    private static final String GPS_SIGNAL_LABEL = "GPS Location";
    private static final String PREFERENCES = "DataMarketPreferences";
    private static final Integer MAX_DATA_ENTRIES = 10;

    private SensorManager mSensorManager;
    private LocationManager mLocationManager;
    private GPSLocationListener mLocationListener;

    private SwitchChangeListener mSwitchChangeListener;
    private SensorListener mSensorListener;

    private DataContainer<EvictingQueue<String>> dataContainer;
    private Map<String, Sensor> mSensorMap;
    private Set<String> mEnabledSensors;

    private HTLCServiceApi api;

    private final ReentrantLock lock = new ReentrantLock();

    private HTLCServiceListener.Stub htlcListener = new HTLCServiceListener.Stub() {
        @Override
        public List<String> getDataFromSensor(String sensorType) throws RemoteException {
            Log.i("Frabu", "Retrieving data from sensor: " + sensorType);
            EvictingQueue<String> sensorData = dataContainer.get(sensorType);
            if (sensorData == null) {
                return new ArrayList<>();
            } else {
                return new ArrayList<>(dataContainer.get(sensorType));
            }
        }

        @Override
        public void handleWalletUpdate() throws RemoteException {
            Log.i("Frabu", "Update the VIEW");
        }

        @Override
        public void channelEstablished() throws  RemoteException {
            Log.i("Frabu", "Micropayment channel was established. " +
                    "We should now send the available sensors");
                    "We should now send the available sensors");
            lock.lock();
            try {
                // Take all enabled sensor names and send them to the Hub
                List<String> currentSensors = new ArrayList<>();
                for (String sensor: mEnabledSensors) {
                    currentSensors.add(sensor);
                }
                if (!mEnabledSensors.isEmpty()) {
                    api.updateSensors(new ArrayList<>(mEnabledSensors));
                }
            } finally {
                lock.unlock();
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i("Frabu", "Service connection established");
            api = HTLCServiceApi.Stub.asInterface(service);
            try {
                api.addListener(htlcListener);
            } catch (RemoteException e) {
                Log.e("Frabu", "Failed to add listener", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Boolean isFirstRun = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean("isFirstRun", true);

        if (isFirstRun) {
            //show start activity
            startActivity(new Intent(MainActivity.this, FirstStartActivity.class));
        }

        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean("isFirstRun", false).commit();

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

            List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
            mEnabledSensors = new HashSet<>();
            mSensorMap = new HashMap<>();
            for (Sensor sensor: sensorList) {
                mSensorMap.put(sensor.getName(), sensor);
            }

            mSwitchChangeListener = new SwitchChangeListener();
            mSensorListener = new SensorListener();
            renderSwitchButtons(sensorList);
            dataContainer = new DataContainer<>();
        }

        File dir = getApplicationContext().getFilesDir();

        Log.i("Frabu", "Firing up service from app");
        Intent intent = new Intent(getApplicationContext(), HTLCService.class);
        intent.putExtra("path", dir.getAbsolutePath().toString());

        Log.i("Frabu", intent.getStringExtra("path"));
        getApplicationContext().startService(intent);
        getApplicationContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        Log.i("Frabu", "Current dir:" + this.getApplicationContext().getApplicationInfo().dataDir);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch(item.getItemId()) {
           case R.id.action_settings:
               startActivity(new Intent(MainActivity.this, SettingsActivity.class));
               return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void renderSwitchButtons(List<Sensor> sensorList) {

        // Add Toggle buttons for the sensors
        for (int i = 0; i < sensorList.size(); i++) {
            addSwitchButton(sensorList.get(i).getName());
        }

        // Add for Signal Strength
        addSwitchButton(SIGNAL_STRENGTH_LABEL);
        addSwitchButton(GPS_SIGNAL_LABEL);
    }

    class GPSLocationListener implements LocationListener {
        public void onLocationChanged(Location location) {
            lock.lock();
            try {
                EvictingQueue<String> currentQueue = dataContainer.get(GPS_SIGNAL_LABEL);
                if (currentQueue == null) {
                    currentQueue = EvictingQueue.create(MAX_DATA_ENTRIES);
                }
                currentQueue.add(
                    location.getTime() + ": " +
                        location.getAccuracy() + " " +
                        location.getAltitude() + " " +
                        location.getLatitude() + " " +
                        location.getLongitude()
                );
                dataContainer.put(GPS_SIGNAL_LABEL, currentQueue);
            } finally {
                lock.unlock();
            }
            Log.i("Frabu",
                location.getTime() + ": " +
                    location.getAccuracy() + " " +
                    location.getAltitude() + " " +
                    location.getBearing() + " " +
                    location.getLatitude() + " " +
                    location.getLongitude()
            );
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {}

        public void onProviderEnabled(String provider) {}

        public void onProviderDisabled(String provider) {
            Log.i("Frabu", "GPS/Use Wireless network is not enabled");
        }
    }

    class SensorListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            lock.lock();
            try {
                Log.i(
                    "Frabu: ",
                    event.timestamp + ": " +
                    event.accuracy + " " +
                    Arrays.toString(event.values)
                );
                String newEntry = event.timestamp + Arrays.toString(event.values);
                EvictingQueue<String> currentQueue = dataContainer.get(event.sensor.getName());
                if (currentQueue == null) {
                    currentQueue = EvictingQueue.create(MAX_DATA_ENTRIES);
                }
                currentQueue.add(newEntry);
                dataContainer.put(event.sensor.getName(), currentQueue);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Do nothing, it will be reflected in the log anyway
        }
    }

    class SwitchChangeListener implements OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            Log.i("Frabu", "Callback");
            lock.lock();
            try {
                String sensorName = buttonView.getText().toString();
                if (isChecked) { // Register listener
                    mEnabledSensors.add(sensorName);
                    if (mSensorMap.containsKey(sensorName)) { // Dealing with a sensor
                        Log.i("Frabu: ", "Registering " + sensorName);
                        mSensorManager.registerListener(
                            mSensorListener,
                            mSensorMap.get(sensorName),
                            SensorManager.SENSOR_DELAY_NORMAL
                        );
                    } else {
                        if (sensorName.equals(SIGNAL_STRENGTH_LABEL)) {

                        } else if (sensorName.equals(GPS_SIGNAL_LABEL)) {
                            mLocationManager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER,
                                0,
                                0,
                                mLocationListener
                            );
                        }
                    }
                } else { // Unregister listener
                    Log.i("Frabu: ", "Unregistering " + buttonView.getTag());
                    mEnabledSensors.remove(sensorName);
                    if (mSensorMap.containsKey(sensorName)) {
                        mSensorManager.unregisterListener(
                            mSensorListener,
                            mSensorMap.get(sensorName)
                        );
                    } else {
                        if (sensorName.equals(SIGNAL_STRENGTH_LABEL)) {

                        } else if (sensorName.equals(GPS_SIGNAL_LABEL)) {
                            mLocationManager.removeUpdates(mLocationListener);
                        }
                    }
                }
                // Update the enabled sensors to the Hub
                try {
                    api.updateSensors(new ArrayList<String>(mEnabledSensors));
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void addSwitchButton(String text) {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        int screenWidth = size.x;

        LinearLayout ll = (LinearLayout)findViewById(R.id.sensor_linear_layout);
        Switch sb = new Switch(this);
        sb.setText(text);
        LayoutParams params = new LayoutParams(screenWidth, 100);
        sb.setLayoutParams(params);
        sb.setTextSize(10);
        sb.setOnCheckedChangeListener(mSwitchChangeListener);
        sb.setChecked(false);
        ll.addView(sb);
    }
}
