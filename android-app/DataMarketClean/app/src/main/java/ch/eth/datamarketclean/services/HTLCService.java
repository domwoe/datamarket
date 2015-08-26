package ch.eth.datamarketclean.services;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.bitcoinj.channels.htlc.TransactionBroadcastScheduler;
import org.bitcoinj.channels.htlc.android.HTLCAndroidClientConnection;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.utils.Threading;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import ch.eth.datamarketclean.HTLCServiceApi;
import ch.eth.datamarketclean.HTLCServiceListener;

/**
 * Created by frabu on 10.07.2015.
 */
public class HTLCService extends Service {

    private static final NetworkParameters PARAMS = RegTestParams.get();
    private static final Integer NETWORK_TIMEOUT = 6000;
    private WalletAppKit appKit;
    private HTLCAndroidClientConnection htlcClient;

    private List<HTLCServiceListener> listeners = new ArrayList<>();

    private HTLCServiceApi.Stub apiEndpoint = new HTLCServiceApi.Stub() {

        @Override
        public void updateSensors(List<String> sensors) throws RemoteException {
            Log.i("Frabu", "Updating sensors to central hub");
            // Manually add prices for now
            List<Long> prices = new ArrayList<Long>();
            for (int i = 0; i < sensors.size(); i++) {
                prices.add(1L);
            }
            htlcClient.updateSensors(sensors, prices);
        }

        @Override
        public void addListener(HTLCServiceListener listener) throws RemoteException {
            synchronized (listeners) {
                Log.i("Frabu", "Added new listener to remote service");
                listeners.add(listener);
            }
        }

        @Override
        public void removeListener(HTLCServiceListener listener) throws RemoteException {
            synchronized (listeners) {
                Log.i("Frabu", "Removed listener from service");
                listeners.remove(listener);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("Frabu", "Successfully created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "Service started ...", Toast.LENGTH_LONG).show();
        String path = intent.getStringExtra("path");
        Log.i("Frabu", "INTENT: " + path);


        appKit = new WalletAppKit(PARAMS, new File(path), "htlc_client");
        try {
            appKit.setPeerNodes(
                    new PeerAddress(
                            InetAddress.getByName("192.168.0.102"),
                            PARAMS.getPort()
                    )
            );
        } catch (UnknownHostException e1) {
            e1.printStackTrace();
        }
        appKit.startAsync();
        appKit.awaitRunning();

        System.out.println(appKit.wallet());

        appKit.wallet().allowSpendingUnconfirmedTransactions();
        if (appKit.wallet().getImportedKeys().size() == 0) {
            // Import new keys
            appKit.wallet().importKey(new ECKey());
        }

        ECKey key = appKit.wallet().getImportedKeys().get(0);

        Log.i("Frabu", key.toAddress(PARAMS).toString());

        final InetSocketAddress server =
                new InetSocketAddress("192.168.0.102", 4242);

        Coin minPayment = Coin.valueOf(0, 1);

        TransactionBroadcastScheduler broadcastScheduler =
            new TransactionBroadcastScheduler(appKit.peerGroup());


        htlcClient = new HTLCAndroidClientConnection(
            server,
            NETWORK_TIMEOUT,
            appKit.wallet(),
            new TransactionBroadcastScheduler(appKit.peerGroup()),
            key,
            minPayment
        );
        htlcClient.start();


        Futures.addCallback(
            htlcClient.getChannelOpenFuture(),
            new FutureCallback<HTLCAndroidClientConnection>() {
                @Override
                public void onSuccess(
                        final HTLCAndroidClientConnection client
                ) {
                    Log.i("Frabu", "Channel open! We can now register the device");
                    try {
                        synchronized(listeners) {
                            for (HTLCServiceListener listener : listeners) {
                                listener.channelEstablished();
                            }
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onFailure(Throwable throwable) {
                    Log.e("Frabu", throwable.getLocalizedMessage());
                }
        }, Threading.USER_THREAD);

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i("Frabu", "Called to BIND");
   //     if (HTLCService.class.getName().equals(intent.getAction())) {
            Log.i("Frabu", "Bound by intent " + intent);
        return apiEndpoint;
     //   } else {
       //     Log.e("Frabu", "Something went wrong when binding");
        //    return null;
       // }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i("Frabu", "Service destroying.");
    }
}
