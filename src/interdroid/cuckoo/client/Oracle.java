package interdroid.cuckoo.client;

import interdroid.cuckoo.base.AboveAverageException;
import interdroid.cuckoo.client.Cuckoo.Resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.apache.commons.math3.distribution.NormalDistribution;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * The main purpose of the Oracle is to make a smart decision whether or not to
 * offload. To this end the method
 * {@link #shouldOffload(Context, String, String, float, long, long, boolean)}
 * is invoked by Cuckoo when a method call is intercepted.
 * 
 * @author rkemp
 * 
 */
public class Oracle {

	public static final String TAG = "Cuckoo Oracle";

	// TODO make these configurable
	public static final long TAIL_HIGH = 5000;
	public static final long TAIL_LOW = 12000;
	public static final long POLL_STEP = 1000;

	/**
	 * Supported offloading strategies
	 */
	public static final String STRATEGY_ENERGY = "energy";
	public static final String STRATEGY_SPEED = "speed";
	public static final String STRATEGY_ENERGY_SPEED = "energy/speed";
	public static final String STRATEGY_SPEED_ENERGY = "speed/energy";
	public static final String STRATEGY_PARALLEL = "parallel";
	public static final String STRATEGY_REMOTE = "remote";

	/**
	 * If the chance is higher than this constant we add it to the list with
	 * resources for which offloading is considered to be beneficial. Values
	 * lower than 0.5 don't make sense, higher than 0.5 will make offloading
	 * more conservative (i.e. prefer local execution even if at the long run
	 * remote execution is better).
	 */
	public static final float CONFIDENCE = .5f; // TODO make this configurablwe

	private static SharedPreferences prefs = null;
	private static SharedPreferences.Editor editor = null;
	private static SQLiteDatabase db = null;
	public static Estimate executionTime;
	public static boolean doNotStore = false;
	public static String forcedStrategy = null;

	public static boolean emptyHistory(Context context, String methodName) {
		return History.isEmpty(getPrefs(context), methodName);
	}

	/**
	 * This method is invoked by the generated Cuckoo code after a method
	 * invocation and stores statistics about the invocation that can be used
	 * for future predictions.
	 * 
	 * @param context
	 * @param methodName
	 * @param statistics
	 */
	public static void storeStatistics(final Context context,
			final String methodName, Statistics statistics) {
		Log.d(TAG, (doNotStore ? "NOT " : "") + "Storing statistics for '"
				+ methodName + "': " + statistics);
		if (doNotStore) {
			return;
		}
		System.out.println(statistics);
		boolean networkStable = ContextState.stopMonitoringNetwork(context);

		// we only keep historic information for WiFi (and only when the network
		// has been stable and it indeed was remotely executed)
		if (ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI
				&& networkStable
				&& !statistics.resource.getHostname().equals("local")) {
			String bssid = ContextState.getWifiInfo().getBSSID();
			boolean lan = Arrays.asList(statistics.resource.getBssids())
					.contains(bssid);
			History.updateWiFiDownloadDB(
					getEditor(context),
					getDB(context),
					bssid,
					statistics.returnSize,
					statistics.downloadTime,
					statistics.resource.estimateDownload(statistics.returnSize),
					lan);
			History.updateWiFiUploadDB(getEditor(context), getDB(context),
					bssid, statistics.inputSize, statistics.uploadTime,
					statistics.resource.estimateDownload(statistics.inputSize),
					lan);

		}

		History.updateExecutionTimeDB(getEditor(context), getDB(context),
				methodName, statistics.resource.getHostname(),
				statistics.executionTime, statistics.weight);

		// start a monitor here to look for tail costs for 3G
		new Thread() {
			public void run() {
				if (ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE
						&& (ContextState.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_UMTS
								|| ContextState.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_HSDPA
								|| ContextState.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_HSPA || ContextState
								.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_HSUPA)) {
					long startBytes = TrafficStats.getTotalRxBytes()
							+ TrafficStats.getTotalTxBytes();
					long startTime = System.currentTimeMillis();
					for (long i = 0; i < TAIL_LOW + TAIL_HIGH; i += POLL_STEP) {
						try {
							sleep(POLL_STEP);
						} catch (InterruptedException e) {
						}
						if (TrafficStats.getTotalRxPackets()
								+ TrafficStats.getTotalTxPackets() > startBytes) {
							// we encountered traffic from another source!
							break;
						}
					}
					long time = System.currentTimeMillis() - startTime;
					double networkPower = ContextState.valueOf(context,
							"radio.active");
					long energy = (long) (Math.min(time, TAIL_HIGH)
							* networkPower + Math.max(0, time - TAIL_HIGH)
							* networkPower / 2);
					History.updateTailEnergy(getDB(context),
							getEditor(context), methodName, energy);
				}
				cleanup();
			}
		}.start();
	}

	public static ArrayList<Resource> getAllResources(Context context) {
		// get all the resources from the Resource Manager
		Cursor cursor = context.getContentResolver().query(
				ResourceManager.Resources.CONTENT_URI,
				new String[] { ResourceManager.Resources.IDENTIFIER,
						ResourceManager.Resources.HUB_ADDRESS,
						ResourceManager.Resources.LOCATION_LATITUDE,
						ResourceManager.Resources.LOCATION_LONGITUDE,
						ResourceManager.Resources.BSSIDS,
						ResourceManager.Resources.UPLOAD,
						ResourceManager.Resources.UPLOAD_VARIANCE,
						ResourceManager.Resources.DOWNLOAD,
						ResourceManager.Resources.DOWNLOAD_VARIANCE }, null,
				new String[] {}, null);

		// see whether we got any results from the resource manager

		// this can be faster, pre process at insertion into the db.
		ArrayList<Resource> known = new ArrayList<Resource>();
		if (cursor != null && cursor.moveToFirst()) {
			do {
				Location location = new Location("db");
				location.setLatitude(cursor.getFloat(2));
				location.setLongitude(cursor.getFloat(3));
				known.add(new Resource(cursor.getString(1), cursor.getInt(0),
						cursor.getFloat(5), cursor.getFloat(6), cursor
								.getFloat(7), cursor.getFloat(8), cursor
								.getString(4).split(","), location));
			} while (cursor.moveToNext());
		}
		cursor.close();
		return known;
	}

	public static Answer shouldOffload(Context context, String strategy,
			String methodName, float weight, long inputSize, long outputSize,
			boolean screenOn) {
		if (forcedStrategy != null) {
			Log.d(TAG, "forcing strategy to '" + forcedStrategy + "', was '"
					+ strategy + "'");
			strategy = forcedStrategy;
		}
		Answer answer = shouldOffload(context, strategy, methodName, weight,
				inputSize, outputSize, true, screenOn);
		cleanup();
		return answer;
	}

	private static Answer shouldOffload(Context context, String strategy,
			String methodName, float weight, long inputSize, long outputSize,
			boolean firstPass, boolean screenOn) {
		Answer answer = new Answer();

		// first check network, if we don't have network, we can fail fast!
		if (firstPass) {
			try {
				ContextState.ensureNetwork(context);
			} catch (NoConnectionException e) {
				return answer;
			}
		}

		// start monitoring to know whether we should store the values later on.
		ContextState.startMonitoringNetwork(context);

		// retrieve all resources from the resource manager
		ArrayList<Resource> allResources = getAllResources(context);
		if (allResources.size() == 0) {
			// we know for sure that we will take the local implementation
			return answer;
		}

		ArrayList<Resource> offloadResources = answer.getOffloadResources();
		ArrayList<Resource> unknownResources = answer.getUnknownResources();

		if (strategy.equals(STRATEGY_PARALLEL)) {
			strategy = STRATEGY_SPEED;
		}

		if (strategy.equals(STRATEGY_ENERGY) || strategy.equals(STRATEGY_SPEED)) {
			// first we compute our estimate for the local invocation
			Estimate local;
			try {
				local = estimateLocal(context, methodName, weight, screenOn,
						strategy);
			} catch (NoHistoryException e) {
				// if we don't have any history, it means that it is the first
				// invocation, therefore all remote resources are unknown and we
				// can return.
				unknownResources.addAll(allResources);
				return answer;
			}
			for (Resource resource : allResources) {
				try {
					Estimate remote = estimateRemote(context, resource,
							methodName, weight, inputSize, outputSize,
							screenOn, strategy, local);
					// get a normal distribution based on the local and remote
					// estimate
					NormalDistribution distribution = new NormalDistribution(
							local.average - remote.average,
							Math.sqrt(remote.variance + local.variance));
					// compute the probability that offloading is better
					double probabilitySuccess = (1 - distribution
							.cumulativeProbability(0));
					Log.d(TAG, "--> estimate 'local': " + local + ", '"
							+ resource + "': " + remote
							+ " success probability: " + probabilitySuccess);
					// if the probability is high enough, store it in a sorted
					// map
					if (probabilitySuccess > CONFIDENCE) {
						resource.setSortValue(probabilitySuccess);
						offloadResources.add(resource);
					}
				} catch (NoHistoryException e) {
					// we don't have history for this resource, so add it to the
					// unknown set
					unknownResources.add(resource);
				} catch (NoConnectionException e) {
					// we don't have a connection (anymore)
					unknownResources.clear();
					return answer;
				} catch (AboveAverageException e) {
					System.out.println("above average!");
					e.printStackTrace();
					// we encountered half way that we are already above average
					continue;
				}
			}
			// sort the list (higher probability first)
			Collections.sort(offloadResources, Collections.reverseOrder());
			return answer;
		} else if (strategy.equals(STRATEGY_ENERGY_SPEED)) {
			Answer energyAnswer = shouldOffload(context, STRATEGY_ENERGY,
					methodName, weight, inputSize, outputSize, false);
			if (energyAnswer.getOffloadResources().size() == 0) {
				return shouldOffload(context, STRATEGY_SPEED, methodName,
						weight, inputSize, outputSize, false);
			} else {
				return energyAnswer;
			}
		} else if (strategy.equals(STRATEGY_SPEED_ENERGY)) {
			Answer speedAnswer = shouldOffload(context, STRATEGY_SPEED,
					methodName, weight, inputSize, outputSize, false);
			if (speedAnswer.getOffloadResources().size() == 0) {
				return shouldOffload(context, STRATEGY_ENERGY, methodName,
						weight, inputSize, outputSize, false);
			} else {
				return speedAnswer;
			}
		} else if (strategy.equals(STRATEGY_REMOTE)) {
			for (Resource resource : allResources) {
				try {
					Estimate remote = estimateRemote(context, resource,
							methodName, weight, inputSize, outputSize,
							screenOn, strategy, null);
					// pick the one with lowest average first
					// TODO: this can be improved to also take variance into
					// account
					resource.setSortValue(remote.average);
					offloadResources.add(resource);
				} catch (NoHistoryException e) {
					// we don't have history for this resource, so add it to the
					// unknown set
					unknownResources.add(resource);
				} catch (NoConnectionException e) {
					// we don't have a connection (anymore)
					unknownResources.clear();
					return answer;
				} catch (AboveAverageException e) {
					// we encountered half way that we are already above average
					continue;
				}
			}
			// sort the list
			Collections.sort(offloadResources);
			return answer;
		} else {
			throw new RuntimeException("invalid strategy: " + strategy
					+ " (this should not happen)");
		}
	}

	private static Estimate estimateLocal(Context context, String methodName,
			float weight, boolean screenOn, String strategy)
			throws NoHistoryException {
		if (strategy.equals(STRATEGY_ENERGY)) {
			return estimateEnergyLocal(context, methodName, weight, screenOn);
		} else {
			// must be STRATEGY_SPEED
			return estimateExecutionTimeLocal(context, methodName, weight);
		}
	}

	private static Estimate estimateEnergyLocal(Context context,
			String methodName, float weight, boolean screenOn)
			throws NoHistoryException {
		// start with the local execution time
		Estimate result = estimateExecutionTimeLocal(context, methodName,
				weight);
		// assume that we use Max Power (Homer to the Max ;-)) for the CPU
		// during the execution time
		double power = ContextState.maxOf(context, "cpu.active");
		if (screenOn) {
			double screen = ContextState.valueOf(context, "screen.on");
			double screenFull = ContextState.valueOf(context, "screen.full");
			try {
				power += screen
						+ (Settings.System.getInt(context.getContentResolver(),
								Settings.System.SCREEN_BRIGHTNESS) / 255.0f)
						* (screenFull - screen);
			} catch (SettingNotFoundException e) {
				// assume half brightness
				power += screen + 0.5 * (screenFull - screen);
			}
		}
		result.average *= power;
		result.variance *= (power * power);
		return result;
	}

	private static Estimate estimateExecutionTimeLocal(Context context,
			String methodName, float weight) throws NoHistoryException {
		return History.estimateExecutionTimeDB(getPrefs(context), methodName,
				"local", weight);
	}

	private static Estimate estimateRemote(Context context, Resource resource,
			String methodName, float weight, long inputSize, long outputSize,
			boolean screenOn, String strategy, Estimate local)
			throws NoHistoryException, NoConnectionException,
			AboveAverageException {
		if (strategy.equals(STRATEGY_ENERGY)) {
			return estimateEnergyRemote(context, resource, methodName, weight,
					inputSize, outputSize, screenOn, local);
		} else {
			// must be STRATEGY_SPEED
			return estimateExecutionTimeRemote(context, resource, methodName,
					weight, inputSize, outputSize, local);
		}
	}

	private static Estimate estimateEnergyRemote(Context context,
			Resource resource, String methodName, float weight, long inputSize,
			long outputSize, boolean screenOn, Estimate local)
			throws NoHistoryException, NoConnectionException,
			AboveAverageException {
		Estimate execution = estimateExecutionTimeAtResource(context,
				methodName, resource.getHostname(), weight);
		// save this for use in Cuckoo.java
		executionTime = execution;
		double lowExecutionPower = ContextState.valueOf(context, "cpu.idle");
		execution.average *= lowExecutionPower;
		execution.variance *= (lowExecutionPower * lowExecutionPower);

		Estimate rtt = estimateRTT(context, resource);
		Estimate upload = estimateUpload(context, resource, inputSize);
		Estimate download = estimateDownload(context, resource, outputSize);
		Estimate hardwareSetup = estimateHardwareSetup(context);
		Estimate totalNetworkEstimate = Estimate.combine(rtt, rtt, upload,
				download, hardwareSetup);

		double networkPower;
		if (ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE) {
			// Cellular
			networkPower = ContextState.valueOf(context, "radio.active");
		} else {
			// WiFi
			networkPower = ContextState.valueOf(context, "wifi.active");
		}
		totalNetworkEstimate.average *= networkPower;
		totalNetworkEstimate.variance *= (networkPower * networkPower);

		Estimate total = Estimate.combine(execution, totalNetworkEstimate);
		if (total.average > local.average) {
			throw new AboveAverageException();
		}

		Estimate screenEstimate = new Estimate();
		if (screenOn) {
			double screenPower = 0;
			screenEstimate = estimateExecutionTimeRemote(context, resource,
					methodName, weight, inputSize, outputSize, null);
			double screen = ContextState.valueOf(context, "screen.on");
			double screenFull = ContextState.valueOf(context, "screen.full");
			try {
				screenPower = screen
						+ (Settings.System.getInt(context.getContentResolver(),
								Settings.System.SCREEN_BRIGHTNESS) / 255.0f)
						* (screenFull - screen);
			} catch (SettingNotFoundException e) {
				// assume half brightness, if we cannot get the brightness from
				// the settings.
				screenPower = screen + 0.5 * (screenFull - screen);
			}
			screenEstimate.average *= screenPower;
			screenEstimate.variance *= (screenPower * screenPower);
		}
		Estimate.combine(total, screenEstimate);
		if (total.average > local.average) {
			throw new AboveAverageException();
		}

		Estimate tail = new Estimate();
		// are we on 3G?
		if (ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE
				&& (ContextState.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_UMTS
						|| ContextState.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_HSDPA
						|| ContextState.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_HSPA || ContextState
						.getNetworkInfo().getSubtype() == TelephonyManager.NETWORK_TYPE_HSUPA)) {
			tail = History.estimateTailEnergy(getPrefs(context), methodName);
		}
		total = Estimate.combine(total, tail);
		if (total.average > local.average) {
			throw new AboveAverageException();
		}
		return total;
	}

	private static Estimate estimateExecutionTimeRemote(Context context,
			Resource resource, String methodName, float weight, long inputSize,
			long outputSize, Estimate local) throws NoHistoryException,
			NoConnectionException, AboveAverageException {

		Estimate execution = estimateExecutionTimeAtResource(context,
				methodName, resource.getHostname(), weight);
		// save this in a global for later access in Cuckoo.java
		executionTime = execution;
		Estimate upload = estimateUpload(context, resource, inputSize);
		Estimate totalEstimate = Estimate.combine(execution, upload);
		if (local != null && totalEstimate.average > local.average) {
			throw new AboveAverageException();
		}
		Estimate download = estimateDownload(context, resource, outputSize);
		totalEstimate = Estimate.combine(totalEstimate, download);
		if (local != null && totalEstimate.average > local.average) {
			throw new AboveAverageException();
		}
		Estimate hardwareSetup = estimateHardwareSetup(context);
		totalEstimate = Estimate.combine(totalEstimate, hardwareSetup);
		if (local != null && totalEstimate.average > local.average) {
			throw new AboveAverageException();
		}
		// rtt twice, once for tcp setup (= socket creation) and once for actual
		// request/reply.
		// do this later on, it is more costly, because it might need to query
		// the location service
		Estimate rtt = estimateRTT(context, resource);
		totalEstimate = Estimate.combine(totalEstimate, rtt);
		totalEstimate = Estimate.combine(totalEstimate, rtt);
		if (local != null && totalEstimate.average > local.average) {
			throw new AboveAverageException();
		}
		return totalEstimate;
	}

	// Latency estimation
	private static final double MAX_SPEED_PERSON = 0.003; // m/ms (~ 100 km/h)
	private static final double SPEED_OF_LIGHT_FIBER = 20086; // m/ms

	private static Estimate estimateRTT(Context context, Resource resource) {
		Estimate result = estimateEdgeRTT(context, resource);
		Location lastFix = ContextState.getLastKnownLocation(context);
		if (lastFix != null) {
			// times two, to and from
			double distanceRoundTrip = 2 * lastFix.distanceTo(resource
					.getLocation());
			long geographicRTT = (long) (distanceRoundTrip / SPEED_OF_LIGHT_FIBER);
			result.average += geographicRTT;
			long timeSinceFix = System.currentTimeMillis() - lastFix.getTime();
			long deltaRTT = (long) (2 * timeSinceFix * MAX_SPEED_PERSON / SPEED_OF_LIGHT_FIBER);
			result.variance += computeVariance(deltaRTT);
		}
		return result;
	}

	private static Estimate estimateEdgeRTT(Context context, Resource resource) {
		Estimate result = new Estimate();
		if (ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE) {
			switch (ContextState.getNetworkInfo().getSubtype()) {
			case TelephonyManager.NETWORK_TYPE_GPRS:
				// values from measurements
				result.average = 335;
				result.variance = 57.57f * 57.57f;
				break;
			case TelephonyManager.NETWORK_TYPE_HSPA:
			case TelephonyManager.NETWORK_TYPE_HSDPA:
			case TelephonyManager.NETWORK_TYPE_HSUPA:
			case TelephonyManager.NETWORK_TYPE_UMTS:
				// values from measurements
				result.average = 81;
				result.variance = 57.7f * 57.7f;
			default:
				// guess for default
				result.average = 100;
				result.variance = 2500;
				break;
			}
		} else {
			// wifi (values from measurements)
			if (Arrays.asList(resource.getBssids()).contains(
					ContextState.getWifiInfo().getBSSID())) {
				// LAN
				result.average = 10;
				result.variance = 1;
			} else {
				// WAN
				result.average = 37;
				result.variance = 8.1f * 8.1f;
			}

		}
		return result;
	}

	private static Estimate estimateUpload(Context context, Resource resource,
			long inputSize) throws NoConnectionException {
		long theoreticalFastest = (long) (inputSize / bandwidthLimitUpload());
		Estimate fromConnection = estimateUploadFromConnection(context,
				resource, inputSize);
		Estimate fromResource = resource.estimateUpload(inputSize);
		// take the maximum time from both estimates (determine bottleneck)
		Estimate max = Estimate.max(fromConnection, fromResource);
		// if the max time is faster than what theoretically could be achieved,
		// take the theoretical
		if (max.average < theoreticalFastest) {
			max.average = theoreticalFastest;
			max.variance = 0.000001f;
		}
		return max;
	}

	private static Estimate estimateDownload(Context context,
			Resource resource, long outputSize) throws NoConnectionException {
		long theoreticalFastest = (long) (outputSize / bandwidthLimitDownload());
		Estimate fromConnection = estimateDownloadFromConnection(context,
				resource, outputSize);
		Estimate fromResource = resource.estimateDownload(outputSize);
		// take the maximum time from both estimates (determine bottleneck)
		Estimate max = Estimate.max(fromConnection, fromResource);
		// if the max time is faster than what theoretically could be achieved,
		// take the theoretical
		if (max.average < theoreticalFastest) {
			max.average = theoreticalFastest;
			max.variance = 0.000001f;
		}
		return max;
	}

	private static float bandwidthLimitDownload() {
		if (ContextState.getNetworkInfo() != null
				&& ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE) {
			// Vodafone download max is 14 Mbps
			// Nexus One download max is 7.2 Mbps
			// TODO this should not be hardcoded but retrieved from a lookup
			// table
			return Math.min(Util.MbpsToBytesPerMs(14),
					Util.MbpsToBytesPerMs(7.2f));
		}
		return Float.MAX_VALUE;
	}

	private static float bandwidthLimitUpload() {
		if (ContextState.getNetworkInfo() != null
				&& ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE) {
			// Vodafone upload max is 3.6 Mbps
			// Nexus One upload max is 2.0 Mbps
			// TODO this should not be hardcoded but retrieved from a lookup
			// table
			return Math.min(Util.MbpsToBytesPerMs(3.6f),
					Util.MbpsToBytesPerMs(2.0f));
		}
		return Float.MAX_VALUE;
	}

	/**
	 * returns upload bandwidth in bytes/ms
	 * 
	 * @param resource
	 * @param context
	 * @return
	 * @throws NoConnectionException
	 */
	private static Estimate estimateUploadFromConnection(Context context,
			Resource resource, long inputSize) throws NoConnectionException {
		if (ContextState.getNetworkInfo() == null
				|| !ContextState.getNetworkInfo().isConnected()) {
			throw new NoConnectionException(
					ContextState.getNetworkInfo() == null ? "No network information"
							: "Network not connected");
		} else {
			Estimate result = new Estimate();
			int type = ContextState.getNetworkInfo().getType();
			int subType = ContextState.getNetworkInfo().getSubtype();
			float bandwidth;
			float stdevBandwidth;
			if (type == ConnectivityManager.TYPE_MOBILE) {
				switch (subType) {
				case TelephonyManager.NETWORK_TYPE_1xRTT: // ~ 50-100 kbps
					result.average = (long) (inputSize / Util
							.KbpsToBytesPerMs(75));
					result.variance = computeVariance(inputSize
							/ Util.KbpsToBytesPerMs(50));
					return result;
				case TelephonyManager.NETWORK_TYPE_CDMA: // ~ 14-64 kbps
					result.average = (long) (inputSize / Util
							.KbpsToBytesPerMs(39));
					result.variance = computeVariance(inputSize
							/ Util.KbpsToBytesPerMs(50));
					return result;
				case TelephonyManager.NETWORK_TYPE_EDGE: // ~ 50-100 kbps
					result.average = (long) (inputSize / Util
							.KbpsToBytesPerMs(39));
					result.variance = computeVariance(inputSize
							/ Util.KbpsToBytesPerMs(50));
					return result;
				case TelephonyManager.NETWORK_TYPE_EVDO_0: // ~ 400-1000 kbps
					result.average = (long) (inputSize / Util
							.KbpsToBytesPerMs(700));
					result.variance = computeVariance(inputSize
							/ Util.KbpsToBytesPerMs(600));
					return result;
				case TelephonyManager.NETWORK_TYPE_EVDO_A: // ~ 600-1400 kbps
					result.average = (long) (inputSize / Util
							.KbpsToBytesPerMs(1000));
					result.variance = computeVariance(inputSize
							/ Util.KbpsToBytesPerMs(800));
					return result;
				case TelephonyManager.NETWORK_TYPE_GPRS: // ~ 100 kbps
					bandwidth = 2.3f;
					stdevBandwidth = 0.2f;
					result.average = (long) (inputSize / (bandwidth));
					result.variance = (float) (Math.pow(stdevBandwidth
							/ bandwidth * result.average, 2));
					return result;
				case TelephonyManager.NETWORK_TYPE_HSDPA: // ~ 2-14 Mbps
				case TelephonyManager.NETWORK_TYPE_HSPA: // ~ 700-1700 kbps
				case TelephonyManager.NETWORK_TYPE_HSUPA: // ~ 1-23 Mbps
				case TelephonyManager.NETWORK_TYPE_UMTS: // ~ 400-7000 kbps
				case TelephonyManager.NETWORK_TYPE_LTE:
				case TelephonyManager.NETWORK_TYPE_HSPAP:
					bandwidth = 36.0f;
					stdevBandwidth = 0.7f;
					result.average = (long) (inputSize / (bandwidth));
					result.variance = (float) (Math.pow(stdevBandwidth
							/ bandwidth * result.average, 2));
					return result;
				case TelephonyManager.NETWORK_TYPE_EHRPD: // ~ 1-2 Mbps
					result.average = (long) (inputSize / Util
							.MbpsToBytesPerMs(1.5f));
					result.variance = computeVariance(inputSize
							/ Util.MbpsToBytesPerMs(1));
					return result;
				case TelephonyManager.NETWORK_TYPE_EVDO_B: // ~ 5 Mbps
					result.average = (long) (inputSize / Util
							.MbpsToBytesPerMs(5));
					result.variance = computeVariance(inputSize
							/ Util.KbpsToBytesPerMs(1));
					return result;
				case TelephonyManager.NETWORK_TYPE_IDEN: // ~25 kbps
					result.average = (long) (inputSize / Util
							.KbpsToBytesPerMs(25));
					result.variance = computeVariance(inputSize
							/ Util.KbpsToBytesPerMs(1));
					return result;
				case TelephonyManager.NETWORK_TYPE_UNKNOWN:
				default:
					result.average = 0;
					result.variance = 0;
					return result;
				}
			} else if (type == ConnectivityManager.TYPE_WIFI) {
				String bssid = ContextState.getWifiInfo().getBSSID();
				try {
					return History
							.estimateWiFiUploadDB(getPrefs(context), bssid,
									inputSize,
									Arrays.asList(resource.getBssids())
											.contains(bssid));
				} catch (NoHistoryException e) {
					result.average = (long) (inputSize / Util
							.MbpsToBytesPerMs(ContextState.getWifiInfo()
									.getLinkSpeed()));
					result.variance = 0;
					return result;
				}
			} else {
				// unknown connection, fail
				throw new NoConnectionException("Cannot handle connection: "
						+ type);
			}
		}
	}

	/**
	 * returns estimate of download time in ms.
	 * 
	 * @param resource
	 * @param context
	 * @return
	 * @throws NoConnectionException
	 */
	private static Estimate estimateDownloadFromConnection(Context context,
			Resource resource, long outputSize) throws NoConnectionException {
		if (ContextState.getNetworkInfo() == null
				|| !ContextState.getNetworkInfo().isConnected()) {
			throw new NoConnectionException(
					ContextState.getNetworkInfo() == null ? "No network information"
							: "Network not connected");
		} else {
			Estimate result = new Estimate();
			int type = ContextState.getNetworkInfo().getType();
			int subType = ContextState.getNetworkInfo().getSubtype();
			float bandwidth;
			float stdevBandwidth;
			if (type == ConnectivityManager.TYPE_MOBILE) {
				switch (subType) {
				case TelephonyManager.NETWORK_TYPE_1xRTT: // ~ 50-100 kbps
					result.average = (long) (outputSize / Util
							.KbpsToBytesPerMs(75));
					result.variance = computeVariance(outputSize
							/ Util.KbpsToBytesPerMs(50));
					return result;
				case TelephonyManager.NETWORK_TYPE_CDMA: // ~ 14-64 kbps
					result.average = (long) (outputSize / Util
							.KbpsToBytesPerMs(39));
					result.variance = computeVariance(outputSize
							/ Util.KbpsToBytesPerMs(50));
					return result;
				case TelephonyManager.NETWORK_TYPE_EDGE: // ~ 50-100 kbps
					result.average = (long) (outputSize / Util
							.KbpsToBytesPerMs(39));
					result.variance = computeVariance(outputSize
							/ Util.KbpsToBytesPerMs(50));
					return result;
				case TelephonyManager.NETWORK_TYPE_EVDO_0: // ~ 400-1000 kbps
					result.average = (long) (outputSize / Util
							.KbpsToBytesPerMs(700));
					result.variance = computeVariance(outputSize
							/ Util.KbpsToBytesPerMs(600));
					return result;
				case TelephonyManager.NETWORK_TYPE_EVDO_A: // ~ 600-1400 kbps
					result.average = (long) (outputSize / Util
							.KbpsToBytesPerMs(1000));
					result.variance = computeVariance(outputSize
							/ Util.KbpsToBytesPerMs(800));
					return result;
				case TelephonyManager.NETWORK_TYPE_GPRS: // ~ 100 kbps
					// based on Nexus One measurements, see thesis
					bandwidth = (float) (0.2578 * Math.log(outputSize) + 3.5926);
					stdevBandwidth = 0.5f;
					result.average = (long) (outputSize / bandwidth);
					// compute the variance, the variance in the bandwidth is
					// 0.5*0.5, however by transforming bandwidth into time we
					// go to an inverse gaussian distribution instead of the
					// normal distribution
					result.variance = (float) Math.pow(stdevBandwidth
							/ bandwidth * result.average, 2);
					return result;
				case TelephonyManager.NETWORK_TYPE_HSDPA: // ~ 2-14 Mbps
				case TelephonyManager.NETWORK_TYPE_HSPA: // ~ 700-1700 kbps
				case TelephonyManager.NETWORK_TYPE_HSUPA: // ~ 1-23 Mbps
				case TelephonyManager.NETWORK_TYPE_UMTS: // ~ 400-7000 kbps
					if (outputSize >= 8 * 1024) {
						bandwidth = (float) (62.438 * Math.log(outputSize) - 37.048);
					} else {
						bandwidth = 95;
					}
					stdevBandwidth = 16.0f;
					result.average = (long) (outputSize / bandwidth);
					result.variance = (float) (Math.pow(stdevBandwidth
							/ bandwidth * result.average, 2));
					return result;
				case TelephonyManager.NETWORK_TYPE_EHRPD: // ~ 1-2 Mbps
					result.average = (long) (outputSize / Util
							.MbpsToBytesPerMs(1.5f));
					result.variance = computeVariance(outputSize
							/ Util.MbpsToBytesPerMs(1));
					return result;
				case TelephonyManager.NETWORK_TYPE_EVDO_B: // ~ 5 Mbps
					result.average = (long) (outputSize / Util
							.MbpsToBytesPerMs(5));
					result.variance = computeVariance(outputSize
							/ Util.KbpsToBytesPerMs(1));
					return result;
				case TelephonyManager.NETWORK_TYPE_HSPAP: // ~ 10-20 Mbps
					result.average = (long) (outputSize / Util
							.MbpsToBytesPerMs(15));
					result.variance = computeVariance(outputSize
							/ Util.MbpsToBytesPerMs(10));
					return result;
				case TelephonyManager.NETWORK_TYPE_IDEN: // ~25 kbps
					result.average = (long) (outputSize / Util
							.KbpsToBytesPerMs(25));
					result.variance = computeVariance(outputSize
							/ Util.KbpsToBytesPerMs(1));
					return result;
				case TelephonyManager.NETWORK_TYPE_LTE: // ~ 10+ Mbps
					result.average = (long) (outputSize / Util
							.MbpsToBytesPerMs(10));
					result.variance = computeVariance(outputSize
							/ Util.MbpsToBytesPerMs(1));
					return result;
					// Unknown
				case TelephonyManager.NETWORK_TYPE_UNKNOWN:
				default:
					result.average = 0;
					result.variance = 0;
					return result;
				}
			} else if (type == ConnectivityManager.TYPE_WIFI) {
				String bssid = ContextState.getWifiInfo().getBSSID();
				try {
					return History
							.estimateWiFiDownloadDB(getPrefs(context), bssid,
									outputSize,
									Arrays.asList(resource.getBssids())
											.contains(bssid));
				} catch (NoHistoryException e) {
					result.average = (long) (outputSize / Util
							.MbpsToBytesPerMs(ContextState.getWifiInfo()
									.getLinkSpeed()));
					result.variance = 0;
					return result;
				}
			} else {
				// unknown connection, fail
				throw new NoConnectionException("Cannot handle connection: "
						+ type);
			}
		}
	}

	private static Estimate estimateExecutionTimeAtResource(Context context,
			String methodName, String resourceId, float weight)
			throws NoHistoryException {
		return History.estimateExecutionTimeDB(getPrefs(context), methodName,
				resourceId, weight);
	}

	private static Estimate estimateHardwareSetup(Context context) {
		if (ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE) {
			switch (ContextState.getNetworkInfo().getSubtype()) {
			case TelephonyManager.NETWORK_TYPE_HSDPA:
			case TelephonyManager.NETWORK_TYPE_HSPA:
			case TelephonyManager.NETWORK_TYPE_HSUPA:
			case TelephonyManager.NETWORK_TYPE_UMTS:
			case TelephonyManager.NETWORK_TYPE_HSPAP:
			case TelephonyManager.NETWORK_TYPE_LTE:
				TelephonyManager manager = (TelephonyManager) context
						.getSystemService(Context.TELEPHONY_SERVICE);
				switch (manager.getDataActivity()) {
				case TelephonyManager.DATA_ACTIVITY_IN:
				case TelephonyManager.DATA_ACTIVITY_INOUT:
				case TelephonyManager.DATA_ACTIVITY_OUT:
					// no additional hardware setup time...
					return new Estimate();
				default:
					// we don't know, we might or might not have additional
					// hardware setup time.
					Estimate result = new Estimate();
					result.average = 1000;
					result.variance = 1000 * 1000;
					return result;
				}
			default:
				break;
			}
		}
		return new Estimate();
	}

	/************** HELPER methods ***************/

	/**
	 * computes the variance for a given range, such that 95% of the values will
	 * be within the range from the average if there is a normal distribution.
	 * 2.5% will be under and 2.5% will be above.
	 */
	private static float computeVariance(float range) {
		return (float) Math.pow((range * 0.5) * .510213f, 2);
	}

	private static void cleanup() {
		if (prefs != null) {
			prefs = null;
		}
		if (editor != null) {
			editor.commit();
			editor = null;
		}
		if (db != null) {
			db.close();
			db = null;
		}
	}

	private static SharedPreferences getPrefs(Context context) {
		if (prefs != null) {
			return prefs;
		} else {
			prefs = context.getSharedPreferences("oracle", 0);
			return prefs;
		}
	}

	private static SharedPreferences.Editor getEditor(Context context) {
		if (editor != null) {
			return editor;
		} else {
			editor = getPrefs(context).edit();
			return editor;
		}
	}

	private static SQLiteDatabase getDB(Context context) {
		if (db != null) {
			return db;
		} else {
			db = Util.openDB(context);
			return db;
		}
	}

}