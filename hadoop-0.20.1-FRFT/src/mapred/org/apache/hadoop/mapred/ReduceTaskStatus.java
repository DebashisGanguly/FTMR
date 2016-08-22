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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;



class ReduceTaskStatus extends TaskStatus {
	private List<TaskAttemptID> failedFetchTasks = new ArrayList<TaskAttemptID>(1);
	private long shuffleStartTime, shuffleFinishTime;
	public ReduceTaskStatus() {}

	public ReduceTaskStatus(TaskAttemptID taskid, float progress, State runState,
			String diagnosticInfo, String stateString, String taskTracker,
			Phase phase, Counters counters) {
		super(taskid, progress, runState, diagnosticInfo, stateString, taskTracker, phase, counters);
	}

	@Override
	public Object clone() {
		ReduceTaskStatus myClone = (ReduceTaskStatus)super.clone();
		myClone.failedFetchTasks = new ArrayList<TaskAttemptID>(failedFetchTasks);
		return myClone;
	}

	@Override
	public boolean getIsMap() {
		return false;
	}

	@Override
	void setFinishTime(long finishTime) {
		super.setFinishTime(finishTime);
	}


	void setShuffleStartTime(long shuffleStartTime) {this.shuffleStartTime = shuffleStartTime;}
	public long getShuffleStartTime() { return shuffleStartTime; }

	void setShuffleFinishTime(long shuffleFinishTime) {this.shuffleFinishTime = shuffleFinishTime;}
	public long getShuffleFinishTime() { return super.getFinishTime(); }

	public long getSortFinishTime() { return super.getSortFinishTime(); }
	void setSortFinishTime(long sortFinishTime) { super.setSortFinishTime(sortFinishTime); }

	@Override
	public List<TaskAttemptID> getFetchFailedMaps() {
		return failedFetchTasks;
	}

	@Override
	void addFetchFailedMap(TaskAttemptID mapTaskId) {
		failedFetchTasks.add(mapTaskId);
	}

	@Override
	synchronized void statusUpdate(TaskStatus status) {
		super.statusUpdate(status);

		List<TaskAttemptID> newFetchFailedMaps = status.getFetchFailedMaps();
		if (failedFetchTasks == null) {
			failedFetchTasks = newFetchFailedMaps;
		} else if (newFetchFailedMaps != null){
			failedFetchTasks.addAll(newFetchFailedMaps);
		}
	}

	@Override
	synchronized void clearStatus() {
		super.clearStatus();
		failedFetchTasks.clear();
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		super.readFields(in);
		int noFailedFetchTasks = in.readInt();
		failedFetchTasks = new ArrayList<TaskAttemptID>(noFailedFetchTasks);
		shuffleStartTime = in.readLong();
		shuffleFinishTime = in.readLong();

		for (int i=0; i < noFailedFetchTasks; ++i) {
			TaskAttemptID id = new TaskAttemptID();
			id.readFields(in);
			failedFetchTasks.add(id);
		}
	}

	@Override
	public void write(DataOutput out) throws IOException {
		super.write(out);
		out.writeInt(failedFetchTasks.size());
		out.writeLong(shuffleStartTime);
		out.writeLong(shuffleFinishTime);

		for (TaskAttemptID taskId : failedFetchTasks) {
			taskId.write(out);
		}
	}
}
