##What's Countly?
[Countly](http://count.ly) is an innovative, real-time, open source mobile analytics application. 
It collects data from mobile devices, and visualizes this information to analyze mobile application 
usage and end-user behavior. There are two parts of Countly: the server that collects and analyzes data, 
and mobile SDK that sends this data. Both parts are open source with different licensing terms.

This repository includes the SDK for Android.

##Installing Android SDK

Installing Android SDK requires two very easy steps. Countly Android SDK uses OpenUDID (which comes ready with the zip file). First step is about OpenUDID requirement and second step is integrating Countly SDK to your project:

###1. Add this to your manifest:

* Add OpenUDID_manager.java and OpenUDID_service.java to your project under Eclipse.

<pre class="prettyprint">
&lt;service android:name=&quot;org.openudid.OpenUDID_service&quot;&gt;
    &lt;intent-filter&gt;
        &lt;action android:name=&quot;org.openudid.GETUDID&quot; /&gt;
    &lt;/intent-filter&gt;
&lt;/service&gt;
</pre>

###2. Add main Countly SDK to your project using steps below:

* Add Countly.java to your project under Eclipse.
* Call `Countly.sharedInstance().init(context, "https://YOUR_SERVER", "YOUR_APP_KEY")` in onCreate, which requires your App key and the URL of your Countly server (use `https://cloud.count.ly` for Countly Cloud).
* Call `Countly.sharedInstance().onStart()` in onStart.
* Call `Countly.sharedInstance().onStop()` in onStop.

Additionally, make sure that *INTERNET* permission is set if there's none in your manifest file.

**Note:** Make sure you use App Key (found under Management -> Applications) and not API Key. Entering API Key will not work. 

**Note:** Call init only once during onCreate of main activity. After that, for each onStart and onStop for 
each activity, call Countly onStart and onStop. 

###3. Countly Messaging support
This SDK can be used for Countly analytics, Countly Messaging push notification service or both at the same time. If the only thing you need is Countly analytics, you can skip this section. If you want yo use Countly Messaging alone or along with Countly analytics, you'll need to add a few more lines to your `AndroidManifest.xml`:
<pre class="prettyprint">
&lt;activity
	android:name="ly.count.android.api.CountlyMessaging$ProxyActivity"
    android:label="@string/app_name" android:theme="@android:style/Theme.Translucent" android:noHistory="true"/&gt;
	&lt;receiver
		android:name="ly.count.android.api.CountlyMessaging"
		android:permission="com.google.android.c2dm.permission.SEND" >
		&lt;intent-filter&gt;
			<action android:name="com.google.android.c2dm.intent.RECEIVE" />
			<category android:name="ly.count.android.api" />
		&lt;/intent-filter&gt;
		&lt;/receiver&gt;
	&lt;service android:name="ly.count.android.api.CountlyMessaging$CountlyMessagingService" >
		&lt;meta-data android:name="broadcast_action" android:value="ly.count.android.api.broadcast" /&gt;
	&lt;/service&gt;
</pre>
... and add one paramter to your Countly SDK startup method call:
<pre class="prettyprint">
Countly.sharedInstance().init(this, "https://YOUR_SERVER", "YOUR_APP_KEY", "YOUR_PROJECT_ID");
</pre>
.. where `YOUR_PROJECT_ID` is Project ID from Google API console for GCM service.

#####How it works? 
It's all done mostly automagically. You just add few lines to `AndroidManifest.xml`, and simple messaging will just work. Countly SDK registers in GCM service and receives registration ID which is then sent to Countly Messaging. When a message arrives:

1. If your app is in foreground, Countly SDK shows an `AlertDialog`. 
2. If your app is not in foreground, Countly SDK issues a `Notification`.
3. If this message is not just a message, but a message with a link or review request, Countly SDK will attach a listener to `Notification` or add a button to `AlertDialog`. So, when user taps on `Notification` or press on 'OK' button in `AlertDialog`, Countly SDK will open that link or will take a user to app review page in the Market respectively.
4. You can override this behaviour by sending a message with 'Silent' switch on in dashboard. With this switch turned on, Countly won't show any notifications or dialogs. In this case, don't forget to call `Countly.sharedInstance().recordMessageAction(message.getId())` if you want to have correct 'ACTIONS PERFORMED' metric in dashboard.
5. Countly SDK will also do required callbacks to Countly Messaging server to ensure correct calculation of messages open and messages with positive responses.
5. You can register `BroadcastReceiver` to get notified when a message arrives:

	<pre class="prettyprint">
/** Register for broadcast action if you need to be notified when Countly message received */
IntentFilter filter = new IntentFilter();
filter.addAction(CountlyMessaging.getBroadcastAction());
registerReceiver(new BroadcastReceiver() {
	@Override
	public void onReceive(Context context, Intent intent) {
		CountlyMessaging.Message message = intent.getParcelableExtra(CountlyMessaging.BROADCAST_RECEIVER_ACTION_MESSAGE);
		Log.i(TAG, "Got a message with data: " + message.getData());
	}
}, filter);
	</pre>

To put that simple, if you need to process a message in your app, send it with 'Silent' switch turned on and listen for `BroadcastReceiver` intent. 

If you just want to show a `Notification` or `AlertDialog` with message and some simple action like opening a URL or taking user to the Market for app review, don't set 'Silent' switch and let it all to be done by Countly.

If you need to customize `Notification` or `AlertDialog`, change the source code of `CountlyMessaging` class, it's pretty simple.

###4. Other

Check Countly Server source code here: 

- [Countly Server (countly-server)](https://github.com/Countly/countly-server)

There are also other Countly SDK repositories below:

- [Countly iOS SDK](https://github.com/Countly/countly-sdk-ios)
- [Countly Android SDK](https://github.com/Countly/countly-sdk-android)
- [Countly Windows Phone SDK](https://github.com/Countly/countly-sdk-windows-phone)
- [Countly Blackberry Webworks SDK](https://github.com/Countly/countly-sdk-blackberry-webworks)
- [Countly Blackberry Cascades SDK](https://github.com/craigmj/countly-sdk-blackberry10-cascades) (Community supported)
- [Countly Mac OS X SDK](https://github.com/mrballoon/countly-sdk-osx) (Community supported)
- [Countly Appcelerator Titanium SDK](https://github.com/euforic/Titanium-Count.ly) (Community supported)
- [Countly Unity3D SDK](https://github.com/Countly/countly-sdk-unity) (Community supported)

##How can I help you with your efforts?
Glad you asked. We need ideas, feedbacks and constructive comments. All your suggestions will be taken care with upmost importance. 

We are on [Twitter](http://twitter.com/gocountly) and [Facebook](http://www.facebook.com/Countly) if you would like to keep up with our fast progress!

For community support page, see [http://support.count.ly](http://support.count.ly "Countly Support").
