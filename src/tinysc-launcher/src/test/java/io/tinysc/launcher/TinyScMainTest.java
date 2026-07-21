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
        assertTrue(terminalOutput.toString("UTF-8").contains("tinysc starting"));
        assertTrue(terminalError.size() > 0);
    }
}
