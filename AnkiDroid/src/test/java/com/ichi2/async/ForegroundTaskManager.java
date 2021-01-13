package com.ichi2.async;

import com.ichi2.libanki.CollectionGetter;
import com.ichi2.libanki.Collection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ForegroundTaskManager extends TaskManager {
    private final CollectionGetter mColGetter;


    public ForegroundTaskManager(CollectionGetter colGetter) {
        mColGetter = colGetter;
    }


    @Override
    protected boolean removeTask(CollectionTask task) {
        return true;
    }


    @Override
    protected void setLatestInstance(CollectionTask task) {
    }


    @Override
    public <ProgressListener, ProgressBackground extends ProgressListener, ResultListener, ResultBackground extends ResultListener> CollectionTask<ProgressListener, ProgressBackground, ResultListener, ResultBackground> launchCollectionTask(
            @NonNull CollectionTask.Task<ProgressBackground, ResultBackground> task,
            @Nullable TaskListener<ProgressListener, ResultListener> listener) {
        if (listener != null) {
            listener.onPreExecute();
        }
        ResultBackground res = task.task(mColGetter.getCol(), new MockTaskManager<>(listener));
        if (listener != null) {
            listener.onPostExecute(res);
        }
        return new EmptyTask<>(task, listener);
    }


    @Override
    public void waitToFinish() {
    }


    @Override
    public boolean waitToFinish(Integer timeoutSeconds) {
        return true;
    }


    @Override
    public void cancelCurrentlyExecutingTask() {
    }


    @Override
    public void cancelAllTasks(Class taskType) {
    }


    @Override
    public boolean waitForAllToFinish(Integer timeoutSeconds) {
        return true;
    }

    public class MockTaskManager<ProgressListener, ProgressBackground extends ProgressListener> implements ProgressSenderAndCancelListener<ProgressBackground> {

        private final @Nullable TaskListener<ProgressListener, ?> mTaskListener;


        public MockTaskManager(@Nullable TaskListener<ProgressListener, ?> listener) {
            mTaskListener = listener;
        }


        @Override
        public boolean isCancelled() {
            return false;
        }


        @Override
        public void doProgress(@Nullable ProgressBackground value) {
            mTaskListener.onProgressUpdate(value);
        }
    }

    public class EmptyTask<ProgressListener, ProgressBackground extends ProgressListener, ResultListener, ResultBackground extends ResultListener> extends
            CollectionTask<ProgressListener, ProgressBackground, ResultListener, ResultBackground> {

        protected EmptyTask(Task<ProgressBackground, ResultBackground> task, TaskListener<ProgressListener, ResultListener> listener) {
            super(task, listener, null);
        }
    }
}
