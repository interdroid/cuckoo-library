package interdroid.cuckoo.client;

import interdroid.cuckoo.client.Cuckoo.Resource;

import java.util.ArrayList;

/**
 * Answer object that can be returned by the
 * {@link Oracle#shouldOffload(android.content.Context, String, String, float, long, long, boolean)}
 * method. An answer contains two lists with resources. One list with resources
 * which are considered to be beneficial for offloading by the Oracle. The other
 * list contains resources for which it is unknown whether offloading is
 * beneficial.
 * 
 * @author rkemp
 * 
 */
public class Answer {

	private ArrayList<Resource> offloadResources = new ArrayList<Cuckoo.Resource>();
	private ArrayList<Resource> unknownResources = new ArrayList<Cuckoo.Resource>();

	/**
	 * Returns the list of {@link Resource}s for which the Oracle considers offloading to be beneficial.
	 * @return the list of {@link Resource}s for which the Oracle considers offloading to be beneficial.
	 */
	public ArrayList<Resource> getOffloadResources() {
		return offloadResources;
	}

	/**
	 * Returns the list of {@link Resource}s for which it is unknown whether offloading is beneficial.
	 * @return the list of {@link Resource}s for which it is unknown whether offloading is beneficial.
	 */
	public ArrayList<Resource> getUnknownResources() {
		return unknownResources;
	}

	/**
	 * Returns true if both {@link #getOffloadResources()} and {@link #getUnknownResources()} are empty.
	 * @return
	 */
	public boolean isEmpty() {
		return offloadResources.isEmpty() && unknownResources.isEmpty();
	}

	
	public String toString() {
		return "offload resources: " + offloadResources.size()
				+ ", unknown resources: " + unknownResources.size();
	}

}
