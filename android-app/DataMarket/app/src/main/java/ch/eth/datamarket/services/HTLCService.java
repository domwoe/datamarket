package ch.eth.datamarket.services;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import org.bitcoinj.channels.htlc.android.HTLCAndroidDriver;

/**
 * Created by frabu on 10.07.2015.
 */
public class HTLCService extends Service {

    private final IBinder htlcBinder = new HTLCBinder();

    @Override
    public void onCreate() {
        Log.i("Frabu", "Service started");
        Toast.makeText(this, "Service created ...", Toast.LENGTH_LONG).show();
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        Toast.makeText(this,"Service destroyed ...", Toast.LENGTH_LONG).show();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        HTLCAndroidDriver driver = new HTLCAndroidDriver();
        Log.i("Frabu", "Firing up Thread");
        new Thread(driver).start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return htlcBinder;
    }

    public class HTLCBinder extends Binder {
        HTLCService getService() {
            return HTLCService.this;
        }
    }

}
