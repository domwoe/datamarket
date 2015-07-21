package ch.eth.datamarketclean;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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

import java.io.File;
import java.util.Arrays;
import java.util.List;

import ch.eth.datamarketclean.services.HTLCService;

import static android.widget.CompoundButton.OnCheckedChangeListener;


public class MainActivity extends Activity {

    private static final String SIGNAL_STRENGTH = "SignalStrength";
    private static final String SIGNAL_STRENGTH_LABEL = "Signal Strength GSM";
    private static final String GPS_SIGNAL = "GPS";
    private static final String GPS_SIGNAL_LABEL = "GPS Location";

    private static final String PREFERENCES = "DataMarketPreferences";

    private SensorManager mSensorManager;
    private LocationManager mLocationManager;
    private GPSLocationListener mLocationListener;

    private List<Sensor> mSensorList;
    private SwitchChangeListener mSwitchChangeListener;
    private SensorListener mSensorListener;

    private HTLCServiceApi api;

    private HTLCServiceListener.Stub htlcListener = new HTLCServiceListener.Stub() {
        @Override
        public void handleWalletUpdate() throws RemoteException {
            Log.i("Frabu", "Update the VIEW");
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
            mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            mSensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
            mSwitchChangeListener = new SwitchChangeListener();
            mSensorListener = new SensorListener();
            renderSwitchButtons();
        }

        File dir = getApplicationContext().getFilesDir();

        Log.i("Frabu", "Firing up service from app");
        Intent intent = new Intent(getApplicationContext(), HTLCService.class);
        intent.putExtra("path", dir.getAbsolutePath().toString());

        Log.i("Frabu", intent.getStringExtra("path"));
        getApplicationContext().startService(intent);
        getApplicationContext().bindService(intent, serviceConnection, 0);

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

    private void renderSwitchButtons() {

        // Add Toggle buttons for the sensors
        for (int i = 0; i < mSensorList.size(); i++) {
            addSwitchButton(mSensorList.get(i).getName(), i);
        }

        // Add for Signal Strength
        addSwitchButton(SIGNAL_STRENGTH_LABEL, SIGNAL_STRENGTH);
        addSwitchButton(GPS_SIGNAL_LABEL, GPS_SIGNAL);
    }

    class GPSLocationListener implements LocationListener {
        public void onLocationChanged(Location location) {
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
            Log.i("Frabu: ",
                    event.timestamp + ": " +
                            event.accuracy + " " +
                            Arrays.toString(event.values)
            );
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
            Object tag = buttonView.getTag();
            if (isChecked) { // Register listener
                if (tag instanceof Integer) { // Dealing with a sensor
                    Log.i("Frabu: ", "Registering " + mSensorList.get((Integer) buttonView.getTag()));
                    mSensorManager.registerListener(
                            mSensorListener,
                            mSensorList.get((Integer)buttonView.getTag()),
                            SensorManager.SENSOR_DELAY_NORMAL
                    );
                } else {
                    if (tag.equals(SIGNAL_STRENGTH)) {

                    } else if (tag.equals(GPS_SIGNAL)) {
                        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mLocationListener);
                    }
                }
            } else { // Unregister listener
                Log.i("Frabu: ", "Unregistering " + buttonView.getTag());
                if (buttonView.getTag() instanceof Integer) {
                    mSensorManager.unregisterListener(
                            mSensorListener,
                            mSensorList.get((Integer) buttonView.getTag())
                    );
                } else {
                    if (tag.equals(SIGNAL_STRENGTH)) {

                    } else if (tag.equals(GPS_SIGNAL)) {
                        mLocationManager.removeUpdates(mLocationListener);
                    }
                }
            }
        }
    }

    private void addSwitchButton(String text, Object tag) {
        LinearLayout ll = (LinearLayout)findViewById(R.id.main_layout);
        Switch sb = new Switch(this);
        sb.setText(text);
        LayoutParams params = new LayoutParams(1000, 150);
        params.setMargins(35, 0, 0, 0);
        sb.setLayoutParams(params);
        sb.setTextSize(10);
        sb.setTag(tag);
        sb.setOnCheckedChangeListener(mSwitchChangeListener);
        sb.setChecked(false);
        ll.addView(sb);
    }

}
