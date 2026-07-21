package io.tinysc.deployment;

import io.tinysc.deployment.model.WebAppDescriptor;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public final class WarInspector {
    private static final int MAX_CLASS_BYTES = 32 * 1024 * 1024;
    private static final byte[] JAVAX_SERVLET = ascii("javax/servlet/");
    private static final byte[] JAKARTA_SERVLET = ascii("jakarta/servlet/");

    private final WebXmlParser webXmlParser = new WebXmlParser();

    public InspectionReport inspect(Path war) throws DeploymentException {
        Path source = war.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new DeploymentException("WAR is not a regular file: " + source);
        }
        Scan scan = new Scan();
        String webXmlVersion = "absent";
        try {
            String sha256 = Digests.sha256(source);
            try (ZipFile zip = ZipFile.builder().setPath(source).get()) {
                ZipArchiveEntry descriptorEntry = zip.getEntry("WEB-INF/web.xml");
                if (descriptorEntry != null) {
                    try (InputStream input = zip.getInputStream(descriptorEntry)) {
                        WebAppDescriptor descriptor = webXmlParser.parse(input,
                                source + "!/WEB-INF/web.xml");
                        webXmlVersion = descriptor.version();
                    }
                }
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    if (lowerName.startsWith("web-inf/classes/") && lowerName.endsWith(".class")) {
                        try (InputStream input = zip.getInputStream(entry)) {
                            scanClass(readBounded(input, MAX_CLASS_BYTES), name, scan);
                        }
                    } else if (lowerName.startsWith("web-inf/lib/")
                            && lowerName.endsWith(".jar")) {
                        if (lowerName.contains("servlet-api")) {
                            scan.servletApiBundled = true;
                        }
                        try (InputStream input = zip.getInputStream(entry)) {
                            scanJar(input, name, scan);
                        }
                    }
                }
            }
            List<String> warnings = new ArrayList<String>();
            InspectionReport.ServletNamespace namespace = scan.namespace();
            if (namespace == InspectionReport.ServletNamespace.MIXED) {
                warnings.add("WAR contains references to both javax.servlet and jakarta.servlet");
            }
            if (scan.maximumMajor > 52) {
                warnings.add("some bundled classes require Java "
                        + InspectionReport.javaVersionForClassMajor(scan.maximumMajor)
                        + "; verify whether those classes are reachable on Java 8");
            }
            if (scan.servletApiBundled) {
                warnings.add("WAR bundles a Servlet API; tinysc will protect the container-provided API");
            }
            return new InspectionReport(source, Files.size(source), sha256, webXmlVersion,
                    namespace, scan.classCount, scan.minimumMajor == Integer.MAX_VALUE
                    ? 0 : scan.minimumMajor, scan.maximumMajor, scan.servletApiBundled, warnings);
        } catch (DeploymentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DeploymentException("cannot inspect WAR " + source + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static void scanJar(InputStream input, String sourceName, Scan scan)
            throws IOException, DeploymentException {
        try (JarInputStream jar = new JarInputStream(input)) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if ("javax/servlet/Servlet.class".equals(name)
                        || "jakarta/servlet/Servlet.class".equals(name)) {
                    scan.servletApiBundled = true;
                }
                if (name.endsWith(".class")) {
                    scanClass(readBounded(jar, MAX_CLASS_BYTES), sourceName + "!/" + name, scan);
                }
            }
        }
    }

    private static void scanClass(byte[] bytes, String sourceName, Scan scan)
            throws DeploymentException {
        if (bytes.length < 8 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe
                || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe) {
            throw new DeploymentException("invalid class file: " + sourceName);
        }
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        scan.classCount++;
        scan.minimumMajor = Math.min(scan.minimumMajor, major);
        scan.maximumMajor = Math.max(scan.maximumMajor, major);
        scan.javax |= contains(bytes, JAVAX_SERVLET);
        scan.jakarta |= contains(bytes, JAKARTA_SERVLET);
    }

    private static byte[] readBounded(InputStream input, int limit)
            throws IOException, DeploymentException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > limit - read) {
                throw new DeploymentException("class file exceeds " + limit + " bytes");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static boolean contains(byte[] source, byte[] pattern) {
        outer:
        for (int offset = 0; offset <= source.length - pattern.length; offset++) {
            for (int index = 0; index < pattern.length; index++) {
                if (source[offset + index] != pattern[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] ascii(String value) {
        byte[] result = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            result[index] = (byte) value.charAt(index);
        }
        return result;
    }

    private static final class Scan {
        private boolean javax;
        private boolean jakarta;
        private boolean servletApiBundled;
        private int classCount;
        private int minimumMajor = Integer.MAX_VALUE;
        private int maximumMajor;

        private InspectionReport.ServletNamespace namespace() {
            if (javax && jakarta) {
                return InspectionReport.ServletNamespace.MIXED;
            }
            if (javax) {
                return InspectionReport.ServletNamespace.JAVAX;
            }
            if (jakarta) {
                return InspectionReport.ServletNamespace.JAKARTA;
            }
            return InspectionReport.ServletNamespace.NONE;
        }
    }
}
