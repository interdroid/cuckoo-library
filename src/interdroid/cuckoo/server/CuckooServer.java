package interdroid.cuckoo.server;

import interdroid.cuckoo.base.AlreadyInitializedException;
import interdroid.cuckoo.base.AlreadyInstalledException;
import interdroid.cuckoo.base.InstallationFailedException;
import interdroid.cuckoo.base.NotInitializedException;
import interdroid.cuckoo.base.NotInstalledException;
import interdroid.cuckoo.base.Protocol;
import interdroid.swan.cuckoo_sensors.CuckooPoller;
import interdroid.swan.cuckoo_sensors.RemoteMonitorThread;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Cuckoo Server is an application that should be run on a system, which
 * will be used to enhance computation on a smartphone. The Cuckoo Server will
 * start an Ibis Registry and wait for requests for remote service invocations.
 * 
 * Services can be installed onto a Cuckoo Server, installed services will be
 * stored locally on disk.
 * 
 * @author rkemp
 */
public class CuckooServer {

	private static final Logger logger = LoggerFactory
			.getLogger("interdroid.cuckoo.server");

	/**
	 * The directory where the Cuckoo Server stores installed services
	 */
	private final static String SERVICE_DIRECTORY_ROOT = "services";

	/**
	 * The directory where hte Cuckoo Server stores installed SWAN sensors
	 */
	private final static String SENSOR_DIRECTORY_ROOT = "sensors";

	/**
	 * The running invocations, we need to administrate these, because they
	 * might get canceled.
	 */
	private Map<String, Thread> mRunningInvocations = new HashMap<String, Thread>();

	/**
	 * List of installed services. If a service is not in this list, any
	 * invocation to that service will result in a {@link NotInstalledException}
	 */
	private List<String> mInstalledServices = new ArrayList<String>();

	/**
	 * Map of initialized services. If a service is installed, but not yet
	 * initialized, any invocation to that service will result in a
	 * {@link NotInitializedException}.
	 */
	private Map<String, Object> mInitializedServices = new HashMap<String, Object>();

	/**
	 * List of installed sensors. If a sensor is not in this list, any
	 * registration to that sensor will result in a
	 * {@link NotInstalledException}
	 */
	private List<String> mInstalledSensors = new ArrayList<String>();

	/**
	 * Map of initialized sensors. If a sensor is installed, but not yet
	 * initialized, any registration to that sensor will result in a
	 * {@link NotInitializedException}.
	 */
	private Map<String, CuckooPoller> mInitializedSensors = new HashMap<String, CuckooPoller>();

	/**
	 * Map of monitor threads, by id
	 */
	private Map<String, RemoteMonitorThread> monitors = new HashMap<String, RemoteMonitorThread>();

	/**
	 * global server properties
	 */
	private Properties properties = new Properties();

	/**
	 * The default port the server listens on
	 */
	private static final int PORT = 9000;

	/**
	 * Starts a new Cuckoo Server. Any arguments will be ignored.
	 * 
	 * @param args
	 *            will be ignored
	 */
	public static void main(String[] args) {
		new File(SERVICE_DIRECTORY_ROOT).mkdirs();
		try {
			new CuckooServer().startCuckooServer();
		} catch (Exception e) {
			System.err.println("Fatal exception in Cuckoo Server: "
					+ e.getMessage());
			e.printStackTrace(System.err);
		}
	}

	private boolean handleInstall(ObjectInputStream in, ObjectOutputStream out)
			throws IOException {
		// read the service name
		final String serviceName = in.readUTF();
		logger.debug("   installing service '" + serviceName + "'...");
		// then the files belonging to this service
		int nrFiles = in.readInt();
		logger.debug("     has " + nrFiles + " files");
		Map<String, byte[]> files = new HashMap<String, byte[]>();
		for (int i = 0; i < nrFiles; i++) {
			String fileName = in.readUTF();
			int size = in.readInt();
			logger.debug("       reading '" + fileName + "' of size " + size);
			byte[] file = new byte[size];
			in.readFully(file);
			files.put(fileName, file);
		}
		logger.debug("     done reading files");
		try {
			// now try to install the service
			logger.debug("     invoking installService");
			installService(serviceName, files);
			logger.debug("   installing service '" + serviceName
					+ "' succeeded");
			out.write(Protocol.RESULT_OK);
			out.flush();
			return false;
		} catch (Exception e) {
			// if something failed, write the exception into the
			// message.
			logger.debug("   installing service '" + serviceName + "' failed: "
					+ e);
			out.write(Protocol.RESULT_EXCEPTION);
			out.writeObject(e);
			out.flush();
			return true;
		}
	}

	private boolean handleInitialize(ObjectInputStream in,
			ObjectOutputStream out) throws IOException {
		// read the service name
		final String serviceName = in.readUTF();
		logger.debug("   initializing service '" + serviceName + "'...");
		try {
			// now try to initialize the service
			initializeService(serviceName);
			out.write(Protocol.RESULT_OK);
			out.flush();
			logger.debug("   initializing service '" + serviceName
					+ "' succeeded");
			return false;
		} catch (Exception e) {
			// if something failed, write the exception into the
			// message.
			logger.debug("   initializing service '" + serviceName
					+ "' failed: " + e);
			out.write(Protocol.RESULT_EXCEPTION);
			out.writeObject(e);
			out.flush();
			out.close();
			return true;
		}
	}

	private boolean handleInvoke(ObjectInputStream in, ObjectOutputStream out)
			throws IOException, ClassNotFoundException {
		final long start = System.currentTimeMillis();
		// read the service name
		final String serviceName = in.readUTF();
		logger.debug("   invoking method on service '" + serviceName + "'...");
		// the method name
		final String methodName = in.readUTF();
		logger.debug("     method: " + methodName);
		// read the parameter type array
		final Class<?>[] parameterTypes = (Class<?>[]) in.readObject();
		logger.debug("     parameter types: " + Arrays.toString(parameterTypes));
		// read the types of parameters array (in or out/inout)
		final boolean[] outParameters = (boolean[]) in.readObject();
		logger.debug("     out parameters: " + Arrays.toString(outParameters));
		// read the actual parameter values
		final Object[] parameters = (Object[]) in.readObject();
		logger.debug("     parameter values: " + Arrays.toString(parameters));
		// do we have to forward?
		final boolean forwardToUnknownResources = in.readBoolean();
		logger.debug("     forward to unknown resources: "
				+ forwardToUnknownResources);
		// read unknown resources
		final String[] unknownResources = (forwardToUnknownResources) ? in
				.readUTF().split(",") : null;
		logger.debug("       unknownResources: "
				+ (unknownResources == null ? "n.a." : Arrays
						.toString(unknownResources)));
		final long uploadTime = System.currentTimeMillis() - start;
		final long startMethod = System.currentTimeMillis();
		try {
			Object result = invokeMethod(serviceName, methodName,
					parameterTypes, parameters);
			final long executionTime = System.currentTimeMillis() - startMethod;
			logger.debug("     result: " + result);
			out.write(Protocol.RESULT_OK);
			out.flush();
			out.writeObject(result);
			for (int i = 0; i < outParameters.length; i++) {
				if (outParameters[i]) {
					out.writeObject(parameters[i]);
				}
			}
			out.writeLong(executionTime);
			out.writeLong(uploadTime);
			out.flush();
			logger.debug("   invoking method '" + methodName + "' on service '"
					+ serviceName + "' succeeded");
			logger.debug("     upload: " + uploadTime);
			logger.debug("     execution: " + executionTime);
			logger.debug("     download: "
					+ (System.currentTimeMillis() - startMethod - executionTime));
			return true;
		} catch (Throwable t) {
			logger.debug("   invoking method '" + methodName + "' on service '"
					+ serviceName + "' failed: " + t);
			out.write(Protocol.RESULT_EXCEPTION);
			out.writeObject(t);
			out.flush();
			if (t instanceof NotInstalledException
					|| t instanceof NotInitializedException) {
				// we keep the session, and expect the install or initialize
				// request.
				return false;
			} else {
				return true;
			}
		}

		// now forward to the others...
		// if (forwardToUnknownResources) {
		// for (String unknownResource : unknownResources) {
		// installService(unknownResource, serviceName);
		// initializeService(unknownResource, serviceName);
		// long remoteExecutionTime = invokeMethod(unknownResource, serviceName,
		// methodName, parameterTypes, outParameters, parameters);
		// out.writeLong(remoteExecutionTime);
		// }
		// }
	}

	private boolean handleCancel(Socket socket) {
		throw new RuntimeException("not implemented");
	}

	// TODO: check data input stream
	private void handleDebug(ObjectInputStream in, ObjectOutputStream out,
			long start) throws IOException {
		logger.debug("   invoking debug...");
		long sleepTime = in.readLong();
		logger.debug("     sleep: " + sleepTime);
		int returnSize = in.readInt();
		logger.debug("     return: " + returnSize);
		int inputSize = in.readInt();
		logger.debug("     input: " + inputSize);
		in.readFully(new byte[inputSize]);
		long uploadTime = System.currentTimeMillis() - start;
		// sleep
		try {
			Thread.sleep(sleepTime);
		} catch (InterruptedException e) {
		}
		long executionTime = System.currentTimeMillis() - start - uploadTime;
		// return
		out.write(Protocol.RESULT_OK);
		out.flush();
		out.write(new byte[returnSize]);
		out.writeLong(uploadTime);
		out.writeLong(executionTime);
		out.flush();
	}

	private boolean handleInstallSensor(ObjectInputStream in,
			ObjectOutputStream out) throws IOException {
		String sensorName = in.readUTF();
		logger.debug("   installing sensor '" + sensorName + "'...");
		int fileSize = in.readInt();
		logger.debug("       reading '" + sensorName + ".class' of size "
				+ fileSize);
		byte[] classFile = new byte[fileSize];
		in.readFully(classFile);
		// then the files belonging to this service
		int nrFiles = in.readInt();
		logger.debug("     has " + nrFiles + " jar files");
		Map<String, byte[]> files = new HashMap<String, byte[]>();
		for (int i = 0; i < nrFiles; i++) {
			String fileName = in.readUTF();
			int size = in.readInt();
			logger.debug("       reading '" + fileName + "' of size " + size);
			byte[] file = new byte[size];
			in.readFully(file);
			files.put(fileName, file);
		}
		logger.debug("     done reading files");

		try {
			// now try to install the service
			logger.debug("     invoking installSensor");
			installSensor(sensorName, classFile, files);
			logger.debug("   installing sensor '" + sensorName + "' succeeded");
			out.write(Protocol.RESULT_OK);
			out.flush();
			return false;
		} catch (Exception e) {
			// if something failed, write the exception into the
			// message.
			logger.debug("   installing sensor '" + sensorName + "' failed: "
					+ e);
			out.write(Protocol.RESULT_EXCEPTION);
			out.writeObject(e);
			out.flush();
			return true;
		}
	}

	private boolean handleInitializeSensor(ObjectInputStream in,
			ObjectOutputStream out) throws IOException {
		// read the sensor name
		final String sensorName = in.readUTF();
		logger.debug("   initializing sensor '" + sensorName + "'...");
		try {
			// now try to initialize the sensor
			initializeSensor(sensorName);
			out.write(Protocol.RESULT_OK);
			out.flush();
			logger.debug("   initializing sensor '" + sensorName
					+ "' succeeded");
			return false;
		} catch (Exception e) {
			// if something failed, write the exception into the
			// message.
			logger.debug("   initializing sensor '" + sensorName + "' failed: "
					+ e);
			out.write(Protocol.RESULT_EXCEPTION);
			out.writeObject(e);
			out.flush();
			out.close();
			return true;
		}
	}

	@SuppressWarnings("unchecked")
	private boolean handleRegisterSensor(ObjectInputStream in,
			ObjectOutputStream out) throws IOException {
		// read the sensor name
		final String sensorName = in.readUTF();
		logger.debug("   registering for sensor '" + sensorName + "'...");
		try {
			final String registrationId = in.readUTF();
			final String apiKey = in.readUTF();
			final String id = in.readUTF();
			final String valuePath = in.readUTF();
			final Map<String, Object> configAsMap = (Map<String, Object>) in
					.readObject();

			// now try to initialize the sensor
			if (!isSensorInstalled(sensorName)) {
				throw new NotInstalledException("Sensor '" + sensorName
						+ "' not installed.");
			}
			if (!isSensorInitialized(sensorName)) {
				throw new NotInitializedException("Sensor '" + sensorName
						+ "' not initialized.");
			}
			RemoteMonitorThread monitor = new RemoteMonitorThread(
					registrationId, apiKey,
					mInitializedSensors.get(sensorName), valuePath, configAsMap);
			monitor.start();
			monitors.put(id, monitor);
			out.write(Protocol.RESULT_OK);
			out.flush();
			logger.debug("   registering sensor '" + sensorName + "' succeeded");
			return true;
		} catch (Exception e) {
			// if something failed, write the exception into the
			// message.
			logger.debug("   registering sensor '" + sensorName + "' failed: "
					+ e);
			out.write(Protocol.RESULT_EXCEPTION);
			out.writeObject(e);
			out.flush();
			return false;
		}
	}

	private boolean handleUnregisterSensor(ObjectInputStream in,
			ObjectOutputStream out) throws IOException {
		final String id = in.readUTF();
		logger.debug("   unregistering for id '" + id + "'...");
		try {
			monitors.remove(id).interrupt();
			out.write(Protocol.RESULT_OK);
			out.flush();
			logger.debug("   unregistering id '" + id + "' succeeded");
			return true;
		} catch (Exception e) {
			// if something failed, write the exception into the
			// message.
			logger.debug("   unregistering id '" + id + "' failed: " + e);
			out.write(Protocol.RESULT_EXCEPTION);
			out.writeObject(e);
			out.flush();
			out.close();
			return true;
		}
	}

	public void startCuckooServer() throws Exception {
		ServerSocket serverSocket = new ServerSocket();
		// serverSocket.setPerformancePreferences(0, 1, 2);
		serverSocket.bind(new InetSocketAddress(PORT), 1);
		displayIbisIdentifier(PORT);
		logger.debug("start accepting...");
		while (true) {
			Socket socket = serverSocket.accept();
			socket.setSoTimeout(1000000);
			socket.setKeepAlive(true);
			socket.setTcpNoDelay(true);
			socket.setSendBufferSize(Protocol.SEND_BUFFER);
			socket.setReceiveBufferSize(1024 * 256);
			ObjectInputStream in = new ObjectInputStream(
					new BufferedInputStream(socket.getInputStream()));
			ObjectOutputStream out = new ObjectOutputStream(
					new BufferedOutputStream(socket.getOutputStream()));
			boolean sessionEnded = false;
			while (!sessionEnded) {
				int opcode = in.read();
				long start = System.currentTimeMillis();
				logger.debug("-> starting " + Protocol.toString(opcode));
				switch (opcode) {
				case Protocol.OPCODE_DEBUG:
					handleDebug(in, out, start);
					sessionEnded = true;
					break;
				case Protocol.OPCODE_INSTALL:
					sessionEnded = handleInstall(in, out);
					break;
				case Protocol.OPCODE_INITIALIZE:
					sessionEnded = handleInitialize(in, out);
					break;
				case Protocol.OPCODE_INVOKE:
					sessionEnded = handleInvoke(in, out);
					break;
				case Protocol.OPCODE_CANCEL:
					sessionEnded = handleCancel(socket);
					break;
				case Protocol.OPCODE_INSTALL_SENSOR:
					sessionEnded = handleInstallSensor(in, out);
					break;
				case Protocol.OPCODE_INITIALIZE_SENSOR:
					sessionEnded = handleInitializeSensor(in, out);
					break;
				case Protocol.OPCODE_REGISTER_SENSOR:
					sessionEnded = handleRegisterSensor(in, out);
					break;
				case Protocol.OPCODE_UNREGISTER_SENSOR:
					sessionEnded = handleUnregisterSensor(in, out);
					break;
				default:
					break;
				}
				logger.debug("   handling " + Protocol.toString(opcode)
						+ " took " + (System.currentTimeMillis() - start)
						+ " ms. " + (sessionEnded ? "ENDED" : "CONTINUING")
						+ "\n");
			}

			in.close();
			out.close();
			socket.close();
		}
	}

	private void displayIbisIdentifier(final int portNumber) {
		new Thread() {
			@SuppressWarnings("deprecation")
			public void run() {
				try {
					File propertyFile = new File("cuckoo.properties");
					if (propertyFile.exists()) {
						BufferedReader reader = new BufferedReader(
								new FileReader(propertyFile));
						String line = null;
						while ((line = reader.readLine()) != null) {
							if (line.contains("=")
									&& !line.trim().startsWith("#")) {
								String[] elements = line.split("=", 2);
								if (elements.length == 2) {
									properties.put(elements[0].trim(),
											elements[1].trim());
								}
							}
						}
						reader.close();
					}

					String hostname = InetAddress.getLocalHost()
							.getCanonicalHostName();
					String port = "" + portNumber;

					List<InetAddress> addrList = new ArrayList<InetAddress>();

					for (Enumeration<NetworkInterface> e = NetworkInterface
							.getNetworkInterfaces(); e.hasMoreElements();) {
						NetworkInterface ifc = e.nextElement();
						if (ifc.isUp()) {
							for (Enumeration<InetAddress> i = ifc
									.getInetAddresses(); i.hasMoreElements();) {
								addrList.add(i.nextElement());
							}
						}
					}
					String inetAddress = null;
					for (InetAddress address : addrList) {
						if (!address.isAnyLocalAddress()
								&& !address.isLinkLocalAddress()
								&& !address.isLoopbackAddress()
								&& !address.isSiteLocalAddress()
								&& !(address.getHostAddress().length() > 15)) {
							inetAddress = address.getHostAddress();
						}
					}
					if (inetAddress == null) {
						System.out
								.println("Failed to determine IP-address: checking property cuckoo.server.ipaddress");
						inetAddress = properties.getProperty(
								"cuckoo.server.ipaddress", null);
						if (inetAddress == null) {
							throw new Exception(
									"Failed to determine IP-address. No IP-address in cuckoo.server.ipaddress.");
						}
					}
					String bssids = properties.getProperty(
							"cuckoo.server.bssids", "");
					String upload = properties.getProperty(
							"cuckoo.server.upload", "-1");
					String uploadVariance = properties.getProperty(
							"cuckoo.server.upload.variance", "1");
					String download = properties.getProperty(
							"cuckoo.server.download", "-1");
					String downloadVariance = properties.getProperty(
							"cuckoo.server.download.variance", "1");
					String location = properties.getProperty(
							"cuckoo.server.location", "0,0");
					String separator = ";";

					String qrString = hostname + separator + bssids + separator
							+ upload + separator + uploadVariance + separator
							+ download + separator + downloadVariance
							+ separator + location + separator + inetAddress
							+ separator + port;

					URL googleChartService = new URL(
							"http://chart.apis.google.com/chart?" + "cht=qr"
									+ "&chs=350x350" + "&chl=" + qrString);
					System.out
							.println("Add server manually to Resource Manager with:\n---\n"
									+ qrString + "\n---");
					ImageIO.write(ImageIO.read(googleChartService
							.openConnection().getInputStream()), "png",
							new File("qr.png"));

					JOptionPane.showOptionDialog(
							new JFrame(),
							"Scan the QR code\nwith your phone",
							"Server started",
							JOptionPane.INFORMATION_MESSAGE,
							JOptionPane.YES_OPTION,
							new ImageIcon(ImageIO.read(googleChartService
									.openConnection().getInputStream())),
							new String[] { "Reset" }, "Reset");
					System.out.println("resetting!");
					for (String s : mRunningInvocations.keySet()) {
						mRunningInvocations.get(s).stop();
					}
					mRunningInvocations.clear();
					displayIbisIdentifier(portNumber);

				} catch (Exception e) {
					System.err.println("could not display the QR-code: "
							+ e.getMessage() + "\n");
					e.printStackTrace();

				}
			}
		}.start();
	}

	/**
	 * convenience method to retrieve the sensor directory for a given sensor
	 * name
	 * 
	 * @param sensorName
	 *            the sensor name
	 * @return the sensor directory
	 */
	private File getSensorDirectory(String sensorName) {
		return new File(SENSOR_DIRECTORY_ROOT + File.separator + sensorName);
	}

	/**
	 * install a sensor on the Cuckoo Server. This won't initialize the sensor,
	 * this needs to be done explicitly using {@link #initializeSensor(String)}.
	 * Registrations can only be done on installed initialized sensors.
	 * 
	 * @param sensorName
	 *            the name of the sensor
	 * @param sensor
	 *            the sensor .class file
	 * @param jars
	 *            the jars needed for this sensor
	 * @throws AlreadyInstalledException
	 *             if a sensor with this name is already installed for this
	 *             server
	 * @throws InstallationFailedException
	 *             if no class file is provided or the directory cannot be
	 *             created
	 */
	private synchronized void installSensor(String sensorName,
			byte[] sensorClassFile, Map<String, byte[]> jars)
			throws AlreadyInstalledException, InstallationFailedException {

		if (isSensorInstalled(sensorName)) {
			throw new AlreadyInstalledException("Sensor is already installed: "
					+ sensorName);
		} else {
			File sensorDirectory = getSensorDirectory(sensorName);

			// make the sensor directory
			if (!sensorDirectory.mkdirs()) {
				throw new InstallationFailedException(
						"Failed to create sensor directory: "
								+ sensorDirectory.getPath());
			}

			// write the class file to disk
			File classFile = new File(SENSOR_DIRECTORY_ROOT + File.separator
					+ sensorName.replace(".", File.separator) + ".class");
			classFile.getParentFile().mkdirs();
			try {
				classFile.createNewFile();
				FileOutputStream fileOut = new FileOutputStream(classFile);
				fileOut.write(sensorClassFile);
				fileOut.flush();
				fileOut.close();
			} catch (FileNotFoundException e) {
			} catch (IOException e) {
			}

			if (jars != null) {
				// write all the jars to disk
				for (String jar : jars.keySet()) {
					try {
						FileOutputStream out = new FileOutputStream(
								sensorDirectory + File.separator + jar);
						out.write(jars.get(jar));
						out.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
			// add this service to the internal administration
			mInstalledSensors.add(sensorName);
		}
	}

	/**
	 * initializes a sensor. After initialization the sensor is ready for
	 * registrations.
	 * 
	 * @param sensorName
	 *            the name of the sensor
	 * @throws NotInstalledException
	 *             if the sensor is not installed, the sensor has to be
	 *             installed before it can be initialized.
	 * @throws AlreadyInitializedException
	 *             if the sensor was already initialized
	 * @throws ClassNotFoundException
	 *             if the sensor cannot be found in the class file that is
	 *             provided during the installation
	 * @throws IllegalArgumentException
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 */
	private synchronized void initializeSensor(String sensorName)
			throws NotInstalledException, AlreadyInitializedException,
			ClassNotFoundException, IllegalArgumentException,
			InstantiationException, IllegalAccessException,
			InvocationTargetException, SecurityException, NoSuchMethodException {

		if (!isSensorInstalled(sensorName)) {
			throw new NotInstalledException("Sensor is not installed: "
					+ sensorName);
		}

		if (isSensorInitialized(sensorName)) {
			throw new AlreadyInitializedException(
					"Sensor is already initialized: " + sensorName);
		}

		// construct the list of URLs for the URLClassLoader
		List<URL> sensorURLs = new ArrayList<URL>();

		try {
			sensorURLs.add(new File(SENSOR_DIRECTORY_ROOT + File.separator)
					.toURI().toURL());
			sensorURLs.add(new File("android" + File.separator + "android.jar")
					.toURI().toURL());
		} catch (MalformedURLException e1) {
			// won't happen
		}

		for (File jarFile : getSensorDirectory(sensorName).listFiles()) {
			try {
				sensorURLs.add(jarFile.toURI().toURL());
			} catch (MalformedURLException e) {
				// ignore, should not happen
			}
		}

		// now try to find the class
		Class<?> sensorClass = Class.forName(sensorName, true,
				new URLClassLoader(sensorURLs.toArray(new URL[] {})));

		// and invoke the constructor
		@SuppressWarnings("rawtypes")
		Constructor constructor = sensorClass.getConstructor();

		// the resulting object is the sensor that needed to be initialized
		CuckooPoller sensor = (CuckooPoller) constructor.newInstance();

		// add this sensor to the internal administration
		mInitializedSensors.put(sensorName, sensor);
	}

	/**
	 * checks whether a sensor is initialized.
	 * 
	 * @param sensorName
	 *            the name of the service
	 * @return true if initialized, otherwise false
	 */
	private synchronized boolean isSensorInitialized(String sensorName) {
		return mInitializedSensors.containsKey(sensorName);
	}

	/**
	 * checks both disk and memory whether the sensor is installed.
	 * 
	 * @param sensorName
	 *            the name of the sensor
	 * @return true if the sensor is installed, false otherwise
	 */
	private synchronized boolean isSensorInstalled(String sensorName) {
		// check if it's in memory
		if (mInstalledSensors.contains(sensorName)) {
			return true;
		} else {
			if (new File(SENSOR_DIRECTORY_ROOT + File.separator
					+ sensorName.replace(".", File.separator) + ".class")
					.exists()) {
				mInstalledServices.add(sensorName);
				return true;
			}
		}
		// otherwise return false
		return false;
	}

	/**
	 * convenience method to retrieve the service directory for a given service
	 * name
	 * 
	 * @param serviceName
	 *            the service name
	 * @return the service directory
	 */
	private File getServiceDirectory(String serviceName) {
		return new File(SERVICE_DIRECTORY_ROOT + File.separator + serviceName);
	}

	/**
	 * install a service on the Cuckoo Server. This won't initialize the
	 * service, this needs to be done explicitly using
	 * {@link #initializeService(String)}. Invocations can only be done on
	 * installed initialized services.
	 * 
	 * @param serviceName
	 *            the name of the service
	 * @param jars
	 *            the jars needed for this service
	 * @throws AlreadyInstalledException
	 *             if a service with this name is already installed for this
	 *             server
	 * @throws InstallationFailedException
	 *             if no jar files are provided or the directory cannot be
	 *             created
	 */
	private synchronized void installService(String serviceName,
			Map<String, byte[]> jars) throws AlreadyInstalledException,
			InstallationFailedException {
		// TODO: add a variable to overwrite existing services?

		if (isInstalled(serviceName)) {
			throw new AlreadyInstalledException(
					"Service is already installed: " + serviceName);
		} else {
			if (jars == null || jars.size() == 0) {
				throw new InstallationFailedException(
						"No jar files provided for service: " + serviceName);
			}
			File serviceDirectory = getServiceDirectory(serviceName);

			// make the service directory
			if (!serviceDirectory.mkdirs()) {
				throw new InstallationFailedException(
						"Failed to create service directory: "
								+ serviceDirectory.getPath());
			}

			// write all the jars to disk
			for (String jar : jars.keySet()) {
				try {
					FileOutputStream out = new FileOutputStream(
							serviceDirectory + File.separator + jar);
					out.write(jars.get(jar));
					out.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			// add this service to the internal administration
			mInstalledServices.add(serviceName);
		}
	}

	/**
	 * initializes a service. After initialization the service is ready for
	 * invocations.
	 * 
	 * @param serviceName
	 *            the name of the service
	 * @throws NotInstalledException
	 *             if the service is not installed, the service has to be
	 *             installed before it can be initialized.
	 * @throws AlreadyInitializedException
	 *             if the service was already initialized
	 * @throws ClassNotFoundException
	 *             if the service cannot be found in the jars that were provided
	 *             during the installation
	 * @throws IllegalArgumentException
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 */
	private synchronized void initializeService(String serviceName)
			throws NotInstalledException, AlreadyInitializedException,
			ClassNotFoundException, IllegalArgumentException,
			InstantiationException, IllegalAccessException,
			InvocationTargetException, SecurityException, NoSuchMethodException {

		if (!isInstalled(serviceName)) {
			throw new NotInstalledException("Service is not installed: "
					+ serviceName);
		}

		if (isInitialized(serviceName)) {
			throw new AlreadyInitializedException(
					"Service is already initialized: " + serviceName);
		}

		// construct the list of URLs for the URLClassLoader
		List<URL> jarURLs = new ArrayList<URL>();
		try {
			jarURLs.add(new File("android" + File.separator + "android.jar")
					.toURI().toURL());
			for (File jarFile : getServiceDirectory(serviceName).listFiles()) {

				jarURLs.add(jarFile.toURI().toURL());
			}
		} catch (MalformedURLException e) {
			// ignore, should not happen
		}

		// now try to find the class
		Class<?> serviceClass = Class.forName(serviceName + "Impl", true,
				new URLClassLoader(jarURLs.toArray(new URL[] {})));

		// and invoke the constructor
		@SuppressWarnings("rawtypes")
		Constructor constructor = serviceClass.getConstructor();

		// the resulting object is the service that needed to be initialized
		Object service = constructor.newInstance();

		// add this service to the internal administration
		mInitializedServices.put(serviceName, service);
	}

	/**
	 * checks whether a service is initialized.
	 * 
	 * @param serviceName
	 *            the name of the service
	 * @return true if initialized, otherwise false
	 */
	private synchronized boolean isInitialized(String serviceName) {
		return mInitializedServices.containsKey(serviceName);
	}

	/**
	 * checks both disk and memory whether the service is installed.
	 * 
	 * @param serviceName
	 *            the name of the service
	 * @return true if the service is installed, false otherwise
	 */
	private synchronized boolean isInstalled(String serviceName) {
		// check if it's in memory
		if (mInstalledServices.contains(serviceName)) {
			return true;
		} else {
			// if not check whether it was installed earlier, check all
			// directories that exist in the service directory root
			for (File dir : new File(SERVICE_DIRECTORY_ROOT)
					.listFiles(new FileFilter() {

						@Override
						public boolean accept(File file) {
							return file.isDirectory();
						}

					})) {
				// if we have a matching directory return true
				if (dir.getName().equals(serviceName)) {
					mInstalledServices.add(serviceName);
					return true;
				}
			}
		}
		// otherwise return false
		return false;
	}

	/**
	 * Invokes a method on a service. The service needs to be installed and
	 * initialized.
	 * 
	 * @param serviceName
	 *            the name of the service
	 * @param methodName
	 *            the name of the method
	 * @param parameterTypes
	 *            the parameter types of the method
	 * @param parameters
	 *            the parameter values for the method
	 * @return the result of the method invocation
	 * @throws NotInstalledException
	 *             if the service is not installed
	 * @throws NotInitializedException
	 *             if the service is not initialized
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 *             if the method doesn't exist on this service
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 */
	private synchronized Object invokeMethod(String serviceName,
			String methodName, Class<?>[] parameterTypes, Object[] parameters)
			throws NotInstalledException, NotInitializedException,
			SecurityException, NoSuchMethodException, IllegalArgumentException,
			IllegalAccessException, InvocationTargetException {

		if (!isInstalled(serviceName)) {
			throw new NotInstalledException("Service is not installed: "
					+ serviceName);
		}

		if (!isInitialized(serviceName)) {
			throw new NotInitializedException("Service is not initialized: "
					+ serviceName);
		}

		// retrieve the service
		final Object service = mInitializedServices.get(serviceName);

		// then get the defined method
		Method method = service.getClass()
				.getMethod(methodName, parameterTypes);

		// and invoke this method
		Object result = method.invoke(service, parameters);

		// return the resulting object
		return result;
	}

}
