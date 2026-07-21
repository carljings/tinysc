package io.tinysc.deployment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InspectionReport {
    public enum ServletNamespace {
        NONE,
        JAVAX,
        JAKARTA,
        MIXED
    }

    private final Path source;
    private final long sourceBytes;
    private final String sha256;
    private final String webXmlVersion;
    private final ServletNamespace namespace;
    private final int classCount;
    private final int minimumClassMajor;
    private final int maximumClassMajor;
    private final boolean servletApiBundled;
    private final List<String> warnings;

    InspectionReport(Path source, long sourceBytes, String sha256, String webXmlVersion,
                     ServletNamespace namespace, int classCount, int minimumClassMajor,
                     int maximumClassMajor, boolean servletApiBundled, List<String> warnings) {
        this.source = source;
        this.sourceBytes = sourceBytes;
        this.sha256 = sha256;
        this.webXmlVersion = webXmlVersion;
        this.namespace = namespace;
        this.classCount = classCount;
        this.minimumClassMajor = minimumClassMajor;
        this.maximumClassMajor = maximumClassMajor;
        this.servletApiBundled = servletApiBundled;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public Path source() {
        return source;
    }

    public long sourceBytes() {
        return sourceBytes;
    }

    public String sha256() {
        return sha256;
    }

    public String webXmlVersion() {
        return webXmlVersion;
    }

    public ServletNamespace namespace() {
        return namespace;
    }

    public int classCount() {
        return classCount;
    }

    public int minimumClassMajor() {
        return minimumClassMajor;
    }

    public int maximumClassMajor() {
        return maximumClassMajor;
    }

    public boolean servletApiBundled() {
        return servletApiBundled;
    }

    public List<String> warnings() {
        return warnings;
    }

    public String recommendedRuntime() {
        if (namespace == ServletNamespace.JAVAX) {
            return "tinysc 1.x";
        }
        if (namespace == ServletNamespace.JAKARTA) {
            return "tinysc 2.x";
        }
        if (namespace == ServletNamespace.MIXED) {
            String descriptorRuntime = descriptorRuntime();
            return descriptorRuntime == null
                    ? "manual review required (mixed namespace)"
                    : descriptorRuntime + " (mixed dependencies; verify)";
        }
        String descriptorRuntime = descriptorRuntime();
        return descriptorRuntime == null ? "unknown" : descriptorRuntime;
    }

    private String descriptorRuntime() {
        if (webXmlVersion == null || webXmlVersion.isEmpty()
                || "absent".equals(webXmlVersion)) {
            return null;
        }
        int separator = webXmlVersion.indexOf('.');
        String majorValue = separator < 0 ? webXmlVersion
                : webXmlVersion.substring(0, separator);
        try {
            int major = Integer.parseInt(majorValue);
            if (major >= 2 && major <= 4) {
                return "tinysc 1.x";
            }
            if (major >= 5) {
                return "tinysc 2.x";
            }
        } catch (NumberFormatException ignored) {
            // A non-standard descriptor version requires manual review.
        }
        return null;
    }

    public static String javaVersionForClassMajor(int major) {
        if (major <= 0) {
            return "unknown";
        }
        if (major <= 48) {
            return "1." + (major - 44);
        }
        return Integer.toString(major - 44);
    }
}
