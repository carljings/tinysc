package io.tinysc.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherLoggingIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesStartupMessagesToConsoleAndLogFile() throws Exception {
        Path launcher = Paths.get(System.getProperty("tinysc.launcher.jar")).toAbsolutePath();
        Path war = Paths.get(System.getProperty("tinysc.probe.war")).toAbsolutePath();
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        Path base = temporaryDirectory.resolve("base");
        Path console = temporaryDirectory.resolve("console.log");
        Path log = base.resolve("logs").resolve("tinysc.log");

        Process process = new ProcessBuilder(Arrays.asList(
                java.toString(),
                "-jar", launcher.toString(),
                "start",
                "--war", war.toString(),
                "--port", "0",
                "--base", base.toString()))
                .redirectErrorStream(true)
                .redirectOutput(console.toFile())
                .start();
        try {
            awaitLogMessage(log, "tinysc ready", 30, TimeUnit.SECONDS);

            String consoleOutput = read(console);
            String logOutput = read(log);
            assertTrue(consoleOutput.contains("tinysc starting"), consoleOutput);
            assertTrue(consoleOutput.contains("tinysc ready"), consoleOutput);
            assertTrue(logOutput.contains("tinysc starting"), logOutput);
            assertTrue(logOutput.contains("tinysc ready"), logOutput);
            assertTrue(logOutput.contains("tinysc log file="), logOutput);

            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS),
                    "Launcher did not stop after graceful process termination");
            String stoppedLogOutput = read(log);
            assertTrue(stoppedLogOutput.contains("tinysc shutdown requested"), stoppedLogOutput);
            assertTrue(stoppedLogOutput.contains("tinysc stopped"), stoppedLogOutput);
        } finally {
            if (process.isAlive()) {
                process.destroy();
            }
            if (process.isAlive() && !process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitLogMessage(Path log, String message, long timeout, TimeUnit unit)
            throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(log) && read(log).contains(message)) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(false, "Timed out waiting for '" + message + "' in " + log
                + System.lineSeparator() + read(log));
    }

    private static String read(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
