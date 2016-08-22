package org.apache.hadoop.mapred;


/**
 * Count number of tasks launched 
 *
 */
public class TaskCounter {
	int partition;
	int[] taskcounter;

	public TaskCounter(int tasks, int replicas) {
		this.partition = tasks/replicas;
		this.taskcounter = new int[partition];
	}

	public synchronized void addtask(int id) {
		taskcounter[id]+=1;
	}

	public void removetask(int id) {
		taskcounter[id]-=1;
	}
	
	public int getCount(int id) {
		return taskcounter[id];
	}
	
	public boolean hasAnyLowerLimit(int limit) {
		for(int i=0; i<taskcounter.length; i++) {
			if(taskcounter[i] < limit)
				return true;
		}
		
		return false;
	}
	
	public int[] getTaskCounter() {
	    return taskcounter;
	}
}
