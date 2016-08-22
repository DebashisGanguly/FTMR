package org.apache.hadoop.mapred;

import static org.apache.hadoop.mapred.Util.getTaskID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;

public class MapLauncherController {
    public static final Log LOG = JobInProgress.LOG;

    // it's a temporary list of the map tasks that are ready to be lauched.
    // this is necessary because, when a task is applied to run, it must be in this list 
    // before went to the mapsLaunched
    // I use this list because it exists a time between the call of the shouldRun method 
    // and the time when the TaskID is added to mapLaunched. The variable is to guarantee
    // that a request related to this task is already be dealing,
    // otherwise it could launch more that than the f+1 tasks planned in the beginning of the execution.
    private Set<TaskID> mapsPrepStage= Collections.synchronizedSet(new TreeSet<TaskID>());
    private Set<TaskID> mapsLaunched = Collections.synchronizedSet(new TreeSet<TaskID>());
    private Set<TaskID> succesfulMap = Collections.synchronizedSet(new TreeSet<TaskID>());
    private Set<TaskID> redLaunched  = Collections.synchronizedSet(new TreeSet<TaskID>());

    private Map<String, List<String>> tasktrackerMap = new HashMap<String, List<String>>();

    public void addMapsLaunched(TaskAttemptID tid) {
        if(tid == null)
            return;

        synchronized (mapsPrepStage) {
            Iterator<TaskID> iter = mapsPrepStage.iterator();
            while(iter.hasNext()) {
                TaskID t = iter.next();
                if(t.equals(getTaskID(tid))) {
                    iter.remove();

                    synchronized (mapsLaunched) {
                        mapsLaunched.add(getTaskID(tid));
                        LOG.debug(tid + " added to MAPSLAUNCHED");
                    }
                }

                //printStatus("addMapsLaunched");
            }
        }
    }

    public void addSuccessfulMap(TaskID tid) {
        if(tid == null)
            return;

        synchronized (mapsLaunched) {
            Iterator<TaskID> iter = mapsLaunched.iterator();
            while(iter.hasNext()) {
                TaskID t = iter.next();
                if(t.equals(tid)) {
                    iter.remove();
                    break;
                }
            }
        }

        synchronized (mapsPrepStage) {
            Iterator<TaskID> iter = mapsPrepStage.iterator();
            while(iter.hasNext()) {
                TaskID t = iter.next();
                if(t.equals(tid)) {
                    iter.remove();
                    break;
                }
            }
        }

        synchronized (succesfulMap) {
            succesfulMap.add(tid);
            LOG.debug(tid.toString() + " added to SUCCESFULMAP");
        }

        //printStatus("addSuccessfulMap");
    }

    /**
     * Check if the tasktracker already ran this task
     * @param tasktracker
     * @param task
     * @return false if the tasktracker ran already the task with the taskID
     */
    public boolean addTaskTrackerMap(String tasktracker, String task) {
        if(!tasktrackerMap.containsKey(tasktracker))
            tasktrackerMap.put(tasktracker, new ArrayList<String>());

        List<String> tasks = tasktrackerMap.get(tasktracker);
        for(String t : tasks)
            if(t.equals(task))
                return false;

                tasks.add(task);
                tasktrackerMap.put(tasktracker, tasks);
                return true;
    }

    public void removeTaskTrackerMap(String tasktracker, String task) {
        if(!tasktrackerMap.containsKey(tasktracker))
            return;

        List<String> tasks = tasktrackerMap.get(tasktracker);
        tasks.remove(task);
    }


    public void addToRun(TaskInProgress tip) {
        if(tip == null) return;

        if(tip.isMapTask()) {
            synchronized (mapsPrepStage) {
                mapsPrepStage.add(tip.getTIPId());
            }
        } else {
            synchronized (redLaunched) {
                redLaunched.add(tip.getTIPId());
            }
        }
    }

    public void removeToRun(TaskInProgress tip) {
        if(tip == null) return;
        
        if(tip.isMapTask()) {
            synchronized (mapsPrepStage) {
                mapsPrepStage.remove(tip.getTIPId());
            }
        } else {
            synchronized (redLaunched) {
                redLaunched.remove(tip.getTIPId());
            }
        }
    }

    public void printStatus(String from) {
        String aux = "TASKS PREP: ";
        for(TaskID tid : mapsPrepStage) {
            aux += tid.toString() + ", ";
        }


        String aux2 = "TASKS LAUNCHED: ";
        for(TaskID tid : mapsLaunched) {
            aux2 += tid.toString() + ", ";
        }

        String aux3 = "MAPS SUCC: ";
        for(TaskID tid : succesfulMap) {
            aux3 += tid.toString() + ", ";
        }

        LOG.debug("\n" + from + " -------------------------\n" + aux + "\n" + aux2 + "\n" + aux3);
    }
}
