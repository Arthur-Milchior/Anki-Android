package com.ichi2.testutils;

import com.ichi2.async.CollectionTask;
import com.ichi2.async.TaskData;
import com.ichi2.async.TaskListener;
import com.ichi2.async.TaskManager;

import androidx.annotation.Nullable;

/** A task manager where people */
public class MockTaskManager extends TaskManager {


    @Override
    public CollectionTask launchCollectionTask(CollectionTask.TASK_TYPE type, @Nullable TaskListener listener, TaskData param) {
        CollectionTask newTask = new CollectionTask(type, listener);
        if (listener != null) {
            listener.onPreExecute();
        }
        TaskData[] params = new TaskData[]{param};
        TaskData td = newTask.doInBackground(params);
        if (listener != null) {
            listener.onPostExecute(td);
        }
        return newTask;
    }

    @Override
    public void publishProgress(CollectionTask ct, TaskData value) {
        ct.getListener().onProgressUpdate(value);
    }
    
    // The methods below are useless since everything is immediately executed
    @Override
    protected void addTasks(CollectionTask task) {
    }


    @Override
    protected boolean removeTask(CollectionTask task) {

        return true;
    }


    @Override
    public void cancelAllTasks(CollectionTask.TASK_TYPE taskType) {

    }


    @Override
    protected void setLatestInstance(CollectionTask task) {

    }

    @Override
    public boolean waitToFinish(Integer timeout) {
        return false;
    }


    @Override
    public void cancelCurrentlyExecutingTask() {

    }
}
