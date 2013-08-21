package interdroid.cuckoo.client;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * Provides history used to make a decision whether or not to offload. Data is
 * persisted to a SQLite database and SharedPreferences.
 * 
 * @author rkemp
 * 
 */
public class History {

	private static final String TAG = "Cuckoo History";

	public static Estimate estimateExecutionTimeDB(SharedPreferences prefs,
			String methodName, String resourceId, double weight)
			throws NoHistoryException {
		String prefix = resourceId + "@" + methodName;
		if (prefs.contains(prefix + "_average")) {
			Estimate estimate = new Estimate();
			estimate.average = (long) (prefs.getFloat(prefix + "_average", 0) * weight);
			estimate.variance = (float) (prefs
					.getFloat(prefix + "_variance", 0) * weight);
			return estimate;
		} else {
			throw new NoHistoryException();
		}
	}

	public static void updateExecutionTimeDB(SharedPreferences.Editor editor,
			SQLiteDatabase db, String methodName, String resourceId, long tNow,
			double weightNow) {
		if (!resourceId.equals("local")) {
			editor.putBoolean(methodName + ".nonempty", true);
		}
		// construct the content values that we store in the db.
		ContentValues values = new ContentValues();
		values.put("method", methodName);
		values.put("resource", resourceId);
		values.put("time", (float) (tNow / weightNow));
		// open the db and insert the values
		db.insert("execution", null, values);
		// pre compute the average and stdev for the next invocation, better do
		// this after the computation than before
		Cursor cursor = db.query("execution", new String[] { "time" },
				"method = ? AND resource = ?", new String[] { methodName,
						resourceId }, null, null, null);
		if (cursor.moveToFirst()) {
			float[] cursorValues = new float[cursor.getCount()];
			float total = 0;
			float tmp = 0;
			for (int i = 0; i < cursorValues.length; i++) {
				cursorValues[i] = cursor.getFloat(0);
				total += cursorValues[i];
				cursor.moveToNext();
			}
			float average = total / (float) cursorValues.length;
			for (int i = 0; i < cursorValues.length; i++) {
				tmp += Math.pow(cursorValues[i] - average, 2);
			}
			float variance = tmp / (float) cursorValues.length;
			// store the average and stdev in the shared preferences for quick
			// access.
			editor.putFloat(resourceId + "@" + methodName + "_average", average)
					.putFloat(resourceId + "@" + methodName + "_variance",
							variance);
			Log.d(TAG,
					cursor.getCount() + "," + average + ","
							+ Math.sqrt(variance));
		}
		cursor.close();
	}

	public static Estimate estimateWiFiDownloadDB(SharedPreferences prefs,
			String bssid, long outputSize, boolean lan)
			throws NoHistoryException {
		return estimateWiFiDB(prefs, bssid, outputSize, (lan ? "LAN" : "WAN")
				+ "_" + "down");
	}

	public static Estimate estimateWiFiUploadDB(SharedPreferences prefs,
			String bssid, long inputSize, boolean lan)
			throws NoHistoryException {
		return estimateWiFiDB(prefs, bssid, inputSize, (lan ? "LAN" : "WAN")
				+ "_" + "up");
	}

	private static Estimate estimateWiFiDB(SharedPreferences prefs,
			String bssid, long messageSize, String type)
			throws NoHistoryException {
		Estimate estimate = new Estimate();
		if (!prefs.contains(bssid + "_" + type + "_intercept")) {
			if (!prefs.contains(bssid + "_" + type + "_" + messageSize
					+ "_average")) {
				throw new NoHistoryException();
			} else {
				// this is the bandwidth.
				estimate.average = (long) (messageSize / prefs.getFloat(bssid
						+ "_" + type + "_" + messageSize + "_average", 0));
				estimate.variance = messageSize
						/ prefs.getFloat(bssid + "_" + type + "_" + messageSize
								+ "_variance", 0);
				return estimate;
			}
		}
		float intercept = prefs.getFloat(bssid + "_" + type + "_intercept", 0);
		float slope = prefs.getFloat(bssid + "_" + type + "_slope", 0);
		estimate.average = (long) (messageSize / (Math.log(messageSize) * slope + intercept));
		float interceptStdev = prefs.getFloat(bssid + "_" + type
				+ "_intercept_stdev", 0);
		float slopeStdev = prefs.getFloat(bssid + "_" + type + "_slope_stdev",
				0);
		estimate.variance = (float) (Math.pow(Math.log(messageSize)
				* slopeStdev, 2) + Math.pow(interceptStdev, 2));
		return estimate;
	}

	public static void updateWiFiDownloadDB(SharedPreferences.Editor editor,
			SQLiteDatabase db, String bssid, long outputSize,
			double downloadTime, Estimate downloadEstimate, boolean lan) {
		updateWiFiDB(editor, db, bssid, outputSize, downloadTime,
				downloadEstimate, (lan ? "LAN" : "WAN") + "_" + "down");
	}

	public static void updateWiFiUploadDB(SharedPreferences.Editor editor,
			SQLiteDatabase db, String bssid, long inputSize, double uploadTime,
			Estimate uploadEstimate, boolean lan) {
		updateWiFiDB(editor, db, bssid, inputSize, uploadTime, uploadEstimate,
				(lan ? "LAN" : "WAN") + "_" + "up");
	}

	private static void updateWiFiDB(SharedPreferences.Editor editor,
			SQLiteDatabase db, String bssid, long messageSize,
			double transferTime, Estimate estimate, String type) {
		if (transferTime < 10 && messageSize < 8 * 1024) {
			Log.d(TAG,
					"not storing wifi values because values are not trustworthy");
			return;
		}
		if (Math.abs(transferTime - estimate.average) < Math
				.sqrt(estimate.variance) * 2) {
			Log.d(TAG,
					"not storing wifi values because transfer time is caused by resource link");
			return;
		}
		ContentValues values = new ContentValues();
		values.put("bssid", bssid);
		values.put("log_size", Math.log(messageSize));
		values.put("bandwidth", messageSize / (double) transferTime);
		// open the db and insert the values
		db.insert("wifi_" + type, null, values);
		// pre compute the regression variables for the next invocation, better
		// do
		// this after the computation than before
		Cursor messageSizeCursor = db.query(true, "wifi_" + type,
				new String[] { "log_size", }, "bssid = ?",
				new String[] { bssid }, null, null, null, null);
		if (messageSizeCursor == null) {
			return;
		}
		boolean singleMessageSize = messageSizeCursor.getCount() < 2;
		messageSizeCursor.close();
		if (singleMessageSize) {
			Cursor cursor = db.query("wifi_" + type,
					new String[] { "bandwidth" }, "bssid = ?",
					new String[] { bssid }, null, null, "_id DESC", "100");
			if (cursor != null && cursor.moveToFirst()) {
				float[] cursorValues = new float[cursor.getCount()];
				float total = 0;
				float tmp = 0;
				for (int i = 0; i < cursorValues.length; i++) {
					cursorValues[i] = cursor.getFloat(0);
					total += cursorValues[i];
					cursor.moveToNext();
				}
				float average = total / (float) cursorValues.length;
				for (int i = 0; i < cursorValues.length; i++) {
					tmp += Math.pow(cursorValues[i] - average, 2);
				}
				float variance = tmp / (float) cursorValues.length;
				// store the average and stdev in the shared preferences for
				// quick
				// access.
				editor.putFloat(
						bssid + "_" + type + "_" + messageSize + "_average",
						average).putFloat(
						bssid + "_" + type + "_" + messageSize + "_variance",
						variance);
			}
			cursor.close();
		} else {// do regression
			Cursor cursor = db.query("wifi_" + type, new String[] { "log_size",
					"bandwidth" }, "bssid = ?", new String[] { bssid }, null,
					null, "_id DESC", "100");
			if (cursor != null && cursor.getCount() >= 2
					&& cursor.moveToFirst()) {
				SimpleRegression regression = new SimpleRegression();
				do {
					regression
							.addData(cursor.getDouble(0), cursor.getDouble(1));
				} while (cursor.moveToNext());
				editor.putFloat(bssid + "_" + type + "_intercept",
						(float) regression.getIntercept())
						.putFloat(bssid + "_" + type + "_intercept_stdev",
								(float) regression.getInterceptStdErr())
						.putFloat(bssid + "_" + type + "_slope",
								(float) regression.getSlope())
						.putFloat(bssid + "_" + type + "_slope_stdev",
								(float) regression.getInterceptStdErr());
			}
			cursor.close();
		}
	}

	public static void updateTailEnergy(SQLiteDatabase db,
			SharedPreferences.Editor editor, String methodName, long energy) {
		ContentValues values = new ContentValues();
		values.put("method", methodName);
		values.put("energy", energy);
		db.insert("tail", null, values);

		Cursor cursor = db.query("tail", new String[] { "energy" },
				"method = ?", new String[] { methodName }, null, null, null);
		if (cursor.moveToFirst()) {
			float[] cursorValues = new float[cursor.getCount()];
			float total = 0;
			float tmp = 0;
			for (int i = 0; i < cursorValues.length; i++) {
				cursorValues[i] = cursor.getFloat(0);
				total += cursorValues[i];
				cursor.moveToNext();
			}
			float average = total / (float) cursorValues.length;
			for (int i = 0; i < cursorValues.length; i++) {
				tmp += Math.pow(cursorValues[i] - average, 2);
			}
			float variance = tmp / (float) cursorValues.length;
			// store the average and stdev in the shared preferences for quick
			// access.
			editor.putFloat("tail" + "@" + methodName + "_average", average)
					.putFloat("tail" + "@" + methodName + "_variance", variance);
		}
		cursor.close();
	}

	public static Estimate estimateTailEnergy(SharedPreferences prefs,
			String methodName) {
		Estimate estimate = new Estimate();
		estimate.average = (long) (prefs.getFloat("tail" + "@" + methodName
				+ "_average", 0));
		estimate.variance = (float) (prefs.getFloat("tail" + "@" + methodName
				+ "_variance", 0));
		return estimate;
	}

	public static boolean isEmpty(SharedPreferences prefs, String methodName) {
		boolean nonEmpty = prefs.contains(methodName + ".nonempty");
		return !nonEmpty;
	}

}
