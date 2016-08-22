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

import java.io.IOException;
import java.util.Collection;

/**
 * Manages information about the {@link TaskTracker}s running on a cluster.
 * This interface exits primarily to test the {@link JobTracker}, and is not
 * intended to be implemented by users.
 */
interface TaskTrackerManager {

  /**
   * @return A collection of the {@link TaskTrackerStatus} for the tasktrackers
   * being managed.
   */
  Collection<TaskTrackerStatus> taskTrackers();
  
  /**
   * @return The number of unique hosts running tasktrackers.
   */
  int getNumberOfUniqueHosts();
  
  /**
   * @return a summary of the cluster's status.
   */
  ClusterStatus getClusterStatus();

  /**
   * Registers a {@link JobInProgressListener} for updates from this
   * {@link TaskTrackerManager}.
   * @param jobInProgressListener the {@link JobInProgressListener} to add
   */
  void addJobInProgressListener(JobInProgressListener listener);

  /**
   * Unregisters a {@link JobInProgressListener} from this
   * {@link TaskTrackerManager}.
   * @param jobInProgressListener the {@link JobInProgressListener} to remove
   */
  void removeJobInProgressListener(JobInProgressListener listener);

  /**
   * Return the {@link QueueManager} which manages the queues in this
   * {@link TaskTrackerManager}.
   *
   * @return the {@link QueueManager}
   */
  QueueManager getQueueManager();
  
  /**
   * Return the current heartbeat interval that's used by {@link TaskTracker}s.
   *
   * @return the heartbeat interval used by {@link TaskTracker}s
   */
  int getNextHeartbeatInterval();

  /**
   * Kill the job identified by jobid
   * 
   * @param jobid
   * @throws IOException
   */
  void killJob(JobID jobid)
      throws IOException;

  /**
   * Obtain the job object identified by jobid
   * 
   * @param jobid
   * @return jobInProgress object
   */
  JobInProgress getJob(JobID jobid);
  
  /**
   * Initialize the Job
   * 
   * @param job JobInProgress object
   */
  void initJob(JobInProgress job);
  
  /**
   * Fail a job.
   * 
   * @param job JobInProgress object
   */
  void failJob(JobInProgress job);
}
