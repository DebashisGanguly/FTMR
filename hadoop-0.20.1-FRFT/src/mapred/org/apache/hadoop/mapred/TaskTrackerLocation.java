package org.apache.hadoop.mapred;


/*******************************************************
 * Location of the TaskTracker
 *******************************************************/
public class TaskTrackerLocation {
	private int httpPort;
	private String taskTrackerName;
	private String localHostname;
	private TaskAttemptID taskAttemptId;

	public int getHttpPort() {
		return httpPort;
	}

	public String getTaskTrackerName() {
		return taskTrackerName;
	}

	public String getLocalHostname() {
		return localHostname;
	}

	public TaskAttemptID getTaskAttemptId() {
		return taskAttemptId;
	}

	public TaskTrackerLocation(String trackerName, String host, int port, TaskAttemptID taskAttemptId) {
		this.taskTrackerName = trackerName;
		this.localHostname = host;
		this.httpPort = port;
		this.taskAttemptId = taskAttemptId;
	}
}
