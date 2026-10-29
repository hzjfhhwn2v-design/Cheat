package dev.flipscout.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Holds submitted tasks until the test runs them, on the test's own thread. */
public final class ManualExecutor extends AbstractExecutorService {
    private final Deque<Runnable> tasks = new ArrayDeque<Runnable>();
    private boolean shutdown;

    @Override
    public synchronized void execute(Runnable command) {
        if (shutdown) {
            throw new RejectedExecutionException("shut down");
        }
        tasks.addLast(command);
    }

    public synchronized int size() {
        return tasks.size();
    }

    /** Runs everything queued, including tasks queued by those tasks. */
    public void runAll() {
        Runnable task;
        while ((task = poll()) != null) {
            task.run();
        }
    }

    private synchronized Runnable poll() {
        return tasks.pollFirst();
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> left = new ArrayList<Runnable>(tasks);
        tasks.clear();
        return left;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }
}
