package io.tinysc.deployment;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class WebAppResources {
    private static final String RESOURCE_PREFIX = "META-INF/resources/";

    private final Path webRoot;
    private final Map<String, URL> jarResources;
    private final Map<String, Set<String>> jarResourcePaths;

    private WebAppResources(Path webRoot, Map<String, URL> jarResources,
                            Map<String, Set<String>> jarResourcePaths) {
        this.webRoot = webRoot;
        this.jarResources = jarResources;
        this.jarResourcePaths = jarResourcePaths;
    }

    public static WebAppResources create(Path webRoot) throws IOException {
        Path normalizedRoot = webRoot.toAbsolutePath().normalize();
        Map<String, URL> resources = new LinkedHashMap<String, URL>();
        Map<String, Set<String>> resourcePaths =
                new LinkedHashMap<String, Set<String>>();
        Path libraries = normalizedRoot.resolve("WEB-INF").resolve("lib");
        if (Files.isDirectory(libraries)) {
            List<Path> jars = new ArrayList<Path>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(libraries, "*.jar")) {
                for (Path jar : stream) {
                    jars.add(jar);
                }
            }
            Collections.sort(jars);
            for (Path jar : jars) {
                indexJar(jar, resources, resourcePaths);
            }
        }
        Map<String, Set<String>> immutablePaths =
                new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> entry : resourcePaths.entrySet()) {
            immutablePaths.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        return new WebAppResources(normalizedRoot,
                Collections.unmodifiableMap(resources),
                Collections.unmodifiableMap(immutablePaths));
    }

    public URL getResource(String path) throws MalformedURLException {
        String normalized = normalize(path);
        if (normalized == null) {
            return null;
        }
        Path file = resolve(normalized);
        if (file != null && Files.exists(file)) {
            if (normalized.endsWith("/") && !Files.isDirectory(file)) {
                return null;
            }
            return file.toUri().toURL();
        }
        if (isShadowedByFile(normalized)) {
            return null;
        }
        return jarResources.get(normalized);
    }

    public Set<String> getResourcePaths(String path) {
        String normalized = normalize(path);
        if (normalized == null) {
            return null;
        }
        String directory = normalized.endsWith("/") ? normalized : normalized + "/";
        Set<String> result = new LinkedHashSet<String>();
        Path fileDirectory = resolve(directory);
        if (fileDirectory != null && Files.isDirectory(fileDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileDirectory)) {
                for (Path child : stream) {
                    String suffix = child.getFileName().toString()
                            + (Files.isDirectory(child) ? "/" : "");
                    result.add(directory + suffix);
                }
            } catch (IOException ignored) {
                return null;
            }
        }
        Set<String> packaged = jarResourcePaths.get(directory);
        if (packaged != null && !isShadowedByFile(directory)) {
            result.addAll(packaged);
        }
        return result.isEmpty() ? null : Collections.unmodifiableSet(result);
    }

    private Path resolve(String normalized) {
        Path resolved = webRoot.resolve(normalized.substring(1)).normalize();
        return resolved.startsWith(webRoot) ? resolved : null;
    }

    private boolean isShadowedByFile(String normalized) {
        Path current = webRoot;
        Path relative = webRoot.relativize(resolve(normalized));
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current) && !Files.isDirectory(current)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        Path resolved = resolve(path);
        if (resolved == null) {
            return null;
        }
        String relative = webRoot.relativize(resolved).toString()
                .replace(java.io.File.separatorChar, '/');
        String normalized = relative.isEmpty() ? "/" : "/" + relative;
        if (path.endsWith("/") && !normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    private static void indexJar(Path jar, Map<String, URL> resources,
                                 Map<String, Set<String>> resourcePaths)
            throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(RESOURCE_PREFIX)) {
                    continue;
                }
                String relative = name.substring(RESOURCE_PREFIX.length());
                if (relative.isEmpty() || !isSafeEntry(relative)) {
                    continue;
                }
                addEntry(jar, relative, entry.isDirectory(), resources, resourcePaths);
            }
        }
    }

    private static void addEntry(Path jar, String relative, boolean directory,
                                 Map<String, URL> resources,
                                 Map<String, Set<String>> resourcePaths)
            throws MalformedURLException {
        String trimmed = directory && relative.endsWith("/")
                ? relative.substring(0, relative.length() - 1) : relative;
        String[] segments = trimmed.split("/");
        String parent = "/";
        StringBuilder entryPath = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (entryPath.length() > 0) {
                entryPath.append('/');
            }
            entryPath.append(segments[index]);
            boolean childDirectory = index < segments.length - 1 || directory;
            String child = "/" + entryPath + (childDirectory ? "/" : "");
            Set<String> children = resourcePaths.get(parent);
            if (children == null) {
                children = new LinkedHashSet<String>();
                resourcePaths.put(parent, children);
            }
            children.add(child);
            if (childDirectory && !resources.containsKey(child)) {
                resources.put(child, jarUrl(jar,
                        RESOURCE_PREFIX + entryPath + "/"));
            }
            parent = childDirectory ? child : parent;
        }
        String resourcePath = "/" + trimmed + (directory ? "/" : "");
        if (!resources.containsKey(resourcePath)) {
            resources.put(resourcePath, jarUrl(jar,
                    RESOURCE_PREFIX + trimmed + (directory ? "/" : "")));
        }
    }

    private static URL jarUrl(Path jar, String entry) throws MalformedURLException {
        return new URL("jar:" + jar.toUri().toURL().toExternalForm() + "!/" + entry);
    }

    private static boolean isSafeEntry(String entry) {
        if (entry.startsWith("/") || entry.indexOf('\\') >= 0) {
            return false;
        }
        String[] segments = entry.split("/");
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }
}
