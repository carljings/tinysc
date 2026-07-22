package io.tinysc.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())),
                defaultThreads);
        assertEquals(3, ServerConfig.builder().ioThreads(3).build().ioThreads());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().ioThreads(0).build());
    }

    @Test
    void defaultsWorkerMinimumAndIdleTimeoutFromWorkerMaximum() {
        ServerConfig defaultConfig = ServerConfig.builder().build();
        assertEquals(Math.min(2, defaultConfig.workerThreads()), defaultConfig.workerMinThreads());
        assertEquals(100, defaultConfig.workerQueueCapacity());
        assertEquals(60000L, defaultConfig.workerIdleTimeoutMillis());

        ServerConfig smallPoolConfig = ServerConfig.builder().workerThreads(3).build();
        assertEquals(2, smallPoolConfig.workerMinThreads());

        ServerConfig singleWorkerConfig = ServerConfig.builder().workerThreads(1).build();
        assertEquals(1, singleWorkerConfig.workerMinThreads());
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
        assertEquals(64L * 1024L * 1024L, defaults.maxRawIngressBytes());
        assertEquals(30000L, defaults.requestReadTimeoutMillis());
        assertEquals(300000L, defaults.requestBodyTimeoutMillis());
        assertEquals(30000L, defaults.responseWriteTimeoutMillis());

        ServerConfig explicit = ServerConfig.builder()
                .workerThreads(3)
                .workerQueueCapacity(5)
                .maxConnections(7)
                .maxRequestBodySize(1024)
                .maxInflightRequestBytes(4096L)
                .maxRawIngressBytes(8192L)
                .requestReadTimeoutMillis(1500L)
                .requestBodyTimeoutMillis(2500L)
                .responseWriteTimeoutMillis(3500L)
                .build();
        assertEquals(7, explicit.maxConnections());
        assertEquals(8L, explicit.maxInflightRequests());
        assertEquals(4096L, explicit.maxInflightRequestBytes());
        assertEquals(8192L, explicit.maxRawIngressBytes());
        assertEquals(1500L, explicit.requestReadTimeoutMillis());
        assertEquals(2500L, explicit.requestBodyTimeoutMillis());
        assertEquals(3500L, explicit.responseWriteTimeoutMillis());
    }

    @Test
    void rejectsInvalidAdmissionLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().maxConnections(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().maxInflightRequestBytes(0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().maxRawIngressBytes(0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().requestReadTimeoutMillis(0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().requestBodyTimeoutMillis(0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().responseWriteTimeoutMillis(0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder()
                        .maxRequestBodySize(1024)
                        .maxInflightRequestBytes(512L)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder()
                        .maxRequestBodySize(1024)
                        .maxRawIngressBytes(512L)
                        .build());
    }

    @Test
    void keepsEmbeddedAccessLogDisabledUnlessExplicitlyEnabled() {
        assertFalse(ServerConfig.builder().build().accessLogEnabled());
        assertTrue(ServerConfig.builder().accessLogEnabled(true).build().accessLogEnabled());
    }
}
