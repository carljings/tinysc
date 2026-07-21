package io.tinysc.servlet.javax;

final class ServletMappingResult {
    private final String servletName;
    private final String pattern;
    private final String servletPath;
    private final String pathInfo;

    ServletMappingResult(String servletName, String pattern, String servletPath, String pathInfo) {
        this.servletName = servletName;
        this.pattern = pattern;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
    }

    String servletName() {
        return servletName;
    }

    String pattern() {
        return pattern;
    }

    String servletPath() {
        return servletPath;
    }

    String pathInfo() {
        return pathInfo;
    }
}
