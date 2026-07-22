package io.tinysc.servlet.javax;

import io.tinysc.deployment.model.WebAppDescriptor;

import java.util.ArrayList;
import java.util.List;

final class ServletMapper {
    private final List<Entry> entries = new ArrayList<Entry>();

    ServletMapper(List<WebAppDescriptor.ServletMapping> mappings) {
        for (WebAppDescriptor.ServletMapping mapping : mappings) {
            for (String pattern : mapping.urlPatterns()) {
                entries.add(new Entry(mapping.servletName(), validatePattern(pattern)));
            }
        }
    }

    ServletMappingResult map(String path) {
        Entry longestPath = null;
        Entry extension = null;
        Entry defaultEntry = null;
        for (Entry entry : entries) {
            String pattern = entry.pattern;
            if (entry.exactResult != null && entry.matchesExact(path)) {
                return entry.exactResult;
            }
            if (entry.pathPrefix != null && pathMatches(pattern, path)) {
                if (longestPath == null || pattern.length() > longestPath.pattern.length()) {
                    longestPath = entry;
                }
            } else if (entry.extensionPattern && extensionMatches(pattern, path)) {
                if (extension == null) {
                    extension = entry;
                }
            } else if ("/".equals(pattern) && defaultEntry == null) {
                defaultEntry = entry;
            }
        }
        if (longestPath != null) {
            String matched = longestPath.pathPrefix;
            String pathInfo = path.length() == matched.length()
                    ? null : path.substring(matched.length());
            return new ServletMappingResult(longestPath.servletName, longestPath.pattern,
                    matched, pathInfo);
        }
        if (extension != null) {
            return new ServletMappingResult(extension.servletName, extension.pattern, path, null);
        }
        if (defaultEntry != null) {
            return new ServletMappingResult(
                    defaultEntry.servletName, defaultEntry.pattern, path, null);
        }
        return null;
    }

    static boolean matchesUrlPattern(String pattern, String path) {
        if (pattern.equals(path) || "/".equals(pattern) || "/*".equals(pattern)) {
            return true;
        }
        if (isPathPattern(pattern)) {
            return pathMatches(pattern, path);
        }
        return isExtensionPattern(pattern) && extensionMatches(pattern, path);
    }

    private static boolean pathMatches(String pattern, String path) {
        int prefixLength = pattern.length() - 2;
        if (prefixLength == 0) {
            return true;
        }
        int pathLength = path.length();
        if (pathLength < prefixLength
                || !path.regionMatches(0, pattern, 0, prefixLength)) {
            return false;
        }
        return pathLength == prefixLength || path.charAt(prefixLength) == '/';
    }

    private static boolean extensionMatches(String pattern, String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        int extensionLength = pattern.length() - 2;
        return dot > slash
                && path.length() == dot + 1 + extensionLength
                && path.regionMatches(dot + 1, pattern, 2, extensionLength);
    }

    private static boolean isPathPattern(String pattern) {
        return pattern.endsWith("/*");
    }

    private static boolean isExtensionPattern(String pattern) {
        return pattern.startsWith("*.") && pattern.length() > 2;
    }

    private static String validatePattern(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid servlet url-pattern");
        }
        if (value.isEmpty() || value.startsWith("/") || isExtensionPattern(value)) {
            return value;
        }
        throw new IllegalArgumentException("invalid servlet url-pattern: " + value);
    }

    private static final class Entry {
        private final String servletName;
        private final String pattern;
        private final String pathPrefix;
        private final boolean extensionPattern;
        private final ServletMappingResult exactResult;

        private Entry(String servletName, String pattern) {
            this.servletName = servletName;
            this.pattern = pattern;
            boolean pathPattern = isPathPattern(pattern);
            extensionPattern = isExtensionPattern(pattern);
            pathPrefix = pathPattern
                    ? pattern.substring(0, pattern.length() - 2) : null;
            exactResult = !pathPattern && !extensionPattern && !"/".equals(pattern)
                    ? new ServletMappingResult(servletName, pattern,
                    pattern.isEmpty() ? "" : pattern, null) : null;
        }

        private boolean matchesExact(String path) {
            return pattern.equals(path) || (pattern.isEmpty() && "/".equals(path));
        }

    }
}
