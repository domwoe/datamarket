package ch.eth.datamarketclean.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Created by frabu on 11.07.2015.
 */
public class HTLCBootBroadcast extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i("Frabu", "Received broadcast!!!");
        //ctx.startService(new Intent(ctx, HTLCService.class));
    }
}
