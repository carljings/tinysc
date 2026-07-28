package io.tinysc.kernel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerExchangeTest {
    @Test
    void createsCompletionFutureOnlyWhenObserved() throws Exception {
        ContainerExchange exchange = exchange();

        assertNull(completionField(exchange));
        exchange.complete();
        assertNull(completionField(exchange));

        CompletableFuture<Void> completion = exchange.completion();
        assertSame(completion, completionField(exchange));
        assertTrue(completion.isDone());
        completion.join();
    }

    @Test
    void completesListenerRegisteredAfterCompletion() {
        ContainerExchange exchange = exchange();
        AtomicInteger notifications = new AtomicInteger();

        exchange.complete();
        CompletableFuture<Void> completion = exchange.completion();
        completion.whenComplete((ignored, failure) -> notifications.incrementAndGet());

        completion.join();
        assertEquals(1, notifications.get());
    }

    @Test
    void completesListenerRegisteredBeforeCompletion() {
        ContainerExchange exchange = exchange();
        CompletableFuture<Void> completion = exchange.completion();
        AtomicInteger notifications = new AtomicInteger();
        completion.whenComplete((ignored, failure) -> notifications.incrementAndGet());

        assertFalse(completion.isDone());
        exchange.complete();

        completion.join();
        assertEquals(1, notifications.get());
    }

    @Test
    void failsListenersRegisteredBeforeAndAfterFailure() {
        IllegalStateException beforeRegistration = new IllegalStateException("before");
        ContainerExchange failedBeforeRegistration = exchange();
        failedBeforeRegistration.fail(beforeRegistration);

        CompletionException before = assertThrows(CompletionException.class,
                () -> failedBeforeRegistration.completion().join());
        assertSame(beforeRegistration, before.getCause());

        IllegalArgumentException afterRegistration = new IllegalArgumentException("after");
        ContainerExchange failedAfterRegistration = exchange();
        CompletableFuture<Void> completion = failedAfterRegistration.completion();
        failedAfterRegistration.fail(afterRegistration);

        CompletionException after = assertThrows(CompletionException.class, completion::join);
        assertSame(afterRegistration, after.getCause());
    }

    @Test
    void keepsFirstTerminalOutcome() {
        ContainerExchange completed = exchange();
        completed.complete();
        completed.fail(new IllegalStateException("ignored"));
        completed.complete();
        completed.completion().join();

        IllegalStateException firstFailure = new IllegalStateException("first");
        ContainerExchange failed = exchange();
        failed.fail(firstFailure);
        failed.complete();
        failed.fail(new IllegalStateException("ignored"));

        CompletionException failure = assertThrows(
                CompletionException.class, () -> failed.completion().join());
        assertSame(firstFailure, failure.getCause());
    }

    @Test
    void rejectsNullFailureWithoutCompletingExchange() {
        ContainerExchange exchange = exchange();

        assertThrows(NullPointerException.class, () -> exchange.fail(null));
        exchange.complete();
        exchange.completion().join();
    }

    @Test
    void resolvesConcurrentCompleteAndFailExactlyOnce() throws Exception {
        ExecutorService contenders = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 250; iteration++) {
                ContainerExchange exchange = exchange();
                CompletableFuture<Void> completion = exchange.completion();
                AtomicInteger notifications = new AtomicInteger();
                completion.whenComplete((ignored, failure) -> notifications.incrementAndGet());
                IllegalStateException expectedFailure = new IllegalStateException("race");
                CountDownLatch start = new CountDownLatch(1);

                Future<?> complete = contenders.submit(() -> {
                    await(start);
                    exchange.complete();
                });
                Future<?> fail = contenders.submit(() -> {
                    await(start);
                    exchange.fail(expectedFailure);
                });

                start.countDown();
                complete.get(2, TimeUnit.SECONDS);
                fail.get(2, TimeUnit.SECONDS);

                if (completion.isCompletedExceptionally()) {
                    CompletionException failure = assertThrows(
                            CompletionException.class, completion::join);
                    assertSame(expectedFailure, failure.getCause());
                } else {
                    completion.join();
                }
                assertEquals(1, notifications.get());
            }
        } finally {
            contenders.shutdownNow();
        }
    }

    private static ContainerExchange exchange() {
        return new ContainerExchange(ContainerRequest.builder()
                .method("GET")
                .rawUri("/")
                .path("/")
                .build());
    }

    private static Object completionField(ContainerExchange exchange) throws Exception {
        Field field = ContainerExchange.class.getDeclaredField("completion");
        field.setAccessible(true);
        return field.get(exchange);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
