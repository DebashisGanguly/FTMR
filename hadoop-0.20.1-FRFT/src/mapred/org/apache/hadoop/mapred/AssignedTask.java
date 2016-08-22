package org.apache.hadoop.mapred;

import java.util.List;

public class AssignedTask {
	private Task task;
	private List<String> siblings;
	public AssignedTask(Task task, List<String> siblings) {
		this.task = task;
		this.siblings = siblings;
	}
	public Task getTask() {
		return task;
	}
	public List<String> getSiblings() {
		return siblings;
	}
}
