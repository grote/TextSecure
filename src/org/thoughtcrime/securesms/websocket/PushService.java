package org.thoughtcrime.securesms.websocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import com.codebutler.android_websockets.WebSocketClient;
import com.codebutler.android_websockets.WebSocketClient.Listener;

import org.thoughtcrime.securesms.Release;
import org.thoughtcrime.securesms.service.SendReceiveService;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.textsecure.crypto.InvalidVersionException;
import org.whispersystems.textsecure.directory.Directory;
import org.whispersystems.textsecure.directory.NotInDirectoryException;
import org.whispersystems.textsecure.push.ContactTokenDetails;
import org.whispersystems.textsecure.push.IncomingEncryptedPushMessage;
import org.whispersystems.textsecure.push.IncomingPushMessage;
import org.whispersystems.textsecure.util.Util;

import java.io.IOException;
import java.net.URI;

public class PushService extends Service implements Listener {
    public static final String TAG = "WebSocket.PushService";

    public static final String ACTION_PING = "WS_PING";
    public static final String ACTION_CONNECT = "WS_CONNECT";
    public static final String ACTION_DISCONNECT = "WS_DISCONNECT";
    private static final String ACTION_ACKNOWLEDGE = "WS_ACKNOWLEDGE";
    private static final int TIMEOUT = 1;
    private static final int MILLIS = 1000;
    private static final int ERROR_LIMIT = 11; // 2^10 * 1000ms * 1 = 1024s ~= 17min

    private WebSocketClient mClient;
    private final IBinder mBinder = new Binder();
    private boolean mShutDown = false;
    private  WakeLock wakelock, onMessageWakeLock;
    private int errors = 0;

    public static Intent startIntent(Context context) {
        Intent i = new Intent(context, PushService.class);
        i.setAction(ACTION_CONNECT);
        return i;
    }

    public static Intent pingIntent(Context context) {
        Intent i = new Intent(context, PushService.class);
        i.setAction(ACTION_PING);
        return i;
    }

    public static Intent closeIntent(Context context) {
        Intent i = new Intent(context, PushService.class);
        i.setAction(ACTION_DISCONNECT);
        return i;
    }

    public static Intent ackIntent(Context context, WebsocketMessage message) {
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
        if (mClient != null && mClient.isConnected()) mClient.disconnect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "WakeLock b4acquire: "+wakelock);
        if(wakelock != null && !wakelock.isHeld())
            wakelock.acquire();
        else if(wakelock != null){
            Log.d(TAG, "Wakelock still held at onStartcommand!");
        }
        else {
            wakelock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            wakelock.acquire();
        }
        Log.d(TAG, "WakeLock acquire: "+wakelock.toString());
        Log.d(TAG, "PushService start command: " + ((intent == null) ? "null" : intent.toUri(0)));
        mShutDown = false;

        if (!TextSecurePreferences.isPushRegistered(getApplicationContext()) || TextSecurePreferences.isGcmRegistered(getApplicationContext())) {
            Log.i(TAG, "PushService not registered");
            wakelock.release();
            Log.d(TAG, "WakeLock release-nr: " + wakelock.toString());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null || ACTION_CONNECT == intent.getAction() || Util.isEmpty(intent.getAction())) {
            ConnectivityManager conn = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = conn.getActiveNetworkInfo();
            if (networkInfo != null) {
                NetworkInfo.DetailedState state = networkInfo.getDetailedState();
                if (state != null) Log.w(TAG, "NetworkInfo: " + state.name());
                else Log.w(TAG, "NetworkState: " + state);
                if (networkInfo.getDetailedState() != NetworkInfo.DetailedState.CONNECTED) {
                    Log.w(TAG, "Not connected, reset");
                    wakelock.release();
                    Log.d(TAG, "WakeLock release-nc: " + wakelock.toString());
                    stopSelf();
                    return START_NOT_STICKY;
                }
            } else {
                Log.w(TAG, "No ActiveNetwork, reset");
                wakelock.release();
                Log.d(TAG, "WakeLock release-no_ac: " + wakelock.toString());
                stopSelf();
                return START_NOT_STICKY;
            }
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            PendingIntent operation = PendingIntent.getService(this, 0, PushService.pingIntent(this), PendingIntent.FLAG_NO_CREATE);
            if (operation == null) {
                Log.d(TAG, "Setup timer");
                operation = PendingIntent.getService(this, 0, PushService.pingIntent(this), PendingIntent.FLAG_UPDATE_CURRENT);
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), 30 * 1000, operation);
            }
        }

        if (mClient == null) {
            WakeLock clientlock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG+".Client");
            mClient = new WebSocketClient(URI.create(Release.WS_URL + "?login="
                    + TextSecurePreferences.getLocalNumber(this) +
                    "&password=" + TextSecurePreferences.getPushServerPassword(
                    this)), this, null, clientlock);
            Log.w(TAG, "mClient created");
        }
        if (intent != null) {
            if (ACTION_DISCONNECT.equals(intent.getAction())) {
                mShutDown = true;
                if (mClient.isConnected()) mClient.disconnect();
            } else if (!mClient.isConnected()) {
                mClient.connect();
                Log.w(TAG, "Connect Client");
            }
            if (ACTION_PING.equals(intent.getAction())) {
                if (mClient.isConnected()) mClient.send("{\"type\":2}"); //TODO FIX this with gson
                else {
                    Log.w(TAG, "Ping failed, client not connected");
                }
            } else if (ACTION_ACKNOWLEDGE.equals(intent.getAction())) {
                if (mClient.isConnected()) {
                    String ackMessage= "{\"type\":1, \"id\":" + WebsocketMessage.fromJson(intent.getStringExtra("ack")).getId() + "}";
                    mClient.send(ackMessage); //TODO Build this JSON properly
                    Log.d(TAG, "Acknowledge message: "+ackMessage);
                }
            }
        }

        wakelock.release();
        Log.d(TAG, "WakeLock release: "+wakelock.toString());
        return START_STICKY;
    }

    public class Binder extends android.os.Binder {

        PushService getService() {
            return PushService.this;
        }
    }

    public synchronized boolean isConnected() {
        return mClient != null && mClient.isConnected();
    }


    @Override
    public void onConnect() {
       errors = 0;
       Log.d(TAG, "Connected to websocket");
    }

    @Override
    public synchronized void onDisconnect(int code, String reason) {
        Log.d(TAG, String.format("Disconnected! Code: %d Reason: %s", code, reason));
        if (!mShutDown) {
            startService(startIntent(this));
        } else {
            stopSelf();
        }
    }

    @Override
    public synchronized void onError(Exception e) {
        if (errors < ERROR_LIMIT){
            errors++;
        }
        int backoff = (1 << (errors - 1)); //Use bit-shifting for exponential calculation

        Log.e(TAG, "Websocket error; Restart in "+(backoff*TIMEOUT)+" seconds", e);

        PendingIntent operation = PendingIntent.getService(this, 0, PushService.pingIntent(this), PendingIntent.FLAG_UPDATE_CURRENT);
        if (operation != null) {
            operation.cancel();
        }
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PendingIntent startUp = PendingIntent.getService(this, 0, PushService.startIntent(this), PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.RTC_WAKEUP, backoff * TIMEOUT * MILLIS ,startUp);
    }

    @Override
    public synchronized void onMessage(String data) {
        Log.d(TAG, "onMessageWakeLock b4acquire: " + onMessageWakeLock);
        if(onMessageWakeLock != null && !onMessageWakeLock.isHeld())
            onMessageWakeLock.acquire();
        else if(onMessageWakeLock != null){
            Log.d(TAG, "onMessageWakeLock still held at onStartcommand!");
        }
        else {
            onMessageWakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG+".onMessage");
            onMessageWakeLock.acquire();
        }
        Log.d(TAG, "onMessageWakeLock acquire: " + onMessageWakeLock.toString());
        try {
            Log.w(TAG, "onMessage: " + data);
            if (Util.isEmpty(data)) {
                return;
            }
            if (!TextSecurePreferences.isPushRegistered(this)) {
                Log.w(TAG, "Not push registered!");
                return;
            }
            if (data.contains("type")) {
                return;
            }
            Log.d(TAG, "REACHED");
            WebsocketMessage websocketMessage = WebsocketMessage.fromJson(data);

            Log.d(TAG, "StartService: ackIntent; "+startService(ackIntent(this, websocketMessage))); //TODO This acks the message prior to reading => could mean that messages with an error are never read?

            //TODO Check if type exists (PONG message)
            String sessionKey = TextSecurePreferences.getSignalingKey(this);
            IncomingEncryptedPushMessage encryptedMessage = new IncomingEncryptedPushMessage(websocketMessage.getMessage(), sessionKey);
            IncomingPushMessage message = encryptedMessage.getIncomingPushMessage();

            if (!isActiveNumber(this, message.getSource())) {
                Directory directory = Directory.getInstance(this);
                ContactTokenDetails contactTokenDetails = new ContactTokenDetails();
                contactTokenDetails.setNumber(message.getSource());

                directory.setNumber(contactTokenDetails, true);
            }

            Intent service = new Intent(this, SendReceiveService.class);
            service.setAction(SendReceiveService.RECEIVE_PUSH_ACTION);
            service.putExtra("message", message);
            Log.d(TAG, "StartService: message; " + startService(service));
        } catch (IOException e) {
            Log.w(TAG, e);
        } catch (InvalidVersionException e) {
            Log.w(TAG, e);
        }catch (Exception e) {
            Log.w(TAG, e);
        }finally {
            if(onMessageWakeLock != null && onMessageWakeLock.isHeld()) {
                onMessageWakeLock.release();
                Log.d(TAG, "onMessageWakeLock release: "+onMessageWakeLock.toString());
            }else {
                Log.d(TAG, "onMessageWakeLock not held!" );
            }
        }
        Log.d(TAG, "EOF onMessageWakeLock: "+onMessageWakeLock.toString());
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

