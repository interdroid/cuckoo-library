package interdroid.cuckoo.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xmlpull.v1.XmlPullParser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.XmlResourceParser;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Helper class that deals with contextual information important for offloading
 * 
 * @author rkemp
 * 
 */
public class ContextState {

	private static final String TAG = "Cuckoo ContextState";

	/**
	 * The time we consider the location used for estimating part of the latency
	 * to be valid
	 */
	private static final long LOCATION_VALIDITY = 6 * 60 * 60 * 1000; // 6 hours

	/**
	 * Boolean indicating whether we have read the power profile, so we don't
	 * have to read it twice.
	 */
	private static boolean hasReadPowerProfile = false;

	private static Map<String, Double> items = new HashMap<String, Double>();
	private static Map<String, List<Double>> arrays = new HashMap<String, List<Double>>();
	private static NetworkInfo networkInfo;
	private static WifiInfo wifiInfo;

	private static boolean networkStable = true;
	private static boolean networkReceiverRegistered = false;
	private static Location location;
	private static long lastLocationQuery = 0;

	/**
	 * Receiver for network information. If the network changed during an
	 * offload, we cannot trust the statistics anymore and should not store
	 * them.
	 */
	public static BroadcastReceiver networkReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			Bundle extras = intent.getExtras();
			if (extras == null) {
				Log.d(TAG, "no extras!");
			} else {
				if (extras.getBoolean(ConnectivityManager.EXTRA_IS_FAILOVER)
						|| extras
								.getBoolean(ConnectivityManager.EXTRA_NO_CONNECTIVITY)) {
					networkStable = false;
				}
			}
		}
	};

	/**
	 * starts monitoring the network for changes that will be received by the
	 * {@link #networkReceiver}.
	 * 
	 * @param context
	 */
	public static void startMonitoringNetwork(final Context context) {
		// do this asynchronous to be faster
		new Thread() {
			public void run() {
				// stop any previous monitoring, just to be sure...
				stopMonitoringNetwork(context);

				// register a receiver to see whether the network was stable
				// between
				// shouldOffload and storeStatistics
				IntentFilter intentFilter = new IntentFilter();
				intentFilter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
				intentFilter
						.addAction("android.net.wifi.supplicant.CONNECTION_CHANGE");
				networkStable = true;
				context.registerReceiver(networkReceiver, intentFilter);
				networkReceiverRegistered = true;

			}
		}.start();
	}

	/**
	 * stops monitoring the network.
	 * 
	 * @param context
	 * @return
	 */
	public static boolean stopMonitoringNetwork(Context context) {
		if (networkReceiverRegistered) {
			context.unregisterReceiver(networkReceiver);
			networkReceiverRegistered = false;
		}
		Log.d(TAG, "stopping monitoring: " + networkStable);
		return networkStable;
	}

	/**
	 * read the internal power profile on the phone. see:
	 * https://source.android.com/devices/tech/power.html#. These values are
	 * used to estimate energy consumption.
	 * 
	 * @param context
	 */
	public static void readPowerProfile(Context context) {
		if (hasReadPowerProfile) {
			return;
		}

		try {
			int id = (Integer) Class.forName("com.android.internal.R$xml")
					.getField("power_profile").getInt(null);
			XmlResourceParser x = context.getResources().getXml(id);

			String key = null;
			String value = null;

			x.next();
			int eventType = x.getEventType();
			while (eventType != XmlPullParser.END_DOCUMENT) {
				if (eventType == XmlPullParser.START_DOCUMENT) {
				} else if (eventType == XmlPullParser.START_TAG) {
					if (x.getAttributeCount() > 0) {
						key = x.getAttributeValue(0);
					}
					if (x.getName().equals("array")) {
						arrays.put(key, new ArrayList<Double>());
					}
				} else if (eventType == XmlPullParser.END_TAG) {
					if (x.getName().equals("item")) {
						items.put(key, Double.parseDouble(value));
					} else if (x.getName().equals("value")) {
						arrays.get(key).add(Double.parseDouble(value));
					}
				} else if (eventType == XmlPullParser.TEXT) {
					value = x.getText();
				}
				eventType = x.next();
			}
		} catch (Throwable t) {
			System.err.println("Power Profile error: " + t.getMessage());
		}
		hasReadPowerProfile = true;
	}

	public static double valueOf(Context context, String key) {
		readPowerProfile(context);
		return items.get(key);
	}

	public static double maxOf(Context context, String key) {
		readPowerProfile(context);
		List<Double> array = arrays.get(key);
		return array.get(array.size() - 1);
	}

	public static double minOf(Context context, String key) {
		readPowerProfile(context);
		return arrays.get(key).get(0);
	}

	public static double middleOf(Context context, String key) {
		readPowerProfile(context);
		List<Double> array = arrays.get(key);
		return array.get(array.size() / 2);
	}

	public static NetworkInfo getNetworkInfo() {
		return networkInfo;
	}

	public static WifiInfo getWifiInfo() {
		return wifiInfo;
	}

	// public for debug purposes
	public static void ensureNetwork(Context context)
			throws NoConnectionException {
		long networkStart = System.currentTimeMillis();
		// Gather 'context' information
		ConnectivityManager connectivityManager = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		networkInfo = connectivityManager.getActiveNetworkInfo();
		if (networkInfo == null || !networkInfo.isConnected()) {
			throw new NoConnectionException("no network");
		}
		WifiManager wifi = (WifiManager) context
				.getSystemService(Context.WIFI_SERVICE);
		wifiInfo = wifi.getConnectionInfo();
		Log.d(TAG, "--> network setup: "
				+ (System.currentTimeMillis() - networkStart));
	}

	public static Location getLastKnownLocation(Context context) {
		if (location == null
				|| System.currentTimeMillis() - lastLocationQuery > LOCATION_VALIDITY) {
			location = ((LocationManager) context
					.getSystemService(Context.LOCATION_SERVICE))
					.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
			lastLocationQuery = System.currentTimeMillis();
		}
		return location;
	}
}
