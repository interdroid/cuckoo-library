package interdroid.cuckoo.client;

/**
 * Helper class used in the rewritten generated code.
 * 
 * @author rkemp
 * 
 */
public abstract class StatusThread extends Thread {

	boolean success = true;
	Object lock;
	public Statistics statistics = new Statistics();

	public StatusThread(Object lock) {
		this.lock = lock;
	}

	public boolean hasFailed() {
		return !success;
	}

	public void run() {
		success = invoke();
		synchronized (lock) {
			lock.notify();
		}
	}

	public abstract boolean invoke();

}
