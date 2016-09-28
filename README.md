# Fault-tolerant MapReduce (FTMR)
MapReduce can transparently handle failures. If a task attempt is in fault on an assigned node, MapReduce can rerun the reassign the task on a separate node. However, at scale the nature of fault is not limited to crash faults alone; rather there are silent errors that affect correctness of final results. Current fault-tolerance of MapReduce run-times like Hadoop cannot deal with such arbitrary faults. The approach to deal with crash faults in Hadoop primarily relies on time redundancy and thus upon failures cannot abide by service level agreement in terms of response time. Hence, researchers have proposed hardware redundancy to run multiple replicas of same task in parallel to deal with faults.

This repository presents two different code bases - Full Replication Fault Tolerance (FRFT) and Data-complementary Replication Fault Tolerance (DCRFT) as two approaches of hardware redundancy to deal with faults in MapReduce paradigm.  Both of these code bases are modified version of official Hadoop 0.20.1.

## Full Replication Fault Tolerance (FRFT)
This is the traditional approach of replication that runs R numbers of replicas for every Map tasks in parallel. The replication factor R is determined by the equation f_c + 2 * f_s + 1, where f_c and f_s are number of crash faults and silent data errors (Byzantine faults) that can be handled by the framework transparently without aborting the job. The framework, transaprent to user, runs R copies of Map tasks in parallel and and end of Map phase (which is determined by either all launched Maps have either succeeded or failed) takes voting of signatures on intermediate outputs produced by completed Maps to determine whether majority consensus is reached or not to move forward to the reduce phase. Here are few important things to note. Firstly, original Hadoop introduces an optimization to overlap Map and Reduce phases by launching reduce tasks upon 50% (DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART) of launched map tasks are finished; whereas here as we have to take voting on signatures of map outputs, we force logical barrier between Map and Reduce phase, i.e., only when all maps are finished and mahority consensus is reached reduce phase starts. Secondly, here we also disable multiple task attempts per task as part of speculative execution as all possible replicas are launched in parallel on the nodes having the data split. Last but not the least, we replicate only map tasks and not the reduce tasks with the assumption that map phase is the most time intensive phase of the job runtime, hence we need to protect maps against failures and whereas reduce tasks can be reexecuted traditionally. 

The framework can be configured by three parameteres:
* mapred.map.tasks.fault.inject (true|false): Whether framework needs to emulate fault injection or not.
* mapred.map.tasks.fault.tolerance: Number of faults that can be tolerated.
* mapred.map.tasks.fault.nature: Nature of faults that can be tolerated.
* 1 - fail-stop errors or crash faults,
* 2 - silent-data errors or Byzantine faults.


It is also important to note that users do not need to configure number of reduce tasks (mapred.reduce.tasks) as the code base transparently creates reduce tasks as the number of input splits.

