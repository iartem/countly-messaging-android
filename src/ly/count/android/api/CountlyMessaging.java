package ly.count.android.api;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import java.io.IOException;
import java.util.List;

/**
 * Countly Messaging
 *
 * NOTE! Countly does default processing of received messages. That is Notification if app is in background and
 * AlertDialog if it's running. To disable this behaviour, you can
 */
public class CountlyMessaging extends WakefulBroadcastReceiver {
    private static final String TAG = "CountlyMessaging";

    private static final String NOTIFICATION_SHOW_DIALOG = "ly.count.messaging.dialog";
    private static final String EXTRA_MESSAGE = "ly.count.messaging.message";

    private static final int NOTIFICATION_TYPE_UNKNOWN  = 0;
    private static final int NOTIFICATION_TYPE_MESSAGE  = 1;
    private static final int NOTIFICATION_TYPE_URL      = 1 << 1;
    private static final int NOTIFICATION_TYPE_REVIEW   = 1 << 2;

    private static final int NOTIFICATION_TYPE_SILENT           = 1 << 3;

    private static final int NOTIFICATION_TYPE_SOUND_DEFAULT    = 1 << 4;
    private static final int NOTIFICATION_TYPE_SOUND_URI        = 1 << 5;

    /**
     * Countly Messaging service message representation.
     */
    public static class Message implements Parcelable{
        private Bundle data;
        private int type;

        public Message(Bundle data) {
            this.data = data;
            this.type = setType();
        }

        public String getId() { return data.getString("c.i"); }
        public String getLink() { return data.getString("c.l"); }
        public String getReview() { return data.getString("c.r"); }
        public String getMessage() { return data.getString("message"); }
        public String getSoundUri() { return data.getString("sound"); }
        public Bundle getData() { return data; }
        public int getType() { return type; }

        /**
         * Depending on message contents, it can represent different types of actions.
         * @return message type according to message contents.
         */
        private int setType() {
            int t = NOTIFICATION_TYPE_UNKNOWN;

            if (getMessage() != null && !"".equals(getMessage())) {
                t |= NOTIFICATION_TYPE_MESSAGE;
            }

            if (getLink() != null && !"".equals(getLink())) {
                t |= NOTIFICATION_TYPE_URL;
            }

            if (getReview() != null && !"".equals(getReview())) {
                t |= NOTIFICATION_TYPE_REVIEW;
            }

            if ("true".equals(data.getString("c.s"))) {
                t |= NOTIFICATION_TYPE_SILENT;
            }

            if (getSoundUri() != null && !"".equals(getSoundUri())) {
                if ("default".equals(getSoundUri())) t |= NOTIFICATION_TYPE_SOUND_DEFAULT;
                else t |= NOTIFICATION_TYPE_SOUND_URI;
            }

            return t;
        }

        public boolean hasLink() { return (type & NOTIFICATION_TYPE_URL) > 0; }
        public boolean hasReview() { return (type & NOTIFICATION_TYPE_REVIEW) > 0; }
        public boolean hasMessage() { return (type & NOTIFICATION_TYPE_MESSAGE) > 0; }
        public boolean isSilent() { return (type & NOTIFICATION_TYPE_SILENT) > 0; }
        public boolean hasSoundUri() { return (type & NOTIFICATION_TYPE_SOUND_URI) > 0; }
        public boolean hasSoundDefault() { return (type & NOTIFICATION_TYPE_SOUND_DEFAULT) > 0; }
        public boolean isUnknown() { return type == NOTIFICATION_TYPE_UNKNOWN; }

        /**
         * Message is considered valid only when it has Countly ID and its type is determined
         * @return whether this message is valid or not
         */
        public boolean isValid() {
            String id = data.getString("c.i");
            return !isUnknown() && id != null && id.length() == 24;
        }

        /**
         * Depending on message contents, different intents can be run.
         * @return Intent
         */
        public Intent getIntent() {
            if (hasLink()) {
                return new Intent(Intent.ACTION_VIEW, Uri.parse(getLink()));
            } else if (hasReview()) {
                return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getContext().getPackageName()));
            } else if (hasMessage()) {
                Intent intent = new Intent(getContext(), getActivityClass());
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                return intent;
            }
            return null;
        }

        /**
         * @return Title for Notification or AlertDialog
         */
        public String getNotificationTitle() {
            return getContext().getString(R.string.app_name);
        }

        /**
         * @return Message for Notification or AlertDialog
         */
        public String getNotificationMessage() {
            if (hasLink()) {
                return hasMessage() ? getMessage() : getContext().getString(R.string.countly_messaging_open_link);
            } else if (hasReview()) {
                return hasMessage() ? getMessage() : getContext().getString(R.string.countly_messaging_leave_review);
            } else if (hasMessage()) {
                return getMessage();
            }
            return null;
        }

        @Override
        public String toString() {
            return data == null ? "empty" : data.toString();
        }

        @Override
        public int describeContents () {
            return 0;
        }

        @Override
        public void writeToParcel (Parcel dest, int flags) {
            dest.writeBundle(data);
        }
        public static final Parcelable.Creator<Message> CREATOR = new Parcelable.Creator<Message>() {
            public Message createFromParcel(Parcel in) {
                return new Message(in);
            }

            public Message[] newArray(int size) {
                return new Message[size];
            }
        };

        private Message(Parcel in) {
            data = in.readBundle();
            type = setType();
        }
    }

    /**
     * Action for Countly Messaging BroadcastReceiver.
     * Once message is arrived, Countly Messaging will send a notification with action "APP_PACKAGE_NAME.countly.messaging"
     * to which you can subscribe via BroadcastReceiver. Note, that
     */
    public static String BROADCAST_RECEIVER_ACTION_MESSAGE = "ly.count.messaging.broadcast.message";
    public static String getBroadcastAction () {
        try {
            ComponentName name = new ComponentName(getContext(), CountlyMessagingService.class);
            Bundle data = getContext().getPackageManager().getServiceInfo(name, PackageManager.GET_META_DATA).metaData;
            return data.getString("broadcast_action");
        } catch (PackageManager.NameNotFoundException ignored) {
            Log.w(TAG, "Set broadcast_action metadata for .CountlyMessaging$CountlyMessagingService in AndroidManifest.xml to receive broadcasts about received messages.");
            return null;
        }
    }


    /**
     * Activity used for messages displaying.
     * When message arrives, Countly displays it either as a Notification, or as a AlertDialog. In any case,
     * this activity is used as a final destination.
     * @return activity
     */
//    private static Activity _activity;
    private static Context _context;
    private static Class<? extends Activity> _activityClass;

    public static void setActivity(Activity activity, Class<? extends Activity> activityClass) {
        _context = activity.getApplicationContext();
        _activityClass = activityClass == null ? activity.getClass() : activityClass;
    }
    private static Context getContext() { return _context; }
    private static Class<? extends Activity> getActivityClass() { return _activityClass; }

    @Override
    public void onReceive (Context context, Intent intent) {
        Log.i(TAG, "Starting service @ " + SystemClock.elapsedRealtime());

        ComponentName comp = new ComponentName(context.getPackageName(), CountlyMessagingService.class.getName());
        startWakefulService(context, intent.setComponent(comp));
        setResultCode(Activity.RESULT_OK);
    }


    private static final String PROPERTY_REGISTRATION_ID = "ly.count.messaging.registration.id";
    private static final String PROPERTY_REGISTRATION_VERSION = "ly.count.messaging.version";
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private static GoogleCloudMessaging gcm;


    public static void initMessaging(Activity activity, Class<? extends Activity> activityClass, String sender) {
        setActivity(activity, activityClass);

        if (gcm != null) {
            return;
        }

        if (checkPlayServices(activity) ) {
            gcm = GoogleCloudMessaging.getInstance(activity);
            String registrationId = getRegistrationId(activity);
            if (registrationId.isEmpty()) {
                registerInBackground(activity.getApplicationContext(), sender);
            } else {
                Countly.sharedInstance().onRegistrationId(registrationId);
            }
        } else {
            Log.i(TAG, "No valid Google Play Services APK found.");
        }
    }

    private static void registerInBackground(final Context context, final String sender) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    String registrationId = gcm.register(sender);
                    Countly.sharedInstance().onRegistrationId(registrationId);
                    storeRegistrationId(context, registrationId);
                } catch (IOException ex) {
                    Log.e(TAG, "Failed to register for GCM identificator: " + ex.getMessage());
                }
                return null;
            }
        }.execute(null, null, null);
    }

    private static void storeRegistrationId(Context context, String regId) {
        int appVersion = getAppVersion(context);
        Log.i(TAG, "Saving regId " + regId + " for app version " + appVersion);
        getGCMPreferences(context).edit().putString(PROPERTY_REGISTRATION_ID, regId).putInt(PROPERTY_REGISTRATION_VERSION, appVersion).commit();
    }


    private static SharedPreferences getGCMPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(context.getApplicationContext().getClass().getSimpleName(), Context.MODE_PRIVATE);
    }

    private static boolean checkPlayServices(Activity activity) {
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(activity);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                GooglePlayServicesUtil.getErrorDialog(resultCode, activity, PLAY_SERVICES_RESOLUTION_REQUEST).show();
            } else {
                Log.i(TAG, "This device is not supported.");
            }
            return false;
        }
        return true;
    }

    private static String getRegistrationId(Activity activity) {
        final SharedPreferences preferences = getGCMPreferences(activity);
        String registrationId = preferences.getString(PROPERTY_REGISTRATION_ID, "");

        if (registrationId.isEmpty()) {
            Log.i(TAG, "Registration not found.");
            return "";
        }

        int registeredVersion = preferences.getInt(PROPERTY_REGISTRATION_VERSION, Integer.MIN_VALUE);
        int currentVersion = getAppVersion(activity.getApplicationContext());
        if (registeredVersion != currentVersion) {
            Log.i(TAG, "App version changed.");
            return "";
        }

        return registrationId;
    }

    private static int getAppVersion(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // should never happen
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    private static boolean isAppInForeground (Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> appProcesses = activityManager.getRunningAppProcesses();
        if (appProcesses == null) {
            return false;
        }
        final String packageName = context.getPackageName();
        for (ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && appProcess.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public static class CountlyMessagingService extends IntentService {
        public static final String TAG = "CountlyMessagingService";

        public CountlyMessagingService () {
            super(TAG);
        }

        @Override
        protected void onHandleIntent (Intent intent) {
            Bundle extras = intent.getExtras();

            GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(this);
            String messageType = gcm.getMessageType(intent);

            if (!extras.isEmpty()) {
                if (GoogleCloudMessaging.MESSAGE_TYPE_MESSAGE.equals(messageType)) {
                    final Message msg = new Message(extras);

                    if (msg.isValid()) {
                        Log.i(TAG, "Got a message from Countly Messaging: " + msg);

                        // Send broadcast
                        Intent broadcast = new Intent(getBroadcastAction());
                        broadcast.putExtra(BROADCAST_RECEIVER_ACTION_MESSAGE, msg);
                        getContext().sendBroadcast(broadcast);

                        // Show message if not silent
                        if (!msg.isSilent()) {
                            // Go through proxy activity to be able to record message open & action performed events
                            Intent proxy = new Intent(getContext(), ProxyActivity.class);
                            proxy.putExtra(EXTRA_MESSAGE, msg);
                            notify(proxy);
                        }

                    }
                }
            }

            CountlyMessaging.completeWakefulIntent(intent);
        }

        protected void notify(Intent proxy) {
            Message msg = proxy.getParcelableExtra(EXTRA_MESSAGE);

            if (isAppInForeground(this)) {
                // Go with dialog
                proxy.putExtra(NOTIFICATION_SHOW_DIALOG, true);
                proxy.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(proxy);
            } else {
                // Notification case
                Countly.sharedInstance().recordMessageOpen(msg.getId());

                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                PendingIntent contentIntent = PendingIntent.getActivity(getContext(), 0, proxy, 0);

                // Get icon from application or use default one
                int icon;
                try {
                    icon = getPackageManager().getApplicationInfo(getPackageName(), 0).icon;
                } catch (PackageManager.NameNotFoundException e) {
                    icon = R.drawable.ic_launcher;
                }

                NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                        .setAutoCancel(true)
                        .setSmallIcon(icon)
                        .setTicker(msg.getNotificationMessage())
                        .setContentTitle(msg.getNotificationTitle())
                        .setContentText(msg.getNotificationMessage())
                        .setContentIntent(contentIntent);

                if (msg.hasSoundDefault()) {
                    builder.setDefaults(Notification.DEFAULT_SOUND);
                } else if (msg.hasSoundUri()) {
                    builder.setSound(Uri.parse(msg.getSoundUri()));
                }

                manager.notify(1, builder.build());
            }
        }
    }

    public static class ProxyActivity extends Activity{

        @Override
        protected void onCreate (Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        protected void onNewIntent (Intent intent) {
            super.onNewIntent(intent);
        }

        @Override
        protected void onStart () {
            super.onStart();

            Bundle extras = getIntent().getExtras();
            final Message msg = extras.getParcelable(EXTRA_MESSAGE);

            if (msg != null) {
                if (extras.containsKey(NOTIFICATION_SHOW_DIALOG)) {
                    Countly.sharedInstance().recordMessageOpen(msg.getId());

                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(msg.getNotificationTitle())
                            .setMessage(msg.getNotificationMessage());

                    if (msg.hasLink()){
                        builder.setCancelable(true)
                                .setPositiveButton(getString(R.string.countly_messaging_open_link), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick (DialogInterface dialog, int which) {
                                        Countly.sharedInstance().recordMessageAction(msg.getId());
                                        finish();
                                        startActivity(msg.getIntent());
                                    }
                                })
                                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                    @Override
                                    public void onCancel (DialogInterface dialog) {
                                        finish();
                                    }
                                });
                    } else if (msg.hasReview()){
                        builder.setCancelable(true)
                                .setPositiveButton(getString(R.string.countly_messaging_leave_review), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick (DialogInterface dialog, int which) {
                                        Countly.sharedInstance().recordMessageAction(msg.getId());
                                        finish();
                                        startActivity(msg.getIntent());
                                    }
                                })
                                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                    @Override
                                    public void onCancel (DialogInterface dialog) {
                                        finish();
                                    }
                                });
                    } else if (msg.hasMessage()) {
                        Countly.sharedInstance().recordMessageAction(msg.getId());
                        builder.setCancelable(true);
                        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel (DialogInterface dialog) {
                                finish();
                            }
                        });
                    } else {
                        throw new IllegalStateException("Countly Message with UNKNOWN type in ProxyActivity");
                    }

                    builder.create().show();
                } else {
                    Countly.sharedInstance().recordMessageAction(msg.getId());
                    startActivity(msg.getIntent());
                }
            }
        }

        @Override
        protected void onStop () {
            super.onStop();
        }
    }
}
