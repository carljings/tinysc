package io.tinysc.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarExploderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void expandsOnceAndThenUsesShaCache() throws Exception {
        Path war = temporaryDirectory.resolve("probe.war");
        writeZip(war, "index.html", "hello");
        WarExploder exploder = new WarExploder();

        WarExploder.Result first = exploder.expand(
                war, temporaryDirectory.resolve("base"), "/probe", WarLimits.defaults());
        WarExploder.Result second = exploder.expand(
                war, temporaryDirectory.resolve("base"), "/probe", WarLimits.defaults());

        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        assertEquals(first.webRoot(), second.webRoot());
        assertEquals("hello", new String(Files.readAllBytes(
                first.webRoot().resolve("index.html")), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsZipSlip() throws Exception {
        Path war = temporaryDirectory.resolve("unsafe.war");
        writeZip(war, "../escape.txt", "owned");

        assertThrows(DeploymentException.class, () -> new WarExploder().expand(
                war, temporaryDirectory.resolve("base"), "/probe", WarLimits.defaults()));
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.txt")));
    }

    private static void writeZip(Path target, String entryName, String content) throws IOException {
        try (OutputStream output = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
