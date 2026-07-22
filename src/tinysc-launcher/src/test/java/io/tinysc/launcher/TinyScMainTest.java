package io.tinysc.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinyScMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsUnknownStartOptionBeforeOpeningWar() throws Exception {
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        PrintStream error = new PrintStream(errorBytes, true, "UTF-8");

        int exitCode = TinyScMain.run(new String[]{
                "start", "--war", "missing.war", "--bogus", "value"
        }, output, error);

        assertEquals(2, exitCode);
        String message = new String(errorBytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(message.contains("Unknown option: --bogus"));
        assertTrue(message.contains("Usage:"));
    }

    @Test
    void rejectsMalformedOption() throws Exception {
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        int exitCode = TinyScMain.run(new String[]{"start", "--war"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errorBytes, true, "UTF-8"));

        assertEquals(2, exitCode);
        assertTrue(new String(errorBytes.toByteArray(), StandardCharsets.UTF_8)
                .contains("Expected --name value"));
    }

    @Test
    void persistsStartupFailureAndRestoresSystemStreams() throws Exception {
        PrintStream originalOutput = System.out;
        PrintStream originalError = System.err;
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream terminalError = new ByteArrayOutputStream();
        Path base = temporaryDirectory.resolve("base");

        int exitCode = TinyScMain.run(new String[]{
                "start",
                "--war", temporaryDirectory.resolve("missing.war").toString(),
                "--base", base.toString(),
                "--port", "0"
        }, new PrintStream(terminalOutput, true, "UTF-8"),
                new PrintStream(terminalError, true, "UTF-8"));

        assertEquals(1, exitCode);
        assertSame(originalOutput, System.out);
        assertSame(originalError, System.err);
        String logOutput = new String(Files.readAllBytes(base.resolve("logs/tinysc.log")),
                StandardCharsets.UTF_8);
        assertTrue(logOutput.contains("tinysc starting"), logOutput);
        assertTrue(logOutput.contains("missing.war"), logOutput);
        assertTrue(logOutput.contains("workerMin="), logOutput);
        assertTrue(logOutput.contains("workerMax="), logOutput);
        assertTrue(logOutput.contains("workerQueue=100"), logOutput);
        assertTrue(logOutput.contains("workerIdleMs=60000"), logOutput);
        assertTrue(logOutput.contains("maxConnections=1024"), logOutput);
        assertTrue(logOutput.contains("maxInflightRequests="), logOutput);
        assertTrue(logOutput.contains("maxInflightRequestBytes=67108864"), logOutput);
        assertTrue(logOutput.contains("requestReadTimeoutMs=30000"), logOutput);
        assertTrue(terminalOutput.toString("UTF-8").contains("tinysc starting"));
        assertTrue(terminalError.size() > 0);
    }

    @Test
    void logsExplicitWorkerSettings() throws Exception {
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream terminalError = new ByteArrayOutputStream();
        Path base = temporaryDirectory.resolve("explicit-base");

        int exitCode = TinyScMain.run(new String[]{
                "start",
                "--war", temporaryDirectory.resolve("missing.war").toString(),
                "--base", base.toString(),
                "--workers", "9",
                "--min-workers", "3",
                "--worker-queue", "7",
                "--worker-idle-timeout", "45",
                "--max-connections", "11",
                "--max-inflight-request-bytes", "4294967296",
                "--request-read-timeout", "12345"
        }, new PrintStream(terminalOutput, true, "UTF-8"),
                new PrintStream(terminalError, true, "UTF-8"));

        assertEquals(1, exitCode);
        String logOutput = new String(Files.readAllBytes(base.resolve("logs/tinysc.log")),
                StandardCharsets.UTF_8);
        assertTrue(logOutput.contains("workerMin=3"), logOutput);
        assertTrue(logOutput.contains("workerMax=9"), logOutput);
        assertTrue(logOutput.contains("workerQueue=7"), logOutput);
        assertTrue(logOutput.contains("workerIdleMs=45000"), logOutput);
        assertTrue(logOutput.contains("maxConnections=11"), logOutput);
        assertTrue(logOutput.contains("maxInflightRequests=16"), logOutput);
        assertTrue(logOutput.contains("maxInflightRequestBytes=4294967296"), logOutput);
        assertTrue(logOutput.contains("requestReadTimeoutMs=12345"), logOutput);
    }

    @Test
    void rejectsInvalidWorkerOptionValues() throws Exception {
        ByteArrayOutputStream minWorkersErrorBytes = new ByteArrayOutputStream();

        int minWorkersExitCode = TinyScMain.run(new String[]{
                "start", "--war", "missing.war", "--min-workers", "0"
        }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(minWorkersErrorBytes, true, "UTF-8"));

        assertEquals(2, minWorkersExitCode);
        assertTrue(new String(minWorkersErrorBytes.toByteArray(), StandardCharsets.UTF_8)
                .contains("Option --min-workers must be positive"));

        ByteArrayOutputStream zeroErrorBytes = new ByteArrayOutputStream();

        int zeroExitCode = TinyScMain.run(new String[]{
                "start", "--war", "missing.war", "--worker-idle-timeout", "0"
        }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(zeroErrorBytes, true, "UTF-8"));

        assertEquals(2, zeroExitCode);
        assertTrue(new String(zeroErrorBytes.toByteArray(), StandardCharsets.UTF_8)
                .contains("Option --worker-idle-timeout must be positive"));

        ByteArrayOutputStream overflowErrorBytes = new ByteArrayOutputStream();
        int overflowExitCode = TinyScMain.run(new String[]{
                "start", "--war", "missing.war", "--worker-idle-timeout", "9223372036854776"
        }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(overflowErrorBytes, true, "UTF-8"));

        assertEquals(2, overflowExitCode);
        assertTrue(new String(overflowErrorBytes.toByteArray(), StandardCharsets.UTF_8)
                .contains("Option --worker-idle-timeout is too large"));
    }

    @Test
    void rejectsInvalidAdmissionOptionValues() throws Exception {
        ByteArrayOutputStream connectionError = new ByteArrayOutputStream();
        int connectionExit = TinyScMain.run(new String[]{
                "start", "--war", "missing.war", "--max-connections", "0"
        }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(connectionError, true, "UTF-8"));
        assertEquals(2, connectionExit);
        assertTrue(new String(connectionError.toByteArray(), StandardCharsets.UTF_8)
                .contains("Option --max-connections must be positive"));

        ByteArrayOutputStream bytesError = new ByteArrayOutputStream();
        int bytesExit = TinyScMain.run(new String[]{
                "start", "--war", "missing.war", "--max-inflight-request-bytes", "0"
        }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(bytesError, true, "UTF-8"));
        assertEquals(2, bytesExit);
        assertTrue(new String(bytesError.toByteArray(), StandardCharsets.UTF_8)
                .contains("Option --max-inflight-request-bytes must be positive"));

        ByteArrayOutputStream timeoutError = new ByteArrayOutputStream();
        int timeoutExit = TinyScMain.run(new String[]{
                "start", "--war", "missing.war", "--request-read-timeout", "0"
        }, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(timeoutError, true, "UTF-8"));
        assertEquals(2, timeoutExit);
        assertTrue(new String(timeoutError.toByteArray(), StandardCharsets.UTF_8)
                .contains("Option --request-read-timeout must be positive"));
    }
}
