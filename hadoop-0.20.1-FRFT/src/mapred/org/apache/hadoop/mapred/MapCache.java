package org.apache.hadoop.mapred;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This threads keeps all TaskEvent from the map tasks that ended successfully.
 * It's a temp thread before sending the TaskEvent to the reduce side
 *
 */
public class MapCache {
	public Map<String, List<TaskCompletionEvent>> cache = new HashMap<String, List<TaskCompletionEvent>>();
	public final int threshold; // this variable tells which is the limit of TaskCompletionEvent, a taskid must receive before notice the RT.
	static final Log LOG = LogFactory.getLog(MapCache.class);

	public MapCache(int threshold) {
		this.threshold = threshold;
	}

	public void add(String taskIdWithoutReplica, TaskCompletionEvent event) {
		if(!cache.containsKey(taskIdWithoutReplica)) {
			List<TaskCompletionEvent> temp = new ArrayList<TaskCompletionEvent>();
			temp.add(event);
			cache.put(taskIdWithoutReplica, temp);
			return;
		}

		List<TaskCompletionEvent> temp = cache.get(taskIdWithoutReplica);
		temp.add(event);
	}

	public List<TaskCompletionEvent> remove(String taskIdWithoutReplica) {
		return cache.remove(taskIdWithoutReplica);
	}

	public List<TaskCompletionEvent> get(String taskIdWithoutReplica) {
		return cache.get(taskIdWithoutReplica);
	}

	public TaskCompletionEvent getValue(String taskIdWithoutReplica, TaskID tid) {
		if(!contains(taskIdWithoutReplica))
			return null;

		List<TaskCompletionEvent> values  = get(taskIdWithoutReplica);

		for(TaskCompletionEvent ev : values) {
			if(ev.getTaskAttemptId().getTaskID().equals(tid))
				return ev;
		}

		return null;
	}

	/**
	 * iterates through the cache to get elements
	 * that achieved the threshold 
	 * @return
	 */
	public String check(String key) {
		LOG.debug("For key " + key + " size is " + cache.get(key).size());
		if(cache.get(key).size() >= threshold) {
			return key;
		}

		return null;
	}

	public boolean contains(String key) {
		return cache.containsKey(key);
	}
}
