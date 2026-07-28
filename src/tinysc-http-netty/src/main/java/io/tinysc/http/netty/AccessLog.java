package io.tinysc.http.netty;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

final class AccessLog implements AutoCloseable {
    static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;
    static final int DEFAULT_BACKUPS = 5;
    static final int DEFAULT_QUEUE_CAPACITY = 8192;

    private static final Logger LOGGER = Logger.getLogger(AccessLog.class.getName());
    private static final long MAX_FLUSH_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    private static final int WRITER_RUNNING = 0;
    private static final int WRITER_WAITING = 1;
    private static final int WRITER_PARKED = 2;

    private final Path path;
    private final Sink file;
    private final ConcurrentLinkedQueue<Event> queue;
    private final int queueCapacity;
    private final OfferObserver offerObserver;
    private final WaitObserver waitObserver;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicInteger activeOffers = new AtomicInteger();
    private final AtomicInteger queuedSlots = new AtomicInteger();
    private final AtomicInteger writerWaitState = new AtomicInteger(WRITER_RUNNING);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong rejectedAfterFailure = new AtomicLong();
    private final Thread writerThread;

    AccessLog(Path path, Sink file, int queueCapacity) {
        this(path, file, queueCapacity, OfferObserver.NONE);
    }

    AccessLog(Path path, Sink file, int queueCapacity, OfferObserver offerObserver) {
        this(path, file, queueCapacity, offerObserver, WaitObserver.NONE);
    }

    AccessLog(Path path, Sink file, int queueCapacity, OfferObserver offerObserver,
              WaitObserver waitObserver) {
        this.path = path;
        this.file = file;
        this.queueCapacity = queueCapacity;
        this.offerObserver = offerObserver == null ? OfferObserver.NONE : offerObserver;
        this.waitObserver = waitObserver == null ? WaitObserver.NONE : waitObserver;
        this.queue = new ConcurrentLinkedQueue<Event>();
        this.writerThread = new Thread(this::writeLoop, "tinysc-access-log");
        writerThread.setDaemon(false);
        try {
            writerThread.start();
        } catch (RuntimeException failure) {
            try {
                file.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static AccessLog open(Path baseDirectory) throws IOException {
        return open(baseDirectory, DEFAULT_MAX_BYTES, DEFAULT_BACKUPS,
                DEFAULT_QUEUE_CAPACITY);
    }

    static AccessLog open(Path baseDirectory, long maxBytes, int backups,
                          int queueCapacity) throws IOException {
        if (baseDirectory == null) {
            throw new IllegalArgumentException("baseDirectory is required");
        }
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (backups < 1) {
            throw new IllegalArgumentException("backups must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        Path logs = baseDirectory.toAbsolutePath().normalize().resolve("logs");
        Files.createDirectories(logs);
        Path path = logs.resolve("access.log");
        return new AccessLog(path, new RollingFile(path, maxBytes, backups), queueCapacity);
    }

    Path path() {
        return path;
    }

    long droppedCount() {
        return dropped.get();
    }

    long rejectedAfterFailureCount() {
        return rejectedAfterFailure.get();
    }

    boolean failed() {
        return failed.get();
    }

    boolean offer(Event event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        if (rejectOfferIfClosed()) {
            return false;
        }
        boolean reserved = false;
        activeOffers.incrementAndGet();
        try {
            if (rejectOfferIfClosed()) {
                return false;
            }
            if (!reserveSlot()) {
                recordQueueFullDrop();
                return false;
            }
            reserved = true;
            offerObserver.afterReserve();
            if (rejectOfferIfClosed()) {
                releaseSlot();
                reserved = false;
                return false;
            }
            queue.offer(event);
            reserved = false;
            try {
                offerObserver.afterPublish();
            } finally {
                signalWriterIfWaiting();
            }
            return true;
        } finally {
            if (reserved) {
                releaseSlot();
            }
            activeOffers.decrementAndGet();
            if (!accepting.get()) {
                signalWriterIfWaiting();
            }
        }
    }

    @Override
    public void close() {
        accepting.set(false);
        signalWriterIfWaiting();
        boolean interrupted = false;
        while (writerThread.isAlive()) {
            try {
                writerThread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        StringBuilder line = new StringBuilder(144);
        char[] characters = new char[256];
        long dirtyEventCount = 0L;
        long flushDeadlineNanos = 0L;
        try {
            while (shouldContinue()) {
                Event event = queue.poll();
                if (event == null) {
                    if (dirtyEventCount != 0L && flushDue(flushDeadlineNanos)) {
                        if (!flushDirty(dirtyEventCount)) {
                            return;
                        }
                        dirtyEventCount = 0L;
                        flushDeadlineNanos = 0L;
                        continue;
                    }
                    awaitWork(dirtyEventCount != 0L, flushDeadlineNanos);
                    continue;
                }
                do {
                    try {
                        characters = write(event, line, characters);
                    } catch (IOException failure) {
                        failWriter(failure, dirtyEventCount + 1L);
                        return;
                    }
                    queuedSlots.decrementAndGet();
                    dirtyEventCount++;
                    if (dirtyEventCount == 1L) {
                        flushDeadlineNanos = System.nanoTime() + MAX_FLUSH_DELAY_NANOS;
                    }
                    if (flushDue(flushDeadlineNanos)) {
                        if (!flushDirty(dirtyEventCount)) {
                            return;
                        }
                        dirtyEventCount = 0L;
                        flushDeadlineNanos = 0L;
                    }
                } while ((event = queue.poll()) != null);
            }
            if (dirtyEventCount != 0L && !flushDirty(dirtyEventCount)) {
                return;
            }
        } finally {
            try {
                file.close();
            } catch (IOException failure) {
                LOGGER.log(Level.SEVERE, "Unable to close access log path=" + path, failure);
            }
        }
    }

    private char[] write(Event event, StringBuilder line, char[] characters)
            throws IOException {
        line.setLength(0);
        event.appendTo(line);
        int length = line.length();
        if (characters.length < length) {
            characters = new char[Math.max(length, characters.length * 2)];
        }
        line.getChars(0, length, characters, 0);
        file.write(characters, length, utf8Length(characters, length));
        return characters;
    }

    static long utf8Length(char[] characters, int length) {
        long bytes = 0L;
        for (int index = 0; index < length; index++) {
            char character = characters[index];
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(character)) {
                if (index + 1 < length
                        && Character.isLowSurrogate(characters[index + 1])) {
                    bytes += 4L;
                    index++;
                } else {
                    bytes++;
                }
            } else if (Character.isLowSurrogate(character)) {
                bytes++;
            } else {
                bytes += 3L;
            }
        }
        return bytes;
    }

    private boolean reserveSlot() {
        while (true) {
            int current = queuedSlots.get();
            if (current >= queueCapacity) {
                return false;
            }
            if (queuedSlots.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseSlot() {
        queuedSlots.decrementAndGet();
    }

    private void awaitWork(boolean dirty, long flushDeadlineNanos) {
        if (!shouldContinue() || queue.peek() != null || (dirty && flushDue(flushDeadlineNanos))) {
            return;
        }
        writerWaitState.set(WRITER_WAITING);
        if (!shouldContinue() || queue.peek() != null || (dirty && flushDue(flushDeadlineNanos))) {
            writerWaitState.compareAndSet(WRITER_WAITING, WRITER_RUNNING);
            return;
        }
        waitObserver.beforePark();
        if (!writerWaitState.compareAndSet(WRITER_WAITING, WRITER_PARKED)) {
            return;
        }
        if (dirty) {
            long waitNanos = flushDeadlineNanos - System.nanoTime();
            if (waitNanos > 0L) {
                LockSupport.parkNanos(this, waitNanos);
            }
        } else {
            LockSupport.park(this);
        }
        writerWaitState.set(WRITER_RUNNING);
    }

    private boolean flushDirty(long dirtyEventCount) {
        try {
            file.flush();
            return true;
        } catch (IOException failure) {
            failWriter(failure, dirtyEventCount);
            return false;
        }
    }

    private void failWriter(IOException failure, long dirtyEventCount) {
        failed.set(true);
        accepting.set(false);
        signalWriterIfWaiting();
        while (activeOffers.get() != 0) {
            LockSupport.parkNanos(this, 1_000_000L);
        }
        long discarded = dirtyEventCount;
        while (queue.poll() != null) {
            discarded++;
        }
        queuedSlots.set(0);
        if (discarded > 0L) {
            dropped.addAndGet(discarded);
        }
        LOGGER.log(Level.SEVERE, "Access log writer failed path=" + path
                + " eventsDropped=" + discarded, failure);
    }

    private boolean shouldContinue() {
        return accepting.get() || activeOffers.get() != 0 || queuedSlots.get() != 0;
    }

    private boolean flushDue(long flushDeadlineNanos) {
        return System.nanoTime() - flushDeadlineNanos >= 0L;
    }

    private boolean rejectOfferIfClosed() {
        if (accepting.get()) {
            return false;
        }
        if (failed.get()) {
            recordRejectedAfterFailure();
        }
        return true;
    }

    private void signalWriterIfWaiting() {
        while (true) {
            int state = writerWaitState.get();
            if (state == WRITER_RUNNING) {
                return;
            }
            if (writerWaitState.compareAndSet(state, WRITER_RUNNING)) {
                LockSupport.unpark(writerThread);
                return;
            }
        }
    }

    private void recordQueueFullDrop() {
        long total = dropped.incrementAndGet();
        if ((total & (total - 1L)) == 0L) {
            LOGGER.warning("Access log queue full; events dropped=" + total
                    + " capacity=" + queueCapacity);
        }
    }

    private void recordRejectedAfterFailure() {
        dropped.incrementAndGet();
        long total = rejectedAfterFailure.incrementAndGet();
        if ((total & (total - 1L)) == 0L) {
            LOGGER.warning("Access log writer unavailable; events rejected=" + total
                    + " path=" + path);
        }
    }

    // Netty's channel event loop owns mutations until complete(). Publishing the
    // completed event through ConcurrentLinkedQueue makes them visible to the writer.
    static final class Event {
        private final long startedAtMillis;
        private final long startedAtNanos;
        private final String remote;
        private final String method;
        private final String path;
        private final String protocol;
        private final boolean keepAlive;
        private int status;
        private long responseBytes;
        private long durationMicros;
        private String outcome;
        private boolean completed;

        Event(long startedAtMillis, String remote, String method, String path,
              String protocol, int status, long responseBytes, long durationMicros,
              boolean keepAlive, String outcome) {
            this(startedAtMillis, 0L, remote, method, path, protocol, keepAlive);
            this.status = status;
            this.responseBytes = responseBytes;
            this.durationMicros = durationMicros;
            this.outcome = outcome;
            this.completed = true;
        }

        Event(long startedAtMillis, long startedAtNanos, String remote,
              String method, String path, String protocol, boolean keepAlive) {
            this.startedAtMillis = startedAtMillis;
            this.startedAtNanos = startedAtNanos;
            this.remote = remote;
            this.method = method;
            this.path = path;
            this.protocol = protocol;
            this.keepAlive = keepAlive;
        }

        void begin(int status) {
            if (completed) {
                return;
            }
            this.status = status;
        }

        void addBytes(int bytes) {
            if (completed) {
                return;
            }
            responseBytes += bytes;
        }

        boolean complete(String outcome) {
            if (completed) {
                return false;
            }
            long duration = startedAtNanos <= 0L ? 0L : System.nanoTime() - startedAtNanos;
            durationMicros = duration <= 0L ? 0L : duration / 1_000L;
            this.outcome = outcome;
            completed = true;
            return true;
        }

        boolean complete(int status, String outcome) {
            begin(status);
            return complete(outcome);
        }

        private void appendTo(StringBuilder line) {
            line.append("ts=").append(startedAtMillis).append(" remote=");
            appendSanitized(line, remote);
            line.append(" method=");
            appendSanitized(line, method);
            line.append(" path=");
            appendSanitized(line, path);
            line.append(" proto=");
            appendSanitized(line, protocol);
            line.append(" status=").append(status)
                    .append(" bytes=").append(responseBytes)
                    .append(" durUs=").append(durationMicros)
                    .append(" ka=").append(keepAlive ? 1 : 0)
                    .append(" outcome=");
            appendSanitized(line, outcome);
            line.append('\n');
        }

        private static void appendSanitized(StringBuilder line, String value) {
            if (value == null || value.isEmpty()) {
                line.append('-');
                return;
            }
            int firstUnsafe = -1;
            for (int index = 0; index < value.length(); index++) {
                if (isUnsafe(value.charAt(index))) {
                    firstUnsafe = index;
                    break;
                }
            }
            if (firstUnsafe < 0) {
                line.append(value);
                return;
            }
            line.append(value, 0, firstUnsafe);
            for (int index = firstUnsafe; index < value.length(); index++) {
                char character = value.charAt(index);
                line.append(isUnsafe(character) ? '_' : character);
            }
        }

        private static boolean isUnsafe(char character) {
            if (character < 0x80) {
                return character <= 0x20 || character == 0x7f;
            }
            return Character.isISOControl(character)
                    || Character.isWhitespace(character)
                    || character == '\u2028' || character == '\u2029';
        }
    }

    interface Sink extends AutoCloseable {
        void write(char[] characters, int length, long encodedBytes) throws IOException;

        void flush() throws IOException;

        @Override
        void close() throws IOException;
    }

    interface OfferObserver {
        OfferObserver NONE = new OfferObserver() {
            @Override
            public void afterReserve() {
            }

            @Override
            public void afterPublish() {
            }
        };

        void afterReserve();

        void afterPublish();
    }

    interface WaitObserver {
        WaitObserver NONE = new WaitObserver() {
            @Override
            public void beforePark() {
            }
        };

        void beforePark();
    }

    private static final class RollingFile implements Sink {
        private final Path path;
        private final long maxBytes;
        private final int backups;
        private BufferedWriter output;
        private long size;

        private RollingFile(Path path, long maxBytes, int backups) throws IOException {
            this.path = path;
            this.maxBytes = maxBytes;
            this.backups = backups;
            if (Files.isRegularFile(path) && Files.size(path) >= maxBytes) {
                rotateFiles();
            }
            openActiveFile();
        }

        @Override
        public void write(char[] characters, int length, long encodedBytes)
                throws IOException {
            if (size > 0L && size + encodedBytes > maxBytes) {
                output.flush();
                output.close();
                rotateFiles();
                openActiveFile();
            }
            output.write(characters, 0, length);
            size += encodedBytes;
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public void close() throws IOException {
            output.close();
        }

        private void rotateFiles() throws IOException {
            Files.deleteIfExists(backup(backups));
            for (int index = backups - 1; index >= 1; index--) {
                Path source = backup(index);
                if (Files.exists(source)) {
                    Files.move(source, backup(index + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(path)) {
                Files.move(path, backup(1), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void openActiveFile() throws IOException {
            output = new BufferedWriter(new OutputStreamWriter(
                    new BufferedOutputStream(Files.newOutputStream(path,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND)), StandardCharsets.UTF_8));
            size = Files.size(path);
        }

        private Path backup(int index) {
            return path.resolveSibling(path.getFileName().toString() + "." + index);
        }
    }
}
