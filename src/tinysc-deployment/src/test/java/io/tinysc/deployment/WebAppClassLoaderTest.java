package io.tinysc.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAppClassLoaderTest {
    @TempDir
    Path webRoot;

    @Test
    void interruptsApplicationOwnedThreadBeforeClosing() throws Exception {
        Files.createDirectories(webRoot.resolve("WEB-INF/classes"));
        WebAppClassLoader loader = WebAppClassLoader.create(
                webRoot, getClass().getClassLoader());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread applicationThread = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }, "test-webapp-thread");
        applicationThread.setContextClassLoader(loader);
        applicationThread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));

        loader.close();

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertFalse(applicationThread.isAlive());
    }
}
