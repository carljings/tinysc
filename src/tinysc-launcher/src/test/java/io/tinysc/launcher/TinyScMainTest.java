package io.tinysc.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinyScMainTest {
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
}
