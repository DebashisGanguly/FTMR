package org.apache.hadoop.mapred;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Count number of tasks launched 
 * @deprecated
 */
public class TaskMap {
	private Map<String, List<TaskID>> trackerToTaskId = new HashMap<String, List<TaskID>>();


	public void addtask(String taskTrackerName, TaskID taskId) {
		if(!trackerToTaskId.containsKey(taskTrackerName)) {
			trackerToTaskId.put(taskTrackerName, new ArrayList<TaskID>());
		}

		List<TaskID> taskIdList = trackerToTaskId.get(taskTrackerName);
		taskIdList.add(taskId);
		trackerToTaskId.put(taskTrackerName, taskIdList);
	}

	public void removetask(String taskTrackerName, TaskID taskId) {
		if(!trackerToTaskId.containsKey(taskTrackerName)) {
			return;
		}

		List<TaskID> taskIdList = trackerToTaskId.get(taskTrackerName);
		taskIdList.remove(taskId);
	}


	public boolean contains(String taskTrackerName, TaskID taskId) {
		if(!trackerToTaskId.containsKey(taskTrackerName)) {
			return false;
		}

		List<TaskID> taskIdList = trackerToTaskId.get(taskTrackerName);
		for(TaskID tid : taskIdList) {
			if(tid.toStringWithoutReplica().equals(taskId.toStringWithoutReplica()))
				return true;
		}

		return false;
	}
}
