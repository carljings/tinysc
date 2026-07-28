package io.tinysc.http.netty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessLogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void usesIndependentDefaultPathAndLimits() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        try {
            assertEquals(temporaryDirectory.resolve("logs/access.log"), log.path());
            assertEquals(64L * 1024L * 1024L, AccessLog.DEFAULT_MAX_BYTES);
            assertEquals(5, AccessLog.DEFAULT_BACKUPS);
            assertEquals(8192, AccessLog.DEFAULT_QUEUE_CAPACITY);
        } finally {
            log.close();
        }
    }

    @Test
    void closeDrainsAndFlushesQueuedEventsBeforeReturning() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory, 1024L * 1024L, 2, 8192);
        for (int index = 0; index < 100; index++) {
            assertTrue(log.offer(event("/request-" + index)));
        }

        log.close();

        List<String> lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(100, lines.size());
        assertTrue(lines.get(0).contains("path=/request-0"));
        assertTrue(lines.get(99).contains("path=/request-99"));
        assertFalse(log.offer(event("/after-close")));
        assertEquals(0L, log.rejectedAfterFailureCount());
    }

    @Test
    void closeWaitsForPublishedEventThenWritesIt() throws Exception {
        RecordingSink sink = new RecordingSink();
        BlockingPublishObserver observer = new BlockingPublishObserver();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8, observer);
        AtomicInteger accepted = new AtomicInteger();
        Thread offerThread = new Thread(() -> {
            if (log.offer(event("/reserved"))) {
                accepted.incrementAndGet();
            }
        }, "access-log-offer");
        offerThread.start();
        assertTrue(observer.published.await(5, TimeUnit.SECONDS));

        Thread closeThread = new Thread(log::close, "access-log-close");
        closeThread.start();
        awaitWaiting(closeThread);

        observer.release.countDown();
        offerThread.join(5000L);
        closeThread.join(5000L);

        assertEquals(1, accepted.get());
        assertFalse(closeThread.isAlive());
        assertEquals(1, sink.lines().size());
        assertEquals(line("/reserved"), sink.lines().get(0));
    }

    @Test
    void closeWaitsForReservedEventToRollBack() throws Exception {
        RecordingSink sink = new RecordingSink();
        BlockingReserveObserver observer = new BlockingReserveObserver();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8, observer);
        AtomicInteger accepted = new AtomicInteger();
        Thread offerThread = new Thread(() -> {
            if (log.offer(event("/reserved"))) {
                accepted.incrementAndGet();
            }
        }, "access-log-offer");
        offerThread.start();
        assertTrue(observer.reserved.await(5, TimeUnit.SECONDS));

        Thread closeThread = new Thread(log::close, "access-log-close");
        closeThread.start();
        awaitWaiting(closeThread);

        observer.release.countDown();
        offerThread.join(5000L);
        closeThread.join(5000L);

        assertEquals(0, accepted.get());
        assertFalse(offerThread.isAlive());
        assertFalse(closeThread.isAlive());
        assertTrue(sink.lines().isEmpty());
    }

    @Test
    void closeDrainsEventPublishedWhileWriterPreparesToPark() throws Exception {
        RecordingSink sink = new RecordingSink();
        BlockingWaitObserver observer = new BlockingWaitObserver();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8,
                AccessLog.OfferObserver.NONE, observer);

        assertTrue(observer.waiting.await(5, TimeUnit.SECONDS));

        AtomicInteger accepted = new AtomicInteger();
        Thread offerThread = new Thread(() -> {
            if (log.offer(event("/raced"))) {
                accepted.incrementAndGet();
            }
        }, "access-log-offer");
        offerThread.start();
        offerThread.join(5000L);

        Thread closeThread = new Thread(log::close, "access-log-close");
        closeThread.start();
        awaitWaiting(closeThread);

        observer.release.countDown();
        awaitLines(sink, 1, 5000L);
        closeThread.join(5000L);

        assertEquals(1, accepted.get());
        assertFalse(offerThread.isAlive());
        assertFalse(closeThread.isAlive());
        assertEquals(1, sink.lines().size());
        assertEquals(line("/raced"), sink.lines().get(0));
    }

    @Test
    void observerFailureAfterPublishStillSignalsWriterBeforeClose() throws Exception {
        RecordingSink sink = new RecordingSink();
        BlockingWaitObserver waitObserver = new BlockingWaitObserver();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8,
                new ThrowingPublishObserver(), waitObserver);

        assertTrue(waitObserver.waiting.await(5, TimeUnit.SECONDS));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> log.offer(event("/published")));
        assertEquals("observer boom", failure.getMessage());

        waitObserver.release.countDown();
        awaitLines(sink, 1, 5000L);

        log.close();

        assertEquals(0L, log.droppedCount());
        assertEquals(1, sink.lines().size());
        assertEquals(line("/published"), sink.lines().get(0));
    }

    @Test
    void idleTailFlushesWithinTimeout() throws Exception {
        BufferingSink sink = new BufferingSink();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8);

        assertTrue(log.offer(event("/tail")));
        awaitFlushedLines(sink, 1, 5000L);

        log.close();

        assertEquals(1, sink.flushedLines().size());
        assertEquals(line("/tail"), sink.flushedLines().get(0));
    }

    @Test
    void closeForcesFlushOfDirtyTail() throws Exception {
        BufferingSink sink = new BufferingSink();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8);

        assertTrue(log.offer(event("/close-flush")));

        log.close();

        assertEquals(1, sink.flushedLines().size());
        assertEquals(line("/close-flush"), sink.flushedLines().get(0));
    }

    @Test
    void idleFlushFailureDropsBufferedEventsExactly() throws Exception {
        FailingFlushSink sink = new FailingFlushSink();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8);

        assertTrue(log.offer(event("/one")));
        assertTrue(log.offer(event("/two")));
        assertTrue(sink.flushAttempted.await(5, TimeUnit.SECONDS));
        awaitFailed(log, 5000L);

        log.close();

        assertEquals(2L, log.droppedCount());
        assertTrue(sink.flushedLines().isEmpty());
    }

    @Test
    void laterWriteFailureDropsDirtyCurrentAndQueuedEventsExactly() throws Exception {
        FailingWriteAfterBufferedSink sink = new FailingWriteAfterBufferedSink(3);
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8);

        assertTrue(log.offer(event("/one")));
        assertTrue(log.offer(event("/two")));
        assertTrue(log.offer(event("/three")));
        assertTrue(sink.failedWrite.await(5, TimeUnit.SECONDS));
        awaitFailed(log, 5000L);

        log.close();

        assertEquals(3L, log.droppedCount());
        assertTrue(sink.flushedLines().isEmpty());
    }

    @Test
    void rejectedOffersAfterWriterFailureAreCountedSeparately() throws Exception {
        FailingFlushSink sink = new FailingFlushSink();
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8);

        assertTrue(log.offer(event("/one")));
        assertTrue(sink.flushAttempted.await(5, TimeUnit.SECONDS));
        awaitFailed(log, 5000L);

        assertFalse(log.offer(event("/after-failure")));

        log.close();

        assertEquals(2L, log.droppedCount());
        assertEquals(1L, log.rejectedAfterFailureCount());
        assertTrue(sink.flushedLines().isEmpty());
    }

    @Test
    void concurrentOffersRespectExactCapacityAndCountDrops() throws Exception {
        BlockingSink sink = new BlockingSink(false);
        int capacity = 8;
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, capacity);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> producers = new ArrayList<Thread>();
        for (int index = 0; index < 32; index++) {
            final int producer = index;
            Thread thread = new Thread(() -> {
                if (log.offer(event("/p" + producer))) {
                    accepted.incrementAndGet();
                }
            }, "producer-" + index);
            producers.add(thread);
            thread.start();
        }
        for (Thread producer : producers) {
            producer.join(5000L);
        }

        assertEquals(capacity, accepted.get());
        assertEquals(32 - capacity, log.droppedCount());

        sink.release.countDown();
        log.close();
        assertEquals(capacity, sink.lines().size());
    }

    @Test
    void writerFailureDropsCurrentAndQueuedEventsExactly() throws Exception {
        BlockingSink sink = new BlockingSink(true);
        AccessLog log = new AccessLog(temporaryDirectory.resolve("access.log"), sink, 8);

        assertTrue(log.offer(event("/one")));
        assertTrue(sink.entered.await(5, TimeUnit.SECONDS));
        assertTrue(log.offer(event("/two")));
        assertTrue(log.offer(event("/three")));

        sink.release.countDown();
        log.close();

        assertEquals(3L, log.droppedCount());
        assertTrue(sink.lines().isEmpty());
    }

    @Test
    void rotatesThroughConfiguredBackups() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory, 1L, 2, 8);
        assertTrue(log.offer(event("/first")));
        assertTrue(log.offer(event("/second")));
        assertTrue(log.offer(event("/third")));

        log.close();

        Path active = log.path();
        assertTrue(read(active).contains("path=/third"));
        assertTrue(read(active.resolveSibling("access.log.1")).contains("path=/second"));
        assertTrue(read(active.resolveSibling("access.log.2")).contains("path=/first"));
        assertFalse(Files.exists(active.resolveSibling("access.log.3")));
    }

    @Test
    void writesUnicodeAsUtf8AndReplacesMalformedSurrogates() throws Exception {
        String path = "/\u4e2d\u6587/\uD83D\uDE00/\uD800/\uDC00";
        AccessLog log = AccessLog.open(temporaryDirectory, 1024L * 1024L, 2, 8);
        assertTrue(log.offer(event(path)));

        log.close();

        assertArrayEquals(line(path).getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(log.path()));
    }

    @Test
    void writerSanitizesEveryPublishedTextField() throws Exception {
        AccessLog.Event unsafe = new AccessLog.Event(123L, "127.0.0.1\nforged",
                "GE T", "/safe\r\nforged\u2003wide\u2028line\u2029end",
                "HTTP/1.1\tbad", 200, 4L, 10L,
                true, "write failed");
        AccessLog log = AccessLog.open(temporaryDirectory, 1024L * 1024L, 2, 8);
        assertTrue(log.offer(unsafe));

                log.close();

        assertEquals("ts=123 remote=127.0.0.1_forged method=GE_T"
                        + " path=/safe__forged_wide_line_end proto=HTTP/1.1_bad status=200"
                        + " bytes=4 durUs=10 ka=1 outcome=write_failed\n",
                read(log.path()));
    }

    @Test
    void calculatesUtf8LengthForAsciiBmpSupplementaryAndMalformedInput() {
        String value = "AZ\u4e2d\uD83D\uDE00\uD800x\uDC00";
        char[] characters = value.toCharArray();

        assertEquals(value.getBytes(StandardCharsets.UTF_8).length,
                AccessLog.utf8Length(characters, characters.length));
        assertEquals(12L, AccessLog.utf8Length(characters, characters.length));
        assertEquals(0L, AccessLog.utf8Length(characters, 0));
    }

    @Test
    void rotatesOnlyAfterExactUtf8ByteLimitIsFilled() throws Exception {
        String firstPath = "/" + repeat("\u4e2d", 100);
        String secondPath = "/second";
        String thirdPath = "/third";
        byte[] first = line(firstPath).getBytes(StandardCharsets.UTF_8);
        byte[] second = line(secondPath).getBytes(StandardCharsets.UTF_8);
        long exactLimit = first.length + second.length;
        AccessLog log = AccessLog.open(temporaryDirectory, exactLimit, 2, 8);
        assertTrue(log.offer(event(firstPath)));
        assertTrue(log.offer(event(secondPath)));
        assertTrue(log.offer(event(thirdPath)));

        log.close();

        Path active = log.path();
        byte[] expectedBackup = new byte[first.length + second.length];
        System.arraycopy(first, 0, expectedBackup, 0, first.length);
        System.arraycopy(second, 0, expectedBackup, first.length, second.length);
        assertArrayEquals(expectedBackup,
                Files.readAllBytes(active.resolveSibling("access.log.1")));
        assertArrayEquals(line(thirdPath).getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(active));
    }

    @Test
    void failsSynchronouslyWhenLogDirectoryCannotBeOpened() throws Exception {
        Files.write(temporaryDirectory.resolve("logs"),
                "not-a-directory".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> AccessLog.open(temporaryDirectory));
    }

    private static AccessLog.Event event(String path) {
        return new AccessLog.Event(123L, "127.0.0.1", "GET",
                path, "HTTP/1.1", 200, 4L, 10L, true, "complete");
    }

    private static String line(String path) {
        return "ts=123 remote=127.0.0.1 method=GET path=" + path
                + " proto=HTTP/1.1 status=200 bytes=4 durUs=10 ka=1 outcome=complete\n";
    }

    private static String repeat(String value, int count) {
        StringBuilder repeated = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            repeated.append(value);
        }
        return repeated.toString();
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static final class BlockingPublishObserver implements AccessLog.OfferObserver {
        private final CountDownLatch published = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void afterReserve() {
        }

        @Override
        public void afterPublish() {
            published.countDown();
            await(release);
        }
    }

    private static final class BlockingReserveObserver implements AccessLog.OfferObserver {
        private final CountDownLatch reserved = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void afterReserve() {
            reserved.countDown();
            await(release);
        }

        @Override
        public void afterPublish() {
        }
    }

    private static final class ThrowingPublishObserver implements AccessLog.OfferObserver {
        @Override
        public void afterReserve() {
        }

        @Override
        public void afterPublish() {
            throw new IllegalStateException("observer boom");
        }
    }

    private static final class BlockingWaitObserver implements AccessLog.WaitObserver {
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void beforePark() {
            waiting.countDown();
            await(release);
        }
    }

    private static class RecordingSink implements AccessLog.Sink {
        private final List<String> lines = new ArrayList<String>();

        @Override
        public synchronized void write(char[] characters, int length, long encodedBytes)
                throws IOException {
            lines.add(new String(characters, 0, length));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        protected synchronized List<String> lines() {
            return new ArrayList<String>(lines);
        }
    }

    private static final class BufferingSink implements AccessLog.Sink {
        private final List<String> pendingLines = new ArrayList<String>();
        private final List<String> flushedLines = new ArrayList<String>();

        @Override
        public synchronized void write(char[] characters, int length, long encodedBytes) {
            pendingLines.add(new String(characters, 0, length));
        }

        @Override
        public synchronized void flush() {
            flushedLines.addAll(pendingLines);
            pendingLines.clear();
        }

        @Override
        public void close() {
        }

        private synchronized List<String> flushedLines() {
            return new ArrayList<String>(flushedLines);
        }
    }

    private static final class FailingFlushSink implements AccessLog.Sink {
        private final CountDownLatch flushAttempted = new CountDownLatch(1);
        private final List<String> pendingLines = new ArrayList<String>();
        private final List<String> flushedLines = new ArrayList<String>();

        @Override
        public synchronized void write(char[] characters, int length, long encodedBytes) {
            pendingLines.add(new String(characters, 0, length));
        }

        @Override
        public synchronized void flush() throws IOException {
            flushAttempted.countDown();
            throw new IOException("simulated flush failure");
        }

        @Override
        public void close() {
        }

        private synchronized List<String> flushedLines() {
            return new ArrayList<String>(flushedLines);
        }
    }

    private static final class FailingWriteAfterBufferedSink implements AccessLog.Sink {
        private final CountDownLatch failedWrite = new CountDownLatch(1);
        private final List<String> pendingLines = new ArrayList<String>();
        private final List<String> flushedLines = new ArrayList<String>();
        private final int failOnWriteNumber;
        private int writes;

        private FailingWriteAfterBufferedSink(int failOnWriteNumber) {
            this.failOnWriteNumber = failOnWriteNumber;
        }

        @Override
        public synchronized void write(char[] characters, int length, long encodedBytes)
                throws IOException {
            writes++;
            if (writes == failOnWriteNumber) {
                failedWrite.countDown();
                throw new IOException("simulated write failure");
            }
            pendingLines.add(new String(characters, 0, length));
        }

        @Override
        public synchronized void flush() {
            flushedLines.addAll(pendingLines);
            pendingLines.clear();
        }

        @Override
        public void close() {
        }

        private synchronized List<String> flushedLines() {
            return new ArrayList<String>(flushedLines);
        }
    }

    private static final class BlockingSink extends RecordingSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean fail;

        private BlockingSink(boolean fail) {
            this.fail = fail;
        }

        @Override
        public synchronized void write(char[] characters, int length, long encodedBytes)
                throws IOException {
            entered.countDown();
            await(release);
            if (fail) {
                throw new IOException("simulated write failure");
            }
            super.write(characters, length, encodedBytes);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (thread.getState() != Thread.State.WAITING
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.WAITING, thread.getState());
    }

    private static void awaitLines(RecordingSink sink, int expected, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (sink.lines().size() < expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(expected, sink.lines().size());
    }

    private static void awaitFlushedLines(BufferingSink sink, int expected, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (sink.flushedLines().size() < expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(expected, sink.flushedLines().size());
    }

    private static void awaitFailed(AccessLog log, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!log.failed() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(log.failed());
    }
}
