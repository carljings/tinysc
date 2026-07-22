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

    @Test
    void defaultsWorkerMinimumAndIdleTimeoutFromWorkerMaximum() {
        ServerConfig defaultConfig = ServerConfig.builder().build();
        assertEquals(Math.min(8, defaultConfig.workerThreads()), defaultConfig.workerMinThreads());
        assertEquals(100, defaultConfig.workerQueueCapacity());
        assertEquals(60000L, defaultConfig.workerIdleTimeoutMillis());

        ServerConfig smallPoolConfig = ServerConfig.builder().workerThreads(3).build();
        assertEquals(3, smallPoolConfig.workerMinThreads());
    }

    @Test
    void keepsExplicitWorkerMinimumAndIdleTimeout() {
        ServerConfig config = ServerConfig.builder()
                .workerThreads(6)
                .workerMinThreads(2)
                .workerIdleTimeoutMillis(1500L)
                .build();

        assertEquals(6, config.workerThreads());
        assertEquals(2, config.workerMinThreads());
        assertEquals(1500L, config.workerIdleTimeoutMillis());
    }

    @Test
    void rejectsInvalidWorkerMinimumAndIdleTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().workerThreads(4).workerMinThreads(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().workerThreads(4).workerMinThreads(5).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().workerIdleTimeoutMillis(0L).build());
    }

    @Test
    void defaultsAndKeepsAdmissionLimits() {
        ServerConfig defaults = ServerConfig.builder().build();
        assertEquals(1024, defaults.maxConnections());
        assertEquals((long) defaults.workerThreads() + defaults.workerQueueCapacity(),
                defaults.maxInflightRequests());
        assertEquals(64L * 1024L * 1024L, defaults.maxInflightRequestBytes());
        assertEquals(30000L, defaults.requestReadTimeoutMillis());

        ServerConfig explicit = ServerConfig.builder()
                .workerThreads(3)
                .workerQueueCapacity(5)
                .maxConnections(7)
                .maxRequestBodySize(1024)
                .maxInflightRequestBytes(4096L)
                .requestReadTimeoutMillis(1500L)
                .build();
        assertEquals(7, explicit.maxConnections());
        assertEquals(8L, explicit.maxInflightRequests());
        assertEquals(4096L, explicit.maxInflightRequestBytes());
        assertEquals(1500L, explicit.requestReadTimeoutMillis());
    }

    @Test
    void rejectsInvalidAdmissionLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().maxConnections(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().maxInflightRequestBytes(0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().requestReadTimeoutMillis(0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder()
                        .maxRequestBodySize(1024)
                        .maxInflightRequestBytes(512L)
                        .build());
    }
}
