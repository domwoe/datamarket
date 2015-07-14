package ch.eth.datamarket.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by frabu on 11.07.2015.
 */
public class HTLCBootBroadcast extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        ctx.startService(new Intent(ctx, HTLCService.class));
    }
}
