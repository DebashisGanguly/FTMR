# Hadoop MapReduce tolerant to arbitrary faults 

MapReduce is often used for critical data processing, e.g., in the context of scientific or financial simulation. However, there is evidence in the literature that there are arbitrary (or Byzantine) faults that may corrupt the results of MapReduce without being detected. We present a Byzantine fault-tolerant MapReduce framework that can run in two modes: non-speculative and speculative.

We thoroughly evaluate experimentally the performance of these two versions of the framework, showing that they use around twice more resources than Hadoop MapReduce, instead of the three times more of alternative solutions. We believe this cost is acceptable for many critical applications.

The prototype of the MapReduce runtime was implemented by modifying the original Hadoop 0.20.0 source code.
