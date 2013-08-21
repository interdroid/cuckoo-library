package interdroid.swan.cuckoo_sensors;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.google.android.gcm.server.Constants;
import com.google.android.gcm.server.Message;
import com.google.android.gcm.server.Result;
import com.google.android.gcm.server.Sender;

public class RemoteMonitorThread extends Thread {

	private CuckooPoller sensor;
	private String valuePath;
	private Map<String, Object> configuration;
	private String apiKey;
	private String registrationId;

	public RemoteMonitorThread(String registrationId, String apiKey,
			CuckooPoller sensor, final String valuePath,
			final Map<String, Object> configuration) {
		this.registrationId = registrationId;
		this.apiKey = apiKey;
		this.sensor = sensor;
		this.valuePath = valuePath;
		this.configuration = configuration;
	}

	public void run() {
		Map<String, Object> previous = null;
		System.out.println("Starting to monitor: " + valuePath + ", "
				+ configuration + ", " + sensor);
		while (!interrupted()) {
			Map<String, Object> values = sensor.poll(valuePath, configuration);
			if (changed(previous, values)) {
				previous = new HashMap<String, Object>();
				previous.putAll(values);
				// push with GCM
				try {
					push(registrationId, apiKey, values, true);
				} catch (IOException e) {
					e.printStackTrace(System.out);
					// should not happen
				}
			}
			try {
				sleep(sensor.getInterval(configuration, true));
			} catch (InterruptedException e) {
				// ignore, we will exit the loop anyways
			}
		}
	}

	private boolean changed(Map<String, Object> old, Map<String, Object> current) {
		if (current == null) {
			// new values are not valid
			return false;
		} else if (old == null) {
			// old values were invalid
			return true;
		} else {
			for (String key : old.keySet()) {
				if (!old.get(key).equals(current.get(key))) {
					// yes, we found a change
					return true;
				}
			}
		}
		return false;
	}

	public final void push(String registrationId, String apiKey,
			Map<String, Object> args, boolean delayWhileIdle)
			throws IOException {
		Sender sender = new Sender(apiKey);
		Message.Builder builder = new Message.Builder();
		builder.timeToLive(60 * 60).collapseKey("MAGIC_STRING")
				.delayWhileIdle(delayWhileIdle);
		for (String key : args.keySet()) {
			builder.addData(key, "" + args.get(key));
		}
		Message message = builder.build();
		Result result = sender.send(message, registrationId, 5);
		if (result.getMessageId() != null) {
			String canonicalRegId = result.getCanonicalRegistrationId();
			if (canonicalRegId != null) {
				// same device has more than on registration ID: update database
				System.out
						.println("same device has more than on registration ID: update database");
			} else {
				System.out.println("ok");
			}
		} else {
			String error = result.getErrorCodeName();
			if (error.equals(Constants.ERROR_NOT_REGISTERED)) {
				// application has been removed from device - unregister
				// database
				System.out
						.println("application has been removed from device - unregister database");
			}
			System.out.println("ok 2: " + error);
		}
	}

}
