package io.tinysc.http.netty;

import io.tinysc.kernel.NamedThreadFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedElasticExecutorTest {
    @Test
    void growsBeforeQueueWhenCurrentWorkersAreBusy() throws Exception {
        BoundedElasticExecutor executor = executor(1, 3, 1000L, 2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(3);
        try {
            for (int index = 0; index < 3; index++) {
                executor.execute(blockingTask(started, release));
            }
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertEquals(3, executor.getPoolSize());
            assertEquals(3, executor.getActiveCount());
            assertEquals(0, executor.getQueue().size());
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void boundsAcceptedTasksByMaximumWorkersAndQueueCapacity() throws Exception {
        BoundedElasticExecutor executor = executor(1, 2, 1000L, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        try {
            executor.execute(blockingTask(started, release));
            assertTrue(await(() -> started.getCount() == 1, 1000L));
            executor.execute(blockingTask(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            executor.execute(() -> {
            });

            assertEquals(1, executor.getQueue().size());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> {
                    }));
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void preservesInterruptWhenHandoffRetryIsInterrupted() throws Exception {
        BoundedElasticExecutor executor = executor(1, 1, 1000L, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            executor.execute(blockingTask(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            executor.execute(() -> {
            });

            Thread.currentThread().interrupt();
            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> {
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void acceptsTaskWhenQueueDrainsDuringHandoffRetry() throws Exception {
        BoundedElasticExecutor executor = new BoundedElasticExecutor(
                1, 1, 1000L, 1, 1000L,
                new NamedThreadFactory("test-worker-", true));
        CountDownLatch releaseRunning = new CountDownLatch(1);
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseQueued = new CountDownLatch(1);
        CountDownLatch queuedStarted = new CountDownLatch(1);
        CountDownLatch thirdRan = new CountDownLatch(1);
        CountDownLatch beginSubmit = new CountDownLatch(1);
        AtomicReference<Throwable> submissionFailure = new AtomicReference<>();
        Thread submitter = new Thread(() -> {
            try {
                beginSubmit.await();
                executor.execute(thirdRan::countDown);
            } catch (Throwable failure) {
                submissionFailure.set(failure);
            }
        });
        try {
            executor.execute(blockingTask(runningStarted, releaseRunning));
            assertTrue(runningStarted.await(1, TimeUnit.SECONDS));
            executor.execute(blockingTask(queuedStarted, releaseQueued));

            submitter.start();
            beginSubmit.countDown();
            assertTrue(awaitState(submitter, Thread.State.TIMED_WAITING, 100L));
            releaseRunning.countDown();
            submitter.join(1000L);

            assertNull(submissionFailure.get());
            releaseQueued.countDown();
            assertTrue(queuedStarted.await(1, TimeUnit.SECONDS));
            assertTrue(thirdRan.await(1, TimeUnit.SECONDS));
        } finally {
            releaseRunning.countDown();
            releaseQueued.countDown();
            submitter.join(1000L);
            shutdown(executor);
        }
    }

    @Test
    void rejectsNewTasksAfterShutdown() {
        BoundedElasticExecutor executor = executor(1, 2, 1000L, 1);
        executor.shutdown();
        assertThrows(RejectedExecutionException.class,
                () -> executor.execute(() -> {
                }));
    }

    @Test
    void shrinksBackToMinimumAfterBurst() throws Exception {
        BoundedElasticExecutor executor = executor(1, 3, 50L, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(3);
        try {
            for (int index = 0; index < 3; index++) {
                final long expectedRemaining = 2 - index;
                executor.execute(blockingTask(started, release));
                assertTrue(await(() -> started.getCount() == expectedRemaining, 1000L));
            }
            assertEquals(3, executor.getPoolSize());

            release.countDown();
            assertTrue(await(() -> executor.getPoolSize() == 1, 2000L));
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    private static BoundedElasticExecutor executor(int minimum, int maximum,
                                                    long idleMillis, int queueCapacity) {
        return new BoundedElasticExecutor(minimum, maximum, idleMillis, queueCapacity,
                new NamedThreadFactory("test-worker-", true));
    }

    private static Runnable blockingTask(CountDownLatch started, CountDownLatch release) {
        return () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static boolean await(BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return condition.getAsBoolean();
    }

    private static boolean awaitState(Thread thread, Thread.State state, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline && thread.isAlive()) {
            if (thread.getState() == state) {
                return true;
            }
            Thread.yield();
        }
        return thread.getState() == state;
    }

    private static void shutdown(BoundedElasticExecutor executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
}
