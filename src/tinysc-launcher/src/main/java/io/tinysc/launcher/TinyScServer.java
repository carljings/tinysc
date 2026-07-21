package io.tinysc.launcher;

import io.tinysc.deployment.PreparedWebApp;
import io.tinysc.deployment.WarDeploymentManager;
import io.tinysc.http.netty.NettyHttpConnector;
import io.tinysc.kernel.ServerConfig;
import io.tinysc.servlet.javax.JavaxServletRuntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class TinyScServer implements AutoCloseable {
    private final ServerConfig config;
    private final Path war;
    private PreparedWebApp application;
    private JavaxServletRuntime runtime;
    private NettyHttpConnector connector;
    private long readyMillis;
    private boolean started;

    public TinyScServer(ServerConfig config, Path war) {
        if (config == null || war == null) {
            throw new IllegalArgumentException("server config and WAR are required");
        }
        this.config = config;
        this.war = war.toAbsolutePath().normalize();
    }

    public synchronized int start() throws Exception {
        if (started) {
            throw new IllegalStateException("server is already started");
        }
        long startedAt = System.nanoTime();
        Files.createDirectories(config.baseDirectory());
        try {
            application = new WarDeploymentManager().prepare(
                    war, config.baseDirectory(), config.contextPath(),
                    TinyScServer.class.getClassLoader());
            runtime = new JavaxServletRuntime(application, config.contextPath());
            runtime.start();
            connector = new NettyHttpConnector(config, runtime);
            int port = connector.start();
            readyMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            started = true;
            return port;
        } catch (Exception failure) {
            closeAfterFailedStart(failure);
            throw failure;
        }
    }

    public void await() throws InterruptedException {
        NettyHttpConnector current = connector;
        if (!started || current == null) {
            throw new IllegalStateException("server is not started");
        }
        current.await();
    }

    public int port() {
        NettyHttpConnector current = connector;
        if (!started || current == null) {
            throw new IllegalStateException("server is not started");
        }
        return current.boundPort();
    }

    public long readyMillis() {
        return readyMillis;
    }

    public long prepareMillis() {
        PreparedWebApp current = application;
        if (current == null) {
            throw new IllegalStateException("deployment has not been prepared");
        }
        return current.prepareMillis();
    }

    public boolean expansionCacheHit() {
        PreparedWebApp current = application;
        if (current == null) {
            throw new IllegalStateException("deployment has not been prepared");
        }
        return current.expansionCacheHit();
    }

    public String sourceSha256() {
        PreparedWebApp current = application;
        if (current == null) {
            throw new IllegalStateException("deployment has not been prepared");
        }
        return current.sourceSha256();
    }

    @Override
    public synchronized void close() throws Exception {
        Exception failure = null;
        if (connector != null) {
            connector.close();
            connector = null;
        }
        if (runtime != null) {
            try {
                runtime.stop(Duration.ofMillis(config.shutdownGraceMillis()));
            } catch (Exception stopFailure) {
                failure = stopFailure;
            }
            runtime = null;
        }
        if (application != null) {
            try {
                application.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            application = null;
        }
        started = false;
        if (failure != null) {
            throw failure;
        }
    }

    private void closeAfterFailedStart(Exception original) {
        try {
            close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}
