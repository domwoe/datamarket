package ch.eth.datamarketclean;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.Button;

/**
 * Created by frabu on 20.04.2015.
 */
public class FirstStartActivity extends Activity {

    private static final String PREFERENCES = "DataMarketPreferences";
    private StartMainActivityButtonClickListener mButtonListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.first_start_activity);

        if (savedInstanceState == null) {
            mButtonListener = new StartMainActivityButtonClickListener();
            final Button button = (Button) findViewById(R.id.saveAndLaunch);
            button.setOnClickListener(mButtonListener);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    class StartMainActivityButtonClickListener implements View.OnClickListener {
        public void onClick(View v) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean("isFirstRun", false).commit();
            //show start activity
            startActivity(new Intent(FirstStartActivity.this, MainActivity.class));
        }
    }
}
