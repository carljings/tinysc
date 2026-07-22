package io.tinysc.http.netty;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class RequestAdmissionController {
    private final long maxConnections;
    private final long maxRequests;
    private final long maxRequestBytes;
    private final AtomicLong activeConnections = new AtomicLong();
    private final AtomicLong activeRequests = new AtomicLong();
    private final AtomicLong activeRequestBytes = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong rejectedRequestBytes = new AtomicLong();
    private final Object requestMonitor = new Object();

    RequestAdmissionController(long maxConnections, long maxRequests, long maxRequestBytes) {
        if (maxConnections <= 0L || maxRequests <= 0L || maxRequestBytes <= 0L) {
            throw new IllegalArgumentException("admission limits must be positive");
        }
        this.maxConnections = maxConnections;
        this.maxRequests = maxRequests;
        this.maxRequestBytes = maxRequestBytes;
    }

    ConnectionLease tryAcquireConnection() {
        if (!tryAdd(activeConnections, 1L, maxConnections)) {
            rejectedConnections.incrementAndGet();
            return null;
        }
        return new ConnectionLease(this);
    }

    RequestLease tryAcquireRequest(long requestBytes) {
        if (requestBytes < 0L) {
            throw new IllegalArgumentException("requestBytes must not be negative");
        }
        if (!tryAdd(activeRequests, 1L, maxRequests)) {
            rejectedRequests.incrementAndGet();
            return null;
        }
        if (!tryAdd(activeRequestBytes, requestBytes, maxRequestBytes)) {
            activeRequests.decrementAndGet();
            signalRequestChange();
            rejectedRequestBytes.incrementAndGet();
            return null;
        }
        return new RequestLease(this, requestBytes);
    }

    boolean awaitNoRequests(long timeoutMillis) throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (requestMonitor) {
            while (activeRequests.get() > 0L) {
                if (remainingNanos <= 0L) {
                    return false;
                }
                long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
                requestMonitor.wait(millis, nanos);
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    long activeConnections() {
        return activeConnections.get();
    }

    long activeRequests() {
        return activeRequests.get();
    }

    long activeRequestBytes() {
        return activeRequestBytes.get();
    }

    long rejectedConnections() {
        return rejectedConnections.get();
    }

    long rejectedRequests() {
        return rejectedRequests.get();
    }

    long rejectedRequestBytes() {
        return rejectedRequestBytes.get();
    }

    private void releaseConnection() {
        activeConnections.decrementAndGet();
    }

    private void releaseRequest(long requestBytes) {
        activeRequestBytes.addAndGet(-requestBytes);
        activeRequests.decrementAndGet();
        signalRequestChange();
    }

    private void signalRequestChange() {
        synchronized (requestMonitor) {
            requestMonitor.notifyAll();
        }
    }

    private static boolean tryAdd(AtomicLong counter, long delta, long maximum) {
        while (true) {
            long current = counter.get();
            if (delta > maximum - current) {
                return false;
            }
            if (counter.compareAndSet(current, current + delta)) {
                return true;
            }
        }
    }

    static final class ConnectionLease implements AutoCloseable {
        private final RequestAdmissionController controller;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ConnectionLease(RequestAdmissionController controller) {
            this.controller = controller;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                controller.releaseConnection();
            }
        }
    }

    static final class RequestLease implements AutoCloseable {
        private final RequestAdmissionController controller;
        private final long requestBytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RequestLease(RequestAdmissionController controller, long requestBytes) {
            this.controller = controller;
            this.requestBytes = requestBytes;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                controller.releaseRequest(requestBytes);
            }
        }
    }
}
