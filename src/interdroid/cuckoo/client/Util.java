package interdroid.cuckoo.client;

import java.io.File;
import java.io.IOException;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

public class Util {

	private static final int DB_VERSION = 4;

	public static float MbpsToBytesPerMs(float mbps) {
		return KbpsToBytesPerMs(mbps * 1000);
	}

	public static float KbpsToBytesPerMs(float kbps) {
		// kilobits per second to bytes per millisecond
		// * 1000 makes bits per second, / 1000 makes bits per ms, / 8 makes
		// bytes per ms.
		return kbps / 8;
	}

	public static SQLiteDatabase openDB(Context context) {
		File dbDir = new File("/sdcard/db.db");
		try {
			dbDir.createNewFile();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// File dbDir = context.getDir("databases", Context.MODE_PRIVATE);
		// SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new
		// File(dbDir,
		// "db"), null);
		SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbDir, null);
		if (db.getVersion() < DB_VERSION) {
			db.execSQL("DROP TABLE IF EXISTS execution");
			db.execSQL("CREATE TABLE execution (_id integer primary key autoincrement, method text, resource text, time number)");
			db.execSQL("DROP TABLE IF EXISTS wifi_WAN_up");
			db.execSQL("CREATE TABLE wifi_WAN_up (_id integer primary key autoincrement, bssid text, log_size number, bandwidth number)");
			db.execSQL("DROP TABLE IF EXISTS wifi_WAN_down");
			db.execSQL("CREATE TABLE wifi_WAN_down (_id integer primary key autoincrement, bssid text, log_size number, bandwidth number)");
			db.execSQL("DROP TABLE IF EXISTS wifi_LAN_up");
			db.execSQL("CREATE TABLE wifi_LAN_up (_id integer primary key autoincrement, bssid text, log_size number, bandwidth number)");
			db.execSQL("DROP TABLE IF EXISTS wifi_LAN_down");
			db.execSQL("CREATE TABLE wifi_LAN_down (_id integer primary key autoincrement, bssid text, log_size number, bandwidth number)");
			db.execSQL("DROP TABLE IF EXISTS tail");
			db.execSQL("CREATE TABLE tail (_id integer primary key autoincrement, method text, energy number)");
			db.setVersion(DB_VERSION);
		}
		return db;
	}
}
