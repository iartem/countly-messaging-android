package ly.count.android.api;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public class CountlyActivity extends Activity {
    public static final String TAG = "CountlyActivity";

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

    /** You should use cloud.count.ly instead of YOUR_SERVER for the line below if you are using Countly Cloud service */
        Countly.sharedInstance().init(this, "http://162.243.29.190", "720e1d07b5dd7263a0a7a42b96c159bb9ca85e43", "640228892478");
//        Countly.sharedInstance().init(this, "http://192.168.56.1:3001", "60758257b5a8595a96648296f4e04c4f923e4f6f", "640228892478");

        /** Register for broadcast action if you need to be notified when Countly message received */
        IntentFilter filter = new IntentFilter();
        filter.addAction(CountlyMessaging.getBroadcastAction());
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Got a broadcast");
            }
        }, filter);

    }
    
    @Override
    public void onStart()
    {
    	super.onStart();
        Countly.sharedInstance().onStart();
    }

    @Override
    public void onStop()
    {
        Countly.sharedInstance().onStop();
    	super.onStop();
    }
}
