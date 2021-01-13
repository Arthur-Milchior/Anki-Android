package com.ichi2.async;

import android.content.res.Resources;
import android.os.AsyncTask;

import com.ichi2.utils.ThreadUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import timber.log.Timber;

/**
 *
 */
public abstract class TaskManager {

    private static TaskManager currentManager = new SingleTaskManager();

    public static TaskManager getManager() {
        return currentManager;
    }

    @VisibleForTesting
    public static void setManager(TaskManager manager) {
        currentManager = manager;
    }


    /**
     * Indicates to the manager that a task is done; either ended or cancelled.
     * @param task A task, assumed to be added before, that must not be executed anymore.
     * @return Whether the task was correctly removed.
     */
    protected abstract boolean removeTask(CollectionTask task);

    /**
     * Save the information that a task is being started right now.
     * @param task A task that is being started right now
     */
    protected abstract void setLatestInstance(CollectionTask task);

    /**
     * Starts a new {@link CollectionTask}, with no listener
     * <p>
     * Tasks will be executed serially, in the order in which they are started.
     * <p>
     * This method must be called on the main thread.
     *
     * @param task the task to execute
     * @return the newly created task
     */
    public <ProgressBackground, ResultBackground> CollectionTask<ProgressBackground, ProgressBackground, ResultBackground, ResultBackground> launchCollectionTask(CollectionTask.Task<ProgressBackground, ResultBackground> task) {
        return launchCollectionTask(task, null);
    }


    /**
     * Starts a new {@link CollectionTask}, with a listener provided for callbacks during execution
     * <p>
     * Tasks will be executed serially, in the order in which they are started.
     * <p>
     * This method must be called on the main thread.
     *
     * @param task the task to execute
     * @param listener to the status and result of the task, may be null
     * @return the newly created task
     */
    public abstract <ProgressListener, ProgressBackground extends ProgressListener, ResultListener, ResultBackground extends ResultListener> CollectionTask<ProgressListener, ProgressBackground, ResultListener, ResultBackground>
    launchCollectionTask(@NonNull CollectionTask.Task<ProgressBackground, ResultBackground> task,
                         @Nullable TaskListener<ProgressListener, ResultListener> listener);


    /**
     * Block the current thread until the currently running CollectionTask instance (if any) has finished.
     */
    public abstract void waitToFinish();

    /**
     * Block the current thread until the currently running CollectionTask instance (if any) has finished.
     * @param timeoutSeconds timeout in seconds
     * @return whether or not the previous task was successful or not
     */
    public abstract boolean waitToFinish(Integer timeoutSeconds);

    /** Cancel the current task only if it's of type taskType */
    public abstract void cancelCurrentlyExecutingTask();

    /** Cancel all tasks of type taskType*/
    public abstract void cancelAllTasks(Class taskType);


    /**
     * Block the current thread until all CollectionTasks have finished.
     * @param timeoutSeconds timeout in seconds
     * @return whether all tasks exited successfully
     */
    @SuppressWarnings("UnusedReturnValue")
    public abstract boolean waitForAllToFinish(Integer timeoutSeconds);


    public ProgressCallback progressCallback(CollectionTask task, Resources res) {
        return new ProgressCallback(task, res);
    }

    /**
         * Helper class for allowing inner function to publish progress of an AsyncTask.
         */
    public static class ProgressCallback<Progress> {
        private final Resources res;
        private final ProgressSender<Progress> task;


        protected ProgressCallback(ProgressSender<Progress> task, Resources res) {
            this.res = res;
            if (res != null) {
                this.task = task;
            } else {
                this.task = null;
            }
        }


        public Resources getResources() {
            return res;
        }


        public void publishProgress(Progress value) {
            if (task != null) {
                task.doProgress(value);
            }
        }
    }
}
