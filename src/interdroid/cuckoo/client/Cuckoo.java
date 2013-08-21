package interdroid.cuckoo.client;

import interdroid.cuckoo.base.NoResourceAvailableException;
import interdroid.cuckoo.base.NotInitializedException;
import interdroid.cuckoo.base.NotInstalledException;
import interdroid.cuckoo.base.Protocol;
import interdroid.swan.cuckoo_sensors.CuckooPoller;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.util.Log;

/**
 * The Cuckoo class helps to connect to a Cuckoo Server.
 * 
 * This class will be used by the generated Stubs to connect with a Cuckoo
 * Server.
 * 
 * @author rkemp
 */
public class Cuckoo {

	private final static String TAG = "Cuckoo";

	public static final String WIFI_WAKE_STRATEGY = "wifi.wake.strategy";
	public static final String WIFI_WAKE_STRATEGY_DEFAULT = "default";
	public static final String WIFI_WAKE_STRATEGY_AWAKE = "awake";
	public static final String WIFI_WAKE_STRATEGY_SLEEP = "sleep";
	public static final String WIFI_WAKE_STRATEGY_JIT = "jit";

	private static String strategy;

	/**
	 * The Communicator object communicates with a single Cuckoo Server.
	 * 
	 * @author rkemp
	 * 
	 */
	private static class Communicator {

		/**
		 * The resource information for this Communicator.
		 */
		private Resource mResource;

		/**
		 * The context of the application using the Communicator, this is needed
		 * to read the assets folder where the remote code is located.
		 */
		private Context mContext;

		private Socket mSocket;

		private ObjectInputStream mIn;

		private ObjectOutputStream mOut;

		private int mUid;

		private long mRTT;

		private boolean trafficStatsSet = false;

		/**
		 * Constructs a Communicator object. This object will be used to
		 * communicate with the given Resource.
		 * 
		 * @param context
		 *            needed to read the assets folder
		 * @param resource
		 *            the resource to communicate with
		 */
		private Communicator(Context context, Resource resource)
				throws IOException {
			mResource = resource;
			mContext = context;
			mUid = android.os.Process.myUid();
			long start = System.currentTimeMillis();
			mSocket = new Socket(resource.getHostname(), resource.getPort());
			mRTT = System.currentTimeMillis() - start;
			mSocket.setKeepAlive(true);
			mSocket.setTcpNoDelay(true);
			mSocket.setSoTimeout(1000000);
			mSocket.setSendBufferSize(Protocol.SEND_BUFFER);
			mSocket.setReceiveBufferSize(Protocol.RECEIVE_BUFFER);
			mOut = new ObjectOutputStream(new BufferedOutputStream(
					mSocket.getOutputStream()));
		}

		private void createIn() throws IOException {
			if (mIn == null) {
				mIn = new ObjectInputStream(new BufferedInputStream(
						mSocket.getInputStream()));
			}
		}

		private void end() {
			mContext = null;
			try {
				if (mOut == null) {
					mSocket.getOutputStream().close();
				} else {
					mOut.close();
				}
				if (mIn == null) {
					mSocket.getInputStream().close();
				} else {
					mIn.close();
				}
				mSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

		}

		/**
		 * Invokes a method on a service running at the Cuckoo Server.
		 * 
		 * @param serviceName
		 *            the name of the service
		 * @param methodName
		 *            the name of the method
		 * @param parameterTypes
		 *            the types of the parameters
		 * @param parameters
		 *            the values of the parameters
		 * @return the return value of the method
		 * @throws Exception
		 *             if the method was not installed, not initialized
		 */
		private Object invokeMethod(final Context context,
				final List<Resource> others, final Statistics statistics,
				String serviceName, final String methodName,
				Class<?>[] parameterTypes, boolean[] outParameters,
				Object... parameters) throws Exception {
			long start = System.currentTimeMillis();
			Log.d(TAG, "invoking method '" + methodName + "' on service '"
					+ serviceName + "'");
			// long uploadBytes = TrafficStats.getUidTxBytes(mUid);
			long downloadBytes = TrafficStats.getUidRxBytes(mUid);
			mOut.write(Protocol.OPCODE_INVOKE);
			mOut.flush();
			mOut.writeUTF(serviceName);
			mOut.writeUTF(methodName);
			mOut.writeObject(parameterTypes);
			mOut.writeObject(outParameters);
			mOut.writeObject(parameters);
			mOut.writeBoolean(others.size() > 0);

			if (others.size() > 0) {
				String othersString = "";
				for (Resource other : others) {
					othersString += other.getHostname() + ":" + other.getPort()
							+ ",";
				}
				mOut.writeUTF(othersString.substring(0,
						othersString.length() - 1));
			}
			mOut.flush();
			statistics.uploadTime = System.currentTimeMillis() - start;
			start = System.currentTimeMillis();
			Log.d(TAG, "  written request (took: " + statistics.uploadTime
					+ " ms.)");
			startKeepWifiAwake(mResource.hostname);

			createIn();
			int resultCode = mIn.read();

			stopKeepWifiAwake();
			Log.d(TAG, "  result: " + Protocol.toString(resultCode));
			final long waitTime = System.currentTimeMillis() - start;
			start = System.currentTimeMillis();
			if (resultCode == Protocol.RESULT_OK) {
				// OK
				Object object = mIn.readObject();
				for (int i = 0; i < outParameters.length; i++) {
					if (outParameters[i]) {
						parameters[i] = mIn.readObject();
					}
				}
				statistics.executionTime = mIn.readLong();
				// The ObjectOutputStream and ObjectInputStream cache objects,
				// so only measure the upload the first time. We can come here a
				// second time, if the service was not installed or initialized
				// at the server.
				if (!trafficStatsSet) {
					statistics.uploadTime = Math.max(mIn.readLong(),
							statistics.uploadTime);
					final long done = System.currentTimeMillis();
					statistics.downloadTime = done - start;
					downloadBytes = TrafficStats.getUidRxBytes(mUid)
							- downloadBytes;
					// uploadBytes = TrafficStats.getUidTxBytes(mUid)
					// - uploadBytes;
					statistics.returnSize = downloadBytes;
					// statistics.inputSize = uploadBytes;
					trafficStatsSet = true;
				}
				statistics.rtt = mRTT;

				statistics.resource = mResource;
				// now read the other values from the message, they may not
				// have
				// been arrived, because execution on other resources takes
				// longer, therefore execute this in a separate thread
				new Thread() {
					public void run() {
						try {
							for (Resource other : others) {
								Statistics otherStatistics = new Statistics();
								otherStatistics.resource = other;
								otherStatistics.executionTime = mIn.readLong();
								otherStatistics.weight = statistics.weight;
								otherStatistics.inputSize = statistics.inputSize;
								otherStatistics.returnSize = statistics.returnSize;
								otherStatistics.downloadTime = -1;
								otherStatistics.uploadTime = -1;
								otherStatistics.rtt = -1;
								Oracle.storeStatistics(context, methodName,
										otherStatistics);
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
						end();
					}
				}.start();
				Log.d(TAG, "wait time: " + waitTime);
				return object;

			} else if (resultCode == Protocol.RESULT_EXCEPTION) {
				Exception e = (Exception) mIn.readObject();
				// if the service was not installed, we will install it,
				// initialize
				// it and invoke it again.

				if (e instanceof NotInstalledException) {
					installService(serviceName);
					initializeService(serviceName);
					return invokeMethod(context, others, statistics,
							serviceName, methodName, parameterTypes,
							outParameters, parameters);
				} else if (e instanceof NotInitializedException) {
					// if it's installed, but not initialized, we will
					// initialize it and invoke it again.
					initializeService(serviceName);
					return invokeMethod(context, others, statistics,
							serviceName, methodName, parameterTypes,
							outParameters, parameters);
				} else {
					// if we got another exception we will just throw it.
					throw e;
				}
			} else {
				throw new Exception("Invalid result after invoke(): "
						+ resultCode);
			}

		}

		private void register(String registrationId, String apiKey,
				CuckooPoller sensor, String id, String valuePath,
				Map<String, Object> configuration) throws Exception {
			Log.d(TAG, "register sensor '" + sensor.getClass().getName() + "'");
			mOut.write(Protocol.OPCODE_REGISTER_SENSOR);
			mOut.flush();
			mOut.writeUTF(sensor.getClass().getName());
			mOut.writeUTF(registrationId);
			mOut.writeUTF(apiKey);
			mOut.writeUTF(id);
			mOut.writeUTF(valuePath);
			mOut.writeObject(configuration);
			mOut.flush();
			createIn();
			int resultCode = mIn.read();
			if (resultCode == Protocol.RESULT_OK) {
				return;
			} else if (resultCode == Protocol.RESULT_EXCEPTION) {
				Exception e = (Exception) mIn.readObject();
				// if the sensor was not installed, we will install it,
				// initialize
				// it and invoke it again.

				if (e instanceof NotInstalledException) {
					installSensor(sensor);
					initializeSensor(sensor);
					register(registrationId, apiKey, sensor, id, valuePath,
							configuration);
				} else if (e instanceof NotInitializedException) {
					// if it's installed, but not initialized, we will
					// initialize it and invoke it again.
					initializeSensor(sensor);
					register(registrationId, apiKey, sensor, id, valuePath,
							configuration);
				} else {
					// if we got another exception we will just throw it.
					throw e;
				}
			} else {
				throw new Exception("Invalid result after register(): "
						+ resultCode);
			}
		}

		private void unregister(String id) throws Exception {
			Log.d(TAG, "unregister id '" + id + "'");
			mOut.write(Protocol.OPCODE_UNREGISTER_SENSOR);
			mOut.flush();
			mOut.writeUTF(id);
			mOut.flush();
			createIn();
			int resultCode = mIn.read();
			if (resultCode == Protocol.RESULT_OK) {
				return;
			} else if (resultCode == Protocol.RESULT_EXCEPTION) {
				Exception e = (Exception) mIn.readObject();
				throw e;
			} else {
				throw new Exception("Invalid result after register(): "
						+ resultCode);
			}
		}

		private void initializeSensor(CuckooPoller sensor) throws Exception {
			Log.d(TAG, "initializing sensor '" + sensor.getClass().getName()
					+ "'");
			mOut.write(Protocol.OPCODE_INITIALIZE_SENSOR);
			mOut.flush();
			mOut.writeUTF(sensor.getClass().getName());
			mOut.flush();
			createIn();
			int resultCode = mIn.read();
			Log.d(TAG, "  result: " + Protocol.toString(resultCode));
			if (resultCode == Protocol.RESULT_OK) {
				return;
			} else if (resultCode == Protocol.RESULT_EXCEPTION) {
				Exception e = (Exception) mIn.readObject();
				throw e;
			} else {
				throw new Exception("Invalid result after initializeSensor(): "
						+ resultCode);
			}
		}

		private void installSensor(CuckooPoller sensor) throws Exception {
			try {
				Log.d(TAG, "installing sensor '" + sensor.getClass().getName()
						+ "'");
				mOut.write(Protocol.OPCODE_INSTALL_SENSOR);
				mOut.flush();
				// send the class name
				mOut.writeUTF(sensor.getClass().getName());
				// send the class file, load it on the other side
				BufferedInputStream fileIn = new BufferedInputStream(mContext
						.getAssets().open(
								sensor.getClass().getSimpleName() + ".class"));
				int length = 0;
				int read = 0;
				final byte[] buf = new byte[128 * 1024];
				while ((read = fileIn.read(buf)) > 0) {
					length += read;
				}
				// and write the file size, so that on the other side we know
				// when to stop reading
				mOut.writeInt(length);
				fileIn.close();
				// reopen the file for copying to other side
				fileIn = new BufferedInputStream(mContext.getAssets().open(
						sensor.getClass().getSimpleName() + ".class"));
				while ((read = fileIn.read(buf)) > 0) {
					mOut.write(buf, 0, read);
				}
				fileIn.close();

				// jars
				int nrFiles = mContext.getAssets().list(
						sensor.getClass().getSimpleName()).length;
				Log.d(TAG, "  has " + nrFiles + " associated files");
				mOut.writeInt(nrFiles);
				// then for each file
				for (String fileName : mContext.getAssets().list(
						sensor.getClass().getSimpleName())) {
					// write the name
					mOut.writeUTF(fileName);
					// first read the file to find out the length of the file.
					// The
					// file might be compressed, so reading it is the only way
					// knowing the size of the file.
					final long start = System.currentTimeMillis();
					fileIn = new BufferedInputStream(mContext.getAssets().open(
							sensor.getClass().getSimpleName() + File.separator
									+ fileName));
					while ((read = fileIn.read(buf)) > 0) {
						length += read;
					}
					Log.d(TAG,
							"  reading file " + fileName + " for length took: "
									+ (System.currentTimeMillis() - start));
					// and write the file size, so that on the other side we
					// know
					// when to stop reading
					mOut.writeInt(length);
					fileIn.close();
					// reopen the file for copying to other side
					fileIn = new BufferedInputStream(mContext.getAssets().open(
							sensor.getClass().getSimpleName() + File.separator
									+ fileName));
					while ((read = fileIn.read(buf)) > 0) {
						mOut.write(buf, 0, read);
					}
					fileIn.close();
					Log.d(TAG, "  written file " + fileName + " (" + length
							+ " bytes)");
					mOut.flush();
				}

				mOut.flush();
				createIn();
				int resultCode = mIn.read();
				Log.d(TAG, "  result: " + Protocol.toString(resultCode));
				if (resultCode == Protocol.RESULT_OK) {
					return;
				} else if (resultCode == Protocol.RESULT_EXCEPTION) {
					Exception e = (Exception) mIn.readObject();
					throw e;
				} else {
					throw new Exception(
							"Invalid result after installSensor(): "
									+ resultCode);
				}
			} catch (FileNotFoundException e) {
				throw new RuntimeException(
						"File not found. Did you copy the Poller class to the assets directory?",
						e);
			}
		}

		/**
		 * Installs a service on the Cuckoo Server. Assumes that the service is
		 * available in the assets of the package.
		 * 
		 * @param serviceName
		 *            the name of the service
		 * @throws Exception
		 *             if no files were provided, if the service was already
		 *             installed, or if the installation failed otherwise
		 */
		private void installService(String serviceName) throws Exception {
			Log.d(TAG, "installing service '" + serviceName + "'");
			// write the install opcode
			mOut.write(Protocol.OPCODE_INSTALL);
			mOut.flush();
			// then the service name
			mOut.writeUTF(serviceName);
			// and the number of files associated to this service
			int nrFiles = mContext.getAssets().list(serviceName).length;
			Log.d(TAG, "  has " + nrFiles + " associated files");
			mOut.writeInt(nrFiles);
			// then for each file
			for (String fileName : mContext.getAssets().list(serviceName)) {
				// write the name
				mOut.writeUTF(fileName);
				// first read the file to find out the length of the file. The
				// file might be compressed, so reading it is the only way
				// knowing the size of the file.
				final long start = System.currentTimeMillis();
				BufferedInputStream fileIn = new BufferedInputStream(mContext
						.getAssets().open(
								serviceName + File.separator + fileName));
				int length = 0;
				int read = 0;
				final byte[] buf = new byte[128 * 1024];
				while ((read = fileIn.read(buf)) > 0) {
					length += read;
				}
				Log.d(TAG, "  reading file " + fileName + " for length took: "
						+ (System.currentTimeMillis() - start));
				// and write the file size, so that on the other side we know
				// when to stop reading
				mOut.writeInt(length);
				fileIn.close();
				// reopen the file for copying to other side
				fileIn = new BufferedInputStream(mContext.getAssets().open(
						serviceName + File.separator + fileName));
				while ((read = fileIn.read(buf)) > 0) {
					mOut.write(buf, 0, read);
				}
				fileIn.close();
				Log.d(TAG, "  written file " + fileName + " (" + length
						+ " bytes)");
				mOut.flush();
			}
			createIn();
			int resultCode = mIn.read();
			Log.d(TAG, "  result: " + Protocol.toString(resultCode));
			if (resultCode == Protocol.RESULT_OK) {
				return;
			} else if (resultCode == Protocol.RESULT_EXCEPTION) {
				Exception e = (Exception) mIn.readObject();
				throw e;
			} else {
				throw new Exception("Invalid result after install(): "
						+ resultCode);
			}
		}

		/**
		 * Initializes a service on the Cuckoo Server. This assumes that the
		 * service already has been installed.
		 * 
		 * @param serviceName
		 *            the name of the service
		 * @throws Exception
		 *             if the service is not installed or if the service is
		 *             already initialized.
		 */
		private void initializeService(String serviceName) throws Exception {
			Log.d(TAG, "initializing service '" + serviceName + "'");
			mOut.write(Protocol.OPCODE_INITIALIZE);
			mOut.flush();
			mOut.writeUTF(serviceName);
			mOut.flush();
			createIn();
			int resultCode = mIn.read();
			Log.d(TAG, "  result: " + Protocol.toString(resultCode));
			if (resultCode == Protocol.RESULT_OK) {
				return;
			} else if (resultCode == Protocol.RESULT_EXCEPTION) {
				Exception e = (Exception) mIn.readObject();
				throw e;
			} else {
				throw new Exception("Invalid result after initialize(): "
						+ resultCode);
			}
		}
	}

	/**
	 * Helper class to store server information (identifier and address) as
	 * retrieved from the database.
	 * 
	 * @author rkemp
	 * 
	 */
	public static class Resource implements Comparable<Resource> {

		private Location location;
		private String hostname;
		private int port;
		private float bandwidthUp;
		private float bandwidthDown;
		private float varianceUp;
		private float varianceDown;
		private String[] bssids;
		private double sortValue;

		public String getHostname() {
			return hostname;
		}

		public int getPort() {
			return port;
		}

		public Estimate estimateDownload(long outputSize) {
			Estimate estimate = new Estimate();
			estimate.average = (long) (outputSize / bandwidthUp);
			estimate.variance = (float) Math.pow(Math.sqrt(varianceUp)
					/ bandwidthUp * estimate.average, 2);
			return estimate;
		}

		public Estimate estimateUpload(long inputSize) {
			Estimate estimate = new Estimate();
			estimate.average = (long) (inputSize / bandwidthDown);
			estimate.variance = (float) Math.pow(Math.sqrt(varianceDown)
					/ bandwidthDown * estimate.average, 2);
			return estimate;
		}

		public String[] getBssids() {
			return bssids;
		}

		public Location getLocation() {
			return location;
		}

		public Resource() {
			this.hostname = "local";
			this.bssids = new String[] {};
		}

		Resource(String hostname, int port, float bandwidthUp,
				float varianceUp, float bandwidthDown, float varianceDown,
				String[] bssids, Location location) {
			this.port = port;
			this.hostname = hostname;
			this.bandwidthDown = bandwidthDown;
			this.varianceDown = varianceDown;
			this.bandwidthUp = bandwidthUp;
			this.varianceUp = varianceUp;
			this.bssids = bssids;
			this.location = location;
		}

		@Override
		public String toString() {
			return hostname + ", " + port;
		}

		public void setSortValue(double sortValue) {
			this.sortValue = sortValue;
		}

		@Override
		public int compareTo(Resource other) {
			return (int) (sortValue - other.sortValue);
		}

	}

	/**
	 * registers a SWAN sensor for communication offloading.
	 * 
	 * @param context
	 * @param resource
	 * @param registrationId
	 * @param apiKey
	 * @param sensor
	 * @param id
	 * @param valuePath
	 * @param configuration
	 * @throws NoResourceAvailableException
	 */
	public static void register(Context context, Resource resource,
			String registrationId, String apiKey, CuckooPoller sensor,
			String id, String valuePath, Map<String, Object> configuration)
			throws NoResourceAvailableException {
		try {
			Communicator communicator = new Communicator(context, resource);
			communicator.register(registrationId, apiKey, sensor, id,
					valuePath, configuration);
			communicator.end();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			e.printStackTrace(System.out);
			throw new NoResourceAvailableException();
		}
	}

	/**
	 * Unregisters a SWAN sensor for communication offloading.
	 * 
	 * @param context
	 * @param resource
	 * @param id
	 * @throws NoResourceAvailableException
	 */
	public static void unregister(Context context, Resource resource, String id)
			throws NoResourceAvailableException {
		try {
			Communicator communicator = new Communicator(context, resource);
			communicator.unregister(id);
			communicator.end();
		} catch (Exception e) {
			throw new NoResourceAvailableException();
		}
	}

	/**
	 * Test the server with a simple ping-pong test.
	 * 
	 * @param resource
	 *            the server to be tested
	 * @return true if the server responds with a pong message, false otherwise
	 */
	public static long[] debugServer(Resource resource, Statistics statistics,
			int inputSize, int outputSize, long sleepTime) {
		long[] result = new long[2];
		Oracle.executionTime = new Estimate();
		Oracle.executionTime.average = sleepTime;
		Oracle.executionTime.variance = (float) Math.pow(sleepTime * 0.1, 2);

		final long start = System.currentTimeMillis();
		System.out.println("start debug: " + start);
		result[0] = System.currentTimeMillis();
		try {
			Socket socket = new Socket(resource.getHostname(),
					resource.getPort());
			statistics.rtt = System.currentTimeMillis() - start;
			System.out.println("socket created: " + System.currentTimeMillis());
			socket.setKeepAlive(true);
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(1000000);
			String sendBuffer = System.getProperty(
					"interdroid.cuckoo.sendbuffer", "" + Protocol.SEND_BUFFER);
			socket.setSendBufferSize(Integer.parseInt(sendBuffer));
			socket.setReceiveBufferSize(Protocol.RECEIVE_BUFFER);
			statistics.localOverheadTime = System.currentTimeMillis() - start;
			int myUid = android.os.Process.myUid();
			long uploadBytes = TrafficStats.getUidTxBytes(myUid);
			long downloadBytes = TrafficStats.getUidRxBytes(myUid);
			System.out
					.println("start uploading: " + System.currentTimeMillis());
			ObjectOutputStream out = new ObjectOutputStream(
					new BufferedOutputStream(socket.getOutputStream()));
			out.write(Protocol.OPCODE_DEBUG);
			out.flush();
			out.writeLong(sleepTime);
			out.writeInt(outputSize);
			out.writeInt(inputSize);
			out.write(new byte[inputSize]);
			out.flush();
			System.out.println("done uploading: " + System.currentTimeMillis());
			statistics.inputSize = TrafficStats.getUidTxBytes(myUid)
					- uploadBytes;
			statistics.clientUploadTime = System.currentTimeMillis() - start
					- statistics.localOverheadTime;
			// read can return -1 in case of EOF
			startKeepWifiAwake(resource.hostname);
			ObjectInputStream in = new ObjectInputStream(
					new BufferedInputStream(socket.getInputStream()));
			int resultCode = in.read();
			stopKeepWifiAwake();
			System.out.println("started downloading: "
					+ System.currentTimeMillis());
			if (resultCode == Protocol.RESULT_OK) {
				long downloadStart = System.currentTimeMillis();
				in.readFully(new byte[outputSize]);
				System.out.println("done downloading: "
						+ System.currentTimeMillis());
				statistics.uploadTime = in.readLong();
				statistics.executionTime = in.readLong();
				statistics.returnSize = TrafficStats.getUidRxBytes(myUid)
						- downloadBytes;
				statistics.downloadTime = System.currentTimeMillis()
						- downloadStart;
				statistics.totalInvocationTime = System.currentTimeMillis()
						- start;
				in.close();
			} else {
				out.close();
				in.close();
				socket.close();
				throw new RuntimeException("wrong result: " + resultCode);
			}
			out.close();
			socket.close();
			result[1] = System.currentTimeMillis();
			System.out.println("stopped debug: " + System.currentTimeMillis());
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	/**
	 * Invoke a synchronous remote method. This method will block until the
	 * result is received.
	 * 
	 * @param context
	 *            the context
	 * @param serviceName
	 *            the service that should be run remotely
	 * @param methodName
	 *            the method that should be invoked
	 * @param parameterTypes
	 *            the parameter types of the method
	 * @param parameters
	 *            the parameter values for the method
	 * @return the result of the remote method invocation
	 * @throws NoResourceAvailableException
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public static Object invokeMethod(Context context, Statistics statistics,
			String serviceName, String methodName, Class<?>[] parameterTypes,
			boolean[] outParameters, Object[] parameters, String strategy,
			float weight, long inputSize, long outputSize, boolean screenOn)
			throws NoResourceAvailableException {
		Answer answer = Oracle.shouldOffload(context, strategy, serviceName
				+ "." + methodName, weight, inputSize, outputSize, screenOn);
		if (answer.isEmpty()) {
			throw new NoResourceAvailableException();
		}
		Cuckoo.strategy = strategy;
		statistics.inputSize = inputSize;
		// TODO: make this a configuration
		int MAX_TRIES = 5;
		for (int i = 0; i < MAX_TRIES; i++) {
			Resource resource;
			ArrayList<Resource> others = answer.getUnknownResources();
			if (i < answer.getOffloadResources().size()) {
				// try resource i + unknown resources
				resource = answer.getOffloadResources().get(i);
			} else if (i < answer.getOffloadResources().size()
					+ answer.getUnknownResources().size()) {
				// try unknown resource i + other unknown resources
				resource = answer.getUnknownResources().get(
						i - answer.getOffloadResources().size());
				others = ((ArrayList<Resource>) answer.getUnknownResources()
						.clone());
				others.remove(i);
			} else {
				break;
			}
			long start = System.currentTimeMillis();
			try {
				Communicator communicator = new Communicator(context, resource);
				statistics.localOverheadTime = System.currentTimeMillis()
						- start;
				Object result = communicator.invokeMethod(context, others,
						statistics, serviceName, methodName, parameterTypes,
						outParameters, parameters);
				communicator.end();
				statistics.totalInvocationTime = System.currentTimeMillis()
						- start;
				return result;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		Log.d(TAG,
				"None of the resources succeeded! Throwing No Resource Available");
		throw new NoResourceAvailableException();
	}

	private static Thread mWifiWakeThread;

	private static void startKeepWifiAwake(final String hostname) {
		String wifiWakeStrategy = System.getProperty(WIFI_WAKE_STRATEGY,
				WIFI_WAKE_STRATEGY_DEFAULT);
		Log.d(TAG, "Keeping WiFi awake with strategy: " + wifiWakeStrategy);
		// first check whether wifi is on
		if (ContextState.getNetworkInfo() != null
				&& ContextState.getNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI) {
			if (wifiWakeStrategy.equals(WIFI_WAKE_STRATEGY_DEFAULT)) {
				wifiWakeStrategy = (strategy.startsWith(Oracle.STRATEGY_ENERGY)) ? WIFI_WAKE_STRATEGY_SLEEP
						: WIFI_WAKE_STRATEGY_AWAKE;
			}

			if (!wifiWakeStrategy.equals(WIFI_WAKE_STRATEGY_SLEEP)) {
				final String currentWakeStrategy = wifiWakeStrategy;

				mWifiWakeThread = new Thread() {
					public void run() {
						try {
							InetAddress address = InetAddress
									.getByName(hostname);
							// if we should wake just in time, wake up exec time
							// - 2 * stdev
							if (currentWakeStrategy
									.equals(WIFI_WAKE_STRATEGY_JIT)) {
								// about 95% of the values lie within two
								// standard deviations (if normal distribution)
								long sleepTime = (long) (Oracle.executionTime.average - 2 * Math
										.sqrt(Oracle.executionTime.variance));
								Log.d(TAG, "Waiting for " + sleepTime
										+ " before waking WiFi");
								sleep(sleepTime);
							}
							final long PING_INTERVAL = 180;
							while (!isInterrupted()) {
								address.isReachable(200);
								sleep(PING_INTERVAL);
							}
						} catch (Throwable t) {
						}
					}
				};
				mWifiWakeThread.start();
			}
		}

	}

	private static void stopKeepWifiAwake() {
		if (mWifiWakeThread != null) {
			mWifiWakeThread.interrupt();
			mWifiWakeThread = null;
		}
	}

}
