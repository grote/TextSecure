package org.thoughtcrime.securesms.websocket;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import com.codebutler.android_websockets.WebSocketClient;
import com.codebutler.android_websockets.WebSocketClient.Listener;
import com.google.thoughtcrimegson.Gson;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import org.thoughtcrime.securesms.Release;
import org.thoughtcrime.securesms.service.SendReceiveService;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.w3c.dom.Text;
import org.whispersystems.textsecure.crypto.InvalidVersionException;
import org.whispersystems.textsecure.directory.Directory;
import org.whispersystems.textsecure.directory.NotInDirectoryException;
import org.whispersystems.textsecure.push.ContactTokenDetails;
import org.whispersystems.textsecure.push.IncomingEncryptedPushMessage;
import org.whispersystems.textsecure.push.IncomingPushMessage;
import org.whispersystems.textsecure.util.Util;

public class PushService extends Service implements Listener {
  public static final String TAG = "WS_PushService";

	public static final String  ACTION_PING        = "WS_PING";
	public static final String  ACTION_CONNECT     = "WS_CONNECT";
	public static final String  ACTION_DISCONNECT  = "WS_DISCONNECT";
    private static final String ACTION_ACKNOWLEDGE = "WS_ACKNOWLEDGE" ;

    private WebSocketClient mClient;
	private final IBinder mBinder = new Binder();
	private boolean mShutDown = false;
	private Context context;
	public static Intent startIntent(Context context){
        context = context;
		Intent i = new Intent(context, PushService.class);
		i.setAction(ACTION_CONNECT);
		return i;
	}

	public static Intent pingIntent(Context context){
		Intent i = new Intent(context, PushService.class);
		i.setAction(ACTION_PING);
		return i;
	}
	
	public static Intent closeIntent(Context context){
		Intent i = new Intent(context, PushService.class);
		i.setAction(ACTION_DISCONNECT);
		return i;
	}

    public static Intent ackIntent(Context context, WebsocketMessage message){
        Intent i = new Intent(context, PushService.class);
        i.setAction(ACTION_ACKNOWLEDGE);
        i.putExtra("ack", message.toJSON());
        return i;
    }

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "Creating Service " + this.toString());
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(TAG, "Destroying Service " + this.toString());
		if(mClient != null && mClient.isConnected()) mClient.disconnect();
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		WakeLock wakelock = ((PowerManager)getSystemService(POWER_SERVICE))
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
		wakelock.acquire();
		Log.i(TAG, "PushService start command");
		if(intent != null) Log.i(TAG, intent.toUri(0));
		mShutDown = false;

        if(!TextSecurePreferences.isPushRegistered(getApplicationContext()) || TextSecurePreferences.isGcmRegistered(getApplicationContext())) {
            Log.i(TAG, "PushService not registered");
            stopSelf();
            wakelock.release();
            return START_NOT_STICKY;
        }

        if(intent == null || ACTION_CONNECT == intent.getAction()){
            ConnectivityManager conn =  (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = conn.getActiveNetworkInfo();
            if(networkInfo != null){
                NetworkInfo.DetailedState state = networkInfo.getDetailedState();
                if(state != null) Log.w(TAG, "NetworkInfo: "+state.name());
                if(networkInfo.getDetailedState() != NetworkInfo.DetailedState.CONNECTED){
                    Log.w(TAG, "Not connected, reset");
                    wakelock.release();
                    return START_NOT_STICKY;
                }
            }else{
                Log.w(TAG, "No ActiveNetwork, reset");
                wakelock.release();
                return START_NOT_STICKY;
            }
            AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            PendingIntent operation = PendingIntent.getService(this, 0, PushService.pingIntent(this), PendingIntent.FLAG_NO_CREATE);
            if(operation == null){
                Log.d(TAG, "Setup timer");
                operation = PendingIntent.getService(this, 0, PushService.pingIntent(this), PendingIntent.FLAG_UPDATE_CURRENT);
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), 50*1000, operation);
            }
        }

        if(mClient == null) {
			WakeLock clientlock = ((PowerManager)getSystemService(POWER_SERVICE))
                                   .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
			mClient = new WebSocketClient(URI.create(Release.WS_URL+"?user="
                            +TextSecurePreferences.getLocalNumber(getApplication())+
                            "&password="+TextSecurePreferences.getPushServerPassword(
                            getApplication())), this, null, clientlock);
		}
		if(intent != null) {
            if(ACTION_DISCONNECT.equals(intent.getAction())) {
                mShutDown = true;
                if (mClient.isConnected()) mClient.disconnect();
            }else if(!mClient.isConnected()) mClient.connect();
            if(ACTION_PING.equals(intent.getAction())){
                if(mClient.isConnected()) mClient.send("{\"type\":2}"); //TODO FIX this with gson
            } else if(ACTION_ACKNOWLEDGE.equals(intent.getAction())){
                if(mClient.isConnected()) mClient.send("{\"type\":1, \"id\":"+WebsocketMessage.fromJson(intent.getStringExtra("ack")).getId()+"}"); //TODO Build this JSON properly
            }
        }
		
		wakelock.release();
		return START_STICKY;
	}

	public class Binder extends android.os.Binder{
		
		PushService getService(){
			return PushService.this;
		}
	}
	
	public synchronized boolean isConnected() {
		return mClient != null && mClient.isConnected();
	}
	
	
	@Override
	public void onConnect() {
		Log.d(TAG, "Connected to websocket");
	}

	@Override
	public synchronized void onDisconnect(int code, String reason) {
		Log.d(TAG, String.format("Disconnected! Code: %d Reason: %s", code, reason));
		if(!mShutDown){
			startService(startIntent(this));
		}
		else{
			stopSelf();
		}
	}

	@Override
	public synchronized void onError(Exception e) {
		Log.e(TAG, "PushService", e);
		startService(startIntent(this));
	}

	@Override
	public synchronized void onMessage(String data) {
		WakeLock wakelock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
		wakelock.acquire();
        if(context == null)
            context = getApplicationContext();
        try{
            if (Util.isEmpty(data))
                return;
            if (!TextSecurePreferences.isPushRegistered(context)) {
                Log.w(TAG, "Not push registered!");
                return;
            }
            if(data.contains("type")){
                return;
            }
            WebsocketMessage websocketMessage = WebsocketMessage.fromJson(data);

            //TODO Check if type exists (PONG message)
            String                       sessionKey       = TextSecurePreferences.getSignalingKey(context);
            IncomingEncryptedPushMessage encryptedMessage = new IncomingEncryptedPushMessage(websocketMessage.getMessage(), sessionKey);
            IncomingPushMessage          message          = encryptedMessage.getIncomingPushMessage();

        if (!isActiveNumber(context, message.getSource())) {
            Directory directory                     = Directory.getInstance(context);
            ContactTokenDetails contactTokenDetails = new ContactTokenDetails();
            contactTokenDetails.setNumber(message.getSource());

            directory.setNumber(contactTokenDetails, true);
        }

        context.startService(ackIntent(context, websocketMessage));
        Intent service = new Intent(context, SendReceiveService.class);
        service.setAction(SendReceiveService.RECEIVE_PUSH_ACTION);
        service.putExtra("message", message);
        context.startService(service);
    } catch (IOException e) {
        Log.w(TAG, e);
    } catch (InvalidVersionException e) {
        Log.w(TAG, e);
    }
		wakelock.release();
	}

	@Override
	public synchronized void onMessage(byte[] arg0) {
		// TODO Auto-generated method stub
		
	}

    private boolean isActiveNumber(Context context, String e164number) {
        boolean isActiveNumber;

        try {
            isActiveNumber = Directory.getInstance(context).isActiveNumber(e164number);
        } catch (NotInDirectoryException e) {
            isActiveNumber = false;
        }

        return isActiveNumber;
    }
}

