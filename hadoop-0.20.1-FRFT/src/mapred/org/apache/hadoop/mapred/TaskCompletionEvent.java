/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import static org.apache.hadoop.mapred.Util.getId;
import static org.apache.hadoop.mapred.Util.getReplicaNumber;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.log4j.Logger;

/**
 * This is used to track task completion events on job tracker. 
 */
public class TaskCompletionEvent 
implements Writable {
	Logger log = Logger.getLogger(TaskCompletionEvent.class);

	static public enum Status {FAILED, KILLED, SUCCEEDED, OBSOLETE, TIPFAILED};

	private int eventId; 
	private String taskTrackerHttp;
	private int taskRunTime; // using int since runtime is the time difference
	private TaskAttemptID taskId;
	private String[] digests = null;

	// Group has the purpose to identify
	// to which group belongs a taskId
	//private String group;

	Status status; 
	boolean isMap = false;
	private int idWithinJob, replicaWithinJob;

	public static final TaskCompletionEvent[] EMPTY_ARRAY = new TaskCompletionEvent[0];
	/**
	 * Default constructor for Writable.
	 *
	 */
	public TaskCompletionEvent(){
		taskId = new TaskAttemptID();
	}

	/**
	 * Constructor. eventId should be created externally and incremented
	 * per event for each job. 
	 * @param eventId event id, event id should be unique and assigned in
	 *  incrementally, starting from 0. 
	 * @param taskId task id
	 * @param status task's status 
	 * @param taskTrackerHttp task tracker's host:port for http. 
	 */
	public TaskCompletionEvent(int eventId, 
			TaskAttemptID taskId,
			boolean isMap,
			Status status, 
			String taskTrackerHttp,
			String[] digests,
			int numReplicas){

		this.taskId = taskId;
		this.idWithinJob = getId(taskId);//idWithinJob;
		this.replicaWithinJob = getReplicaNumber(taskId);//replicaWithinJob;
		this.isMap = isMap;
		this.eventId = eventId; 
		this.status =status; 
		this.taskTrackerHttp = taskTrackerHttp;
		this.digests = digests;
	}

	/**
	 * Constructor. eventId should be created externally and incremented
	 * per event for each job. 
	 * @param eventId event id, event id should be unique and assigned in
	 *  incrementally, starting from 0. 
	 * @param taskId task id
	 * @param status task's status 
	 * @param taskTrackerHttp task tracker's host:port for http. 
	 */
	public TaskCompletionEvent(int eventId, 
			TaskAttemptID taskId,
			boolean isMap,
			Status status, 
			String taskTrackerHttp,
			int numReplicas){

		this.taskId = taskId;
		this.idWithinJob = getId(taskId);//idWithinJob;
		this.replicaWithinJob = getReplicaNumber(taskId);//replicaWithinJob;
		this.isMap = isMap;
		this.eventId = eventId; 
		this.status =status; 
		this.taskTrackerHttp = taskTrackerHttp;
	}


	/**
	 * Returns event Id. 
	 * @return event id
	 */
	public int getEventId() {
		return eventId;
	}
	/**
	 * Returns task id. 
	 * @return task id
	 * @deprecated use {@link #getTaskAttemptId()} instead.
	 */
	@Deprecated
	public String getTaskId() {
		return taskId.toString();
	}

	/**
	 * Returns task id. 
	 * @return task id
	 */
	public TaskAttemptID getTaskAttemptId() {
		return taskId;
	}

	/**
	 * Returns enum Status.SUCESS or Status.FAILURE.
	 * @return task tracker status
	 */
	public Status getTaskStatus() {
		return status;
	}
	/**
	 * http location of the tasktracker where this task ran. 
	 * @return http location of tasktracker user logs
	 */
	public String getTaskTrackerHttp() {
		return taskTrackerHttp;
	}

	/**
	 * Returns time (in millisec) the task took to complete. 
	 */
	public int getTaskRunTime() {
		return taskRunTime;
	}

	/**
	 * Set the task completion time
	 * @param taskCompletionTime time (in millisec) the task took to complete
	 */
	public void setTaskRunTime(int taskCompletionTime) {
		this.taskRunTime = taskCompletionTime;
	}

	/**
	 * set event Id. should be assigned incrementally starting from 0. 
	 * @param eventId
	 */
	public void setEventId(
			int eventId) {
		this.eventId = eventId;
	}
	/**
	 * Sets task id. 
	 * @param taskId
	 * @deprecated use {@link #setTaskID(TaskAttemptID)} instead.
	 */
	@Deprecated
	public void setTaskId(String taskId) {
		this.taskId = TaskAttemptID.forName(taskId);
	}

	/**
	 * Sets task id. 
	 * @param taskId
	 */
	public void setTaskID(TaskAttemptID taskId) {
		this.taskId = taskId;
	}

	/**
	 * Set task status. 
	 * @param status
	 */
	public void setTaskStatus(
			Status status) {
		this.status = status;
	}
	/**
	 * Set task tracker http location. 
	 * @param taskTrackerHttp
	 */
	public void setTaskTrackerHttp(
			String taskTrackerHttp) {
		this.taskTrackerHttp = taskTrackerHttp;
	}

	@Override
	public String toString(){
		StringBuffer buf = new StringBuffer(); 
		buf.append("Task Id : "); 
		buf.append(taskId.toString()); 
		buf.append(", Status : ");  
		buf.append(status.name());

		return buf.toString();
	}

	@Override
	public boolean equals(Object o) {
		if(o == null)
			return false;
		if(o.getClass().equals(TaskCompletionEvent.class)) {
			TaskCompletionEvent event = (TaskCompletionEvent) o;
			boolean res = this.isMap == event.isMapTask() 
			&& this.eventId == event.getEventId()
			&& this.idWithinJob == event.idWithinJob()
			&& this.replicaWithinJob == event.replicaWithinJob()
			&& this.status.equals(event.getTaskStatus())
			&& this.taskId.equals(event.getTaskAttemptId()) 
			&& this.taskRunTime == event.getTaskRunTime()
			&& this.taskTrackerHttp.equals(event.getTaskTrackerHttp());

			return res;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return toString().hashCode(); 
	}

	public boolean isMapTask() {
		return isMap;
	}

	public int idWithinJob() {
		return idWithinJob;
	}

	public int replicaWithinJob() {
		return replicaWithinJob;
	}

	public String[] getDigests() {
		return digests;
	}

	//////////////////////////////////////////////
	// Writable
	//////////////////////////////////////////////
	public void write(DataOutput out) throws IOException {
		taskId.write(out);

		WritableUtils.writeVInt(out, idWithinJob);
		WritableUtils.writeVInt(out, replicaWithinJob);
		out.writeBoolean(isMap);
		WritableUtils.writeEnum(out, status); 
		WritableUtils.writeString(out, taskTrackerHttp);
		WritableUtils.writeVInt(out, taskRunTime);
		WritableUtils.writeVInt(out, eventId);

		if(digests != null) {
			WritableUtils.writeVInt(out, 1);
			WritableUtils.writeStringArray(out, digests);
		} 
		else 
			WritableUtils.writeVInt(out, 0);
	}

	public void readFields(DataInput in) throws IOException {
		/*
		 * TaskCompletionEvent info:
		 			TaskId: attempt_201005301215_0001_m_000001_0_m_0
					idWithinJob: 1
					isMap: true
					status: SUCCEEDED
					traskTrackerHttp: http://virtuakarmic:50060
					taskRunTime: 4679
					EventId: 2
		 */
		this.taskId.readFields(in); 

		this.idWithinJob = WritableUtils.readVInt(in);
		this.replicaWithinJob = WritableUtils.readVInt(in);
		this.isMap = in.readBoolean();
		this.status = WritableUtils.readEnum(in, Status.class);
		this.taskTrackerHttp = WritableUtils.readString(in);
		this.taskRunTime = WritableUtils.readVInt(in);
		this.eventId = WritableUtils.readVInt(in);

		int n = WritableUtils.readVInt(in);
		if(n == 1)
			this.digests = WritableUtils.readStringArray(in);
	}
}
