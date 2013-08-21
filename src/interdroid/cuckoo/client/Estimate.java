package interdroid.cuckoo.client;

public class Estimate {

	public long average;
	public float variance;

	public static Estimate combine(Estimate... estimates) {
		Estimate result = new Estimate();
		for (Estimate estimate : estimates) {
			result.average += estimate.average;
			result.variance += estimate.variance;
		}
		return result;
	}

	/**
	 * This method returns the minimum of two Estimates. This is a complex task
	 * and the implementation is simplified to select the one with the lowest
	 * average as result. Further discussion of computing the minimum of two
	 * distributions can be found at: www.untruth.org/~josh/math/normal-min.pdf
	 * 
	 * @param a
	 * @param b
	 * @return
	 */
	public static Estimate min(Estimate a, Estimate b) {
		return (a.average < b.average) ? a : b;
	}
	
	/**
	 * This method returns the minimum of two Estimates. This is a complex task
	 * and the implementation is simplified to select the one with the lowest
	 * average as result. Further discussion of computing the minimum of two
	 * distributions can be found at: www.untruth.org/~josh/math/normal-min.pdf
	 * 
	 * @param a
	 * @param b
	 * @return
	 */
	public static Estimate max(Estimate a, Estimate b) {
		return (a.average > b.average) ? a : b;
	}

	@Override
	public String toString() {
		return average + " (stdev: " + Math.sqrt(variance) + ", var: "
				+ variance + ")";
	}

}
