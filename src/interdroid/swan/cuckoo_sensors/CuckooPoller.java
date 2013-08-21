package interdroid.swan.cuckoo_sensors;

import java.util.Map;

public interface CuckooPoller {

	public Map<String, Object> poll(String valuePath,
			Map<String, Object> configuration);

	public long getInterval(Map<String, Object> configuration, boolean remote);

}
