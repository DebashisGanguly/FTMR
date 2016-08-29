package org.apache.hadoop.mapred;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class MajorityVoting implements VotingSystem {
    private static final Log LOG = LogFactory.getLog(MajorityVoting.class);
    private int numReplicas;
    private int partitions;

    private Map<String, List<Integer>> tasksSuccessful = Collections.synchronizedMap(new HashMap<String, List<Integer>>());

    // mapHashList is a list where TaskID is the id and String[] is an array off hashes of a task. The hashes saved
    // contains the partition
    private Map<String, List<String>> mapHashList = Collections.synchronizedMap(new HashMap<String, List<String>>());
    private Map<String, List<String>> redHashList = Collections.synchronizedMap(new HashMap<String, List<String>>());
    private Map<String, List<String>> firstMapHash= Collections.synchronizedMap(new HashMap<String, List<String>>());

    private Map<String, List<TaskCompletionEvent>> buffer = new HashMap<String, List<TaskCompletionEvent>>();
    private Map<String, List<TaskID>> maptaskList = Collections.synchronizedMap(new HashMap<String, List<TaskID>>());
    private Map<String, List<TaskID>> redtaskList = Collections.synchronizedMap(new HashMap<String, List<TaskID>>());
    private List<TaskCompletionEvent> first = new ArrayList<TaskCompletionEvent>();

    public static final int NO_MAJORITY=-1;
    public static final int NOT_ENOUGH_ELEMENTS=-2;
    public static final int MAJORITY=1;

    public MajorityVoting(int numReplicas, int nrOfReduces) {
        this.numReplicas = numReplicas;
        this.partitions = nrOfReduces;
    }

    public synchronized void addKey(String key){
        if(!tasksSuccessful.containsKey(key))
            tasksSuccessful.put(key, new ArrayList<Integer>());
    }

    public synchronized void addValue(String key, Integer value){
        List<Integer> ids = tasksSuccessful.get(key);

        if(!ids.contains(value)) {
            ids.add(value);
            tasksSuccessful.put(key, ids); // add finished replicas
        }
    }

    public void addTaskCompletionEvent(TaskID tid, TaskCompletionEvent event) {
        Map<String, List<TaskCompletionEvent>> list = buffer;

        LOG.debug("Added to taskCompletionEvent :" + tid.toString());
        if(event == null || tid == null)
            return;

        String id = tid.toStringWithoutReplica();
        if(!list.containsKey(id))
            list.put(id, new ArrayList<TaskCompletionEvent>());

        List<TaskCompletionEvent> v = list.get(id);
        v.add(event);
        list.put(id, v);
    }

    public List<TaskCompletionEvent> getTaskCompletionEvent(TaskID tid) {
        Map<String, List<TaskCompletionEvent>> list = buffer;

        if(tid == null)
            return null;

        return list.remove(tid.toStringWithoutReplica());
    }

    public List<Integer> getTask(String taskId){
        return tasksSuccessful.get(taskId);
    }

    public int size() {
        synchronized (tasksSuccessful) {
            return tasksSuccessful.size();
        }
    }

    /**
     * Add digest
     */
    public void addHash(TaskID tid, boolean map, String[] values) {
        if(tid == null || values == null)
            return;

        if(tid.isSetupOrCleanup())
            return;

        Map<String, List<String>> list = tid.isMap() ? mapHashList : redHashList;
        String id = tid.isMap() ? tid.toString() : tid.toStringWithoutReplica();

        addTask(tid);	

        if(!list.containsKey(id))
            list.put(id, Arrays.asList(values));
        else {// concat
            List<String> temp = new ArrayList<String>(Arrays.asList(values));
            List<String> v = list.get(id);
            temp.addAll(v);
            list.put(id, temp);
        }
    }

    public void addFirstHash(TaskID tid, String[] values) {
        if(tid == null || values == null)
            return;

        if(tid.isSetupOrCleanup())
            return;

        String id = tid.toStringWithoutReplica();
        if(!firstMapHash.containsKey(id))
            firstMapHash.put(id, Arrays.asList(values));
    }

    public String[] getFirstHash(TaskID tid) {
        if(tid == null)
            return null;

        if(tid.isSetupOrCleanup())
            return null;

        String id = tid.toStringWithoutReplica();
        if(firstMapHash.containsKey(id))
            return (String[]) firstMapHash.get(id).toArray();

        return null;
    }

    /**
     * Remove the digest
     */
    public boolean removeHash(TaskID tid) {
        if(tid == null)
            return false;

        if(tid.isSetupOrCleanup())
            return false;

        Map<String, List<String>> list = tid.isMap() ? mapHashList : redHashList;
        String id = tid.isMap() ? tid.toString() : tid.toStringWithoutReplica();

        if(tid.isMap()) {
            removeTask(tid.toStringWithoutReplica(), tid);
        }

        if(list.containsKey(id) && tid.isMap()) {
            list.remove(tid);
            return true;
        }

        return false;
    }

    public void addFirst(TaskCompletionEvent event) {
        first.add(event);
    }

    public List<TaskID> getTask(TaskID tid) {
        Map<String, List<TaskID>> list = tid.isMap() ? maptaskList : redtaskList;

        if(!list.containsKey(tid.toStringWithoutReplica()))
            return null;

        return list.get(tid.toStringWithoutReplica());
    }

    /**
     * Add a <task without replica, List<task>> for map
     * Is to help to count the map digests 
     * @param id
     * @param task
     */
    private void addTask(TaskID task) {
        if(task == null)
            return;

        Map<String, List<TaskID>> list = task.isMap() ? maptaskList : redtaskList;

        List<TaskID> temp = new ArrayList<TaskID>();
        temp.add(task);

        if(!list.containsKey(task.toStringWithoutReplica()))
            list.put(task.toStringWithoutReplica(), temp);
        else {// concat
            List<TaskID> v = list.get(task.toStringWithoutReplica());
            v.addAll(temp);
            list.put(task.toStringWithoutReplica(), v);
        }
    }

    private boolean removeTask(String id, TaskID task) {
        if(id == null || task == null)
            return false;

        Map<String, List<TaskID>> list = task.isMap() ? maptaskList : redtaskList;
        boolean result = false;

        if(!list.containsKey(id))
            return false;
        else {// remove element

            List<TaskID> v = list.get(id);
            if(v != null && !v.isEmpty()) {

                ListIterator<TaskID> iter = v.listIterator();
                while(iter.hasNext()) {
                    TaskID value = iter.next();
                    if(value != null && value.equals(task)) {
                        iter.remove();
                        result=true;
                        break;
                    }
                }

                list.put(id, list.get(id));
            }
        }

        return result;
    }


    public String[] getHash(TaskID tid) {
        Map<String, List<String>> list = tid.isMap() ? mapHashList : redHashList;

        return list.get(tid.toString()) == null ? null : list.get(tid.toString()).toArray(new String[list.get(tid.toString()).size()]);
    }

    /**
     * 
     * @param id Id of the reduce task
     * @param threshold
     * @param tasksNumber
     * @return
     */
    public int hasMajorityOfDigests(TaskID tid) {
        if(tid==null)
            return NOT_ENOUGH_ELEMENTS;

        Map<String, List<String>> hlist  = tid.isMap() ? mapHashList : redHashList;
        String id = tid.isMap() ? tid.toString() : tid.toStringWithoutReplica();

        if(tid.isMap()) {
            Map<String, List<TaskID>> tlist = tid.isMap() ? maptaskList : redtaskList;
            List<TaskID> tasks = tlist.get(tid.toStringWithoutReplica());

            // no majority of values yet
            if(tasks.size() < getThreshold())
                return NOT_ENOUGH_ELEMENTS;

            for(int part=0; part<partitions; part++) {
                List<String> digests = new ArrayList<String>();
                for(TaskID t : tasks) {
                    digests.add((hlist.get(t.toString())).get(part));
                }

                if(countDigests(digests) == NO_MAJORITY)
                    return NO_MAJORITY;
            }

            return MAJORITY;
        }

        List<String> digests = hlist.get(id);
        if(digests.size() < getThreshold())
            return NOT_ENOUGH_ELEMENTS;

        return countDigests(digests);
    }

    public boolean allEqual(TaskID tid, String[] digests) {
        if(tid==null)
            return false;

        Map<String, List<String>> list  = tid.isMap() ? mapHashList : redHashList;

        if(tid.isMap()) {
            for(int i=0; i<numReplicas; i++) {
                List<String> tasks = list.get(tid.toStringWithoutReplica() + "_" + i);
                if(tasks == null)
                    return false;

                //                if(tasks.isEmpty()) // if there's no element, we assume it's ok. This is used in tentative execution
                //                    return true;

                for(int j=0; j<digests.length; j++) {
                    if(!digests[i].equals(tasks.get(i)))
                        return false;
                }
            }
        }

        return true;
    }

    public boolean digestsEquals(String[] digest1, String[] digest2) {
        if(digest1 == null || digest2 == null)
            return false;

        if(digest1.length != digest2.length)
            return false;

        for(int i=0; i<digest1.length; i++) {
            if(!digest1[i].equals(digest2[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty(TaskID tid) {
        if(tid==null)
            return false;

        Map<String, List<String>> list  = tid.isMap() ? mapHashList : redHashList;

        if(tid.isMap()) {
            List<String> tasks = list.get(tid.toStringWithoutReplica());
            if(tasks == null) {
                list.put(tid.toStringWithoutReplica(), new ArrayList<String>());
                return true;
            }

            if(tasks.isEmpty()) // if there's no element, we assume it's ok. This is used in tentative execution
                return true;
        }

        return true;
    }

    /**
     * Count the number of digests that have the same value
     * @param digests
     * @return
     */
    private int countDigests(List<String> digests) {
        if(digests == null)
            return NOT_ENOUGH_ELEMENTS;

        for(int i=0; i<digests.size(); i++) {
            int count = 1;
            String key = digests.get(i);

            for(int j=i+1; j<digests.size(); j++) {
                if(key.equals(digests.get(j))) {
                    count++;

                    if(count >= getThreshold())
                        return MAJORITY;
                }
            }
        }

        return NO_MAJORITY;
    }


    /**
     * Get the digest that has more occurrences
     * @param digests
     * @return the digest
     */
    private String getDigests(List<String> digests) {
        if(digests == null)
            return null;

        for(int i=0; i<digests.size(); i++) {
            String key = digests.get(i);
            int count = 1;

            for(int j=i+1; j<digests.size(); j++) {
                if(key.equals(digests.get(j))) {
                    count++;

                    if(count >= getThreshold())
                        return key;
                }
            }
        }

        return null;
    }



    private int hasMajorityOfDigests(String ref, List<String> digests) {
        int count = 0;

        for(int i=0; i<digests.size(); i++) {
            if(ref.equals(digests.get(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Finds a reduce task that hasn't a majority of digests 
     */
    public int getTaskWithoutMajority (){
        if(redHashList == null) return -1;

        Iterator<String> iter = redHashList.keySet().iterator();
        while(iter.hasNext()) {
            String k = iter.next();
            boolean flag = false;
            List<String> digests = redHashList.get(k);
            if(digests.size() >= getThreshold()) {
                for(String d : digests) {
                    // if it has an occurrence where it's found a majority
                    if(!(hasMajorityOfDigests(d, digests) < getThreshold())) {
                        break;
                    } else flag=true;

                }
            }

            if(flag) {
                k+="_0";// just to append a replica number that doesn't do nothing
                TaskID tid = TaskID.forName(k);
                return tid.getId();

            }
        }

        return -1;
    }

    public int getThreshold() {
        return ((numReplicas/2)+1);
    }

    public static int getThreshold(int numReplicas) {
        return twoThirds(numReplicas);
    }

    /**
     * return two thirds from the number
     * @param value
     * @return
     */
    public static int twoThirds(int value) {
        return (value*2)/3;
    }

    public static int getNrReplicatedTasks(int tasks, int replica) {
        return tasks * ((replica * 2) +1);
    }
}
