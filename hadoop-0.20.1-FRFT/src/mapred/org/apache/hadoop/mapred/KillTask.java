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

import org.apache.hadoop.mapred.TaskTracker.TaskInProgress;

/** A Map task. */
class KillTask extends Task {
	private TaskAttemptID taskAttemptId;

	{   // set phase for this task
		setPhase(TaskStatus.Phase.CLEANUP); 
	}

	public KillTask() {
		super();
	}


	public KillTask(TaskAttemptID taskAttemptId) {
		this.taskAttemptId = taskAttemptId ;
	}

	public TaskAttemptID getTask() {
		return taskAttemptId;
	}

	@Override
	public void write(DataOutput out) 
	throws IOException {
		out.writeUTF(this.taskAttemptId.toString());
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		this.taskAttemptId = TaskAttemptID.forName(in.readUTF());
	}


	@Override
	public TaskRunner createRunner(TaskTracker tracker, TaskInProgress tip)
	throws IOException {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public boolean isMapTask() {
		return this.taskAttemptId != null ? this.taskAttemptId.isMap() : false;
	}


	@Override
	public void run(JobConf job, TaskUmbilicalProtocol umbilical)
	throws IOException, ClassNotFoundException, InterruptedException {
		// TODO Auto-generated method stub

	}
}
