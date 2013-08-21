package interdroid.cuckoo.base;

/**
 * This file specifies the protocol that is used between the Cuckoo client and
 * the server
 * 
 * @author rkemp
 */
public class Protocol {

	/**
	 * Operation codes
	 */
	public static final int OPCODE_DEBUG = 1;
	public static final int OPCODE_INSTALL = 2;
	public static final int OPCODE_INITIALIZE = 3;
	public static final int OPCODE_INVOKE = 4;
	public static final int OPCODE_CANCEL = 5;
	public static final int OPCODE_INSTALL_SENSOR = 6;
	public static final int OPCODE_INITIALIZE_SENSOR = 7;
	public static final int OPCODE_REGISTER_SENSOR = 8;
	public static final int OPCODE_UNREGISTER_SENSOR = 9;

	/**
	 * Result codes
	 */
	public static final int RESULT_OK = 100;
	public static final int RESULT_EXCEPTION = 101;

	/**
	 * Default send and receive buffer sizes
	 */
	public static final int SEND_BUFFER = 1024 * 1024;
	public static final int RECEIVE_BUFFER = 1024 * 1024;

	/**
	 * Convenience method to convert a protocol code into a human readable
	 * string
	 * 
	 * @param code
	 * @return
	 */

	public static String toString(int code) {
		switch (code) {
		case OPCODE_DEBUG:
			return "debug";
		case OPCODE_INSTALL:
			return "install";
		case OPCODE_INITIALIZE:
			return "initialize";
		case OPCODE_INVOKE:
			return "invoke";
		case OPCODE_CANCEL:
			return "cancel";
		case RESULT_OK:
			return "OK";
		case RESULT_EXCEPTION:
			return "EXCEPTION";
		case OPCODE_INITIALIZE_SENSOR:
			return "INITIALIZE SENSOR";
		case OPCODE_INSTALL_SENSOR:
			return "INSTALL SENSOR";
		case OPCODE_REGISTER_SENSOR:
			return "REGISTER SENSOR";
		case OPCODE_UNREGISTER_SENSOR:
			return "UNREGISTER SENSOR";
		default:
			return "unknown code: " + code;
		}
	}
}
