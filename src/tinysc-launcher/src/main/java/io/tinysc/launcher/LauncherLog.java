package io.tinysc.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class LauncherLog implements AutoCloseable {
    static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;
    static final int DEFAULT_BACKUPS = 5;

    private final Path path;
    private final RollingFileOutputStream file;
    private final Object writeLock = new Object();
    private final TeeOutputStream outputTarget;
    private final TeeOutputStream errorTarget;
    private final PrintStream output;
    private final PrintStream error;
    private PrintStream previousSystemOut;
    private PrintStream previousSystemErr;
    private boolean installed;
    private boolean closed;

    private LauncherLog(Path path, RollingFileOutputStream file,
                        PrintStream terminalOut, PrintStream terminalErr) {
        this.path = path;
        this.file = file;
        outputTarget = new TeeOutputStream(terminalOut, file, writeLock);
        errorTarget = new TeeOutputStream(terminalErr, file, writeLock);
        output = printStream(outputTarget);
        error = printStream(errorTarget);
    }

    static LauncherLog open(Path baseDirectory, PrintStream terminalOut,
                            PrintStream terminalErr) throws IOException {
        return open(baseDirectory, terminalOut, terminalErr,
                DEFAULT_MAX_BYTES, DEFAULT_BACKUPS);
    }

    static LauncherLog open(Path baseDirectory, PrintStream terminalOut,
                            PrintStream terminalErr, long maxBytes, int backups)
            throws IOException {
        if (baseDirectory == null || terminalOut == null || terminalErr == null) {
            throw new IllegalArgumentException("base directory and terminal streams are required");
        }
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (backups < 1) {
            throw new IllegalArgumentException("backups must be positive");
        }
        Path logs = baseDirectory.toAbsolutePath().normalize().resolve("logs");
        Files.createDirectories(logs);
        Path path = logs.resolve("tinysc.log");
        RollingFileOutputStream file = new RollingFileOutputStream(path, maxBytes, backups);
        return new LauncherLog(path, file, terminalOut, terminalErr);
    }

    PrintStream output() {
        return output;
    }

    PrintStream error() {
        return error;
    }

    Path path() {
        return path;
    }

    synchronized void install() {
        ensureOpen();
        if (installed) {
            return;
        }
        previousSystemOut = System.out;
        previousSystemErr = System.err;
        System.setOut(output);
        System.setErr(error);
        installed = true;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (installed) {
            if (System.out == output) {
                System.setOut(previousSystemOut);
            }
            if (System.err == error) {
                System.setErr(previousSystemErr);
            }
            installed = false;
        }
        output.flush();
        error.flush();
        IOException failure = null;
        synchronized (writeLock) {
            outputTarget.stop();
            errorTarget.stop();
            try {
                file.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
        }
        output.close();
        error.close();
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("launcher log is closed");
        }
    }

    private static PrintStream printStream(OutputStream output) {
        try {
            return new PrintStream(output, true, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is not available", impossible);
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream terminal;
        private final OutputStream file;
        private final Object writeLock;
        private boolean stopped;

        private TeeOutputStream(OutputStream terminal, OutputStream file, Object writeLock) {
            this.terminal = terminal;
            this.file = file;
            this.writeLock = writeLock;
        }

        @Override
        public void write(int value) throws IOException {
            synchronized (writeLock) {
                ensureOpen();
                terminal.write(value);
                file.write(value);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            synchronized (writeLock) {
                ensureOpen();
                terminal.write(bytes, offset, length);
                file.write(bytes, offset, length);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (writeLock) {
                if (!stopped) {
                    terminal.flush();
                    file.flush();
                }
            }
        }

        @Override
        public void close() {
            synchronized (writeLock) {
                stopped = true;
            }
        }

        private void stop() {
            stopped = true;
        }

        private void ensureOpen() throws IOException {
            if (stopped) {
                throw new IOException("launcher log is closed");
            }
        }
    }

    private static final class RollingFileOutputStream extends OutputStream {
        private final Path path;
        private final long maxBytes;
        private final int backups;
        private OutputStream output;
        private long size;
        private boolean closed;

        private RollingFileOutputStream(Path path, long maxBytes, int backups)
                throws IOException {
            this.path = path;
            this.maxBytes = maxBytes;
            this.backups = backups;
            if (Files.isRegularFile(path) && Files.size(path) >= maxBytes) {
                rotateFiles();
            }
            openActiveFile();
        }

        @Override
        public synchronized void write(int value) throws IOException {
            rotateIfNeeded(1);
            output.write(value);
            size++;
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return;
            }
            rotateIfNeeded(length);
            output.write(bytes, offset, length);
            size += length;
        }

        @Override
        public synchronized void flush() throws IOException {
            ensureOpen();
            output.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            try {
                output.close();
            } finally {
                closed = true;
            }
        }

        private void rotateIfNeeded(int nextWriteBytes) throws IOException {
            ensureOpen();
            if (size > 0L && size + nextWriteBytes > maxBytes) {
                output.flush();
                output.close();
                rotateFiles();
                openActiveFile();
            }
        }

        private void rotateFiles() throws IOException {
            Files.deleteIfExists(backup(backups));
            for (int index = backups - 1; index >= 1; index--) {
                Path source = backup(index);
                if (Files.exists(source)) {
                    Files.move(source, backup(index + 1),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(path)) {
                Files.move(path, backup(1), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void openActiveFile() throws IOException {
            output = Files.newOutputStream(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            size = Files.size(path);
            closed = false;
        }

        private Path backup(int index) {
            return path.resolveSibling(path.getFileName().toString() + "." + index);
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("log file is closed: " + path);
            }
        }
    }
}
