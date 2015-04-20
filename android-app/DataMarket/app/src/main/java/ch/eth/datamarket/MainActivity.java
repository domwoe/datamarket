package ch.eth.datamarket;

import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.LinearLayout.LayoutParams;

import java.util.Arrays;
import java.util.List;

import static android.widget.CompoundButton.*;


public class MainActivity extends ActionBarActivity {

    private static final String SIGNAL_STRENGTH = "SignalStrength";
    private static final String SIGNAL_STRENGTH_LABEL = "Signal Strength GSM";
    private static final String PREFERENCES = "DataMarketPreferences";

    private SensorManager mSensorManager;
    private LocationManager mLocationManager;

    private List<Sensor> mSensorList;
    private SwitchChangeListener mSwitchChangeListener;
    private SensorListener mSensorListener;

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
    }

    class SensorListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            Log.i("Frabu: ",
                    event.timestamp + ": " +
                    event.sensor.getStringType() + " " +
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
            if (isChecked) { // Register listener

                if (buttonView.getTag() instanceof Integer) { // Dealing with a sensor
                    Log.i("Frabu: ", "Registering " + mSensorList.get((Integer)buttonView.getTag()).getStringType());
                    mSensorManager.registerListener(
                            mSensorListener,
                            mSensorList.get((Integer)buttonView.getTag()),
                            SensorManager.SENSOR_DELAY_NORMAL
                    );
                } else { //Signal Strength

                }
            } else { // Unregister listener
                Log.i("Frabu: ", "Unregistering " + buttonView.getTag());
                if (buttonView.getTag() instanceof Integer) {
                    mSensorManager.unregisterListener(
                            mSensorListener,
                            mSensorList.get((Integer) buttonView.getTag())
                    );
                } else {

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
