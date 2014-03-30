package org.thoughtcrime.securesms.websocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.w3c.dom.Text;

public class NetworkReceiver extends BroadcastReceiver {   
  public static final String TAG = "ws.NetworkReceiver";
    
@Override
public void onReceive(Context context, Intent intent) {
	    ConnectivityManager conn =  (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
	    NetworkInfo networkInfo = conn.getActiveNetworkInfo();
	    if(TextSecurePreferences.isGcmRegistered(context) && !TextSecurePreferences.isPushRegistered(context))return;
		if (networkInfo != null && networkInfo.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
	        Log.i(TAG, "connected");
			context.startService(PushService.startIntent(context.getApplicationContext()));
	    } 
	    else if(networkInfo != null){
	    	NetworkInfo.DetailedState state = networkInfo.getDetailedState();
	    	Log.i(TAG, state.name());
	    }
	    else {
	        Log.i(TAG, "lost connection");
	        AlarmManager am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
           PendingIntent operation = PendingIntent.getService(context, 0, PushService.pingIntent(context), PendingIntent.FLAG_NO_CREATE);
	        if(operation != null){
	        	am.cancel(operation);
	        	operation.cancel();
	        }
	        context.startService(PushService.closeIntent(context));
	    }
	}
}
