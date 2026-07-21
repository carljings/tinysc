package io.tinysc.deployment;

import io.tinysc.deployment.model.WebAppDescriptor;

import java.io.IOException;
import java.nio.file.Path;

public final class PreparedWebApp implements AutoCloseable {
    private final Path source;
    private final Path webRoot;
    private final String sourceSha256;
    private final boolean expansionCacheHit;
    private final long prepareMillis;
    private final WebAppDescriptor descriptor;
    private final WebAppClassLoader classLoader;

    PreparedWebApp(Path source, Path webRoot, String sourceSha256, boolean expansionCacheHit,
                   long prepareMillis, WebAppDescriptor descriptor,
                   WebAppClassLoader classLoader) {
        this.source = source;
        this.webRoot = webRoot;
        this.sourceSha256 = sourceSha256;
        this.expansionCacheHit = expansionCacheHit;
        this.prepareMillis = prepareMillis;
        this.descriptor = descriptor;
        this.classLoader = classLoader;
    }

    public Path source() {
        return source;
    }

    public Path webRoot() {
        return webRoot;
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public boolean expansionCacheHit() {
        return expansionCacheHit;
    }

    public long prepareMillis() {
        return prepareMillis;
    }

    public WebAppDescriptor descriptor() {
        return descriptor;
    }

    public WebAppClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public void close() throws IOException {
        classLoader.close();
    }
}
