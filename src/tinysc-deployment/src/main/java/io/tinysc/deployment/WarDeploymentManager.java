package io.tinysc.deployment;

import io.tinysc.deployment.model.WebAppDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WarDeploymentManager {
    private final WarExploder exploder;
    private final WebXmlParser parser;
    private final WarLimits limits;

    public WarDeploymentManager() {
        this(new WarExploder(), new WebXmlParser(), WarLimits.defaults());
    }

    public WarDeploymentManager(WarExploder exploder, WebXmlParser parser, WarLimits limits) {
        this.exploder = exploder;
        this.parser = parser;
        this.limits = limits;
    }

    public PreparedWebApp prepare(Path source, Path baseDirectory, String contextPath,
                                  ClassLoader parent) throws DeploymentException {
        long started = System.nanoTime();
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path webRoot;
        String sha256;
        boolean cacheHit;
        if (Files.isDirectory(normalizedSource)) {
            webRoot = normalizedSource;
            sha256 = "exploded";
            cacheHit = true;
        } else {
            WarExploder.Result expansion = exploder.expand(
                    normalizedSource, baseDirectory, contextPath, limits);
            webRoot = expansion.webRoot();
            sha256 = expansion.sha256();
            cacheHit = expansion.cacheHit();
        }

        WebAppClassLoader classLoader = null;
        try {
            WebAppDescriptor descriptor = parser.parseWebRoot(webRoot);
            classLoader = WebAppClassLoader.create(webRoot, parent);
            WebAppResources resources = WebAppResources.create(webRoot);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            return new PreparedWebApp(normalizedSource, webRoot, sha256, cacheHit,
                    elapsedMillis, descriptor, classLoader, resources);
        } catch (DeploymentException exception) {
            closeQuietly(classLoader, exception);
            throw exception;
        } catch (Exception exception) {
            closeQuietly(classLoader, exception);
            throw new DeploymentException("cannot prepare web application: "
                    + exception.getMessage(), exception);
        }
    }

    private static void closeQuietly(WebAppClassLoader classLoader, Exception original) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}
