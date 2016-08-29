package org.apache.hadoop.mapred;

import java.util.List;

public interface VotingSystem {
	/**
	 * Number of successful tasks
	 * @return
	 */
	public int size();
	
	/**
	 * Get list of replicas
	 * @param taskId TaskId without replica
	 * @return
	 */
	public List<Integer> getTask(String taskId);
	
	/**
	 * Add Taskid without replica
	 * @param key
	 */
	public void addKey(String key);
	
	/**
	 * 
	 * @param key Taskid without replica
	 * @param value Replica number
	 */
	public void addValue(String key, Integer value);
	
	/**
	 * Saves digest
	 * @param tid
	 * @param values
	 */
	public void addHash(TaskID tid, boolean map, String[] values);
	
	/**
	 * Removes digest
	 * @param tid
	 * @return
	 */
	public boolean removeHash(TaskID tid);
	/**
	 * Get the hash for the tid
	 * @param tid
	 * @return
	 */
	public String[] getHash(TaskID tid);
	/**
	 * Get the limit to consider a majority
	 * @return (n/2)+1
	 */
	public int getThreshold();
	
	/**
	 * Check if a reduce tasks have a majority of digests
	 * 
	 * @param tid ID 
	 * @param threshold threshold
	 * @param total number of tasks
	 * 
	 * @return MAJORITY_VOTING, or NO_MAJORITY, or NOT_ENOUGH_ELEMENTS
	 */
	public int hasMajorityOfDigests(TaskID tid);
	
	/**
	 * Get a task id of a reduce task that hasn't got a majority of equal digests
	 * @return
	 */
	public int getTaskWithoutMajority ();
	
	
	/**
	 * All digests are equal to digest param
	 * @param tid
	 * @param digest
	 * @return
	 */
	public boolean allEqual(TaskID tid, String[] digest);
	
	/**
	 * Add task completion event
	 * @param tid
	 * @param event
	 */
	public void addTaskCompletionEvent(TaskID tid, TaskCompletionEvent event);
	
	/**
     * Add the first replica of each map task that ended.
     * E.g. M_000000_2 and M_000001_0, or M_000000_3 and M_000001_0
     * @param event
     */
	public void addFirst(TaskCompletionEvent event);
	
	
	/**
	 * Compare digests
	 * @param digest1
	 * @param digest2
	 * @return
	 */
	public boolean digestsEquals(String[] digest1, String[] digest2);
	
	/**
	 * Add the first tid who got the digests
	 * @param tid
	 * @param values
	 */
	public void addFirstHash(TaskID tid, String[] values);
	
	/**
	 * Return an array of digests related to the first event
	 * @param tid
	 * @return
	 */
	public String[] getFirstHash(TaskID tid);
	/**
	 * Is empty 
	 * @param tid
	 * @return
	 */
	public boolean isEmpty(TaskID tid);
	
	/**
	 * Get Task Completion Events
	 * @param tid
	 * @return
	 */
	public List<TaskCompletionEvent> getTaskCompletionEvent(TaskID tid);
	
	/**
	 * Get a list of tasks	
	 * @param tid
	 * @return
	 */
	public List<TaskID> getTask(TaskID tid);
}
