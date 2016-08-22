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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobHistory.Values;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsRecord;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.util.StringUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/*************************************************************
 * JobInProgress maintains all the info for keeping
 * a Job on the straight and narrow.  It keeps its JobProfile
 * and its latest JobStatus, plus a set of tables for 
 * doing bookkeeping of its Tasks.
 * ***********************************************************
 */
class JobInProgress {
    static final Log LOG = LogFactory.getLog(JobInProgress.class);
    private static final int NOTUSED = 0;
    /**
     * A special value indicating that
     * {@link #findNewMapTask(TaskTrackerStatus, int, int, int, double)} should
     * schedule any only off-switch and speculative map tasks for this job.
     */
    private static final int NON_LOCAL_CACHE_LEVEL = -1;
    // The maximum percentage of trackers in cluster added to the 'blacklist'.
    private static final double CLUSTER_BLACKLIST_PERCENT = 0.25;
    // The maximum percentage of fetch failures allowed for a map
    private static final double MAX_ALLOWED_FETCH_FAILURES_PERCENT = 0.5;
    // Maximum no. of fetch-failure notifications after which
    // the map task is killed
    private static final int MAX_FETCH_FAILURES_NOTIFICATIONS = 3;
    final JobTracker jobtracker;
    private final int maxLevel;
    /**
     * A special value indicating that
     * {@link #findNewMapTask(TaskTrackerStatus, int, int, int, double)} should
     * schedule any available map tasks for this job, including speculative tasks.
     */
    private final int anyCacheLevel;
    // Indicates how many times the job got restarted
    private final int restartCount;
    JobProfile profile;
    JobStatus status;
    Path jobFile = null;
    Path localJobFile = null;
    Path localJarFile = null;
    TaskInProgress maps[] 		= new TaskInProgress[0];
    TaskInProgress reduces[] 	= new TaskInProgress[0];
    TaskInProgress cleanup[] 	= new TaskInProgress[0];
    TaskInProgress setup[] 		= new TaskInProgress[0];
    Map<String, Integer> mapCount =  new HashMap<String, Integer>();
    Map<String, Integer> redCount =  new HashMap<String, Integer>();
    List<TaskTrackerLocation> mapTrackerLocation = new ArrayList<TaskTrackerLocation>();
    List<TaskTrackerLocation> redTrackerLocation = new ArrayList<TaskTrackerLocation>();
    MapLauncherController launcher;
    // runningMapTasks include speculative tasks, so we need to capture
    // speculative tasks separately
    int speculativeMapTasks 	= 0;
    int speculativeReduceTasks 	= 0;
    int mapFailuresPercent 		= 0;
    int reduceFailuresPercent 	= 0;
    JobPriority priority = JobPriority.NORMAL;
    long startTime;
    long launchTime;
    long finishTime;
    AtomicBoolean tasksInited = new AtomicBoolean(false);

    //    private static final float DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART = 0.05f;
    //    int completedMapsForReduceSlowstart = 0;
    //task -> replica number
    private VotingSystem voting;
    private int tamperedMap = 0;
    private int tamperedReduce = 0;
    private boolean[] map_voters;// array of values of the majority voting results.
    private boolean[] reduce_voters;// array of values of the majority voting results.
    private int numMapTasks 			= 0;
    private int replicatedNumMapTasks 	= 0;
    private int numReduceTasks 			= 0;
    private int replicatedNumReduceTasks = 0;
    private int numReplicas;
    // Counters to track currently running/finished/failed Map/Reduce task-attempts
    private int runningMapTasks 	= 0;
    private int runningReduceTasks 	= 0;
    private int finishedMapTasks 	= 0;
    private int finishedReduceTasks = 0;
    private int failedMapTasks 		= 0;
    private int failedReduceTasks 	= 0;
    private int failedMapTIPs 	= 0;
    private int failedReduceTIPs = 0;
    private volatile boolean launchedCleanup 	= false;
    private volatile boolean launchedSetup 		= false;
    private volatile boolean jobKilled 			= false;
    private volatile boolean jobFailed 			= false;
    // NetworkTopology Node to the set of TIPs
    private Map<Node, List<TaskInProgress>> nonRunningMapCache;
    // Map of NetworkTopology Node to set of running TIPs
    private Map<Node, Set<TaskInProgress>> runningMapCache;
    // A list of non-local non-running maps
    private List<TaskInProgress> nonLocalMaps;
    // A set of non-local running maps
    private Set<TaskInProgress> nonLocalRunningMaps;
    // A list of non-running reduce TIPs
    private List<TaskInProgress> nonRunningReduces;
    private List<TaskID> urgentMapsToRun;
    // A set of running reduce TIPs
    private Set<TaskInProgress> runningReduces;
    // A set of back running reduce TIPs
    private Set<TaskInProgress> backuprunningReduces;
    // A list of cleanup tasks for the map task attempts, to be launched
    private List<TaskAttemptID> mapCleanupTasks = new LinkedList<TaskAttemptID>();
    // A list of cleanup tasks for the reduce task attempts, to be launched
    private List<TaskAttemptID> reduceCleanupTasks = new LinkedList<TaskAttemptID>();
    // count the number of tasks launched
    private TaskCounter mapTaskcounter;
    private TaskCounter redTaskcounter;
    private TaskCounter summapTaskcounter;
    private TaskCounter sumredTaskcounter;
    private int taskCompletionEventTracker;
    // count number of maptasks that ended
    // used for tentative execution
    private int[] mapTaskCompletionEventTracker;
    private List<TaskCompletionEvent> taskCompletionEvents;
    // No. of tasktrackers in the cluster
    private volatile int clusterSize = 0;
    // The no. of tasktrackers where >= conf.getMaxTaskFailuresPerTracker()
    // tasks have failed
    private volatile int flakyTaskTrackers = 0;
    // Map of trackerHostName -> no. of task failures
    private Map<String, Integer> trackerToFailuresMap = new TreeMap<String, Integer>();
    //Confine estimation algorithms to an "oracle" class that JIP queries.
    private ResourceEstimator resourceEstimator;
    private JobConf conf;
    private JobInitKillStatus jobInitKillStatus = new JobInitKillStatus();

    private LocalFileSystem localFs;
    private JobID 	jobId;
    private boolean hasSpeculativeMaps;
    private boolean hasSpeculativeReduces;
    private long 	inputLength = 0;

    private JobEndProcess endProcess;
    private Counters jobCounters = new Counters();
    private MetricsRecord jobMetrics;
    // Map of mapTaskId -> no. of fetch failures
    private Map<TaskAttemptID, Integer> mapTaskIdToFetchFailuresMap = new TreeMap<TaskAttemptID, Integer>();
    private Object schedulingInfo;
    /**
     * A taskid assigned to this JobInProgress has reported in successfully.
     */
    private int majority_count = 0;
    /**
     * Thread that is constantly checking when the job ended successfully or with errors.
     */
    private boolean more_tasks = false;

    /**
     * Create an almost empty JobInProgress, which can be used only for tests
     */
    protected JobInProgress(JobID jobid, JobConf conf) {
        this.conf = conf;
        this.jobId = jobid;
        this.numMapTasks    = conf.getNumMapTasks();
        this.numReduceTasks = conf.getNumReduceTasks();
        this.numReplicas    = conf.getFaultTolerance();
        this.replicatedNumMapTasks      = this.numReplicas * this.numMapTasks;
        this.replicatedNumReduceTasks   = this.numReplicas * this.numReduceTasks;
        this.maxLevel = NetworkTopology.DEFAULT_HOST_LEVEL;
        this.anyCacheLevel  = this.maxLevel+1;
        this.jobtracker     = null;
        this.restartCount   = 0;
        this.urgentMapsToRun = new ArrayList<TaskID>();
        mapTaskcounter = new TaskCounter(replicatedNumMapTasks, numReplicas);
        redTaskcounter = new TaskCounter(replicatedNumReduceTasks, numReplicas);
        summapTaskcounter = new TaskCounter(replicatedNumMapTasks, numReplicas);
        sumredTaskcounter = new TaskCounter(replicatedNumReduceTasks, numReplicas);
        launcher = new MapLauncherController();

        voting          = new MajorityVoting(numReplicas, numReduceTasks);
        map_voters      = new boolean[numMapTasks];
        reduce_voters   = new boolean[numReduceTasks];

        endProcess      = new JobEndProcess();
    }

    /**
     * Create a JobInProgress with the given job file, plus a handle
     * to the tracker.
     */
    public JobInProgress(JobID jobid, JobTracker jobtracker, 
            JobConf default_conf) throws IOException {
        this(jobid, jobtracker, default_conf, 0);
    }

    /**
     * Initialize JobInProgress variables
     *
     * @param jobid
     * @param jobtracker
     * @param default_conf
     * @param rCount
     *
     * @throws IOException
     */
    public JobInProgress(JobID jobid, JobTracker jobtracker, JobConf default_conf, int rCount)
            throws IOException {
        this.restartCount = rCount;
        this.jobId = jobid;

        // http://localhost:50030/jobdetails.jsp?jobid=job_201004051459_0002
        String url = "http://" + jobtracker.getJobTrackerMachine() + ":" + jobtracker.getInfoPort() + "/jobdetails.jsp?jobid=" + jobid;

        this.jobtracker = jobtracker;
        this.status     = new JobStatus(jobid, 0.0f, 0.0f, JobStatus.PREP);
        this.startTime  = System.currentTimeMillis();
        status.setStartTime(startTime);
        this.localFs = FileSystem.getLocal(default_conf);

        JobConf default_job_conf = new JobConf(default_conf);
        this.localJobFile = default_job_conf.getLocalPath(JobTracker.SUBDIR +"/"+jobid + ".xml");
        this.localJarFile = default_job_conf.getLocalPath(JobTracker.SUBDIR +"/"+ jobid + ".jar");

        Path jobDir     = jobtracker.getSystemDirectoryForJob(jobId);
        FileSystem fs   = jobDir.getFileSystem(default_conf);

        jobFile = new Path(jobDir, "job.xml");
        fs.copyToLocalFile(jobFile, localJobFile);

        conf = new JobConf(localJobFile);

        this.priority = conf.getJobPriority();
        this.status.setJobPriority(this.priority);
        this.profile = new JobProfile(conf.getUser(), jobid,
                jobFile.toString(), url, conf.getJobName(),
                conf.getQueueName());

        String jarFile = conf.getJar();

        if (jarFile != null) {
            // hdfs://localhost:54310/tmp/dir/hadoop-hadoop/mapred/system/job_201004051459_0004/job.jar
            fs.copyToLocalFile(new Path(jarFile), localJarFile);
            conf.setJar(localJarFile.toString());
        }

        this.numReplicas	= conf.getFaultTolerance();
        this.numMapTasks 	= conf.getNumMapTasks();
        this.numReduceTasks = conf.getNumReduceTasks();

        this.replicatedNumMapTasks 	= conf.getNumMapTasks() * this.numReplicas;
        this.replicatedNumReduceTasks = conf.getNumReduceTasks() * this.numReplicas;
        mapTaskcounter = new TaskCounter(replicatedNumMapTasks, numReplicas);
        redTaskcounter = new TaskCounter(replicatedNumReduceTasks, numReplicas);
        summapTaskcounter = new TaskCounter(replicatedNumMapTasks, numReplicas);
        sumredTaskcounter = new TaskCounter(replicatedNumReduceTasks, numReplicas);

        this.taskCompletionEvents = new ArrayList<TaskCompletionEvent> (replicatedNumMapTasks + replicatedNumReduceTasks + 10);

        mapTaskCompletionEventTracker = new int[numMapTasks];

        this.urgentMapsToRun = new ArrayList<TaskID>();

        this.mapFailuresPercent = conf.getMaxMapTaskFailuresPercent();
        this.reduceFailuresPercent = conf.getMaxReduceTaskFailuresPercent();

        MetricsContext metricsContext = MetricsUtil.getContext("mapred");
        this.jobMetrics = MetricsUtil.createRecord(metricsContext, "job");
        this.jobMetrics.setTag("user", conf.getUser());
        this.jobMetrics.setTag("sessionId", conf.getSessionId());
        this.jobMetrics.setTag("jobName", conf.getJobName());
        this.jobMetrics.setTag("jobId", jobid.toString());
        this.hasSpeculativeMaps     = conf.getMapSpeculativeExecution();
        this.hasSpeculativeReduces  = conf.getReduceSpeculativeExecution();
        this.maxLevel               = jobtracker.getNumTaskCacheLevels();
        this.anyCacheLevel          = this.maxLevel+1;
        this.nonLocalMaps           = new LinkedList<TaskInProgress>();
        this.nonLocalRunningMaps    = new LinkedHashSet<TaskInProgress>();
        this.runningMapCache        = new IdentityHashMap<Node, Set<TaskInProgress>>();
        this.nonRunningReduces      = new LinkedList<TaskInProgress>();

        this.runningReduces = new LinkedHashSet<TaskInProgress>();
        this.backuprunningReduces = new LinkedHashSet<TaskInProgress>();
        this.resourceEstimator = new ResourceEstimator(this);

        launcher = new MapLauncherController();

        voting          = new MajorityVoting(numReplicas, numReduceTasks);
        map_voters      = new boolean[numMapTasks];
        reduce_voters   = new boolean[numReduceTasks];

        endProcess = new JobEndProcess();
    }

    static String convertTrackerNameToHostName(String trackerName) {
        // Ugly!
        // Convert the trackerName to it's host name
        int indexOfColon = trackerName.indexOf(":");
        String trackerHostName = (indexOfColon == -1) ?
                trackerName :
                    trackerName.substring(0, indexOfColon);
        return trackerHostName.substring("tracker_".length());
    }

    public VotingSystem getVoting() {
        return voting;
    }

    /**
     * Notify the JT to run another task
     * @param taskid
     */
    public void notifyMapTask(TaskID taskid) {
        urgentMapsToRun.add(taskid);
    }

    /**
     * Called periodically by JobTrackerMetrics to update the metrics for
     * this job.
     */
    public void updateMetrics() {
        for(int idx=0; idx<numReplicas; idx++) {
            Counters counters = getCounters();
            for (Counters.Group group : counters) {
                jobMetrics.setTag("group", group.getDisplayName());
                for (Counters.Counter counter : group) {
                    jobMetrics.setTag("counter", counter.getDisplayName());
                    jobMetrics.setMetric("value", (float) counter.getCounter());
                    jobMetrics.update();
                }
            }
        }
    }

    public void addMapsLaunched(TaskAttemptID tid) {
        launcher.addMapsLaunched(tid);
    }

    /**
     * Called when the job is complete
     */
    public void cleanUpMetrics() {
        // Deletes all metric data for this job (in internal table in metrics package).
        // This frees up RAM and possibly saves network bandwidth, since otherwise
        // the metrics package implementation might continue to send these job metrics
        // after the job has finished.
        jobMetrics.removeTag("group");
        jobMetrics.removeTag("counter");
        jobMetrics.remove();
    }

    /**
     * Get TIP that are in the same node as the splits
     * @param splits
     * @param maxLevel
     * @return
     */
    private Map<Node, List<TaskInProgress>> createCache(JobClient.RawSplit[] splits, int maxLevel) {
        Map<Node, List<TaskInProgress>> cache = new IdentityHashMap<Node, List<TaskInProgress>>(maxLevel);
        LOG.info("HDFS file replication: " + splits.length);

        for (int i = 0; i < splits.length; i++) {
            String[] splitLocations = splits[i].getLocations();

            if(splitLocations.length == 0) {
                for(int replica=0; replica<numReplicas; replica++) {
                    int idx = replica + (numReplicas * i);
                    nonLocalMaps.add(maps[idx]);
                }

                continue;
            }

            // in case the split locations is lower than the number of replicas,
            // copy the existing locations to the missing places
            if(splitLocations.length < numReplicas) {
                String [] sp = new String[numReplicas];
                for(int p=0; p<numReplicas; p++) {
                    sp[p] = splitLocations[p%splitLocations.length];
                }
                splitLocations = sp;
            } else if (splitLocations.length > numReplicas) {
                // in case the split locations is bigger than the number of replicas,
                // throw out the excessive locations
                String [] sp = new String[numReplicas];
                for(int p=0; p<numReplicas; p++) {
                    sp[p] = splitLocations[p];
                }
                splitLocations = sp;
            }

            /*
             Re-Sort the split locations such that each map replica will run in different hosts
             */
            String [] sp = new String[splitLocations.length];
            int x = i;
            for(int y=0; y<splitLocations.length; y++) {
                sp[y] = splitLocations[(y+x)%splitLocations.length];
            }
            splitLocations = sp;



            int replica = 0;
            for(String host: splitLocations) {
                Node node = jobtracker.resolveAndAddToTopology(host);

                for (int j = 0; j < maxLevel; j++) {
                    List<TaskInProgress> hostMaps = cache.get(node);
                    if (hostMaps == null) {
                        hostMaps = Collections.synchronizedList(new ArrayList<TaskInProgress>());
                        cache.put(node, hostMaps);
                    }

                    //check whether the hostMaps already contains an entry for a TIP
                    //This will be true for nodes that are racks and multiple nodes in
                    //the rack contain the input for a tip. Note that if it already
                    //exists in the hostMaps, it must be the last element there since
                    //we process one TIP at a time sequentially in the split-size order
                    int idx = conf.getDeferredExecution() ? replica + (numReplicas * i) : (replica * numMapTasks) + i;
                    hostMaps.add(maps[idx]);
                    node = node.getParent();
                }

                replica++;
            }
        }

        if(conf.getMapTasksOrdered())
            LOG.debug("ORDERED");
        else
            LOG.debug("NO ORDERED");

        if(conf.getMapTasksOrdered()) {
            Iterator<Node> iter = cache.keySet().iterator();
            while(iter.hasNext()) {
                Node n = iter.next();
                List<TaskInProgress> hostMaps = cache.get(n);
                Collections.sort (hostMaps, new Comparator<TaskInProgress>() {
                    public int compare(TaskInProgress p1, TaskInProgress p2) {
                        return p1.getTIPId().getReplicaNumber() < p2.getTIPId().getReplicaNumber() ? -1 :
                            (p1.getTIPId().getReplicaNumber() > p2.getTIPId().getReplicaNumber() ? 1 : 0);
                    }
                });
            }
        }

        if(LOG.isDebugEnabled())
            printCache( cache);

        return cache;
    }

    private void printCache(Map<Node, List<TaskInProgress>> cache) {
        Iterator<Node> node = cache.keySet().iterator();
        StringBuffer res = new StringBuffer("Cache --------\n");
        while(node.hasNext()) {
            Node n = node.next();
            List<TaskInProgress> tasks = cache.get(n);
            StringBuffer buf = new StringBuffer("<");
            for(TaskInProgress t : tasks)
                buf.append(t.getTIPId().toString() + ",");
            buf.append(">");
            res.append(n.getName() + "[" + n.getNetworkLocation() + "]" + "(" + n.getLevel() + ") - " + buf.toString() + "\n");
        }

        res.append("-----------------------------");
    }

    /**
     * Check if the job has been initialized.
     * @return <code>true</code> if the job has been initialized,
     *         <code>false</code> otherwise
     */
    public boolean inited() {
        return tasksInited.get();
    }

    boolean hasRestarted() {
        return restartCount > 0;
    }

    /**
     * Construct the splits, etc.  This is invoked from an async
     * thread so that split-computation doesn't block anyone.
     */
    public synchronized void initTasks()
            throws IOException, KillInterruptedException {
        if (tasksInited.get() || isComplete()) {
            return;
        }

        synchronized(jobInitKillStatus){
            if(jobInitKillStatus.killed || jobInitKillStatus.initStarted) {
                return;
            }

            jobInitKillStatus.initStarted = true;
        }

        LOG.info("Initializing " + jobId);

        // log job info
        JobHistory.JobInfo.logSubmitted(getJobID(), conf, jobFile.toString(), this.startTime, hasRestarted());

        // log the job priority
        setPriority(this.priority);

        // read input splits and create a map per a split
        String jobFile  = profile.getJobFile();

        Path sysDir     = new Path(this.jobtracker.getSystemDir());
        FileSystem fs   = sysDir.getFileSystem(conf);

        String jobSplit = conf.get("mapred.job.split.file");// path to job.split
        DataInputStream splitFile = fs.open(new Path(jobSplit));

        JobClient.RawSplit[] splits;
        try {
            splits = JobClient.readSplitFile(splitFile);
        } finally {
            splitFile.close();
        }

        // numMapTasks be enough
        this.numMapTasks = splits.length;
        this.replicatedNumMapTasks = numMapTasks * numReplicas;

        // if the number of splits is larger than a configured value
        // then fail the job.
        int maxTasks = jobtracker.getMaxTasksPerJob();
        if (maxTasks > 0 && numMapTasks + numReduceTasks > maxTasks) {
            throw new IOException("The number of tasks for this job " +
                    (numMapTasks + numReduceTasks) +
                    " exceeds the configured limit " + maxTasks);
        }

        jobtracker.getInstrumentation().addWaiting(getJobID(), numMapTasks + numReduceTasks);

        maps = new TaskInProgress[replicatedNumMapTasks];

        if(conf.getDeferredExecution()) {
            LOG.debug("Deferred execution enabled");

            for(int i=0; i < splits.length; ++i) {
                inputLength += splits[i].getDataLength();

                for(int j=0; j<numReplicas; j++) {
                    int idx = (i*numReplicas) + j;
                    //task_201007081056_0001_m_000002_2_m
                    //task_201007081056_0001_m_idx_replica_m
                    maps[idx] = new TaskInProgress(jobId, jobFile, splits[i], jobtracker, conf, this, i, j);

                    // set count
                    increaseCount(mapCount, maps[idx].getTIPId().toStringWithoutReplica());
                }
            }
        } else {
            LOG.debug("Tentative execution enabled");
            int count=0;
            for(int numReplica=0; numReplica<numReplicas; numReplica++) {
                for(int i=0; i < splits.length; ++i) {
                    inputLength += splits[i].getDataLength();

                    //task_201007081056_0001_m_000002_2_m
                    //task_201007081056_0001_m_idx_replica_m
                    maps[count] = new TaskInProgress(jobId, jobFile, splits[i], jobtracker, conf, this, i, numReplica);

                    // set count
                    increaseCount(mapCount, maps[count].getTIPId().toStringWithoutReplica());
                    count++;
                }
            }
        }

        LOG.info("Input size for job " + jobId + " = " + inputLength + ". Number of splits = " + splits.length);

        if (numMapTasks > 0) {
            nonRunningMapCache = createCache(splits, maxLevel);
        }

        // Print the map cache
        printMapCache();

        // set the launch time
        this.launchTime = System.currentTimeMillis();

        reduces = new TaskInProgress[replicatedNumReduceTasks];

        if(numReduceTasks > 0) {
            for (int i = 0; i < numReduceTasks; i++)  {
                for(int numReplica=0; numReplica<numReplicas; numReplica++) {
                    int idx = numReplica + (i * numReplicas);

                    // The obtainNewReduceTask method takes a task by the partition (idx) in the reduces[],
                    // creates an attempt and start executing in a Child process
                    //task_201007081056_0001_r_000002_0_2_r
                    //task_201007081056_0001_r_partition_nrR_r
                    reduces[idx] = new TaskInProgress(jobId, jobFile, numMapTasks, jobtracker, conf, this, i, numReplica);
                    nonRunningReduces.add(reduces[idx]);
                    increaseCount(redCount, reduces[idx].getTIPId().toStringWithoutReplica());
                }
            }
        }

        // Calculate the minimum number of maps to be complete before
        // we should start scheduling reduces
        //        completedMapsForReduceSlowstart =
        //                (int)Math.ceil((conf.getFloat("mapred.reduce.slowstart.completed.maps",
        //                        DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART) *
        //                        MajorityVoting.getThreshold(replicatedNumMapTasks)));

        // create cleanup two cleanup tips, one map and one reduce.
        cleanup = new TaskInProgress[2];

        // cleanup map tip. This map doesn't use any splits. Just assign an empty split.
        JobClient.RawSplit emptySplit = new JobClient.RawSplit();
        cleanup[0] = new TaskInProgress(jobId, jobFile, emptySplit, jobtracker, conf, this, numMapTasks, NOTUSED, true);
        cleanup[0].setJobCleanupTask();

        // cleanup reduce tip.
        cleanup[1] = new TaskInProgress(jobId, jobFile, replicatedNumMapTasks, jobtracker, conf, this, numReduceTasks, NOTUSED, true);
        cleanup[1].setJobCleanupTask();

        // create two setup tips, one map and one reduce.
        setup = new TaskInProgress[2];

        // setup map tip. This map doesn't use any split. Just assign an empty split.
        setup[0] = new TaskInProgress(jobId, jobFile, emptySplit, jobtracker, conf, this, numMapTasks + 1, NOTUSED, true);
        setup[0].setJobSetupTask();

        // setup reduce tip.
        setup[1] = new TaskInProgress(jobId, jobFile, replicatedNumMapTasks, jobtracker, conf, this, numReduceTasks + 1, NOTUSED, true);
        setup[1].setJobSetupTask();

        synchronized(jobInitKillStatus){
            jobInitKillStatus.initDone = true;
            if(jobInitKillStatus.killed) {
                throw new KillInterruptedException("Job " + jobId + " killed in init");
            }
        }

        tasksInited.set(true);
        JobHistory.JobInfo.logInited(profile.getJobID(), this.launchTime, numMapTasks, numReduceTasks);

        LOG.debug("JobEndProcess starting...");
        endProcess.start();
    }

    private void printMapCache() {
        /*
          Map cache
    /default-rack - task_201203071111_0001_m_000000_0, task_201203071111_0001_m_000000_1, task_201203071111_0001_m_000000_2, task_201203071111_0001_m_000001_0, task_201203071111_0001_m_000001_1, task_201203071111_0001_m_000001_2, task_201203071111_0001_m_000002_0, task_201203071111_0001_m_000002_1, task_201203071111_0001_m_000002_2, task_201203071111_0001_m_000003_0, task_201203071111_0001_m_000003_1, task_201203071111_0001_m_000003_2, task_201203071111_0001_m_000004_0, task_201203071111_0001_m_000004_1, task_201203071111_0001_m_000004_2, task_201203071111_0001_m_000005_0, task_201203071111_0001_m_000005_1, task_201203071111_0001_m_000005_2, task_201203071111_0001_m_000006_0, task_201203071111_0001_m_000006_1, task_201203071111_0001_m_000006_2, task_201203071111_0001_m_000007_0, task_201203071111_0001_m_000007_1, task_201203071111_0001_m_000007_2, task_201203071111_0001_m_000008_0, task_201203071111_0001_m_000008_1, task_201203071111_0001_m_000008_2, task_201203071111_0001_m_000009_0, task_201203071111_0001_m_000009_1, task_201203071111_0001_m_000009_2,
    /default-rack/ubuntu-linux1 - task_201203071111_0001_m_000000_0, task_201203071111_0001_m_000002_0, task_201203071111_0001_m_000005_0,
    /default-rack/ubuntu-linux2 - task_201203071111_0001_m_000000_2, task_201203071111_0001_m_000001_2, task_201203071111_0001_m_000002_1, task_201203071111_0001_m_000003_0, task_201203071111_0001_m_000004_2, task_201203071111_0001_m_000006_1, task_201203071111_0001_m_000007_1, task_201203071111_0001_m_000008_1, task_201203071111_0001_m_000009_1,
    /default-rack/ubuntu-linux4 - task_201203071111_0001_m_000001_1, task_201203071111_0001_m_000002_2, task_201203071111_0001_m_000003_1, task_201203071111_0001_m_000004_1, task_201203071111_0001_m_000005_2, task_201203071111_0001_m_000006_2, task_201203071111_0001_m_000007_2, task_201203071111_0001_m_000008_0, task_201203071111_0001_m_000009_0,
    /default-rack/ubuntu-linux3 - task_201203071111_0001_m_000000_1, task_201203071111_0001_m_000001_0, task_201203071111_0001_m_000003_2, task_201203071111_0001_m_000004_0, task_201203071111_0001_m_000005_1, task_201203071111_0001_m_000006_0, task_201203071111_0001_m_000007_0, task_201203071111_0001_m_000008_2, task_201203071111_0001_m_000009_2,
         */
        Iterator<Node> iter = nonRunningMapCache.keySet().iterator();
        StringBuffer buf = new StringBuffer("Map cache\n");

        while(iter.hasNext()) {
            Node n = iter.next();
            List<TaskInProgress> auxtip = nonRunningMapCache.get(n);
            StringBuffer t = new StringBuffer();
            for(TaskInProgress tip : auxtip) {
                t.append(tip.getTIPId().toString() + ", ");
            }
            buf.append("\t" + n + " - " + t + "\n");
        }

        LOG.debug(buf.toString());
    }

    /////////////////////////////////////////////////////
    // Accessors for the JobInProgress
    /////////////////////////////////////////////////////
    public JobProfile getProfile() {
        return profile;
    }

    public JobStatus getStatus() {
        return status;
    }

    public synchronized long getLaunchTime() {
        return launchTime;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getFinishTime() {
        return finishTime;
    }

    public int desiredMaps() {
        return replicatedNumMapTasks;
    }

    public synchronized int finishedMaps() {
        return finishedMapTasks;
    }

    public int desiredReduces() {
        return replicatedNumReduceTasks;
    }

    public synchronized int runningMaps() {
        return runningMapTasks;
    }

    public synchronized int runningReduces() {
        return runningReduceTasks;
    }

    public synchronized int finishedReduces() {
        return finishedReduceTasks;
    }

    /**
     * Nr of maps pending
     * @return
     */
    public synchronized int pendingMaps() {
        return replicatedNumMapTasks - runningMapTasks - failedMapTIPs - finishedMapTasks + speculativeMapTasks;
    }

    /**
     * Nr of reduces pending
     * @return
     */
    public synchronized int pendingReduces() {
        return replicatedNumReduceTasks - runningReduceTasks - failedReduceTIPs - finishedReduceTasks + speculativeReduceTasks;
    }

    public JobPriority getPriority() {
        return this.priority;
    }

    public void setPriority(JobPriority priority) {
        if(priority == null) {
            this.priority = JobPriority.NORMAL;
        } else {
            this.priority = priority;
        }
        synchronized (this) {
            status.setJobPriority(priority);
        }
        // log and change to the job's priority
        JobHistory.JobInfo.logJobPriority(jobId, priority);
    }

    // Update the job start/launch time (upon restart) and log to history
    synchronized void updateJobInfo(long startTime, long launchTime) {
        // log and change to the job's start/launch time
        this.startTime = startTime;
        this.launchTime = launchTime;
        JobHistory.JobInfo.logJobInfo(jobId, startTime, launchTime);
    }

    /**
     * Get the number of times the job has restarted
     */
    int getNumRestarts() {
        return restartCount;
    }

    long getInputLength() {
        return inputLength;
    }

    boolean isCleanupLaunched() {
        return launchedCleanup;
    }

    boolean isSetupLaunched() {
        return launchedSetup;
    }

    /**
     * Get the list of map tasks
     * @return the raw array of maps for this job
     */
    TaskInProgress[] getMapTasks() {
        return maps;
    }

    /**
     * Get the list of cleanup tasks
     * @return the array of cleanup tasks for the job
     */
    TaskInProgress[] getCleanupTasks() {
        return cleanup;
    }

    /**
     * Get the list of setup tasks
     * @return the array of setup tasks for the job
     */
    TaskInProgress[] getSetupTasks() {
        return setup;
    }

    /**
     * Get the list of reduce tasks
     * @return the raw array of reduce tasks for this job
     */
    TaskInProgress[] getReduceTasks() {
        return reduces;
    }

    /**
     * Return the nonLocalRunningMaps
     * @return
     */
    Set<TaskInProgress> getNonLocalRunningMaps() {
        return nonLocalRunningMaps;
    }

    /**
     * Return the runningMapCache
     * @return
     */
    Map<Node, Set<TaskInProgress>> getRunningMapCache() {
        return runningMapCache;
    }

    /**
     * Return runningReduces
     * @return
     */
    Set<TaskInProgress> getRunningReduces() {
        return runningReduces;
    }

    /**
     * Get the job configuration
     * @return the job's configuration
     */
    JobConf getJobConf() {
        return conf;
    }

    /**
     * Return a vector of completed TaskInProgress objects
     */
    public synchronized Vector<TaskInProgress> reportTasksInProgress(boolean shouldBeMap, boolean shouldBeComplete) {
        Vector<TaskInProgress> results = new Vector<TaskInProgress>();
        TaskInProgress tips[] = null;
        if (shouldBeMap) {
            tips = maps;
        } else {
            tips = reduces;
        }

        for (TaskInProgress tip : tips) {
            if(tip != null  && tip.isComplete() == shouldBeComplete) {
                results.add(tip);
            }
        }

        return results;
    }

    /**
     * Return a vector of cleanup TaskInProgress objects
     */
    public synchronized Vector<TaskInProgress> reportCleanupTIPs(boolean shouldBeComplete) {
        Vector<TaskInProgress> results = new Vector<TaskInProgress>();
        for (int i = 0; i < cleanup.length; i++) {
            if (cleanup[i].isComplete() == shouldBeComplete) {
                results.add(cleanup[i]);
            }
        }

        return results;
    }

    /**
     * Return a vector of setup TaskInProgress objects
     */
    public synchronized Vector<TaskInProgress> reportSetupTIPs(boolean shouldBeComplete) {
        Vector<TaskInProgress> results = new Vector<TaskInProgress>();
        for (int i = 0; i < setup.length; i++) {
            if (setup[i].isComplete() == shouldBeComplete) {
                results.add(setup[i]);
            }
        }
        return results;
    }

    ////////////////////////////////////////////////////
    // Status update methods
    ////////////////////////////////////////////////////

    public String getInfoOutputs() {
        InputStream in = null;
        BufferedReader reader = null;
        StringBuffer sb = new StringBuffer("");
        try {
            for(TaskTrackerLocation location : mapTrackerLocation) {
                String host = location.getLocalHostname();
                int port = location.getHttpPort();
                URLConnection con = new URL("http://" + host + ":" + port + "/infoOutput").openConnection();
                in = con.getInputStream();
                reader = new BufferedReader(new InputStreamReader(in));
                String line;
                if(reader != null) {
                    while  ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }
        } catch (MalformedURLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        finally{
            try {
                if(reader != null)
                    reader.close();

                in.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return sb.toString();
    }

    /**
     * Assuming {@link JobTracker} is locked on entry.
     */
    public synchronized void updateTaskStatus(TaskInProgress tip, TaskStatus status) {
        double oldProgress 		= tip.getProgress();   // save old progress
        boolean wasRunning 		= tip.isRunning();
        boolean wasComplete 	= tip.isComplete();
        boolean wasPending 		= tip.isOnlyCommitPending();
        TaskAttemptID taskid 	= status.getTaskID();

        // If the TIP is already completed and the task reports as SUCCEEDED then
        // mark the task as KILLED.
        // In case of task with no promotion the task tracker will mark the task
        // as SUCCEEDED.
        // User has requested to kill the task, but TT reported SUCCEEDED,
        // mark the task KILLED.
        if ((wasComplete || tip.wasKilled(taskid)) &&
                (status.getRunState() == TaskStatus.State.SUCCEEDED)) {
            status.setRunState(TaskStatus.State.KILLED);
        }

        // If the job is complete and a task has just reported its
        // state as FAILED_UNCLEAN/KILLED_UNCLEAN,
        // make the task's state FAILED/KILLED without launching cleanup attempt.
        // Note that if task is already a cleanup attempt,
        // we don't change the state to make sure the task gets a killTaskAction
        if ((this.isComplete() || jobFailed || jobKilled) && !tip.isCleanupAttempt(taskid)) {
            if (status.getRunState() == TaskStatus.State.FAILED_UNCLEAN) {
                status.setRunState(TaskStatus.State.FAILED);
            } else if (status.getRunState() == TaskStatus.State.KILLED_UNCLEAN) {
                status.setRunState(TaskStatus.State.KILLED);
            }
        }

        boolean change = tip.updateStatus(status);
        if (change) {
            TaskStatus.State state = status.getRunState();

            // get the TaskTrackerStatus where the task ran
            TaskTrackerStatus ttStatus = this.jobtracker.getTaskTracker(tip.machineWhereTaskRan(taskid));
            String httpTaskLogLocation = null;

            if (null != ttStatus){
                String host;
                if (NetUtils.getStaticResolution(ttStatus.getHost()) != null) {
                    host = NetUtils.getStaticResolution(ttStatus.getHost());
                } else {
                    host = ttStatus.getHost();
                }

                httpTaskLogLocation = "http://" + host + ":" + ttStatus.getHttpPort();
            }

            TaskCompletionEvent taskEvent = null;

            if (state == TaskStatus.State.SUCCEEDED) {
                taskEvent = setStatusSuccessful(tip, status, taskid,httpTaskLogLocation);
            } else if (state == TaskStatus.State.COMMIT_PENDING) {
                // If it is the first attempt reporting COMMIT_PENDING
                // ask the task to commit.
                LOG.debug("Task " + taskid.toString() + " is COMMIT_PENDING. WasComplete? " + wasComplete  + " WasPending? " + wasPending);

                if (!wasComplete && !wasPending) {
                    tip.doCommit(taskid);
                }

                return;
            }
            else if (state == TaskStatus.State.FAILED_UNCLEAN || state == TaskStatus.State.KILLED_UNCLEAN) {
                tip.incompleteSubTask(taskid, this.status);

                // add this task, to be rescheduled as cleanup attempt
                if (tip.isMapTask()) {
                    mapCleanupTasks.add(taskid);
                    mapTaskcounter.removetask(taskid.getTaskID().getId());
                } else {
                    reduceCleanupTasks.add(taskid);
                    redTaskcounter.removetask(taskid.getTaskID().getId());

                }
                // Remove the task entry from jobtracker
                jobtracker.removeTaskEntry(taskid);
                launcher.removeToRun(tip);
            }
            //For a failed task update the JT datastructures.
            else if (state == TaskStatus.State.FAILED || state == TaskStatus.State.KILLED) {
                taskEvent = setStatusFailed(tip, status, wasRunning,
                        wasComplete, taskid, state, ttStatus,
                        httpTaskLogLocation);
            }

            // inform all JTs
            if(state == TaskStatus.State.SUCCEEDED ||
                    state == TaskStatus.State.FAILED ||
                    state == TaskStatus.State.KILLED) {

            }


            // Add the 'complete' task i.e. successful/failed
            // It _is_ safe to add the TaskCompletionEvent.Status.SUCCEEDED
            // *before* calling TIP.completedTask since:
            // a. One and only one task of a TIP is declared as a SUCCESS, the
            //    other (speculative tasks) are marked KILLED by the TaskCommitThread
            // b. TIP.completedTask *does not* throw _any_ exception at all.
            if (taskEvent != null) {
                this.taskCompletionEvents.add(taskEvent);
                this.taskCompletionEventTracker++;
                if(state==TaskStatus.State.SUCCEEDED)
                    completedTask(tip, status);
            }
        }

        // Update JobInProgress status
        if (!tip.isJobCleanupTask() && !tip.isJobSetupTask()) {
            double progressDelta = tip.getProgress() - oldProgress;

            if (tip.isMapTask())
                this.status.setMapProgress((float) (this.status.mapProgress() + (progressDelta / maps.length)));
            else
                this.status.setReduceProgress((float) (this.status.reduceProgress() + (progressDelta / reduces.length)));
        }

        // this part was made for the case when all the map output obtained by the reduce tasks
        // aren't right. So a RT will fail. This check if all RT failed
        if(failedReduceTasks == replicatedNumReduceTasks) {
            LOG.debug("FAIL!!!");
            fail();
        }
    }

    TaskCompletionEvent setStatusFailed(TaskInProgress tip,
            TaskStatus status, boolean wasRunning, boolean wasComplete,
            TaskAttemptID taskid, TaskStatus.State state,
            TaskTrackerStatus ttStatus, String httpTaskLogLocation) {
        TaskCompletionEvent taskEvent;
        // Get the event number for the (possibly) previously successful
        // task. If there exists one, then set that status to OBSOLETE
        int eventNumber;
        if ((eventNumber = tip.getSuccessEventNumber()) != -1) {
            if(LOG.isDebugEnabled()) {
                LOG.debug("EventNumber: " + eventNumber + " size: " + taskCompletionEvents.size() + " "
                        + tip.getTIPId().toString());

                StringBuffer buf = new StringBuffer();
                for(TaskCompletionEvent t : taskCompletionEvents) {
                    buf.append(t.getEventId() + " - " + t.getTaskId() + "\n");
                }

                LOG.debug(buf);
            }

            TaskCompletionEvent t = this.taskCompletionEvents.get(eventNumber);

            if (t.getTaskAttemptId().equals(taskid))
                t.setTaskStatus(TaskCompletionEvent.Status.OBSOLETE);
        }

        LOG.debug(taskid.toString() + " failed");
        // Tell the job to fail the relevant task
        failedTask(tip, taskid, status, ttStatus, wasRunning, wasComplete);

        // Did the task failure lead to tip failure?
        TaskCompletionEvent.Status taskCompletionStatus =
                (state == TaskStatus.State.FAILED ) ? TaskCompletionEvent.Status.FAILED : TaskCompletionEvent.Status.KILLED;

        if (tip.isFailed())
            taskCompletionStatus = TaskCompletionEvent.Status.TIPFAILED;

        taskEvent = new TaskCompletionEvent(
                taskCompletionEventTracker,
                taskid,
                status.getIsMap() &&
                !tip.isJobCleanupTask() &&
                !tip.isJobSetupTask(),
                taskCompletionStatus,
                httpTaskLogLocation,
                numReplicas);

        jobtracker.removeTaskEntry(taskid);
        launcher.removeToRun(tip);

        // add this task, to be rescheduled as cleanup attempt
        if (tip.isMapTask()) {
            mapCleanupTasks.add(taskid);
            mapTaskcounter.removetask(taskid.getTaskID().getId());
        } else {
            reduceCleanupTasks.add(taskid);
            redTaskcounter.removetask(taskid.getTaskID().getId());

        }
        return taskEvent;
    }

    TaskCompletionEvent setStatusSuccessful(TaskInProgress tip,
            TaskStatus status, TaskAttemptID taskid, String httpTaskLogLocation) {
        TaskCompletionEvent taskEvent;
        // this taskcompletion event contains all the digests
        // generated by the map task
        taskEvent = new TaskCompletionEvent(
                taskCompletionEventTracker,
                taskid,
                status.getIsMap() &&
                !tip.isJobCleanupTask() &&
                !tip.isJobSetupTask(),
                TaskCompletionEvent.Status.SUCCEEDED,
                httpTaskLogLocation,
                status.getDigests(),
                numReplicas);

        taskEvent.setTaskRunTime((int)(status.getFinishTime() - status.getStartTime()));
        tip.setSuccessEventNumber(taskCompletionEventTracker);

        if(!taskid.getTaskID().isSetupOrCleanup())
            voting.addTaskCompletionEvent(taskid.getTaskID(), taskEvent);
        return taskEvent;
    }

    /**
     * Returns the job-level counters.
     *
     * @return the job-level counters.
     */
    public synchronized Counters getJobCounters() {
        return jobCounters;
    }

    /**
     *  Returns map phase counters by summing over all map tasks in progress.
     */
    public synchronized Counters getMapCounters() {
        return incrementTaskCounters(new Counters(), maps);
    }

    /**
     *  Returns map phase counters by summing over all map tasks in progress.
     */
    public synchronized Counters getReduceCounters() {
        return incrementTaskCounters(new Counters(), reduces);
    }

    /**
     *  Returns the total job counters, by adding together the job,
     *  the map and the reduce counters.
     */
    public synchronized Counters getCounters() {
        Counters result = new Counters();
        result.incrAllCounters(getJobCounters());
        incrementTaskCounters(result, maps);
        return incrementTaskCounters(result, reduces);
    }


    /////////////////////////////////////////////////////
    // Create/manage tasks
    /////////////////////////////////////////////////////

    /**
     * Increments the counters with the counters from each task.
     * @param counters the counters to increment
     * @param tips the tasks to add in to counters
     * @return counters the same object passed in as counters
     */
    private Counters incrementTaskCounters(Counters counters, TaskInProgress[] tips) {
        for (TaskInProgress tip : tips) {
            if(tip != null)
                counters.incrAllCounters(tip.getCounters());
        }
        return counters;
    }

    /**
     * Return a MapTask, if appropriate, to run on the given tasktracker
     */
    public synchronized Task obtainNewMapTask(TaskTrackerStatus tts,
            int clusterSize,
            int numUniqueHosts
            ) throws IOException {
        if (status.getRunState() != JobStatus.RUNNING) {
            LOG.info("Cannot create task split for " + profile.getJobID());
            return null;
        }

        int target = findNewMapTask(tts, clusterSize, numUniqueHosts, anyCacheLevel, status.mapProgress());
        if (target == -1)
            return null;

        TaskInProgress tip = maps[target];
        Task result = tip.getTaskToRun(tts.getTrackerName(), tts.getHost());

        if(result != null) {
            LOG.debug("Scheduling: " + tip.getTIPId()  + " to " + result.getTaskID());
            addRunningTaskToTIP(maps[target], result.getTaskID(), tts, true);
            mapTrackerLocation.add(new TaskTrackerLocation(tts.getTrackerName(), tts.getHost(), tts.getHttpPort(), result.getTaskID()));
        }

        return result;
    }

    /*
     * Return task cleanup attempt if any, to run on a given tracker
     */
    public Task obtainTaskCleanupTask(TaskTrackerStatus tts, boolean isMapSlot)
            throws IOException {
        if (!tasksInited.get()) {
            return null;
        }
        synchronized (this) {
            if (this.status.getRunState() != JobStatus.RUNNING || jobFailed || jobKilled) {
                return null;
            }
            String taskTracker = tts.getTrackerName();
            if (!shouldRunOnTaskTracker(taskTracker)) {
                return null;
            }
            TaskAttemptID taskid = null;
            TaskInProgress tip = null;
            if (isMapSlot) {
                if (!mapCleanupTasks.isEmpty()) {
                    taskid = mapCleanupTasks.remove(0);

                    int id = taskid.getTaskID().getId();
                    int r  = taskid.getTaskID().getReplicaNumber();

                    int idx = conf.getDeferredExecution() ? id*numReplicas+r : r*numMapTasks+id;
                    tip = maps[idx];

                    if(tip != null)
                        LOG.debug("In TaskCleanup, chosen " + tip.getTIPId() + " to " + taskid.toString());
                }
            } else {
                if (!reduceCleanupTasks.isEmpty()) {
                    taskid = reduceCleanupTasks.remove(0);

                    for(int i=0; i<reduces.length; i++) {
                        if(reduces[i].getTIPId().equals(taskid.getTaskID())) {
                            tip = reduces[i];
                            break;
                        }
                    }

                    LOG.debug("In TaskCleanup, chosen " + tip != null ? tip.getTIPId() : "NULL" + " to " + taskid.toString());
                }
            }

            if (tip != null) {
                return tip.addRunningTask(taskid, taskTracker, tts.getHost(), true);
            }

            return null;
        }
    }

    public synchronized Task obtainNewLocalMapTask(TaskTrackerStatus tts,
            int clusterSize,
            int numUniqueHosts)
                    throws IOException {
        if (!tasksInited.get()) {
            LOG.info("Cannot create task split for " + profile.getJobID());
            return null;
        }

        // target is the nr of the partition. A partition is a set of replicas
        int target = findNewMapTask(tts, clusterSize, numUniqueHosts, maxLevel, status.mapProgress());
        if (target == -1) {
            return null;
        }

        TaskInProgress tip = maps[target];
        Task result = tip.getTaskToRun(tts.getTrackerName(), tts.getHost());

        if(result != null) {
            LOG.debug("Scheduling: " + tip.getTIPId()  + " to " + tts.getTrackerName() + " - " + Arrays.deepToString(tip.getSplitLocations()));
            addRunningTaskToTIP(maps[target], result.getTaskID(), tts, true);
            mapTrackerLocation.add(new TaskTrackerLocation(tts.getTrackerName(), tts.getHost(), tts.getHttpPort(), result.getTaskID()));
        }

        return result;
    }

    public synchronized Task obtainNewNonLocalMapTask(TaskTrackerStatus tts,
            int clusterSize,
            int numUniqueHosts)
                    throws IOException {
        if (!tasksInited.get()) {
            LOG.info("Cannot create task split for " + profile.getJobID());
            return null;
        }

        int target = findNewMapTask(tts, clusterSize, numUniqueHosts, NON_LOCAL_CACHE_LEVEL, status.mapProgress());
        if (target == -1)
            return null;

        TaskInProgress tip = maps[target];
        Task result = tip.getTaskToRun(tts.getTrackerName(), tts.getHost());

        if(result != null) {
            LOG.debug("Scheduling: " + tip.getTIPId()  + " to " + result.getTaskID());
            addRunningTaskToTIP(maps[target], result.getTaskID(), tts, true);
            mapTrackerLocation.add(new TaskTrackerLocation(tts.getTrackerName(), tts.getHost(), tts.getHttpPort(), result.getTaskID()));
        }

        return result;
    }

    /**
     * Return a CleanupTask, if appropriate, to run on the given tasktracker
     */
    public Task obtainJobCleanupTask(TaskTrackerStatus tts,
            int clusterSize,
            int numUniqueHosts,
            boolean isMapSlot
            ) throws IOException {
        if(!tasksInited.get())
            return null;

        synchronized(this) {
            // Check whether cleanup task can be launched for the job.
            if (!canLaunchJobCleanupTask()) {
                return null;
            }

            String taskTracker = tts.getTrackerName();

            // Update the last-known clusterSize
            this.clusterSize = clusterSize;
            // Is this tracker reliable?
            if (!shouldRunOnTaskTracker(taskTracker)) {
                return null;
            }

            List<TaskInProgress> cleanupTaskList = new ArrayList<TaskInProgress>();
            if (isMapSlot)
                cleanupTaskList.add(cleanup[0]);
            else
                cleanupTaskList.add(cleanup[1]);

            TaskInProgress tip = findTaskFromList(cleanupTaskList, tts, numUniqueHosts, false);
            if (tip == null) {
                return null;
            }

            // Now launch the cleanupTask
            Task result = tip.getTaskToRun(taskTracker, tts.getHost());
            if (result != null)
                addRunningTaskToTIP(tip, result.getTaskID(), tts, true);

            return result;
        }
    }

    /**
     * Check whether cleanup task can be launched for the job.
     *
     * Cleanup task can be launched if it is not already launched
     * or job is Killed
     * or all maps and reduces are complete
     * @return true/false
     */
    private synchronized boolean canLaunchJobCleanupTask() {
        // check if the job is running
        if (status.getRunState() != JobStatus.RUNNING &&
                status.getRunState() != JobStatus.PREP)
        {
            return false;
        }

        // check if cleanup task has been launched already or if setup isn't
        // launched already. The later check is useful when number of maps is
        // zero.
        if (launchedCleanup || !isSetupFinished()) {
            return false;
        }

        // check if job has failed or killed
        if (jobKilled || jobFailed) {
            return true;
        }

        // Check if all maps and reducers have finished.
        boolean launchCleanupTask = ((finishedMapTasks + failedMapTIPs) == (replicatedNumMapTasks));
        if (launchCleanupTask)
            launchCleanupTask = ((finishedReduceTasks + failedReduceTIPs) == replicatedNumReduceTasks);

        return launchCleanupTask;
    }

    /**
     * Return a SetupTask, if appropriate, to run on the given tasktracker
     */
    public Task obtainJobSetupTask(TaskTrackerStatus tts,
            int clusterSize,
            int numUniqueHosts,
            boolean isMapSlot
            ) throws IOException {
        if(!tasksInited.get()) {
            return null;
        }

        synchronized(this) {
            if (!canLaunchSetupTask()) {
                return null;
            }
            String taskTracker = tts.getTrackerName();
            // Update the last-known clusterSize
            this.clusterSize = clusterSize;
            if (!shouldRunOnTaskTracker(taskTracker)) {
                return null;
            }

            List<TaskInProgress> setupTaskList = new ArrayList<TaskInProgress>();
            if (isMapSlot) {
                setupTaskList.add(setup[0]);
            } else {
                setupTaskList.add(setup[1]);
            }
            TaskInProgress tip = findTaskFromList(setupTaskList, tts, numUniqueHosts, false);
            if (tip == null) {
                return null;
            }

            // Now launch the setupTask
            Task result = tip.getTaskToRun(tts.getTrackerName(), tts.getHost());
            if (result != null) {
                addRunningTaskToTIP(tip, result.getTaskID(), tts, true);
            }

            return result;
        }
    }

    /**
     * Reduce tasks should launch?
     * @return
     */
    public synchronized boolean scheduleReduces() {
        if(conf.getDeferredExecution())
            return !summapTaskcounter.hasAnyLowerLimit(MajorityVoting.getThreshold(replicatedNumMapTasks)/numMapTasks);

        return  !summapTaskcounter.hasAnyLowerLimit(1);
    }

    /**
     * Check whether setup task can be launched for the job.
     *
     * Setup task can be launched after the tasks are inited
     * and Job is in PREP state
     * and if it is not already launched
     * or job is not Killed/Failed
     * @return true/false
     */
    private synchronized boolean canLaunchSetupTask() {
        return (tasksInited.get() && status.getRunState() == JobStatus.PREP &&
                !launchedSetup && !jobKilled && !jobFailed);
    }

    /**
     * Return a ReduceTask, if appropriate, to run on the given tasktracker.
     * We don't have cache-sensitivity for reduce tasks, as they
     *  work on temporary MapRed files.
     */
    public synchronized Task obtainNewReduceTask(TaskTrackerStatus tts,
            int clusterSize,
            int numUniqueHosts)
                    throws IOException {
        if (status.getRunState() != JobStatus.RUNNING) {
            LOG.info("Cannot create task split for " + profile.getJobID());
            return null;
        }

        // idx
        int  target = findNewReduceTask(tts, clusterSize, numUniqueHosts, status.reduceProgress());
        if (target == -1) {
            return null;
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("Reduce task fetched");
            int[] counter = summapTaskcounter.getTaskCounter();
            StringBuffer str = new StringBuffer();
            for(int i=0; i<counter.length; i++)
                str.append(counter[i]  + ", ");
            LOG.debug("MapTasks status: [ " + str.toString() + " ]");
        }

        TaskInProgress tip = reduces[target];
        Task result = tip.getTaskToRun(tts.getTrackerName(), tts.getHost());

        if (result != null) {
            LOG.debug("Scheduling: " + tip.getTIPId() + " to " + result.getTaskID());
            addRunningTaskToTIP(tip, result.getTaskID(), tts, true);
            redTrackerLocation.add(new TaskTrackerLocation(tts.getTrackerName(), tts.getHost(), tts.getHttpPort(), result.getTaskID()));
        }

        return result;
    }

    // returns the (cache)level at which the nodes matches
    private int getMatchingLevelForNodes(Node n1, Node n2) {
        int count = 0;
        do {
            if (n1.equals(n2)) {
                return count;
            }
            ++count;
            n1 = n1.getParent();
            n2 = n2.getParent();
        } while (n1 != null);
        return this.maxLevel;
    }

    /**
     * Populate the data structures as a task is scheduled.
     *
     * Assuming {@link JobTracker} is locked on entry.
     *
     * @param tip The tip for which the task is added
     * @param id The attempt-id for the task
     * @param tts task-tracker status
     * @param isScheduled Whether this task is scheduled from the JT or has
     *        joined back upon restart
     */
    synchronized void addRunningTaskToTIP(TaskInProgress tip, TaskAttemptID id,
            TaskTrackerStatus tts,
            boolean isScheduled) {
        // Make an entry in the tip if the attempt is not scheduled i.e externally
        // added
        if (!isScheduled) {
            tip.addRunningTask(id, tts.getTrackerName(), tts.getHost());
        }

        final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();

        // keeping the earlier ordering intact
        String name;
        String splits = "";
        Enum counter = null;

        if (tip.isJobSetupTask()) {
            launchedSetup = true;
            name = Values.SETUP.name();
        }
        else if (tip.isJobCleanupTask()) {
            launchedCleanup = true;
            name = Values.CLEANUP.name();
        }
        else if (tip.isMapTask()) {
            ++runningMapTasks;
            name = Values.MAP.name();
            counter = Counter.TOTAL_LAUNCHED_MAPS;
            splits = tip.getSplitNodes();

            if (tip.getActiveTasks().size() > 1)
                speculativeMapTasks++;
            metrics.launchMap(id);
        } else {
            ++runningReduceTasks;
            name = Values.REDUCE.name();
            counter = Counter.TOTAL_LAUNCHED_REDUCES;
            if (tip.getActiveTasks().size() > 1)
                speculativeReduceTasks++;
            metrics.launchReduce(id);
        }

        // Note that the logs are for the scheduled tasks only. Tasks that join on
        // restart has already their logs in place.
        if (tip.isFirstAttempt(id)) {
            JobHistory.Task.logStarted(tip.getTIPId(), name, tip.getExecStartTime(), splits);
        }

        if (!tip.isJobSetupTask() && !tip.isJobCleanupTask()) {
            jobCounters.incrCounter(counter, 1);
            jobCounters.setCounter(Counter.NUMBER_REPLICAS, numReplicas);
            jobCounters.setCounter(Counter.TOTAL_MAPS, replicatedNumMapTasks);
        }

        //TODO The only problem with these counters would be on restart.
        // The jobtracker updates the counter only when the task that is scheduled
        // if from a non-running tip and is local (data, rack ...). But upon restart
        // as the reports come from the task tracker, there is no good way to infer
        // when exactly to increment the locality counters. The only solution is to
        // increment the counters for all the tasks irrespective of
        //    - whether the tip is running or not
        //    - whether its a speculative task or not
        //
        // So to simplify, increment the data locality counter whenever there is
        // data locality.
        if (tip.isMapTask() && !tip.isJobSetupTask() && !tip.isJobCleanupTask()) {
            // increment the data locality counter for maps
            Node tracker = jobtracker.getNode(tts.getHost());
            int level = this.maxLevel;

            // find the right level across split locations
            for (String local : maps[getIndex(tip, numReplicas)].getSplitLocations()) {
                Node datanode = jobtracker.getNode(local);
                int newLevel = this.maxLevel;
                if (tracker != null && datanode != null)
                    newLevel = getMatchingLevelForNodes(tracker, datanode);

                if (newLevel < level){
                    level = newLevel;
                    // an optimization
                    if (level == 0) {
                        break;
                    }
                }
            }

            // Choose data locality
            switch (level) {
            case 0 :
                LOG.info("Choosing data-local task " + tip.getTIPId());
                jobCounters.incrCounter(Counter.DATA_LOCAL_MAPS, 1);
                break;
            case 1:
                LOG.info("Choosing rack-local task " + tip.getTIPId());
                jobCounters.incrCounter(Counter.RACK_LOCAL_MAPS, 1);
                break;
            default :
                // check if there is any locality
                if (level != this.maxLevel) {
                    LOG.info("Choosing cached task at level " + level + tip.getTIPId());
                    jobCounters.incrCounter(Counter.OTHER_LOCAL_MAPS, 1);
                }
                break;
            }
        }
    }

    private int getIndex(TaskInProgress tip, int num_replicas) {
        if(conf.getDeferredExecution())
            return (tip.getIdWithinJob() * num_replicas) + tip.getTIPId().getReplicaNumber();

        return tip.getTIPId().getReplicaNumber() * numMapTasks + tip.getIdWithinJob();
    }

    /**
     * Note that a task has failed on a given tracker and add the tracker  
     * to the blacklist iff too many trackers in the cluster i.e. 
     * (clusterSize * CLUSTER_BLACKLIST_PERCENT) haven't turned 'flaky' already.
     * 
     * @param trackerName task-tracker on which a task failed
     */
    void addTrackerTaskFailure(String trackerName) {
        if (flakyTaskTrackers < (clusterSize * CLUSTER_BLACKLIST_PERCENT)) { 
            String trackerHostName = convertTrackerNameToHostName(trackerName);

            Integer trackerFailures = trackerToFailuresMap.get(trackerHostName);
            if (trackerFailures == null) {
                trackerFailures = 0;
            }
            trackerToFailuresMap.put(trackerHostName, ++trackerFailures);

            // Check if this tasktracker has turned 'flaky'
            if (trackerFailures.intValue() == conf.getMaxTaskFailuresPerTracker()) {
                ++flakyTaskTrackers;
                LOG.info("TaskTracker at '" + trackerHostName + "' turned 'flaky'");
            }
        }
    }

    private int getTrackerTaskFailures(String trackerName) {
        String trackerHostName = convertTrackerNameToHostName(trackerName);
        Integer failedTasks = trackerToFailuresMap.get(trackerHostName);
        return (failedTasks != null) ? failedTasks.intValue() : 0; 
    }

    /**
     * Get the black listed trackers for the job
     * 
     * @return List of blacklisted tracker names
     */
    List<String> getBlackListedTrackers() {
        List<String> blackListedTrackers = new ArrayList<String>();
        for (Map.Entry<String,Integer> e : trackerToFailuresMap.entrySet()) {
            if (e.getValue().intValue() >= conf.getMaxTaskFailuresPerTracker()) {
                blackListedTrackers.add(e.getKey());
            }
        }
        return blackListedTrackers;
    }

    /**
     * Get the no. of 'flaky' tasktrackers for a given job.
     * 
     * @return the no. of 'flaky' tasktrackers for a given job.
     */
    int getNoOfBlackListedTrackers() {
        return flakyTaskTrackers;
    }

    /**
     * Get the information on tasktrackers and no. of errors which occurred
     * on them for a given job. 
     * 
     * @return the map of tasktrackers and no. of errors which occurred
     *         on them for a given job. 
     */
    synchronized Map<String, Integer> getTaskTrackerErrors() {
        // Clone the 'trackerToFailuresMap' and return the copy
        Map<String, Integer> trackerErrors = 
                new TreeMap<String, Integer>(trackerToFailuresMap);
        return trackerErrors;
    }

    /**
     * Remove a map TIP from the lists for running maps.
     * Called when a map fails/completes (note if a map is killed,
     * it won't be present in the list since it was completed earlier)
     * @param tip the tip that needs to be retired
     */
    private synchronized void retireMap(TaskInProgress tip) {
        if (runningMapCache == null) {
            LOG.warn("Running cache for maps missing!! "
                    + "Job details are missing.");
            return;
        }

        String[] splitLocations = tip.getSplitLocations();

        // Remove the TIP from the list for running non-local maps
        if (splitLocations.length == 0) {
            nonLocalRunningMaps.remove(tip);
            return;
        }

        // Remove from the running map caches
        for(String host: splitLocations) {
            Node node = jobtracker.getNode(host);

            for (int j = 0; j < maxLevel; ++j) {
                Set<TaskInProgress> hostMaps = runningMapCache.get(node);
                if (hostMaps != null) {
                    hostMaps.remove(tip);
                    if (hostMaps.size() == 0) {
                        runningMapCache.remove(node);
                    }
                }
                node = node.getParent();
            }
        }
    }

    /**
     * Remove a reduce TIP from the list for running-reduces
     * Called when a reduce fails/completes
     * @param tip the tip that needs to be retired
     */
    private synchronized void retireReduce(TaskInProgress tip) {
        if (runningReduces == null) {
            LOG.warn("Running list for reducers missing!! "
                    + "Job details are missing.");
            return;
        }

        runningReduces.remove(tip);

        if(!conf.getDeferredExecution())
            backuprunningReduces.add(tip);
    }

    /**
     * Adds a map tip to the list of running maps.
     * @param tip the tip that needs to be scheduled as running
     */
    private synchronized void scheduleMap(TaskInProgress tip) {

        if (runningMapCache == null) {
            LOG.warn("Running cache for maps is missing!! Job details are missing.");
            return;
        }
        String[] splitLocations = tip.getSplitLocations();

        // Add the TIP to the list of non-local running TIPs
        if (splitLocations.length == 0) {
            nonLocalRunningMaps.add(tip);
            return;
        }

        for(String host: splitLocations) {
            Node node = jobtracker.getNode(host);

            for (int j = 0; j < maxLevel; ++j) {
                Set<TaskInProgress> hostMaps = runningMapCache.get(node);
                if (hostMaps == null) {
                    // create a cache if needed
                    hostMaps = new LinkedHashSet<TaskInProgress>();
                    runningMapCache.put(node, hostMaps);
                }
                hostMaps.add(tip);
                node = node.getParent();
            }
        }
    }

    /**
     * Adds a reduce tip to the list of running reduces
     * @param tip the tip that needs to be scheduled as running
     */
    private synchronized void scheduleReduce(TaskInProgress tip) {
        if (runningReduces == null) {
            LOG.warn("Running cache for reducers missing!! Job details are missing.");
            return;
        }

        LOG.debug("Adding TaskInProgress " + tip.getTIPId().toString() + " to runningReduces");
        runningReduces.add(tip);
    }

    /**
     * Adds the failed TIP in the front of the list for non-running maps
     * @param tip the tip that needs to be failed
     */
    private synchronized void failMap(TaskInProgress tip) {
        if (nonRunningMapCache == null) {
            LOG.warn("Non-running cache for maps missing!! Job details are missing.");
            return;
        }

        // 1. Its added everywhere since other nodes (having this split local)
        //    might have removed this tip from their local cache
        // 2. Give high priority to failed tip - fail early

        String[] splitLocations = tip.getSplitLocations();

        // Add the TIP in the front of the list for non-local non-running maps
        if (splitLocations.length == 0) {
            nonLocalMaps.add(0, tip);
            return;
        }

        for(String host: splitLocations) {
            Node node = jobtracker.getNode(host);

            for (int j = 0; j < maxLevel; ++j) {
                List<TaskInProgress> hostMaps = nonRunningMapCache.get(node);
                if (hostMaps == null) {
                    hostMaps = new LinkedList<TaskInProgress>();
                    nonRunningMapCache.put(node, hostMaps);
                }
                hostMaps.add(0, tip);
                node = node.getParent();
            }
        }
    }

    /**
     * Adds a failed TIP in the front of the list for non-running reduces
     * @param tip the tip that needs to be failed
     */
    private synchronized void failReduce(TaskInProgress tip) {
        if (nonRunningReduces == null) {
            LOG.warn("Failed cache for reducers missing!! "
                    + "Job details are missing.");
            return;
        }
        nonRunningReduces.add(0, tip);
    }

    /**
     * Find a non-running task in the passed list of TIPs
     * @param tips a collection of TIPs
     * @param ttStatus the status of tracker that has requested a task to run
     * @param numUniqueHosts number of unique hosts that run trask trackers
     * @param removeFailedTip whether to remove the failed tips
     */
    private synchronized TaskInProgress findTaskFromList(
            Collection<TaskInProgress> tips, 
            TaskTrackerStatus ttStatus,
            int numUniqueHosts,
            boolean removeFailedTip) {

        Iterator<TaskInProgress> iter = tips.iterator();

        while (iter.hasNext()) {
            TaskInProgress tip = iter.next();

            // Select a tip if
            //   1. runnable   : still needs to be run and is not completed
            //   2. ~running   : no other node is running it
            //   3. earlier attempt failed : has not failed on this host
            //                               and has failed on all the other hosts
            // A TIP is removed from the list if 
            // (1) this tip is scheduled
            // (2) if the passed list is a level 0 (host) cache
            // (3) when the TIP is non-schedulable (running, killed, complete)
            if (tip.isRunnable() && !tip.isRunning()) {
                // check if the tip has failed on this host
                if (!tip.hasFailedOnMachine(ttStatus.getHost()) || 
                        tip.getNumberOfFailedMachines() >= numUniqueHosts) {

                    if(tip.isJobCleanupTask() || tip.isJobSetupTask()) {
                    } else {
                        TaskCounter taskcounter     = tip.isMapTask() ? mapTaskcounter : redTaskcounter;
                        TaskCounter sumtaskcounter  = tip.isMapTask() ? summapTaskcounter : sumredTaskcounter;
                        int count = taskcounter.getCount(tip.getTIPId().getId());

                        // launch two thirds of tasks
                        boolean threshold = count < voting.getThreshold();

                        if(threshold) {
                            taskcounter.addtask(tip.getTIPId().getId());
                            sumtaskcounter.addtask(tip.getTIPId().getId());
                        } else continue;

                        launcher.addToRun(tip);
                    }

                    // check if the tip has failed on all the nodes
                    iter.remove();
                    return tip;
                } else if (removeFailedTip) { 
                    // the case where we want to remove a failed tip from the host cache
                    // point#3 in the TIP removal logic above
                    iter.remove();
                }
            } else {
                // see point#3 in the comment above for TIP removal logic
                iter.remove();
            }
        }

        return null;
    }

    /**
     * Find reduce task
     * @param tip
     * @param ttStatus
     * @param numUniqueHosts
     * @param removeFailedTip
     * @return
     */
    private synchronized TaskInProgress findTaskFromList(
            TaskInProgress tip, 
            TaskTrackerStatus ttStatus,
            int numUniqueHosts,
            boolean removeFailedTip) {

        if (tip.isRunnable() && !tip.isRunning()) {
            // check if the tip has failed on this host
            if (!tip.hasFailedOnMachine(ttStatus.getHost()) || tip.getNumberOfFailedMachines() >= numUniqueHosts) {

                if(tip.isJobCleanupTask() || tip.isJobSetupTask()) {
                } else {
                    int count = redTaskcounter.getCount(tip.getTIPId().getId());
                    LOG.debug(tip.getTIPId().toStringWithoutReplica() + "(" + tip.getTIPId().toString() + ")  launched: " 
                            + count + "(threshold: " + voting.getThreshold() + ")");

                    // launch two thirds of tasks
                    redTaskcounter.addtask(tip.getTIPId().getId());
                }

                launcher.addToRun(tip);

                return tip;
            }
        }

        return null;
    }

    private void endTaskFromList(Collection<TaskInProgress> tips) {
        Iterator<TaskInProgress> iter = tips.iterator();

        while (iter.hasNext()) {
            TaskInProgress tip = iter.next();
            tip.completeAllTasks();
        }
    }

    /**
     * Find a speculative task
     * @param list a list of tips
     * @param avgProgress the average progress for speculation
     * @param currentTime current time in milliseconds
     * @param shouldRemove whether to remove the tips
     * @return a tip that can be speculated on the tracker
     */
    private synchronized TaskInProgress findSpeculativeTask(
            Collection<TaskInProgress> list, TaskTrackerStatus ttStatus,
            double avgProgress, long currentTime, boolean shouldRemove) {

        Iterator<TaskInProgress> iter = list.iterator();

        while (iter.hasNext()) {
            TaskInProgress tip = iter.next();
            // should never be true! (since we delete completed/failed tasks)
            if (!tip.isRunning()) {
                iter.remove();
                continue;
            }

            if (!tip.hasRunOnMachine(ttStatus.getHost(), ttStatus.getTrackerName())) {
                if (tip.hasSpeculativeTask(currentTime, avgProgress)) {
                    // In case of shared list we don't remove it. Since the TIP failed 
                    // on this tracker can be scheduled on some other tracker.
                    if (shouldRemove) {
                        iter.remove(); //this tracker is never going to run it again
                    }
                    return tip;
                } 
            } else {
                // Check if this tip can be removed from the list.
                // If the list is shared then we should not remove.
                if (shouldRemove) {
                    // This tracker will never speculate this tip
                    iter.remove();
                }
            }
        }
        return null;
    }

    /**
     * Find new map task
     * @param tts The task tracker that is asking for a task
     * @param clusterSize The number of task trackers in the cluster
     * @param numUniqueHosts The number of hosts that run task trackers
     * @param avgProgress The average progress of this kind of task in this job
     * @param maxCacheLevel The maximum topology level until which to schedule
     *                      maps. 
     *                      A value of {@link #anyCacheLevel} implies any 
     *                      available task (node-local, rack-local, off-switch and 
     *                      speculative tasks).
     *                      A value of {@link #NON_LOCAL_CACHE_LEVEL} implies only
     *                      off-switch/speculative tasks should be scheduled.
     * @return the index in tasks of the selected task (or -1 for no task)
     */
    private synchronized int findNewMapTask(final TaskTrackerStatus tts, 
            final int clusterSize,
            final int numUniqueHosts,
            final int maxCacheLevel,
            final double avgProgress) {
        if (numMapTasks == 0) {
            LOG.info("No maps to schedule for " + profile.getJobID());
            return -1;
        }

        String taskTracker = tts.getTrackerName();
        TaskInProgress tip = null;

        // Update the last-known clusterSize
        this.clusterSize = clusterSize;

        if (!shouldRunOnTaskTracker(taskTracker)) {
            return -1;
        }

        // Check to ensure this TaskTracker has enough resources to 
        // run tasks from this job
        long outSize = resourceEstimator.getEstimatedMapOutputSize();
        long availSpace = tts.getResourceStatus().getAvailableSpace();
        if(availSpace < outSize) {
            LOG.warn("No room for map task. Node " + tts.getHost() + 
                    " has " + availSpace + 
                    " bytes free; but we expect map to take " + outSize);

            return -1; //see if a different TIP might work better. 
        }

        // For scheduling a map task, we have two caches and a list (optional)
        //  I)   one for non-running task
        //  II)  one for running task (this is for handling speculation)
        //  III) a list of TIPs that have empty locations (e.g., dummy splits),
        //       the list is empty if all TIPs have associated locations

        // First a look up is done on the non-running cache and on a miss, a look 
        // up is done on the running cache. The order for lookup within the cache:
        //   1. from local node to root [bottom up]
        //   2. breadth wise for all the parent nodes at max level

        // We fall to linear scan of the list (III above) if we have misses in the 
        // above caches
        Node node = jobtracker.getNode(tts.getHost());

        //
        // I) Non-running TIP :
        // 

        // 1. check from local node to the root [bottom up cache lookup]
        //    i.e if the cache is available and the host has been resolved
        //    (node!=null)
        if (node != null) {
            Node key = node;
            int level = 0;

            key = node;
            // maxCacheLevel might be greater than this.maxLevel if findNewMapTask is
            // called to schedule any task (local, rack-local, off-switch or speculative)
            // tasks or it might be NON_LOCAL_CACHE_LEVEL (i.e. -1) if findNewMapTask is
            //  (i.e. -1) if findNewMapTask is to only schedule off-switch/speculative
            // tasks
            int maxLevelToSchedule = Math.min(maxCacheLevel, maxLevel);
            for (level = 0;level < maxLevelToSchedule; ++level) {
                List <TaskInProgress> cacheForLevel = nonRunningMapCache.get(key);
                if (cacheForLevel != null) {
                    tip = findTaskFromList(cacheForLevel, tts, numUniqueHosts,level == 0);
                    if (tip != null) {
                        LOG.debug("2: " + tip.printInfo());
                        // Add to running cache
                        scheduleMap(tip);

                        // remove the cache if its empty
                        if (cacheForLevel.size() == 0) {
                            nonRunningMapCache.remove(key);
                        }

                        return getIndex(tip, numReplicas);
                    }
                }
                key = key.getParent();
            }

            // Check if we need to only schedule a local task (node-local/rack-local)
            if (level == maxCacheLevel) {
                return -1;
            }
        }

        //2. Search breadth-wise across parents at max level for non-running 
        //   TIP if
        //     - cache exists and there is a cache miss 
        //     - node information for the tracker is missing (tracker's topology
        //       info not obtained yet)

        // collection of node at max level in the cache structure
        Collection<Node> nodesAtMaxLevel = jobtracker.getNodesAtMaxLevel();

        // get the node parent at max level
        Node nodeParentAtMaxLevel = (node == null) ? null : JobTracker.getParentNode(node, maxLevel - 1);

        for (Node parent : nodesAtMaxLevel) {

            // skip the parent that has already been scanned
            if (parent == nodeParentAtMaxLevel) {
                continue;
            }


            List<TaskInProgress> cache = nonRunningMapCache.get(parent);
            if (cache != null) {
                tip = findTaskFromList(cache, tts, numUniqueHosts, false);
                if (tip != null) {
                    LOG.debug("3: " + tip.printInfo());
                    // Add to the running cache
                    scheduleMap(tip);

                    // remove the cache if empty
                    if (cache.size() == 0) {
                        nonRunningMapCache.remove(parent);
                    }
                    LOG.info("Choosing a non-local task " + tip.getTIPId());
                    //					return tip.getIdWithinJob();
                    int idx = getIndex(tip, numReplicas);
                    return idx;	
                }
            }
        }

        // 3. Search non-local tips for a new task
        tip = findTaskFromList(nonLocalMaps, tts, numUniqueHosts, false);
        if (tip != null) {
            LOG.debug("4: " + tip.printInfo());
            // Add to the running list
            scheduleMap(tip);

            LOG.info("Choosing a non-local task " + tip.getTIPId());
            int idx = getIndex(tip, numReplicas);
            return idx;
        }

        //
        // II) Running TIP :
        // 
        if (hasSpeculativeMaps) {
            long currentTime = System.currentTimeMillis();

            // 1. Check bottom up for speculative tasks from the running cache
            if (node != null) {
                Node key = node;
                for (int level = 0; level < maxLevel; ++level) {
                    Set<TaskInProgress> cacheForLevel = runningMapCache.get(key);
                    if (cacheForLevel != null) {
                        tip = findSpeculativeTask(cacheForLevel, tts, avgProgress, currentTime, level == 0);

                        if (tip != null) {
                            LOG.debug("Scheduled speculative: " + tip.getTIPId().toString());
                            LOG.debug("5: " + tip.printInfo());
                            if (cacheForLevel.size() == 0) {
                                runningMapCache.remove(key);
                            }
                            //							return tip.getIdWithinJob();
                            int idx = getIndex(tip, numReplicas);
                            return idx;	
                        }
                    }
                    key = key.getParent();
                }
            }

            // 2. Check breadth-wise for speculative tasks
            for (Node parent : nodesAtMaxLevel) {
                // ignore the parent which is already scanned
                if (parent == nodeParentAtMaxLevel) {
                    continue;
                }

                Set<TaskInProgress> cache = runningMapCache.get(parent);
                if (cache != null) {
                    tip = findSpeculativeTask(cache, tts, avgProgress, currentTime, false);
                    if (tip != null) {
                        LOG.debug("Scheduled speculative: " + tip.getTIPId().toString());
                        LOG.debug("6: " + tip.printInfo());
                        // remove empty cache entries
                        if (cache.size() == 0) {
                            runningMapCache.remove(parent);
                        }
                        LOG.info("Choosing a non-local task " + tip.getTIPId() + " for speculation");

                        int idx = getIndex(tip, numReplicas);
                        return idx;	
                    }
                }
            }

            // 3. Check non-local tips for speculation
            tip = findSpeculativeTask(nonLocalRunningMaps, tts, avgProgress, currentTime, false);
            if (tip != null) {
                LOG.debug("Scheduled speculative: " + tip.getTIPId().toString());
                LOG.info("Choosing a non-local task " + tip.getTIPId() + " for speculation");
                LOG.debug("7: " + tip.printInfo());

                int idx = getIndex(tip, numReplicas);
                return idx;	
            }
        }		

        return -1;
    }


    /**
     * Find new reduce task
     * 
     * @param tts The task tracker that is asking for a task
     * @param clusterSize The number of task trackers in the cluster
     * @param numUniqueHosts The number of hosts that run task trackers
     * @param avgProgress The average progress of this kind of task in this job
     * 
     * @return the index in tasks of the selected task (or -1 for no task)
     */
    private synchronized int findNewReduceTask(TaskTrackerStatus tts, 
            int clusterSize,
            int numUniqueHosts,
            double avgProgress) {
        if (numReduceTasks == 0) {
            LOG.info("No reduces to schedule for " + profile.getJobID());
            return -1;
        }

        String taskTracker = tts.getTrackerName();
        TaskInProgress tip = null;

        // Update the last-known clusterSize
        this.clusterSize = clusterSize;

        if (!shouldRunOnTaskTracker(taskTracker)) {
            return -1;
        }

        long outSize = resourceEstimator.getEstimatedReduceInputSize();
        long availSpace = tts.getResourceStatus().getAvailableSpace();
        if(availSpace < outSize) {
            LOG.warn("No room for reduce task. Node " + taskTracker + " has " +
                    availSpace + 
                    " bytes free; but we expect reduce input to take " + outSize);

            return -1; //see if a different TIP might work better. 
        }

        // 1. check for a never-executed reduce tip
        // reducers don't have a cache and so pass -1 to explicitly call that out
        tip = findTaskFromList(nonRunningReduces, tts, numUniqueHosts, false);

        if (tip != null) {
            scheduleReduce(tip);
            int id = (tip.getIdWithinJob() * conf.getFaultTolerance()) + tip.getTIPId().getReplicaNumber();

            return id;
        }

        // 2. check for a reduce tip to be speculated
        if (hasSpeculativeReduces) {
            tip = findSpeculativeTask(runningReduces, tts, avgProgress, System.currentTimeMillis(), false);
            if (tip != null) {
                LOG.debug("Scheduled speculative: " + tip.getTIPId().toString());
                scheduleReduce(tip);

                int id = (tip.getIdWithinJob() * conf.getFaultTolerance()) + tip.getTIPId().getReplicaNumber();
                return id;
            }
        }

        if(conf.getDeferredExecution()) {// this only works when deferred execution is selected
            int id = voting.getTaskWithoutMajority();// get the id of a task that hasn't got a majority of digests
            if(id>=0) {
                return obtainBackupTask(nonRunningReduces, id, tts, numUniqueHosts, false);
            }
        }


        return -1;
    }

    /**
     * When there is no majority of digests, it returns another task
     * @param tips
     * @param id
     * @param ttStatus
     * @param numUniqueHosts
     * @param removeFailedTip
     * @return
     */
    private int obtainBackupTask(Collection<TaskInProgress> tips,
            int id,
            TaskTrackerStatus ttStatus,
            int numUniqueHosts,
            boolean removeFailedTip) {

        List<TaskInProgress> aux = new ArrayList<TaskInProgress>();

        Iterator<TaskInProgress> iter = tips.iterator();
        while(iter.hasNext()) {
            TaskInProgress tip = iter.next();
            if(tip.getTIPId().getId() == id) {
                iter.remove();
                aux.add(tip);
                break;
            }
        }

        if(aux.size() > 0) {
            TaskInProgress tip = findTaskFromList(aux.get(0), ttStatus, numUniqueHosts, false);

            if (tip != null) {	
                scheduleReduce(tip);
                return (tip.getIdWithinJob() * conf.getFaultTolerance()) + tip.getTIPId().getReplicaNumber();
            } else
                tips.add(tip);
        }

        return -1;
    }

    private boolean shouldRunOnTaskTracker(String taskTracker) {
        //
        // Check if too many tasks of this job have failed on this
        // tasktracker prior to assigning it a new one.
        int taskTrackerFailedTasks = getTrackerTaskFailures(taskTracker);
        if ((flakyTaskTrackers < (clusterSize * CLUSTER_BLACKLIST_PERCENT)) && 
                taskTrackerFailedTasks >= conf.getMaxTaskFailuresPerTracker()) {
            if (LOG.isDebugEnabled()) {
                String flakyTracker = convertTrackerNameToHostName(taskTracker); 
                LOG.debug("Ignoring the black-listed tasktracker: '" + flakyTracker + "' for assigning a new task");
            }

            return false;
        }

        return true;
    }

    public synchronized boolean completedTask(TaskInProgress tip, TaskStatus status) {
        TaskAttemptID taskid = status.getTaskID();
        int oldNumAttempts = tip.getActiveTasks().size();
        final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();

        LOG.debug("Tip " + tip.getTIPId().toString() + " is complete? " + tip.isComplete());

        // Sanity check: is the TIP already complete?
        // It _is_ safe to not decrement running{Map|Reduce}Tasks and
        // finished{Map|Reduce}Tasks variables here because one and only
        // one task-attempt of a TIP gets to completedTask. This is because
        // the TaskCommitThread in the JobTracker marks other, completed,
        // speculative tasks as _complete_.
        if (tip.isComplete()) {
            // Mark this task as KILLED
            tip.alreadyCompletedTask(taskid);

            // Let the JobTracker cleanup this taskid if the job isn't running
            if (this.status.getRunState() != JobStatus.RUNNING) {
                jobtracker.markCompletedTaskAttempt(status.getTaskTracker(), taskid);
            }

            return false;
        }

        // Mark the TIP as complete
        LOG.debug(taskid.toString() + " completed");
        tip.completed(taskid);

        resourceEstimator.updateWithCompletedTask(status, tip);

        // Update jobhistory
        TaskTrackerStatus ttStatus = this.jobtracker.getTaskTracker(status.getTaskTracker());
        String trackerHostname = jobtracker.getNode(ttStatus.getHost()).toString();
        String taskType = getTaskType(tip);

        if (status.getIsMap()){ // MAP side

            JobHistory.MapAttempt.logStarted(status.getTaskID(), status.getStartTime(),
                    status.getTaskTracker(),
                    ttStatus.getHttpPort(),
                    taskType);
            JobHistory.MapAttempt.logFinished(status.getTaskID(), status.getFinishTime(),
                    trackerHostname, taskType,
                    status.getStateString(),
                    status.getCounters());
        }
        else { // REDUCE side
            JobHistory.ReduceAttempt.logStarted( status.getTaskID(), status.getStartTime(),
                    status.getTaskTracker(),
                    ttStatus.getHttpPort(),
                    taskType);
            JobHistory.ReduceAttempt.logFinished(status.getTaskID(), status.getShuffleFinishTime(),
                    status.getSortFinishTime(), status.getFinishTime(),
                    trackerHostname,
                    taskType,
                    status.getStateString(),
                    status.getCounters());
        }

        JobHistory.Task.logFinished(tip.getTIPId(),
                taskType,
                tip.getExecFinishTime(),
                status.getCounters());


        int newNumAttempts = tip.getActiveTasks().size();
        if (tip.isJobSetupTask()) {
            // setup task has finished. kill the extra setup tip
            killSetupTip(!tip.isMapTask());
            // Job can start running now.
            this.status.setSetupProgress(1.0f);
            // move the job to running state if the job is in prep state
            if (this.status.getRunState() == JobStatus.PREP) {
                this.status.setRunState(JobStatus.RUNNING);
                JobHistory.JobInfo.logStarted(profile.getJobID());
            }
        } else if (tip.isJobCleanupTask()) {
            // cleanup task has finished. Kill the extra cleanup tip
            if (tip.isMapTask()) {
                // kill the reduce tip
                cleanup[1].kill();
            } else {
                cleanup[0].kill();
            }
            //
            // The Job is done
            // if the job is failed, then mark the job failed.
            if (jobFailed) {
                terminateJob(JobStatus.FAILED);
            }
            // if the job is killed, then mark the job killed.
            if (jobKilled) {
                terminateJob(JobStatus.KILLED);
            }
            else {
                LOG.debug("Job " + getJobID().toString() + " done.");
                jobComplete();
            }
            // The job has been killed/failed/successful
            // JobTracker should cleanup this task
            jobtracker.markCompletedTaskAttempt(status.getTaskTracker(), taskid);
        } else if (tip.isMapTask()) {
            // save map digests
            if(LOG.isDebugEnabled()) {
                String[] digests = status.getDigests();
                for (String d : digests) {
                    LOG.debug("Add digest to " + tip.getTIPId().toString() + ": " + ShaAbstractHash.convertHashToString(d.getBytes()));
                }
            }

            voting.addHash(tip.getTIPId(), true, status.getDigests());

            runningMapTasks -= 1;

            // check if this was a speculative task
            if (oldNumAttempts > 1)
                speculativeMapTasks -= (oldNumAttempts - newNumAttempts);

            finishedMapTasks += 1;

            mapTaskCompletionEventTracker[taskid.getTaskID().getId()]++;
            // if it's deferred execution, check if there is some taskcompletionevent to run
            if(conf.getDeferredExecution()) {
                executionDecision(taskid.getTaskID());
            } else {
                List<TaskCompletionEvent> events = voting.getTaskCompletionEvent(taskid.getTaskID());

                /**
                 * 1 - First map task is always dispatched
                 * 2 - subsequent map tasks:
                 *      a) if x value is equal to the first one, no problem.
                 *      b) if it's different from the first one
                 *          b1) if it still haven't got a majority of values, and it's the last map task that ran, the job will fail.
                 *          b2) if it still haven't got a majority of values, and there's more task to ran. Wait for more map tasks to finish
                 *          b3) if we've a majority of values and the new digest is different from the first one, execute the task
                 */
                // there's no map element saved. start passing data to reduce
                for(TaskCompletionEvent event : events) {
                    if(mapTaskCompletionEventTracker[taskid.getTaskID().getId()] == 1) {// is it the first task?
                        voting.addFirst(event);
                        voting.addFirstHash(tip.getTIPId(), status.getDigests());
                    } else {
                        int maj = executionDecision(taskid.getTaskID());

                        String[] digest = voting.getFirstHash(taskid.getTaskID());
                        if(maj == MajorityVoting.MAJORITY) {
                            if(!voting.digestsEquals(status.getDigests(), digest)) {
                                // relaunch all reduce tasks finished
                                relaunchReduceTask();

                            }
                        }
                    }

                    taskCompletionEvents.add(event);
                    taskCompletionEventTracker++;
                }
            }

            decreaseCount(mapCount, Util.getTaskID(taskid).toStringWithoutReplica());
            metrics.completeMap(taskid);

            LOG.debug(tip.getTIPId() + " has finished.");
            launcher.addSuccessfulMap(tip.getTIPId());

            // remove the completed map from the resp running caches
            retireMap(tip);
            if ((finishedMapTasks + failedMapTIPs) == replicatedNumMapTasks) {
                this.status.setMapProgress(1.0f);
            }
        } else {
            // save reduce digests
            synchronized (reduce_voters) {
                if(LOG.isDebugEnabled()) {
                    String[] digests = status.getDigests();
                    for (String d : digests) {
                        LOG.debug("Add digest to " + tip.getTIPId().toString() + ": " + ShaAbstractHash.convertHashToString(d.getBytes()));
                    }
                }

                voting.addHash(tip.getTIPId(), false, status.getDigests());

                int maj = voting.hasMajorityOfDigests(tip.getTIPId());
                reduce_voters[tip.getTIPId().getId()] = maj < MajorityVoting.MAJORITY ? false : true;


                if(maj == MajorityVoting.NO_MAJORITY) {
                    TaskCounter taskcounter = redTaskcounter;
                    taskcounter.removetask(tip.getTIPId().getId());
                }

                runningReduceTasks -= 1;

                if (oldNumAttempts > 1)
                    speculativeReduceTasks -= (oldNumAttempts - newNumAttempts);

                finishedReduceTasks += 1;
                metrics.completeReduce(taskid);
                decreaseCount(redCount, Util.getTaskID(taskid).toStringWithoutReplica());

                // remove the completed reduces from the running reducers set
                retireReduce(tip);

                boolean flag = true;
                for(int i=0; i<reduce_voters.length; i++) {
                    flag &= reduce_voters[i];
                }

                if(flag)
                    jobComplete();

                if((finishedReduceTasks == replicatedNumReduceTasks && !flag))
                    fail();

                // check if all replicas ended and no majority is found.
                // in that case, fail
                if(maj == MajorityVoting.NO_MAJORITY ) {
                    List<TaskID> t = voting.getTask(tip.getTIPId());
                    if(t != null && t.size() == numReplicas)
                        fail();
                }
            }
        }

        return true;
    }

    /**
     * Re-launching reduce task
     */
    private void relaunchReduceTask() {
        Iterator<TaskInProgress> iter = backuprunningReduces.iterator();
        while(iter.hasNext()) {
            TaskInProgress t = iter.next();
            LOG.info("Relaunching reduce task " + t.getTIPId().toString());
            scheduleReduce(t);
        }
    }

    /**
     * Verifies if it got a majority of digests. If so, notifies the completion of the task
     *
     * @param taskid
     */
    private int executionDecision(TaskID taskid) {
        int maj = voting.hasMajorityOfDigests(taskid);

        map_voters[taskid.getId()] = maj == MajorityVoting.MAJORITY ? true : false;

        if(maj == MajorityVoting.MAJORITY) {
            List<TaskCompletionEvent> events = voting.getTaskCompletionEvent(taskid);
            if(events != null && events.size() > 0) {
                for(TaskCompletionEvent event : events) {
                    taskCompletionEvents.add(event);
                    taskCompletionEventTracker++;
                }
            }
        }
        // this is in the case, the digests of the map tasks aren't equals, there's the need to execute more tasks
        else if(maj == MajorityVoting.NO_MAJORITY) {
            TaskCounter sumtaskcounter = summapTaskcounter;
            int count = sumtaskcounter.getCount(taskid.getId());

            if(count == numReplicas) {// no more replicas can be launched
                LOG.error(taskid.toString() + " didn't produced a majority of digests. All job will fail.");
                fail();
            }
            else {
                mapTaskcounter.removetask(taskid.getId());// register the task that failed
            }
        }

        return maj;
    }

    private boolean valid(boolean[] array) {
        if(array.length == 0)
            return false;

        for(int i=0; i<array.length; i++)
            if(!array[i])
                return false;

        return true;
    }

    /**
     * The job is done since all it's component tasks are either
     * successful or have failed.
     */
    private void jobComplete() {
        final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();
        //
        // All tasks are complete, then the job is done!
        //
        if (this.status.getRunState() == JobStatus.RUNNING ) {
            this.status.setRunState(JobStatus.SUCCEEDED);
            this.status.setCleanupProgress(1.0f);
            if (maps.length == 0) {
                this.status.setMapProgress(1.0f);
            }
            if (reduces.length == 0) {
                this.status.setReduceProgress(1.0f);
            }
            this.finishTime = System.currentTimeMillis();
            status.setEndTime(this.finishTime);

            LOG.info("Job " + this.status.getJobID() + " has completed successfully.");
            JobHistory.JobInfo.logFinished(this.status.getJobID(), finishTime,
                    this.finishedMapTasks,
                    this.finishedReduceTasks, failedMapTasks,
                    failedReduceTasks, getCounters());
            // Note that finalize will close the job history handles which garbage collect
            // might try to finalize
            garbageCollect();

            metrics.completeJob(this.conf, this.status.getJobID());
        }
    }

    private synchronized void terminateJob(int jobTerminationState) {
        if ((status.getRunState() == JobStatus.RUNNING) ||
                (status.getRunState() == JobStatus.PREP)) {
            if (jobTerminationState == JobStatus.FAILED) {
                this.status = new JobStatus(status.getJobID(),
                        1.0f, 1.0f, 1.0f, JobStatus.FAILED,
                        status.getJobPriority());
                this.finishTime = System.currentTimeMillis();
                JobHistory.JobInfo.logFailed(this.status.getJobID(), finishTime,
                        this.finishedMapTasks,
                        this.finishedReduceTasks);
            } else {
                this.status = new JobStatus(status.getJobID(),
                        1.0f, 1.0f, 1.0f, JobStatus.KILLED,
                        status.getJobPriority());
                this.finishTime = System.currentTimeMillis();
                JobHistory.JobInfo.logKilled(this.status.getJobID(), finishTime,
                        this.finishedMapTasks,
                        this.finishedReduceTasks);
            }
            garbageCollect();
            jobtracker.getInstrumentation().terminateJob(this.conf, this.status.getJobID());
        }
    }

    public void stopIt() {
        endProcess.stopIt();
    }

    /**
     * Terminate the job and all its component tasks.
     *
     * Calling this will lead to marking the job as failed/killed. Cleanup
     * tip will be launched. If the job has not initiated, it will directly call
     * terminateJob as there is no need to launch cleanup tip.
     *
     * This method is reentrant.
     *
     * @param jobTerminationState job termination state
     */
    private synchronized void terminate(int jobTerminationState) {
        if(!tasksInited.get()) {
            //init could not be done, we just terminate directly.
            terminateJob(jobTerminationState);
            return;
        }

        if ((status.getRunState() == JobStatus.RUNNING) ||
                (status.getRunState() == JobStatus.PREP)) {
            LOG.info("Killing job '" + this.status.getJobID() + "'");
            if (jobTerminationState == JobStatus.FAILED) {
                if(jobFailed) {//reentrant
                    return;
                }

                jobFailed = true;
            } else if (jobTerminationState == JobStatus.KILLED) {
                if(jobKilled) {//reentrant
                    return;
                }

                jobKilled = true;
            }
            // clear all unclean tasks
            clearUncleanTasks();
            //
            // kill all TIPs.
            //
            for (int i = 0; i < setup.length; i++) {
                setup[i].kill();
            }
            for (int i = 0; i < maps.length; i++) {
                maps[i].kill();
            }
            for (int i = 0; i < reduces.length; i++) {
                reduces[i].kill();
            }
        }
    }

    /**
     * remove all tasks from mapCleanupTasks and/or reduceCleanupTasks
     */
    private void clearUncleanTasks() {
        TaskAttemptID taskid = null;
        TaskInProgress tip = null;

        while (!mapCleanupTasks.isEmpty()) {
            taskid = mapCleanupTasks.remove(0);
            tip = maps[taskid.getTaskID().getId()];

            updateTaskStatus(tip, tip.getTaskStatus(taskid));
        }

        while (!reduceCleanupTasks.isEmpty()) {
            taskid = reduceCleanupTasks.remove(0);
            tip = reduces[taskid.getTaskID().getId()];
            updateTaskStatus(tip, tip.getTaskStatus(taskid));
        }
    }

    /**
     * Kill the job and all its component tasks. This method should be called from
     * jobtracker and should return fast as it locks the jobtracker.
     */
    public void kill() {
        boolean killNow = false;
        synchronized(jobInitKillStatus) {
            jobInitKillStatus.killed = true;
            //if not in middle of init, terminate it now
            if(!jobInitKillStatus.initStarted || jobInitKillStatus.initDone) {
                //avoiding nested locking by setting flag
                killNow = true;
            }
        }
        if(killNow) {
            terminate(JobStatus.KILLED);
        }
    }

    /**
     * Fails the job and all its component tasks. This should be called only from
     * {@link JobInProgress} or {@link JobTracker}. Look at
     * {@link JobTracker#failJob(JobInProgress)} for more details.
     */
    synchronized void fail() {
        stopIt();
        terminate(JobStatus.FAILED);
    }

    /**
     * A task assigned to this JobInProgress has reported in as failed.
     * Most of the time, we'll just reschedule execution.  However, after
     * many repeated failures we may instead decide to allow the entire
     * job to fail or succeed if the user doesn't care about a few tasks failing.
     *
     * Even if a task has reported as completed in the past, it might later
     * be reported as failed.  That's because the TaskTracker that hosts a map
     * task might die before the entire job can complete.  If that happens,
     * we need to schedule reexecution so that downstream reduce tasks can
     * obtain the map task's output.
     */
    private void failedTask(TaskInProgress tip, TaskAttemptID taskid,
            TaskStatus status,
            TaskTrackerStatus taskTrackerStatus,
            boolean wasRunning, boolean wasComplete) {
        final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();
        // check if the TIP is already failed
        boolean wasFailed = tip.isFailed();

        // Mark the taskid as FAILED or KILLED
        tip.incompleteSubTask(taskid, this.status);

        boolean isRunning = tip.isRunning();
        boolean isComplete = tip.isComplete();

        if(!taskid.getTaskID().isSetupOrCleanup()) {
            TaskCounter counter = taskid.isMap() ? mapTaskcounter : redTaskcounter;
            counter.removetask(taskid.getTaskID().getId());
        }

        //update running  count on task failure.
        if (wasRunning && !isRunning) {
            if (tip.isJobCleanupTask()) {
                launchedCleanup = false;
            } else if (tip.isJobSetupTask()) {
                launchedSetup = false;
            } else if (tip.isMapTask()) {
                runningMapTasks -= 1;
                metrics.failedMap(taskid);
                // remove from the running queue and put it in the non-running cache
                // if the tip is not complete i.e if the tip still needs to be run
                if (!isComplete) {
                    retireMap(tip);
                    failMap(tip);
                }
            } else {
                runningReduceTasks -= 1;
                metrics.failedReduce(taskid);
                // remove from the running queue and put in the failed queue if the tip
                // is not complete
                if (!isComplete) {
                    retireReduce(tip);
                    failReduce(tip);
                }
            }
        }

        // the case when the map was complete but the task tracker went down.
        if (wasComplete && !isComplete) {
            if (tip.isMapTask()) {
                // Put the task back in the cache. This will help locality for cases
                // where we have a different TaskTracker from the same rack/switch
                // asking for a task.
                // We bother about only those TIPs that were successful
                // earlier (wasComplete and !isComplete)
                // (since they might have been removed from the cache of other
                // racks/switches, if the input split blocks were present there too)
                failMap(tip);
                finishedMapTasks -= 1;
            }
        }

        // update job history
        // get taskStatus from tip
        TaskStatus taskStatus       = tip.getTaskStatus(taskid);
        int a=0;
        if(taskStatus.getTaskTracker() == null)
            a=1;
        String taskTrackerName      = taskStatus.getTaskTracker();
        String taskTrackerHostName  = convertTrackerNameToHostName(taskTrackerName);
        int taskTrackerPort = -1;
        if (taskTrackerStatus != null) {
            taskTrackerPort = taskTrackerStatus.getHttpPort();
        }

        long startTime = taskStatus.getStartTime();
        long finishTime = taskStatus.getFinishTime();
        List<String> taskDiagnosticInfo = tip.getDiagnosticInfo(taskid);
        String diagInfo = taskDiagnosticInfo == null ? "" :
            StringUtils.arrayToString(taskDiagnosticInfo.toArray(new String[0]));
        String taskType = getTaskType(tip);
        if (taskStatus.getIsMap()) {
            JobHistory.MapAttempt.logStarted(taskid, startTime,
                    taskTrackerName, taskTrackerPort, taskType);
            if (taskStatus.getRunState() == TaskStatus.State.FAILED) {
                JobHistory.MapAttempt.logFailed(taskid, finishTime,
                        taskTrackerHostName, diagInfo, taskType);
            } else {
                JobHistory.MapAttempt.logKilled(taskid, finishTime,
                        taskTrackerHostName, diagInfo, taskType);
            }
        } else {
            JobHistory.ReduceAttempt.logStarted(taskid, startTime,
                    taskTrackerName, taskTrackerPort, taskType);
            if (taskStatus.getRunState() == TaskStatus.State.FAILED) {
                JobHistory.ReduceAttempt.logFailed(taskid, finishTime,
                        taskTrackerHostName, diagInfo, taskType);
            } else {
                JobHistory.ReduceAttempt.logKilled(taskid, finishTime,
                        taskTrackerHostName, diagInfo, taskType);
            }
        }

        // After this, try to assign tasks with the one after this, so that
        // the failed task goes to the end of the list.
        if (!tip.isJobCleanupTask() && !tip.isJobSetupTask()) {
            if (tip.isMapTask()) {
                failedMapTasks++;
            } else {
                failedReduceTasks++;
            }
        }

        // Note down that a task has failed on this tasktracker
        if (status.getRunState() == TaskStatus.State.FAILED) {
            addTrackerTaskFailure(taskTrackerName);
        }

        //
        // Let the JobTracker know that this task has failed
        //
        jobtracker.markCompletedTaskAttempt(status.getTaskTracker(), taskid);

        //
        // Check if we need to kill the job because of too many failures or
        // if the job is complete since all component tasks have completed

        // We do it once per TIP and that too for the task that fails the TIP
        if (!wasFailed && tip.isFailed()) {
            //
            // Allow upto 'mapFailuresPercent' of map tasks to fail or
            // 'reduceFailuresPercent' of reduce tasks to fail
            //
            boolean killJob = tip.isJobCleanupTask() || tip.isJobSetupTask() ? true :
                tip.isMapTask() ?
                        ((++failedMapTIPs*100) > (mapFailuresPercent*numMapTasks)) :
                            ((++failedReduceTIPs*100) > (reduceFailuresPercent*numReduceTasks));

                        if (killJob) {
                            LOG.info("Aborting job " + profile.getJobID());
                            JobHistory.Task.logFailed(tip.getTIPId(),
                                    taskType,
                                    finishTime,
                                    diagInfo);
                            if (tip.isJobCleanupTask()) {
                                // kill the other tip
                                if (tip.isMapTask()) {
                                    cleanup[1].kill();
                                } else {
                                    cleanup[0].kill();
                                }
                                terminateJob(JobStatus.FAILED);
                            } else {
                                if (tip.isJobSetupTask()) {
                                    // kill the other tip
                                    killSetupTip(!tip.isMapTask());
                                }
                                fail();
                            }
                        }

                        // Update the counters
                        if (!tip.isJobCleanupTask() && !tip.isJobSetupTask()) {
                            if (tip.isMapTask()) {
                                jobCounters.incrCounter(Counter.NUM_FAILED_MAPS, 1);
                            } else {
                                jobCounters.incrCounter(Counter.NUM_FAILED_REDUCES, 1);
                            }
                        }
        }
    }

    void killSetupTip(boolean isMap) {
        if (isMap) {
            setup[0].kill();
        } else {
            setup[1].kill();
        }
    }

    boolean isSetupFinished() {
        return setup[0].isComplete() || setup[0].isFailed() || setup[1].isComplete()
                || setup[1].isFailed();
    }

    /**
     * Fail a task with a given reason, but without a status object.
     *
     * Assuming {@link JobTracker} is locked on entry.
     *
     * @param tip The task's tip
     * @param taskid The task id
     * @param reason The reason that the task failed
     * @param trackerName The task tracker the task failed on
     */
    public void failedTask(TaskInProgress tip, TaskAttemptID taskid, String reason,
            TaskStatus.Phase phase, TaskStatus.State state,
            String trackerName) {
        TaskStatus status = TaskStatus.createTaskStatus(tip.isMapTask(),
                taskid,
                0.0f,
                state,
                reason,
                reason,
                trackerName, phase,
                new Counters());

        // update the actual start-time of the attempt
        TaskStatus oldStatus = tip.getTaskStatus(taskid);
        long startTime = oldStatus == null ? System.currentTimeMillis() : oldStatus.getStartTime();
        status.setStartTime(startTime);
        status.setFinishTime(System.currentTimeMillis());
        boolean wasComplete = tip.isComplete();
        updateTaskStatus(tip, status);
        boolean isComplete = tip.isComplete();

        if (wasComplete && !isComplete) { // mark a successful tip as failed
            String taskType = getTaskType(tip);
            JobHistory.Task.logFailed(tip.getTIPId(), taskType, tip.getExecFinishTime(), reason, taskid);
        }
    }

    /**
     * The job is dead.  We're now GC'ing it, getting rid of the job
     * from all tables.  Be sure to remove all of this job's tasks
     * from the various tables.
     */
    synchronized void garbageCollect() {
        // Let the JobTracker know that a job is complete
        jobtracker.getInstrumentation().decWaiting(getJobID(), pendingMaps() + pendingReduces());
        jobtracker.storeCompletedJob(this);
        jobtracker.finalizeJob(this);

        try {
            // Definitely remove the local-disk copy of the job file
            if (localJobFile != null) {
                localFs.delete(localJobFile, true);
                localJobFile = null;
            }
            if (localJarFile != null) {
                localFs.delete(localJarFile, true);
                localJarFile = null;
            }

            // clean up splits
            for (int i = 0; i < maps.length; i++) {
                maps[i].clearSplit();
            }

            // JobClient always creates a new directory with job files
            // so we remove that directory to cleanup
            // Delete temp dfs dirs created if any, like in case of
            // speculative exn of reduces.
            Path tempDir = jobtracker.getSystemDirectoryForJob(getJobID());
            new CleanupQueue().addToQueue(conf,tempDir);
        } catch (IOException e) {
            LOG.warn("Error cleaning up "+profile.getJobID()+": "+e);
        }

        cleanUpMetrics();
        // free up the memory used by the data structures
        this.nonRunningMapCache = null;
        this.runningMapCache = null;
        this.nonRunningReduces = null;
        this.runningReduces = null;

    }

    /**
     * Return the TaskInProgress that matches the tipid.
     */
    public synchronized TaskInProgress getTaskInProgress(TaskID tipid) {
        if (tipid.isMap()) {
            if (tipid.equals(cleanup[0].getTIPId())) { // cleanup map tip
                return cleanup[0];
            }
            if (tipid.equals(setup[0].getTIPId())) { //setup map tip
                return setup[0];
            }

            for (TaskInProgress tip : maps) {
                if (tip != null && tipid.toString().equals(tip.getTIPId().toString()))
                    return tip;
            }
        } else {
            if (tipid.equals(cleanup[1].getTIPId())) { // cleanup reduce tip
                return cleanup[1];
            }
            if (tipid.equals(setup[1].getTIPId())) { //setup reduce tip
                return setup[1];
            }
            for (TaskInProgress tip : reduces) {
                if (tip != null && tipid.toString().equals(tip.getTIPId().toString()))
                    return tip;
            }
        }
        return null;
    }

    /**
     * Find the details of someplace where a map has finished
     * @param mapId the id of the map
     * @return the task status of the completed task
     */
    public synchronized TaskStatus findFinishedMap(int mapId) {
        TaskInProgress tip = maps[mapId];
        if (tip.isComplete()) {
            TaskStatus[] statuses = tip.getTaskStatuses();
            for(int i=0; i < statuses.length; i++) {
                if (statuses[i].getRunState() == TaskStatus.State.SUCCEEDED) {
                    return statuses[i];
                }
            }
        }
        return null;
    }

    synchronized int getNumTaskCompletionEvents() {
        return taskCompletionEvents.size();
    }

    /**
     * Get map task finished
     * @param fromEventId
     * @param maxEvents
     * @return
     */
    synchronized public TaskCompletionEvent[] getTaskCompletionEvents(int fromEventId, int maxEvents) {
        TaskCompletionEvent[] events = TaskCompletionEvent.EMPTY_ARRAY;

        if (taskCompletionEvents.size() > fromEventId) {
            int actualMax = Math.min(maxEvents, (taskCompletionEvents.size() - fromEventId));
            events = taskCompletionEvents.subList(fromEventId, actualMax + fromEventId).toArray(events);
        }

        return events;
    }

    /*
     *  at org.apache.hadoop.mapred.JobInProgress.fetchFailureNotification(JobInProgress.java:2863)
        at org.apache.hadoop.mapred.JobTracker.updateTaskStatuses(JobTracker.java:3653)
        at org.apache.hadoop.mapred.JobTracker.processHeartbeat(JobTracker.java:2792)
        at org.apache.hadoop.mapred.JobTracker.heartbeat(JobTracker.java:2564)
     */
    synchronized void fetchFailureNotification(TaskInProgress tip,
            TaskAttemptID mapTaskId, String trackerName) {
        Integer fetchFailures = mapTaskIdToFetchFailuresMap.get(mapTaskId);

        fetchFailures = (fetchFailures == null) ? 1 : (fetchFailures+1);
        mapTaskIdToFetchFailuresMap.put(mapTaskId, fetchFailures);

        LOG.info("Failed fetch notification #" + fetchFailures + " for task " + mapTaskId);

        float failureRate = (float)fetchFailures / runningReduceTasks;

        // declare faulty if fetch-failures >= max-allowed-failures
        boolean isMapFaulty = (failureRate >= MAX_ALLOWED_FETCH_FAILURES_PERCENT) ? true : false;

        if (fetchFailures >= MAX_FETCH_FAILURES_NOTIFICATIONS && isMapFaulty) {
            LOG.info("Too many fetch-failures for output of task: " + mapTaskId + " ... killing it");

            failedTask(tip, mapTaskId, "Too many fetch-failures",
                    (tip.isMapTask() ? TaskStatus.Phase.MAP : TaskStatus.Phase.REDUCE),
                    TaskStatus.State.FAILED, trackerName);

            mapTaskIdToFetchFailuresMap.remove(mapTaskId);
            mapTaskcounter.removetask(mapTaskId.getTaskID().getId());
        }
    }

    /**
     * @return The JobID of this JobInProgress.
     */
    public JobID getJobID() {
        return jobId;
    }

    public synchronized Object getSchedulingInfo() {
        return this.schedulingInfo;
    }

    public synchronized void setSchedulingInfo(Object schedulingInfo) {
        this.schedulingInfo = schedulingInfo;
        this.status.setSchedulingInfo(schedulingInfo.toString());
    }

    boolean isComplete() {
        return status.isJobComplete();
    }

    /**
     * Get the task type for logging it to {@link JobHistory}.
     */
    private String getTaskType(TaskInProgress tip) {
        if (tip.isJobCleanupTask()) {
            return Values.CLEANUP.name();
        } else if (tip.isJobSetupTask()) {
            return Values.SETUP.name();
        } else if (tip.isMapTask()) {
            return Values.MAP.name();
        } else {
            return Values.REDUCE.name();
        }
    }

    public Map<String, Integer> getMapTaskTrackerCount() {
        Map<String, Integer> result = new HashMap<String, Integer>();

        for(TaskTrackerLocation tracker : mapTrackerLocation) {
            if(!result.containsKey(tracker.getTaskTrackerName())) {
                result.put(tracker.getTaskTrackerName(), 0);
            }

            Integer count = result.get(tracker.getTaskTrackerName());
            count++;
            result.put(tracker.getTaskTrackerName(), count);
        }

        return result;
    }

    public Map<String, Integer> getRedTaskTrackerCount() {
        Map<String, Integer> result = new HashMap<String, Integer>();

        for(TaskTrackerLocation tracker : redTrackerLocation) {
            if(!result.containsKey(tracker.getTaskTrackerName())) {
                result.put(tracker.getTaskTrackerName(), 0);
            }

            Integer count = result.get(tracker.getTaskTrackerName());
            count++;
            result.put(tracker.getTaskTrackerName(), count);
        }

        return result;
    }

    private boolean decreaseCount(Map<String, Integer> map, String taskId) {
        if(map.containsKey(taskId)) {
            Integer count = map.get(taskId);
            if(count > 0) {
                count--;
                map.put(taskId, count);
                return true;
            }
        }

        return false;
    }

    private void increaseCount(Map<String, Integer> map, String taskId) {
        if(!map.containsKey(taskId)) {
            map.put(taskId, 0);
        }
        Integer count = map.get(taskId);
        count++;
        map.put(taskId, count);
    }

    private int getCount(Map<String, Integer> map, String taskId) {
        if(!map.containsKey(taskId)) {
            return -1;
        }

        return map.get(taskId);
    }

    /**
     * Check if all map tasks completed
     * @return
     */
    private boolean allCompleteCount() {
        boolean result = true;
        Iterator<String> iter = mapCount.keySet().iterator();

        while(iter.hasNext()) {
            String taskId = iter.next();
            Integer count = mapCount.get(taskId);

            if(count > 0)
                return false;
        }

        return false;
    }

    public int getFailedMapTasks() {
        return failedMapTasks;
    }

    public void setFailedMapTasks(int failedMapTasks) {
        this.failedMapTasks = failedMapTasks;
    }

    public int getFailedMapTIPs() {
        return failedMapTIPs;
    }

    public void setFailedMapTIPs(int failedMapTIPs) {
        this.failedMapTIPs = failedMapTIPs;
    }

    public int getRunningMapTasks() {
        return runningMapTasks;
    }

    public void setRunningMapTasks(int runningMapTasks) {
        this.runningMapTasks = runningMapTasks;
    }

    public int getRunningReduceTasks() {
        return runningReduceTasks;
    }

    public void setRunningReduceTasks(int runningReduceTasks) {
        this.runningReduceTasks = runningReduceTasks;
    }

    public int getFinishedMapTasks() {
        return finishedMapTasks;
    }

    public void setFinishedMapTasks(int finishedMapTasks) {
        this.finishedMapTasks = finishedMapTasks;
    }

    public int getFinishedReduceTasks() {
        return finishedReduceTasks;
    }

    public void setFinishedReduceTasks(int finishedReduceTasks) {
        this.finishedReduceTasks = finishedReduceTasks;
    }

    public int getFailedReduceTasks() {
        return failedReduceTasks;
    }

    public void setFailedReduceTasks(int failedReduceTasks) {
        this.failedReduceTasks = failedReduceTasks;
    }

    public int getNumMapTasks() {
        return numMapTasks;
    }

    public void setNumMapTasks(int numMapTasks) {
        this.numMapTasks = numMapTasks;
    }

    public int getReplicatedNumMapTasks() {
        return replicatedNumMapTasks;
    }

    public void setReplicatedNumMapTasks(int replicatedNumMapTasks) {
        this.replicatedNumMapTasks = replicatedNumMapTasks;
    }

    public int getNumReduceTasks() {
        return numReduceTasks;
    }

    public void setNumReduceTasks(int numReduceTasks) {
        this.numReduceTasks = numReduceTasks;
    }

    public int getReplicatedNumReduceTasks() {
        return replicatedNumReduceTasks;
    }

    public void setReplicatedNumReduceTasks(int replicatedNumReduceTasks) {
        this.replicatedNumReduceTasks = replicatedNumReduceTasks;
    }

    public int getTamperedMap() {
        return tamperedMap;
    }

    public void addTamperedMap() {
        this.tamperedMap++;
    }

    public int getTamperedReduce() {
        return tamperedReduce;
    }

    public void addTamperedReduce() {
        this.tamperedReduce++;
    }

    // Per-job counters
    public enum Counter {
        NUMBER_REPLICAS,
        TOTAL_MAPS,
        NUM_FAILED_MAPS,
        NUM_FAILED_REDUCES,
        TOTAL_LAUNCHED_MAPS,
        TOTAL_LAUNCHED_REDUCES,
        OTHER_LOCAL_MAPS,
        DATA_LOCAL_MAPS,
        RACK_LOCAL_MAPS,
    }

    /**
     * Used when the a kill is issued to a job which is initializing.
     */
    static class KillInterruptedException extends InterruptedException {
        private static final long serialVersionUID = 1L;
        public KillInterruptedException(String msg) {
            super(msg);
        }
    }

    /**
     * To keep track of kill and initTasks status of this job. initTasks() take
     * a lock on JobInProgress object. kill should avoid waiting on
     * JobInProgress lock since it may take a while to do initTasks().
     */
    private static class JobInitKillStatus {
        //flag to be set if kill is called
        boolean killed;

        boolean initStarted;
        boolean initDone;
    }

    private class JobEndProcess extends Thread {
        private boolean lock = true;
        public void run() {
            while (lock) {
                int finMapTasks = 0;
                int finReduceTasks = 0;
                int runMapTasks = 0;
                int runReduceTasks = 0;
                int failMapTasks = 0;
                int failRedTasks = 0;



                for(int i=0; i<maps.length; i++) {
                    if(maps[i].isRunning())
                        runMapTasks++;
                    else if(maps[i].isComplete())
                        finMapTasks++;
                    failMapTasks += maps[i].getActiveTasks().size();
                }

                if(LOG.isDebugEnabled()) {
                    StringBuffer buf = new StringBuffer();
                    buf.append("Map");
                    buf.append("id\trunning\tcomplete\tfailed\tkilled\tactivetasks\n");
                    for(int i=0; i<maps.length; i++) {
                        boolean run = maps[i].isRunning();
                        boolean comp= maps[i].isComplete();
                        boolean f = maps[i].isFailed();
                        boolean k = maps[i].wasKilled();
                        int actSize = maps[i].getActiveTasks().size();

                        buf.append(maps[i].getTIPId().toString() + "\t" + run +"\t" + comp + "\t"
                                + f + "\t" + k + "\t" + "\t" + actSize + "\n");
                    }

                    LOG.debug(buf);
                }

                if(LOG.isDebugEnabled()) {
                    StringBuffer buf = new StringBuffer();
                    buf.append("Reduces");
                    buf.append("id\trunning\tcomplete\tfailed\tkilled\tactivetasks\n");
                    for(int i=0; i<reduces.length; i++) {
                        boolean run = reduces[i].isRunning();
                        boolean comp= reduces[i].isComplete();
                        boolean f = reduces[i].isFailed();
                        boolean k = reduces[i].wasKilled();
                        int actSize = reduces[i].getActiveTasks().size();

                        buf.append(reduces[i].getTIPId().toString() + "\t" + run +"\t" + comp + "\t"
                                + f + "\t" + k + "\t" + "\t" + actSize + "\n");
                    }

                    LOG.debug(buf);
                }


                for(int i=0; i<reduces.length; i++) {
                    if(reduces[i].isRunning())
                        runReduceTasks++;
                    else if(reduces[i].isComplete())
                        finReduceTasks++;
                    failRedTasks += reduces[i].getActiveTasks().size();
                }

                if((runMapTasks-failMapTasks) == 0
                        && (runReduceTasks-failRedTasks) == 0
                        && finishedMapTasks >=  MajorityVoting.twoThirds(replicatedNumMapTasks)
                        && finishedReduceTasks >=  MajorityVoting.twoThirds(replicatedNumReduceTasks)) {

                    synchronized (reduce_voters) {
                        if(valid(reduce_voters)) {
                            JobInProgress.this.status.setReduceProgress(1.0f);
                            jobComplete();
                        } else {
                            if(finMapTasks == replicatedNumMapTasks || finReduceTasks == replicatedNumReduceTasks) {
                                LOG.fatal("Job " + jobId.toString() + " will fail. All reduce tasks havent returned the same result.");
                                fail();
                            }
                        }
                    }
                }

                if(failMapTasks == replicatedNumMapTasks ||failRedTasks == replicatedNumReduceTasks) {
                    LOG.info("Job failed.");
                    fail();
                }


                try {
                    sleep(2000);
                    LOG.debug("ping...");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            LOG.debug(this.getName() + " stop");
        }

        public void stopIt() {
            lock=false;
        }
    }
}
