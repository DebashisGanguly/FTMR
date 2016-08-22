# Hadoop MapReduce tolerant to arbitrary faults

MapReduce is often used for critical data processing, e.g., in the context of scientific or financial simulation. However, there is evidence in the literature that there are arbitrary (or Byzantine) faults that may corrupt the results of MapReduce without being detected. We present a Byzantine fault-tolerant MapReduce framework that can run in two modes: non-speculative and speculative.

We thoroughly evaluate experimentally the performance of these two versions of the framework, showing that they use around twice more resources than Hadoop MapReduce, instead of the three times more of alternative solutions. We believe this cost is acceptable for many critical applications.

The prototype of the MapReduce runtime was implemented by modifying the original Hadoop 0.20.0 source code.

This work have been published in [1](http://ieeexplore.ieee.org/xpl/login.jsp?tp=&arnumber=6133124&url=http%3A%2F%2Fieeexplore.ieee.org%2Fxpls%2Fabs_all.jsp%3Farnumber%3D6133124) and [2](http://ieeexplore.ieee.org/xpl/login.jsp?tp=&arnumber=6412676&url=http%3A%2F%2Fieeexplore.ieee.org%2Fxpls%2Fabs_all.jsp%3Farnumber%3D6412676).

# Configuration of MapReduce BFT

I have configure MapReduce based on the [site](http://www.michael-noll.com/tutorials/running-hadoop-on-ubuntu-linux-single-node-cluster/).

`mapred-site.xml` has several new parameters to configure the platform.

    tasktracker.tasks.fault.tolerance -> nr of faults to tolerate 2f+1
    mapred.map.tasks.deferred.execution -> true | false if we want to run the scheduler in deferred/non-speculative or tentative/speculative


# Wordcount

Wordcount is the common example to run with the application. I have run the example with the following command:

    hadoop jar hadoop-0.20.1-examples.jar wordcount /input /output

