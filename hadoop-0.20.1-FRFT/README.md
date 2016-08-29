# Hadoop MapReduce tolerant to arbitrary faults 

MapReduce is often used for critical data processing, e.g., in the context of scientific or financial simulation. However, there is evidence in the literature that there are arbitrary (or Byzantine) faults that may corrupt the results of MapReduce without being detected. 

We present full replication to deal with faults.

The prototype of the MapReduce runtime was implemented by modifying the original Hadoop 0.20.0 source code.
