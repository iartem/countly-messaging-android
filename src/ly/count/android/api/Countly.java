package ly.count.android.api;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;

import android.app.Activity;
import android.content.SharedPreferences;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import org.OpenUDID.OpenUDID_manager;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

public class Countly {
	private static Countly sharedInstance_;
	private Timer timer_;
	private ConnectionQueue queue_;
	private EventQueue eventQueue_;
	private boolean isVisible_;
	private double unsentSessionLength_;
	private double lastTime_;
	private int activityCount_;
	private CountlyDB countlyDB_;

	static public Countly sharedInstance() {
		if (sharedInstance_ == null)
			sharedInstance_ = new Countly();

		return sharedInstance_;
	}

	private Countly() {
		queue_ = new ConnectionQueue();
		timer_ = new Timer();
		timer_.schedule(new TimerTask() {
			@Override
			public void run() {
				onTimer();
			}
		}, 60 * 1000, 60 * 1000);

		isVisible_ = false;
		unsentSessionLength_ = 0;
		activityCount_ = 0;
	}

	public void init(Activity activity, String serverURL, String appKey) {
        init(activity, null, serverURL, appKey, null);
    }

	public void init(Activity activity, String serverURL, String appKey, String gcmSenderId) {
        init(activity, activity.getClass(), serverURL, appKey, gcmSenderId);
    }

	public void init(Activity activity, Class<? extends Activity> activityClass, String serverURL, String appKey, String gcmSenderId) {
		OpenUDID_manager.sync(activity);
		countlyDB_ = new CountlyDB(activity);

		queue_.setContext(activity);
		queue_.setServerURL(serverURL);
		queue_.setAppKey(appKey);
		queue_.setCountlyDB(countlyDB_);

		eventQueue_ = new EventQueue(countlyDB_);

        if (!"".equals(gcmSenderId)) {
            CountlyMessaging.initMessaging(activity, activityClass, gcmSenderId);
        }
	}

	public void onStart() {
		activityCount_++;
		if (activityCount_ == 1)
			onStartHelper();
	}

	public void onStop() {
		activityCount_--;
		if (activityCount_ == 0)
			onStopHelper();
	}

	public void onRegistrationId(String registrationId) {
        queue_.tokenSession(registrationId);
	}

	public void onStartHelper() {
		lastTime_ = System.currentTimeMillis() / 1000.0;

		queue_.beginSession();

		isVisible_ = true;
	}

	public void onStopHelper() {
		if (eventQueue_.size() > 0)
			queue_.recordEvents(eventQueue_.events());

		double currTime = System.currentTimeMillis() / 1000.0;
		unsentSessionLength_ += currTime - lastTime_;

		int duration = (int) unsentSessionLength_;
		queue_.endSession(duration);
		unsentSessionLength_ -= duration;

		isVisible_ = false;
	}

	public void recordEvent(String key, int count) {
		eventQueue_.recordEvent(key, count);

		if (eventQueue_.size() >= 10)
			queue_.recordEvents(eventQueue_.events());
	}

	public void recordEvent(String key, int count, double sum) {
		eventQueue_.recordEvent(key, count, sum);

		if (eventQueue_.size() >= 10)
			queue_.recordEvents(eventQueue_.events());
	}

	public void recordEvent(String key, Map<String, String> segmentation, int count) {
		eventQueue_.recordEvent(key, segmentation, count);

		if (eventQueue_.size() >= 10)
			queue_.recordEvents(eventQueue_.events());
	}

	public void recordEvent(String key, Map<String, String> segmentation, int count, double sum) {
		eventQueue_.recordEvent(key, segmentation, count, sum);

		if (eventQueue_.size() >= 10)
			queue_.recordEvents(eventQueue_.events());
	}

	public void recordMessageAction (String key) {
		eventQueue_.recordPushAction(key);

		if (eventQueue_.size() >= 10)
			queue_.recordEvents(eventQueue_.events());
	}

	public void recordMessageOpen (String key) {
		eventQueue_.recordPushOpen(key);

		if (eventQueue_.size() >= 10)
			queue_.recordEvents(eventQueue_.events());
	}

	private void onTimer() {
		if (isVisible_ == false)
			return;

		double currTime = System.currentTimeMillis() / 1000.0;
		unsentSessionLength_ += currTime - lastTime_;
		lastTime_ = currTime;

		int duration = (int) unsentSessionLength_;
		queue_.updateSession(duration);
		unsentSessionLength_ -= duration;

		if (eventQueue_.size() > 0)
			queue_.recordEvents(eventQueue_.events());
	}
}

class ConnectionQueue {
	private CountlyDB queue_;
	private Thread thread_ = null;
	private String appKey_;
	private Context context_;
	private String serverURL_;

	public void setAppKey(String appKey) {
		appKey_ = appKey;
	}

	public void setContext(Context context) {
		context_ = context;
	}

	public void setServerURL(String serverURL) {
		serverURL_ = serverURL;
	}

	public void setCountlyDB(CountlyDB countlyDB) {
		queue_ = countlyDB;
	}

	public void beginSession() {
		String data;
		data = "app_key=" + appKey_;
		data += "&" + "device_id=" + DeviceInfo.getUDID();
		data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
		data += "&" + "sdk_version=" + "2.0";
		data += "&" + "begin_session=" + "1";
		data += "&" + "metrics=" + DeviceInfo.getMetrics(context_);

        String dimensions = DeviceInfo.getDimensionsJson(context_);
        if (dimensions != null) data += "&dimensions=" + dimensions;

		queue_.offer(data);

		tick();
	}

	public void updateSession(int duration) {
		String data;
		data = "app_key=" + appKey_;
		data += "&" + "device_id=" + DeviceInfo.getUDID();
		data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
		data += "&" + "session_duration=" + duration;

        String dimensions = DeviceInfo.getDimensionsJson(context_);
        if (dimensions != null) data += "&dimensions=" + dimensions;

		queue_.offer(data);

		tick();
	}

	public void tokenSession(String token) {
        DeviceInfo.addDimension(context_, "push", "true");

		String data;
		data = "app_key=" + appKey_;
		data += "&" + "device_id=" + DeviceInfo.getUDID();
		data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
		data += "&" + "token_session=1";
		data += "&" + "android_token=" + token;
		data += "&" + "locale=" + Locale.getDefault().getLanguage();

        String dimensions = DeviceInfo.getDimensionsJson(context_);
        if (dimensions != null) data += "&dimensions=" + dimensions;

		queue_.offer(data);

		tick();
	}

	public void endSession(int duration) {
		String data;
		data = "app_key=" + appKey_;
		data += "&" + "device_id=" + DeviceInfo.getUDID();
		data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
		data += "&" + "end_session=" + "1";
		data += "&" + "session_duration=" + duration;

        String dimensions = DeviceInfo.getDimensionsJson(context_);
        if (dimensions != null) data += "&dimensions=" + dimensions;

		queue_.offer(data);

		tick();
	}

	public void recordEvents(String events) {
		String data;
		data = "app_key=" + appKey_;
		data += "&" + "device_id=" + DeviceInfo.getUDID();
		data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
		data += "&" + "events=" + events;

        String dimensions = DeviceInfo.getDimensionsJson(context_);
        if (dimensions != null) data += "&dimensions=" + dimensions;

		queue_.offer(data);

		tick();
	}

	private void tick() {
		if (thread_ != null && thread_.isAlive())
			return;

		if (queue_.isEmpty())
			return;

		thread_ = new Thread() {
			@Override
			public void run() {
				while (true) {
					String data = queue_.peek();

					if (data == null)
						break;

					int index = data.indexOf("REPLACE_UDID");
					if (index != -1) {
						if (OpenUDID_manager.isInitialized() == false)
							break;
						data = data.replaceFirst("REPLACE_UDID", OpenUDID_manager.getOpenUDID());
					}

					try {
						DefaultHttpClient httpClient = new DefaultHttpClient();
						HttpGet method = new HttpGet(new URI(serverURL_ + "/i?" + data));
						HttpResponse response = httpClient.execute(method);
						InputStream input = response.getEntity().getContent();
						while (input.read() != -1)
							;
						httpClient.getConnectionManager().shutdown();

						Log.d("Countly", "ok ->" + data);

						queue_.poll();
					} catch (Exception e) {
						Log.d("Countly", e.toString());
						Log.d("Countly", "error ->" + data);
						break;
					}
				}
			}
		};

		thread_.start();
	}
}

class DeviceInfo {
	public static String getUDID() {
		return OpenUDID_manager.isInitialized() == false ? "REPLACE_UDID" : OpenUDID_manager.getOpenUDID();
	}

    private static final String DIMENSIONS_PROPERTY = "countly_dimensions";
    private static final String DIMENSION_KV_SEPARATOR = "=";

    private static String escape(String str) {
        return str.replace("\"", "\\\"").replace("\\", "\\\\");
    }

    public static String getDimensionsJson(Context context) {
        Map<String, String> dimensions = getDimensions(context);
        if (dimensions == null) return null;

        StringBuilder json = new StringBuilder("{");
        for (String key : dimensions.keySet()) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(key).append("\":\"").append(escape(dimensions.get(key))).append("\"");
        }
        json.append('}');

        try {
            return URLEncoder.encode(json.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    public static Map<String, String> getDimensions(Context context) {
        Set<String> dims = context.getApplicationContext().getSharedPreferences(Countly.class.getSimpleName(),
                Context.MODE_PRIVATE).getStringSet(DIMENSIONS_PROPERTY, null);

        if (dims == null) return null;
        else {
            Map<String, String> dimensions = new HashMap<String, String>();
            for (String dim : dims) {
                String[] kv = dim.split(DIMENSION_KV_SEPARATOR);
                if (kv.length > 0) dimensions.put(kv[0], kv[1]);
            }
            return dimensions.size() > 0 ? dimensions : null;
        }
    }

    public static void addDimension(Context context, String key, String value) {
        Map<String, String> dimensions = getDimensions(context);
        if (dimensions == null) dimensions = new HashMap<String, String>();

        dimensions.put(key, value);

        Set<String> dims = new HashSet<String>();
        for (String k : dimensions.keySet()) {
            dims.add(k + DIMENSION_KV_SEPARATOR + dimensions.get(k));
        }
        context.getApplicationContext().getSharedPreferences(Countly.class.getSimpleName(), Context.MODE_PRIVATE).edit()
                .putStringSet(DIMENSIONS_PROPERTY, dims).commit();
    }

	public static String getOS() {
		return "Android";
	}

	public static String getOSVersion() {
		return android.os.Build.VERSION.RELEASE;
	}

	public static String getDevice() {
		return android.os.Build.MODEL;
	}

	public static String getResolution(Context context) {
		WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

		Display display = wm.getDefaultDisplay();

		DisplayMetrics metrics = new DisplayMetrics();
		display.getMetrics(metrics);

		return metrics.widthPixels + "x" + metrics.heightPixels;
	}

	public static String getCarrier(Context context) {
		TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		return manager.getNetworkOperatorName();
	}

	public static String getLocale() {
		Locale locale = Locale.getDefault();
		return locale.getLanguage() + "_" + locale.getCountry();
	}

	public static String appVersion(Context context) {
		String result = "1.0";
		try {
			result = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
		} catch (NameNotFoundException e) {
		}

		return result;
	}

	public static String getMetrics(Context context) {
		String result = "";		
		JSONObject json = new JSONObject();

		try {
			json.put("_device", getDevice());
			json.put("_os", getOS());
			json.put("_os_version", getOSVersion());
			json.put("_carrier", getCarrier(context));
			json.put("_resolution", getResolution(context));
			json.put("_locale", getLocale());
			json.put("_app_version", appVersion(context));
		} catch (JSONException e) {
			e.printStackTrace();
		}

		result = json.toString();

		try {
			result = java.net.URLEncoder.encode(result, "UTF-8");
		} catch (UnsupportedEncodingException e) {

		}

		return result;
	}
}

class Event {
	public String key = null;
	public Map<String, String> segmentation = null;
	public int count = 0;
	public double sum = 0;
	public int timestamp = 0;
}

class EventQueue {
	private ArrayList<Event> events_;
	private CountlyDB countlyDB_;

	public EventQueue(CountlyDB countlyDB) {
		countlyDB_ = countlyDB;
		events_ = countlyDB_.getEvents();
	}

	public int size() {
		synchronized (this) {
			return events_.size();
		}
	}

	public String events() {
		String result = "";

		synchronized (this) {
			JSONArray eventArray = new JSONArray();

			for (int i = 0; i < events_.size(); ++i) {
				JSONObject json = new JSONObject();
				Event currEvent = events_.get(i);

				try {
					json.put("key", currEvent.key);
					json.put("count", currEvent.count);
					json.put("sum", currEvent.sum);
					json.put("timestamp", currEvent.timestamp);

					if (currEvent.segmentation != null) {
						json.put("segmentation", new JSONObject(currEvent.segmentation));
					}
				} catch (JSONException e) {
					e.printStackTrace();
				}

				eventArray.put(json);
			}

			result = eventArray.toString();

			events_.clear();
			countlyDB_.clearEvents();
		}

		try {
			result = java.net.URLEncoder.encode(result, "UTF-8");
		} catch (UnsupportedEncodingException e) {

		}

		return result;
	}

	public void recordEvent(String key, int count) {
		synchronized (this) {
			for (int i = 0; i < events_.size(); ++i) {
				Event event = events_.get(i);

				if (event.key.equals(key)) {
					event.count += count;
					event.timestamp = Math.round((event.timestamp + (System.currentTimeMillis() / 1000)) / 2);
					countlyDB_.saveEvents(events_);
					return;
				}
			}

			Event event = new Event();
			event.key = key;
			event.count = count;
			event.timestamp = Math.round(System.currentTimeMillis() / 1000);
			events_.add(event);

			countlyDB_.saveEvents(events_);
		}
	}

	public void recordEvent(String key, int count, double sum) {
		synchronized (this) {
			for (int i = 0; i < events_.size(); ++i) {
				Event event = events_.get(i);

				if (event.key.equals(key)) {
					event.count += count;
					event.sum += sum;
					event.timestamp = Math.round((event.timestamp + (System.currentTimeMillis() / 1000)) / 2);
					countlyDB_.saveEvents(events_);
					return;
				}
			}

			Event event = new Event();
			event.key = key;
			event.count = count;
			event.sum = sum;
			event.timestamp = Math.round(System.currentTimeMillis() / 1000);
			events_.add(event);

			countlyDB_.saveEvents(events_);
		}
	}

	public void recordEvent(String key, Map<String, String> segmentation, int count) {
		synchronized (this) {
			for (int i = 0; i < events_.size(); ++i) {
				Event event = events_.get(i);

				if (event.key.equals(key) && event.segmentation != null && event.segmentation.equals(segmentation)) {
					event.count += count;
					event.timestamp = Math.round((event.timestamp + (System.currentTimeMillis() / 1000)) / 2);
					countlyDB_.saveEvents(events_);
					return;
				}
			}

			Event event = new Event();
			event.key = key;
			event.segmentation = segmentation;
			event.count = count;
			event.timestamp = Math.round(System.currentTimeMillis() / 1000);
			events_.add(event);

			countlyDB_.saveEvents(events_);
		}
	}

	public void recordEvent(String key, Map<String, String> segmentation, int count, double sum) {
		synchronized (this) {
			for (int i = 0; i < events_.size(); ++i) {
				Event event = events_.get(i);

				if (event.key.equals(key) && event.segmentation != null && event.segmentation.equals(segmentation)) {
					event.count += count;
					event.sum += sum;
					event.timestamp = Math.round((event.timestamp + (System.currentTimeMillis() / 1000)) / 2);
					countlyDB_.saveEvents(events_);
					return;
				}
			}

			Event event = new Event();
			event.key = key;
			event.segmentation = segmentation;
			event.count = count;
			event.sum = sum;
			event.timestamp = Math.round(System.currentTimeMillis() / 1000);
			events_.add(event);

			countlyDB_.saveEvents(events_);
		}
	}

    public void recordPushOpen(String id) {
        Map<String, String> segmentation = new HashMap<String, String>();
        segmentation.put("i", id);
        recordEvent("_push_open", segmentation, 1);
    }

    public void recordPushAction(String id) {
        Map<String, String> segmentation = new HashMap<String, String>();
        segmentation.put("i", id);
        recordEvent("_push_action", segmentation, 1);
    }
}

class CountlyDB extends SQLiteOpenHelper {

	private static final int DATABASE_VERSION = 1;
	private static final String DATABASE_NAME = "countly";
	private static final String CONNECTIONS_TABLE_NAME = "CONNECTIONS";
	private static final String EVENTS_TABLE_NAME = "EVENTS";
	private static final String CONNECTIONS_TABLE_CREATE = "CREATE TABLE " + CONNECTIONS_TABLE_NAME + " (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, CONNECTION TEXT NOT NULL);";
	private static final String EVENTS_TABLE_CREATE = "CREATE TABLE " + EVENTS_TABLE_NAME + " (ID INTEGER UNIQUE NOT NULL, EVENT TEXT NOT NULL);";

	CountlyDB(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(CONNECTIONS_TABLE_CREATE);
		db.execSQL(EVENTS_TABLE_CREATE);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int arg1, int arg2) {

	}

	public String peek() {
		synchronized (this) {
			SQLiteDatabase db = this.getReadableDatabase();

			Cursor cursor = db.query(CONNECTIONS_TABLE_NAME, null, null, null, null, null, "ID DESC", "1");

			String connection = null;

			if (cursor != null && cursor.getCount() > 0) {
				cursor.moveToFirst();
				connection = cursor.getString(1);
				Log.d("Countly", "Fetched: " + connection);
			}

			return connection;
		}
	}

	public String poll() {
		synchronized (this) {
			SQLiteDatabase db = this.getReadableDatabase();

			Cursor cursor = db.query(CONNECTIONS_TABLE_NAME, null, null, null, null, null, "ID DESC", "1");

			String connection = null;

			if (cursor != null && cursor.getCount() > 0) {
				cursor.moveToFirst();
				connection = cursor.getString(1);
				int rawId = Integer.parseInt(cursor.getString(0));

				SQLiteDatabase writeDb = this.getWritableDatabase();
				writeDb.execSQL("DELETE FROM " + CONNECTIONS_TABLE_NAME + " WHERE ID = " + rawId + ";");

				Log.d("Countly", "Fetched and deleted: " + connection);
			}

			return connection;
		}
	}

	public void offer(String data) {
		SQLiteDatabase db = this.getWritableDatabase();

		db.execSQL("INSERT INTO " + CONNECTIONS_TABLE_NAME + "(CONNECTION) VALUES('" + data + "');");

		Log.d("Countly", "Insert into " + CONNECTIONS_TABLE_NAME + ": " + data);
	}

	public boolean isEmpty() {
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cursor = db.query(CONNECTIONS_TABLE_NAME, null, null, null, null, null, "ID DESC", "1");

		return !(cursor != null && cursor.getCount() > 0);
	}

	// Event related functions

	public ArrayList<Event> getEvents() {
		SQLiteDatabase db = this.getReadableDatabase();

		Cursor cursor = db.query(EVENTS_TABLE_NAME, null, null, null, null, null, "ID = 1", "1");
		ArrayList<Event> eventsArray = new ArrayList<Event>();

		if (cursor != null && cursor.getCount() > 0) {
			cursor.moveToFirst();
			String events = cursor.getString(1);

			JSONObject json = new JSONObject();

			try {
				json = new JSONObject(events);
			} catch (JSONException e) {
				e.printStackTrace();
			}

			JSONArray jArray = json.optJSONArray("events");

			if (jArray != null) {
				for (int i = 0; i < jArray.length(); i++) {
					try {
						eventsArray.add(jsonToEvent(new JSONObject(jArray.get(i).toString())));
					} catch (JSONException e) {
						e.printStackTrace();
					}
				}
			}
		}

		return eventsArray;
	}

	public void saveEvents(ArrayList<Event> events) {
		JSONArray eventArray = new JSONArray();
		JSONObject json = new JSONObject();

		for (int i = 0; i < events.size(); ++i) {
			eventArray.put(eventToJSON(events.get(i)));
		}

		try {
			json.put("events", eventArray);
		} catch (JSONException e) {
			e.printStackTrace();
		}

		SQLiteDatabase db = this.getWritableDatabase();

		db.execSQL("INSERT OR REPLACE INTO " + EVENTS_TABLE_NAME + "(ID, EVENT) VALUES(1, '" + json.toString() + "');");
	}

	public void clearEvents() {
		SQLiteDatabase writeDb = this.getWritableDatabase();
		writeDb.execSQL("DELETE FROM " + EVENTS_TABLE_NAME + ";");
	}

	private JSONObject eventToJSON(Event event) {
		JSONObject json = new JSONObject();

		try {
			json.put("key", event.key);
			json.put("count", event.count);
			json.put("sum", event.sum);
			json.put("timestamp", event.timestamp);

			if (event.segmentation != null) {
				json.put("segmentation", new JSONObject(event.segmentation));
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}

		return json;
	}

	private Event jsonToEvent(JSONObject json) {
		Event event = new Event();

		try {
			event.key = json.get("key").toString();
			event.count = Integer.valueOf(json.get("count").toString());
			event.sum = Double.valueOf(json.get("sum").toString());
			event.timestamp = Integer.valueOf(json.get("timestamp").toString());

			HashMap<String, String> segmentation = new HashMap<String, String>();
			@SuppressWarnings("unchecked")
			Iterator<String> nameItr = ((JSONObject) json.get("segmentation")).keys();

			while (nameItr.hasNext()) {
				String key = nameItr.next();
				segmentation.put(key, ((JSONObject) json.get("segmentation")).getString(key));
			}

			event.segmentation = segmentation;
		} catch (JSONException e) {
			e.printStackTrace();
		}

		return event;
	}
}