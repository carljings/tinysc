package io.tinysc.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerConfigTest {
    @Test
    void normalizesRootContextPath() {
        assertEquals("", ServerConfig.builder().contextPath("/").build().contextPath());
        assertEquals("/example",
                ServerConfig.builder().contextPath("/example").build().contextPath());
    }

    @Test
    void rejectsAmbiguousContextPath() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().contextPath("/example/").build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().contextPath("/../admin").build());
    }

    @Test
    void keepsIoThreadPoolExplicitAndBounded() {
        int defaultThreads = ServerConfig.builder().build().ioThreads();
        assertEquals(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())),
                defaultThreads);
        assertEquals(3, ServerConfig.builder().ioThreads(3).build().ioThreads());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().ioThreads(0).build());
    }
}
