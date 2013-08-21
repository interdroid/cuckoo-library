package interdroid.cuckoo.client;

import interdroid.cuckoo.client.Cuckoo.Resource;

public class Statistics {

	public Resource resource; // the unique identifier of the server

	// the weight
	public double weight;

	// execution time of the method
	public long executionTime;

	// the upload time
	public long uploadTime;

	// the client upload time (just for debugging purposes)
	public long clientUploadTime;

	// the download time
	public long downloadTime;

	// the input size (in bytes)
	public long inputSize;

	// the output size (in bytes)
	public long returnSize;

	// local overhead for setting up the connection, this is likely to be
	// related to the rtt
	public long localOverheadTime;

	// the round trip time
	public long rtt;

	// the invocation time measured by the client
	public long totalInvocationTime;

	public String toString() {
		if (resource.getHostname().equals("local")) {
			return "local: \t\t\t" + executionTime;
		}
		return "total: \t\t\t"
				+ totalInvocationTime
				+ "\n local overhead: \t"
				+ localOverheadTime
				+ "\n upload time: \t\t"
				+ uploadTime
				+ " ("
				+ inputSize
				+ " bytes)\n execution time: \t"
				+ executionTime
				+ "\n download time: \t"
				+ downloadTime
				+ " ("
				+ returnSize
				+ " bytes)\n rtt: \t\t\t\t"
				+ rtt
				+ "\n delta: \t\t\t"
				+ (totalInvocationTime - uploadTime - executionTime
						- downloadTime - localOverheadTime - rtt);
	}

}
