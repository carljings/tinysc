package io.tinysc.http.netty;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class BoundedElasticExecutor extends ThreadPoolExecutor {
    private final GrowthQueue workQueue;
    private final AtomicInteger submittedTasks = new AtomicInteger();

    BoundedElasticExecutor(int minimumThreads, int maximumThreads,
                           long idleTimeoutMillis, int queueCapacity,
                           ThreadFactory threadFactory) {
        this(new GrowthQueue(queueCapacity), minimumThreads, maximumThreads,
                idleTimeoutMillis, threadFactory);
    }

    private BoundedElasticExecutor(GrowthQueue workQueue, int minimumThreads,
                                   int maximumThreads, long idleTimeoutMillis,
                                   ThreadFactory threadFactory) {
        super(minimumThreads, maximumThreads, idleTimeoutMillis, TimeUnit.MILLISECONDS,
                workQueue, threadFactory, new AbortPolicy());
        this.workQueue = workQueue;
        workQueue.executor(this);
    }

    @Override
    public void execute(Runnable task) {
        submittedTasks.incrementAndGet();
        try {
            super.execute(task);
        } catch (RejectedExecutionException rejected) {
            if (isShutdown() || !workQueue.force(task)) {
                submittedTasks.decrementAndGet();
                throw rejected;
            }
        }
    }

    @Override
    protected void afterExecute(Runnable task, Throwable failure) {
        submittedTasks.decrementAndGet();
        super.afterExecute(task, failure);
    }

    private int submittedTaskCount() {
        return submittedTasks.get();
    }

    private static final class GrowthQueue extends ArrayBlockingQueue<Runnable> {
        private static final long serialVersionUID = 1L;

        private BoundedElasticExecutor executor;

        private GrowthQueue(int capacity) {
            super(capacity);
        }

        private void executor(BoundedElasticExecutor value) {
            executor = value;
        }

        @Override
        public boolean offer(Runnable task) {
            BoundedElasticExecutor current = executor;
            if (current == null) {
                return super.offer(task);
            }
            int poolSize = current.getPoolSize();
            if (poolSize < current.getMaximumPoolSize()
                    && current.submittedTaskCount() > poolSize) {
                return false;
            }
            return super.offer(task);
        }

        private boolean force(Runnable task) {
            return super.offer(task);
        }
    }
}
