package io.tinysc.kernel;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class ServerConfig {
    private final String bindAddress;
    private final int port;
    private final String contextPath;
    private final Path baseDirectory;
    private final int maxInitialLineLength;
    private final int maxHeaderSize;
    private final int maxRequestBodySize;
    private final int maxConnections;
    private final long maxInflightRequests;
    private final long maxInflightRequestBytes;
    private final long requestReadTimeoutMillis;
    private final int ioThreads;
    private final int workerThreads;
    private final int workerMinThreads;
    private final int workerQueueCapacity;
    private final long workerIdleTimeoutMillis;
    private final long shutdownGraceMillis;

    private ServerConfig(Builder builder) {
        bindAddress = requireText(builder.bindAddress, "bindAddress");
        if (builder.port < 0 || builder.port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        port = builder.port;
        contextPath = normalizeContextPath(builder.contextPath);
        baseDirectory = builder.baseDirectory.toAbsolutePath().normalize();
        maxInitialLineLength = requirePositive(builder.maxInitialLineLength, "maxInitialLineLength");
        maxHeaderSize = requirePositive(builder.maxHeaderSize, "maxHeaderSize");
        maxRequestBodySize = requirePositive(builder.maxRequestBodySize, "maxRequestBodySize");
        maxConnections = requirePositive(builder.maxConnections, "maxConnections");
        maxInflightRequestBytes = requirePositive(
                builder.maxInflightRequestBytes, "maxInflightRequestBytes");
        if (maxInflightRequestBytes < maxRequestBodySize) {
            throw new IllegalArgumentException(
                    "maxInflightRequestBytes must be at least maxRequestBodySize");
        }
        requestReadTimeoutMillis = requirePositive(
                builder.requestReadTimeoutMillis, "requestReadTimeoutMillis");
        ioThreads = requirePositive(builder.ioThreads, "ioThreads");
        workerThreads = requirePositive(builder.workerThreads, "workerThreads");
        workerMinThreads = builder.workerMinThreads == null
                ? Math.min(8, workerThreads) : builder.workerMinThreads.intValue();
        if (workerMinThreads < 1 || workerMinThreads > workerThreads) {
            throw new IllegalArgumentException("workerMinThreads must be between 1 and workerThreads");
        }
        workerQueueCapacity = requirePositive(builder.workerQueueCapacity, "workerQueueCapacity");
        maxInflightRequests = (long) workerThreads + workerQueueCapacity;
        if (builder.workerIdleTimeoutMillis <= 0) {
            throw new IllegalArgumentException("workerIdleTimeoutMillis must be positive");
        }
        workerIdleTimeoutMillis = builder.workerIdleTimeoutMillis;
        if (builder.shutdownGraceMillis < 0) {
            throw new IllegalArgumentException("shutdownGraceMillis must not be negative");
        }
        shutdownGraceMillis = builder.shutdownGraceMillis;
    }

    public String bindAddress() {
        return bindAddress;
    }

    public int port() {
        return port;
    }

    public String contextPath() {
        return contextPath;
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public int maxInitialLineLength() {
        return maxInitialLineLength;
    }

    public int maxHeaderSize() {
        return maxHeaderSize;
    }

    public int maxRequestBodySize() {
        return maxRequestBodySize;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public long maxInflightRequests() {
        return maxInflightRequests;
    }

    public long maxInflightRequestBytes() {
        return maxInflightRequestBytes;
    }

    public long requestReadTimeoutMillis() {
        return requestReadTimeoutMillis;
    }

    public int ioThreads() {
        return ioThreads;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int workerMinThreads() {
        return workerMinThreads;
    }

    public int workerQueueCapacity() {
        return workerQueueCapacity;
    }

    public long workerIdleTimeoutMillis() {
        return workerIdleTimeoutMillis;
    }

    public long shutdownGraceMillis() {
        return shutdownGraceMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static String normalizeContextPath(String value) {
        if (value == null || value.isEmpty() || "/".equals(value)) {
            return "";
        }
        if (!value.startsWith("/") || value.endsWith("/")) {
            throw new IllegalArgumentException("contextPath must start with '/' and must not end with '/'");
        }
        if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0 || value.contains("..")) {
            throw new IllegalArgumentException("contextPath contains an illegal segment");
        }
        return value;
    }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }

    public static final class Builder {
        private String bindAddress = "127.0.0.1";
        private int port = 8080;
        private String contextPath = "";
        private Path baseDirectory = Paths.get(".");
        private int maxInitialLineLength = 8192;
        private int maxHeaderSize = 16384;
        private int maxRequestBodySize = 16 * 1024 * 1024;
        private int maxConnections = 1024;
        private long maxInflightRequestBytes = 64L * 1024L * 1024L;
        private long requestReadTimeoutMillis = 30000L;
        private int ioThreads = Math.max(1, Math.min(2,
                Runtime.getRuntime().availableProcessors()));
        private int workerThreads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        private Integer workerMinThreads;
        private int workerQueueCapacity = 100;
        private long workerIdleTimeoutMillis = 60000L;
        private long shutdownGraceMillis = 30000L;

        private Builder() {
        }

        public Builder bindAddress(String value) {
            bindAddress = value;
            return this;
        }

        public Builder port(int value) {
            port = value;
            return this;
        }

        public Builder contextPath(String value) {
            contextPath = value;
            return this;
        }

        public Builder baseDirectory(Path value) {
            if (value == null) {
                throw new IllegalArgumentException("baseDirectory must not be null");
            }
            baseDirectory = value;
            return this;
        }

        public Builder maxInitialLineLength(int value) {
            maxInitialLineLength = value;
            return this;
        }

        public Builder maxHeaderSize(int value) {
            maxHeaderSize = value;
            return this;
        }

        public Builder maxRequestBodySize(int value) {
            maxRequestBodySize = value;
            return this;
        }

        public Builder maxConnections(int value) {
            maxConnections = value;
            return this;
        }

        public Builder maxInflightRequestBytes(long value) {
            maxInflightRequestBytes = value;
            return this;
        }

        public Builder requestReadTimeoutMillis(long value) {
            requestReadTimeoutMillis = value;
            return this;
        }

        public Builder ioThreads(int value) {
            ioThreads = value;
            return this;
        }

        public Builder workerThreads(int value) {
            workerThreads = value;
            return this;
        }

        public Builder workerMinThreads(int value) {
            workerMinThreads = value;
            return this;
        }

        public Builder workerQueueCapacity(int value) {
            workerQueueCapacity = value;
            return this;
        }

        public Builder workerIdleTimeoutMillis(long value) {
            workerIdleTimeoutMillis = value;
            return this;
        }

        public Builder shutdownGraceMillis(long value) {
            shutdownGraceMillis = value;
            return this;
        }

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }

    private static long requirePositive(long value, String field) {
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
