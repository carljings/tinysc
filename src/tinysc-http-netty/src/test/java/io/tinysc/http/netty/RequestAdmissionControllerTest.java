package io.tinysc.http.netty;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestAdmissionControllerTest {
    @Test
    void boundsConnectionsAndRecoversAfterRelease() {
        RequestAdmissionController controller = new RequestAdmissionController(1, 2L, 10L);
        RequestAdmissionController.ConnectionLease first = controller.tryAcquireConnection();

        assertNotNull(first);
        assertNull(controller.tryAcquireConnection());
        assertEquals(1L, controller.activeConnections());
        assertEquals(1L, controller.rejectedConnections());

        first.close();
        first.close();
        RequestAdmissionController.ConnectionLease recovered =
                controller.tryAcquireConnection();
        assertNotNull(recovered);
        recovered.close();
        assertEquals(0L, controller.activeConnections());
    }

    @Test
    void boundsRequestsAndBytesAndReleasesOnlyOnce() {
        RequestAdmissionController controller = new RequestAdmissionController(1, 2L, 10L);
        RequestAdmissionController.RequestLease first = controller.tryAcquireRequest(6L);

        assertNotNull(first);
        assertNull(controller.tryAcquireRequest(5L));
        assertEquals(1L, controller.activeRequests());
        assertEquals(6L, controller.activeRequestBytes());
        assertEquals(1L, controller.rejectedRequestBytes());

        RequestAdmissionController.RequestLease second = controller.tryAcquireRequest(4L);
        assertNotNull(second);
        assertNull(controller.tryAcquireRequest(0L));
        assertEquals(1L, controller.rejectedRequests());

        first.close();
        first.close();
        second.close();
        assertEquals(0L, controller.activeRequests());
        assertEquals(0L, controller.activeRequestBytes());
    }

    @Test
    void zeroByteRequestDoesNotConsumeAnAlreadyFullByteBudget() {
        RequestAdmissionController controller = new RequestAdmissionController(1, 2L, 1L);
        RequestAdmissionController.RequestLease body = controller.tryAcquireRequest(1L);
        RequestAdmissionController.RequestLease empty = controller.tryAcquireRequest(0L);

        assertNotNull(body);
        assertNotNull(empty);
        assertEquals(2L, controller.activeRequests());
        assertEquals(1L, controller.activeRequestBytes());

        empty.close();
        assertEquals(1L, controller.activeRequests());
        assertEquals(1L, controller.activeRequestBytes());
        body.close();
        assertEquals(0L, controller.activeRequests());
        assertEquals(0L, controller.activeRequestBytes());
    }

    @Test
    void wakesAllWaitersWhenLastRequestIsReleased() throws Exception {
        RequestAdmissionController controller = new RequestAdmissionController(1, 2L, 10L);
        RequestAdmissionController.RequestLease lease = controller.tryAcquireRequest(1L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> controller.awaitNoRequests(5_000L));
            Future<Boolean> second = executor.submit(() -> controller.awaitNoRequests(5_000L));
            awaitWaiterCount(controller, 2);

            lease.close();

            assertTrue(first.get(1L, TimeUnit.SECONDS));
            assertTrue(second.get(1L, TimeUnit.SECONDS));
            assertEquals(0, controller.requestWaiters());
        } finally {
            lease.close();
            executor.shutdownNow();
        }
    }

    @Test
    void waiterStartingAfterLastReleaseReturnsWithoutRegistering() throws Exception {
        RequestAdmissionController controller = new RequestAdmissionController(1, 1L, 10L);
        RequestAdmissionController.RequestLease lease = controller.tryAcquireRequest(1L);

        lease.close();

        assertTrue(controller.awaitNoRequests(1_000L));
        assertEquals(0, controller.requestWaiters());
    }

    @Test
    void timeoutAndInterruptionUnregisterWaiters() throws Exception {
        RequestAdmissionController controller = new RequestAdmissionController(1, 1L, 10L);
        RequestAdmissionController.RequestLease lease = controller.tryAcquireRequest(1L);
        assertFalse(controller.awaitNoRequests(1L));
        assertEquals(0, controller.requestWaiters());

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread waiter = new Thread(() -> {
            try {
                controller.awaitNoRequests(5_000L);
                failure.set(new AssertionError("interrupted waiter returned normally"));
            } catch (InterruptedException expected) {
                // expected
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        }, "request-admission-waiter");
        try {
            waiter.start();
            awaitWaiterCount(controller, 1);

            waiter.interrupt();
            waiter.join(1_000L);

            assertFalse(waiter.isAlive());
            assertNull(failure.get());
            assertEquals(0, controller.requestWaiters());
        } finally {
            waiter.interrupt();
            waiter.join(1_000L);
            lease.close();
        }
    }

    @Test
    void releasesDoNotEnterRequestMonitorWhenThereAreNoWaiters() throws Exception {
        RequestAdmissionController controller = new RequestAdmissionController(1, 1L, 10L);
        Object requestMonitor = requestMonitor(controller);
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> holder = executor.submit(() -> {
            synchronized (requestMonitor) {
                monitorHeld.countDown();
                releaseMonitor.await();
            }
            return null;
        });
        try {
            assertTrue(monitorHeld.await(1L, TimeUnit.SECONDS));
            Future<?> releases = executor.submit(() -> {
                for (int index = 0; index < 10_000; index++) {
                    RequestAdmissionController.RequestLease lease =
                            controller.tryAcquireRequest(0L);
                    if (lease == null) {
                        throw new AssertionError("request was unexpectedly rejected");
                    }
                    lease.close();
                }
            });
            releases.get(1L, TimeUnit.SECONDS);
            assertEquals(0L, controller.activeRequests());
            assertEquals(0, controller.requestWaiters());
        } finally {
            releaseMonitor.countDown();
            holder.get(1L, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    private static void awaitWaiterCount(RequestAdmissionController controller,
                                         int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (controller.requestWaiters() != expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(expected, controller.requestWaiters());
    }

    private static Object requestMonitor(RequestAdmissionController controller)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = RequestAdmissionController.class.getDeclaredField("requestMonitor");
        field.setAccessible(true);
        return field.get(controller);
    }
}
