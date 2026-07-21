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
        Entry exact = null;
        Entry longestPath = null;
        Entry extension = null;
        Entry defaultEntry = null;
        for (Entry entry : entries) {
            String pattern = entry.pattern;
            if (pattern.equals(path) || (pattern.isEmpty() && "/".equals(path))) {
                exact = entry;
                break;
            }
            if (isPathPattern(pattern) && pathMatches(pattern, path)) {
                if (longestPath == null || pattern.length() > longestPath.pattern.length()) {
                    longestPath = entry;
                }
            } else if (isExtensionPattern(pattern) && extensionMatches(pattern, path)) {
                if (extension == null) {
                    extension = entry;
                }
            } else if ("/".equals(pattern) && defaultEntry == null) {
                defaultEntry = entry;
            }
        }
        if (exact != null) {
            return new ServletMappingResult(exact.servletName, exact.pattern,
                    exact.pattern.isEmpty() ? "" : path, null);
        }
        if (longestPath != null) {
            String matched = longestPath.pattern.substring(0, longestPath.pattern.length() - 2);
            String pathInfo = path.length() == matched.length() ? null : path.substring(matched.length());
            return new ServletMappingResult(longestPath.servletName, longestPath.pattern,
                    matched, pathInfo);
        }
        if (extension != null) {
            return new ServletMappingResult(extension.servletName, extension.pattern, path, null);
        }
        if (defaultEntry != null) {
            return new ServletMappingResult(defaultEntry.servletName, defaultEntry.pattern, path, null);
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
        String prefix = pattern.substring(0, pattern.length() - 2);
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean extensionMatches(String pattern, String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash && path.substring(dot + 1).equals(pattern.substring(2));
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

        private Entry(String servletName, String pattern) {
            this.servletName = servletName;
            this.pattern = pattern;
        }
    }
}
