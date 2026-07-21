package io.tinysc.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherLogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsLogsDirectoryAndUsesDefaultLogPath() throws Exception {
        Path expectedLog = temporaryDirectory.resolve("logs/tinysc.log");

        LauncherLog log = openLog(1024, 2);
        try {
            log.output().print("started\n");
            log.output().flush();

            assertEquals(expectedLog, log.path());
            assertTrue(Files.isDirectory(expectedLog.getParent()));
            assertTrue(Files.isRegularFile(expectedLog));
        } finally {
            log.close();
        }
    }

    @Test
    void mirrorsStandardOutputAndErrorToTerminalAndLogFile() throws Exception {
        ByteArrayOutputStream terminalOutputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream terminalErrorBytes = new ByteArrayOutputStream();
        PrintStream terminalOutput = printStream(terminalOutputBytes);
        PrintStream terminalError = printStream(terminalErrorBytes);
        Path logPath;

        LauncherLog log = LauncherLog.open(
                temporaryDirectory, terminalOutput, terminalError, 1024, 2);
        try {
            logPath = log.path();
            log.output().print("standard-output\n");
            log.error().print("standard-error\n");
            log.output().flush();
            log.error().flush();
        } finally {
            log.close();
        }

        assertEquals("standard-output\n", utf8(terminalOutputBytes));
        assertEquals("standard-error\n", utf8(terminalErrorBytes));
        assertEquals("standard-output\nstandard-error\n", readUtf8(logPath));
    }

    @Test
    void appendsToExistingLogFile() throws Exception {
        Path logDirectory = temporaryDirectory.resolve("logs");
        Path logPath = logDirectory.resolve("tinysc.log");
        Files.createDirectories(logDirectory);
        Files.write(logPath, "previous-run\n".getBytes(StandardCharsets.UTF_8));

        LauncherLog log = openLog(1024, 2);
        try {
            log.output().print("current-run\n");
            log.output().flush();
        } finally {
            log.close();
        }

        assertEquals("previous-run\ncurrent-run\n", readUtf8(logPath));
    }

    @Test
    void rotatesLogThroughConfiguredBackups() throws Exception {
        byte[] first = "first-record-000000\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-record-00000\n".getBytes(StandardCharsets.UTF_8);
        byte[] third = "third-record-000000\n".getBytes(StandardCharsets.UTF_8);
        Path logPath;

        LauncherLog log = openLog(16, 2);
        try {
            logPath = log.path();
            write(log.output(), first);
            write(log.output(), second);
            write(log.output(), third);
        } finally {
            log.close();
        }

        assertEquals(new String(third, StandardCharsets.UTF_8), readUtf8(logPath));
        assertEquals(new String(second, StandardCharsets.UTF_8),
                readUtf8(logPath.resolveSibling("tinysc.log.1")));
        assertEquals(new String(first, StandardCharsets.UTF_8),
                readUtf8(logPath.resolveSibling("tinysc.log.2")));
        assertFalse(Files.exists(logPath.resolveSibling("tinysc.log.3")));
    }

    @Test
    void closeRestoresSystemStreamsWithoutClosingTerminalStreams() throws Exception {
        PrintStream originalOutput = System.out;
        PrintStream originalError = System.err;
        CloseTrackingOutputStream terminalOutputBytes = new CloseTrackingOutputStream();
        CloseTrackingOutputStream terminalErrorBytes = new CloseTrackingOutputStream();
        PrintStream terminalOutput = printStream(terminalOutputBytes);
        PrintStream terminalError = printStream(terminalErrorBytes);
        LauncherLog log = LauncherLog.open(
                temporaryDirectory, terminalOutput, terminalError, 1024, 2);

        try {
            try {
                log.install();
                assertSame(log.output(), System.out);
                assertSame(log.error(), System.err);
            } finally {
                log.close();
            }
            assertSame(originalOutput, System.out);
            assertSame(originalError, System.err);
            assertFalse(terminalOutputBytes.closed);
            assertFalse(terminalErrorBytes.closed);

            terminalOutput.print("terminal-output-open");
            terminalError.print("terminal-error-open");
            terminalOutput.flush();
            terminalError.flush();
            assertEquals("terminal-output-open", utf8(terminalOutputBytes));
            assertEquals("terminal-error-open", utf8(terminalErrorBytes));
        } finally {
            System.setOut(originalOutput);
            System.setErr(originalError);
        }
    }

    @Test
    void failsToOpenWhenLogsPathIsARegularFile() throws Exception {
        Files.write(temporaryDirectory.resolve("logs"),
                "not-a-directory".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> openLog(1024, 2));
    }

    private LauncherLog openLog(long maxBytes, int backups) throws IOException {
        return LauncherLog.open(
                temporaryDirectory,
                printStream(new ByteArrayOutputStream()),
                printStream(new ByteArrayOutputStream()),
                maxBytes,
                backups);
    }

    private static PrintStream printStream(ByteArrayOutputStream bytes) {
        try {
            return new PrintStream(bytes, true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void write(PrintStream stream, byte[] bytes) {
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String utf8(ByteArrayOutputStream bytes) {
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
