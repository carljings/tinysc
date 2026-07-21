package io.tinysc.deployment;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class WarExploder {
    private static final String COMPLETE_MARKER = ".tinysc-complete";

    public Result expand(Path war, Path baseDirectory, String contextPath, WarLimits limits)
            throws DeploymentException {
        Path source = war.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
            throw new DeploymentException("WAR must be a regular non-symbolic-link file: " + source);
        }
        try {
            String sha256 = Digests.sha256(source);
            Path workRoot = baseDirectory.toAbsolutePath().normalize()
                    .resolve("work").resolve(contextDirectory(contextPath));
            Path target = workRoot.resolve(sha256);
            Path lockDirectory = workRoot.resolve(".locks");
            Files.createDirectories(lockDirectory);
            Path lockPath = lockDirectory.resolve(sha256 + ".lock");
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (isComplete(target, sha256)) {
                    return new Result(target, sha256, true);
                }
                if (Files.exists(target)) {
                    deleteTree(target, workRoot);
                }
                Path staging = workRoot.resolve(".staging-" + sha256 + "-" + UUID.randomUUID());
                Files.createDirectories(staging);
                boolean moved = false;
                try {
                    extract(source, staging, limits);
                    Files.write(staging.resolve(COMPLETE_MARKER),
                            (sha256 + "\n").getBytes(StandardCharsets.US_ASCII),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    moveDirectory(staging, target);
                    moved = true;
                } finally {
                    if (!moved && Files.exists(staging)) {
                        deleteTree(staging, workRoot);
                    }
                }
                return new Result(target, sha256, false);
            }
        } catch (DeploymentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DeploymentException("cannot expand WAR " + source + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static void extract(Path war, Path staging, WarLimits limits)
            throws IOException, DeploymentException {
        int entryCount = 0;
        long totalBytes = 0;
        Set<String> names = new HashSet<String>();
        try (ZipFile zip = ZipFile.builder().setPath(war).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > limits.maxEntries()) {
                    throw new DeploymentException("WAR contains more than "
                            + limits.maxEntries() + " entries");
                }
                String name = validateEntryName(entry.getName());
                String duplicateKey = name.toLowerCase(Locale.ROOT);
                if (!names.add(duplicateKey)) {
                    throw new DeploymentException("WAR contains a duplicate entry: " + name);
                }
                if (entry.isUnixSymlink()) {
                    throw new DeploymentException("WAR contains a symbolic link: " + name);
                }
                if (!zip.canReadEntryData(entry)) {
                    throw new DeploymentException("WAR entry uses an unsupported or encrypted method: "
                            + name);
                }
                long declaredSize = entry.getSize();
                if (declaredSize > limits.maxEntryBytes()) {
                    throw new DeploymentException("WAR entry exceeds size limit: " + name);
                }
                if (declaredSize > 0 && totalBytes > limits.maxExpandedBytes() - declaredSize) {
                    throw new DeploymentException("WAR exceeds expanded size limit");
                }
                Path output = staging.resolve(name).normalize();
                if (!output.startsWith(staging)) {
                    throw new DeploymentException("WAR entry escapes staging directory: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                long written;
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream target = Files.newOutputStream(output,
                             StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    written = copyBounded(input, target, limits.maxEntryBytes());
                }
                totalBytes += written;
                if (totalBytes > limits.maxExpandedBytes()) {
                    throw new DeploymentException("WAR exceeds expanded size limit");
                }
            }
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long limit)
            throws IOException, DeploymentException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (written > limit - read) {
                throw new DeploymentException("WAR entry exceeds size limit while expanding");
            }
            output.write(buffer, 0, read);
            written += read;
        }
        return written;
    }

    private static String validateEntryName(String name) throws DeploymentException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new DeploymentException("WAR contains an unsafe entry name: " + name);
        }
        String[] segments = name.split("/");
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || (index == 0 && segment.indexOf(':') >= 0)) {
                throw new DeploymentException("WAR contains an unsafe entry name: " + name);
            }
        }
        return name;
    }

    private static boolean isComplete(Path target, String sha256) throws IOException {
        Path marker = target.resolve(COMPLETE_MARKER);
        if (!Files.isDirectory(target) || !Files.isRegularFile(marker)) {
            return false;
        }
        String value = new String(Files.readAllBytes(marker), StandardCharsets.US_ASCII).trim();
        return sha256.equals(value);
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path target, Path allowedRoot) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedRoot = allowedRoot.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("refusing to delete outside WAR work directory: " + target);
        }
        Files.walkFileTree(normalizedTarget, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String contextDirectory(String contextPath) {
        if (contextPath == null || contextPath.isEmpty() || "/".equals(contextPath)) {
            return "ROOT";
        }
        String value = contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static final class Result {
        private final Path webRoot;
        private final String sha256;
        private final boolean cacheHit;

        private Result(Path webRoot, String sha256, boolean cacheHit) {
            this.webRoot = webRoot;
            this.sha256 = sha256;
            this.cacheHit = cacheHit;
        }

        public Path webRoot() {
            return webRoot;
        }

        public String sha256() {
            return sha256;
        }

        public boolean cacheHit() {
            return cacheHit;
        }
    }
}
