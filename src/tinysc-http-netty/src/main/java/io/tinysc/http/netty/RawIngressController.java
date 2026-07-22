package io.tinysc.http.netty;

import java.util.concurrent.atomic.AtomicLong;

final class RawIngressController {
    private final long maxBytes;
    private final AtomicLong activeReservations = new AtomicLong();
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong rejectedReservations = new AtomicLong();

    RawIngressController(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    Lease tryAcquire(long initialBytes) {
        if (initialBytes < 0L) {
            throw new IllegalArgumentException("initialBytes must not be negative");
        }
        if (!tryReserveBytes(initialBytes)) {
            rejectedReservations.incrementAndGet();
            return null;
        }
        activeReservations.incrementAndGet();
        return new Lease(this, initialBytes);
    }

    long activeReservations() {
        return activeReservations.get();
    }

    long reservedBytes() {
        return reservedBytes.get();
    }

    long rejectedReservations() {
        return rejectedReservations.get();
    }

    private boolean tryExtend(long bytes) {
        if (!tryReserveBytes(bytes)) {
            rejectedReservations.incrementAndGet();
            return false;
        }
        return true;
    }

    private boolean tryReserveBytes(long bytes) {
        if (bytes == 0L) {
            return true;
        }
        while (true) {
            long current = reservedBytes.get();
            if (bytes > maxBytes - current) {
                return false;
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    private void release(long bytes) {
        if (bytes != 0L) {
            reservedBytes.addAndGet(-bytes);
        }
        activeReservations.decrementAndGet();
    }

    static final class Lease implements AutoCloseable {
        private final RawIngressController controller;
        private long reservedBytes;
        private boolean closed;

        private Lease(RawIngressController controller, long reservedBytes) {
            this.controller = controller;
            this.reservedBytes = reservedBytes;
        }

        synchronized boolean tryReserve(long bytes) {
            if (bytes < 0L) {
                throw new IllegalArgumentException("bytes must not be negative");
            }
            if (closed) {
                return false;
            }
            if (bytes == 0L) {
                return true;
            }
            if (!controller.tryExtend(bytes)) {
                return false;
            }
            reservedBytes += bytes;
            return true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            controller.release(reservedBytes);
        }
    }
}
