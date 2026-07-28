package io.tinysc.deployment.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WebAppDescriptor {
    private final String version;
    private final boolean metadataComplete;
    private final Map<String, String> contextParams;
    private final List<String> listenerClasses;
    private final Map<String, FilterDefinition> filters;
    private final List<FilterMapping> filterMappings;
    private final Map<String, ServletDefinition> servlets;
    private final List<ServletMapping> servletMappings;
    private final int sessionTimeoutMinutes;
    private final List<String> welcomeFiles;

    private WebAppDescriptor(Builder builder) {
        version = builder.version;
        metadataComplete = builder.metadataComplete;
        contextParams = immutableMap(builder.contextParams);
        listenerClasses = immutableList(builder.listenerClasses);
        filters = immutableMap(builder.filters);
        filterMappings = immutableList(builder.filterMappings);
        servlets = immutableMap(builder.servlets);
        servletMappings = immutableList(builder.servletMappings);
        sessionTimeoutMinutes = builder.sessionTimeoutMinutes;
        welcomeFiles = immutableList(builder.welcomeFiles);
    }

    public String version() {
        return version;
    }

    public boolean metadataComplete() {
        return metadataComplete;
    }

    public Map<String, String> contextParams() {
        return contextParams;
    }

    public List<String> listenerClasses() {
        return listenerClasses;
    }

    public Map<String, FilterDefinition> filters() {
        return filters;
    }

    public List<FilterMapping> filterMappings() {
        return filterMappings;
    }

    public Map<String, ServletDefinition> servlets() {
        return servlets;
    }

    public List<ServletMapping> servletMappings() {
        return servletMappings;
    }

    public int sessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public List<String> welcomeFiles() {
        return welcomeFiles;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(source));
    }

    private static <T> List<T> immutableList(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public static final class Builder {
        private String version = "3.1";
        private boolean metadataComplete;
        private final Map<String, String> contextParams = new LinkedHashMap<String, String>();
        private final List<String> listenerClasses = new ArrayList<String>();
        private final Map<String, FilterDefinition> filters = new LinkedHashMap<String, FilterDefinition>();
        private final List<FilterMapping> filterMappings = new ArrayList<FilterMapping>();
        private final Map<String, ServletDefinition> servlets = new LinkedHashMap<String, ServletDefinition>();
        private final List<ServletMapping> servletMappings = new ArrayList<ServletMapping>();
        private int sessionTimeoutMinutes = 30;
        private final List<String> welcomeFiles = new ArrayList<String>();

        private Builder() {
        }

        public Builder version(String value) {
            if (value != null && !value.isEmpty()) {
                version = value;
            }
            return this;
        }

        public Builder metadataComplete(boolean value) {
            metadataComplete = value;
            return this;
        }

        public Builder contextParam(String name, String value) {
            putUnique(contextParams, name, value, "context-param");
            return this;
        }

        public Builder listenerClass(String className) {
            listenerClasses.add(requireText(className, "listener-class"));
            return this;
        }

        public Builder filter(FilterDefinition definition) {
            putUnique(filters, definition.name(), definition, "filter");
            return this;
        }

        public Builder filterMapping(FilterMapping mapping) {
            filterMappings.add(mapping);
            return this;
        }

        public Builder servlet(ServletDefinition definition) {
            putUnique(servlets, definition.name(), definition, "servlet");
            return this;
        }

        public Builder servletMapping(ServletMapping mapping) {
            servletMappings.add(mapping);
            return this;
        }

        public Builder sessionTimeoutMinutes(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("session-timeout must not be negative");
            }
            sessionTimeoutMinutes = value;
            return this;
        }

        public Builder welcomeFile(String value) {
            welcomeFiles.add(requireText(value, "welcome-file"));
            return this;
        }

        public WebAppDescriptor build() {
            for (FilterMapping mapping : filterMappings) {
                if (!filters.containsKey(mapping.filterName())) {
                    throw new IllegalArgumentException("filter-mapping references unknown filter: "
                            + mapping.filterName());
                }
            }
            for (ServletMapping mapping : servletMappings) {
                if (!servlets.containsKey(mapping.servletName())) {
                    throw new IllegalArgumentException("servlet-mapping references unknown servlet: "
                            + mapping.servletName());
                }
            }
            return new WebAppDescriptor(this);
        }

        private static <K, V> void putUnique(Map<K, V> map, K key, V value, String kind) {
            if (map.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate " + kind + ": " + key);
            }
        }
    }

    public static final class ServletDefinition {
        private final String name;
        private final String className;
        private final String jspFile;
        private final Map<String, String> initParams;
        private final Integer loadOnStartup;
        private final boolean asyncSupported;
        private final MultipartConfigDefinition multipartConfig;

        public ServletDefinition(String name, String className, String jspFile,
                                 Map<String, String> initParams, Integer loadOnStartup,
                                 boolean asyncSupported) {
            this(name, className, jspFile, initParams, loadOnStartup, asyncSupported, null);
        }

        public ServletDefinition(String name, String className, String jspFile,
                                 Map<String, String> initParams, Integer loadOnStartup,
                                 boolean asyncSupported, MultipartConfigDefinition multipartConfig) {
            this.name = requireText(name, "servlet-name");
            if ((className == null || className.isEmpty()) == (jspFile == null || jspFile.isEmpty())) {
                throw new IllegalArgumentException(
                        "servlet must declare exactly one of servlet-class or jsp-file: " + name);
            }
            this.className = className;
            this.jspFile = jspFile;
            this.initParams = immutableMap(initParams);
            this.loadOnStartup = loadOnStartup;
            this.asyncSupported = asyncSupported;
            this.multipartConfig = multipartConfig;
        }

        public String name() {
            return name;
        }

        public String className() {
            return className;
        }

        public String jspFile() {
            return jspFile;
        }

        public Map<String, String> initParams() {
            return initParams;
        }

        public Integer loadOnStartup() {
            return loadOnStartup;
        }

        public boolean asyncSupported() {
            return asyncSupported;
        }

        public MultipartConfigDefinition multipartConfig() {
            return multipartConfig;
        }
    }

    public static final class MultipartConfigDefinition {
        private final String location;
        private final long maxFileSize;
        private final long maxRequestSize;
        private final int fileSizeThreshold;

        public MultipartConfigDefinition(String location, long maxFileSize,
                                         long maxRequestSize, int fileSizeThreshold) {
            if (maxFileSize < -1) {
                throw new IllegalArgumentException(
                        "max-file-size must be -1 or non-negative");
            }
            if (maxRequestSize < -1) {
                throw new IllegalArgumentException(
                        "max-request-size must be -1 or non-negative");
            }
            if (fileSizeThreshold < 0) {
                throw new IllegalArgumentException(
                        "file-size-threshold must not be negative");
            }
            this.location = location == null ? "" : location;
            this.maxFileSize = maxFileSize;
            this.maxRequestSize = maxRequestSize;
            this.fileSizeThreshold = fileSizeThreshold;
        }

        public String location() {
            return location;
        }

        public long maxFileSize() {
            return maxFileSize;
        }

        public long maxRequestSize() {
            return maxRequestSize;
        }

        public int fileSizeThreshold() {
            return fileSizeThreshold;
        }
    }

    public static final class FilterDefinition {
        private final String name;
        private final String className;
        private final Map<String, String> initParams;
        private final boolean asyncSupported;

        public FilterDefinition(String name, String className, Map<String, String> initParams,
                                boolean asyncSupported) {
            this.name = requireText(name, "filter-name");
            this.className = requireText(className, "filter-class");
            this.initParams = immutableMap(initParams);
            this.asyncSupported = asyncSupported;
        }

        public String name() {
            return name;
        }

        public String className() {
            return className;
        }

        public Map<String, String> initParams() {
            return initParams;
        }

        public boolean asyncSupported() {
            return asyncSupported;
        }
    }

    public static final class ServletMapping {
        private final String servletName;
        private final List<String> urlPatterns;

        public ServletMapping(String servletName, List<String> urlPatterns) {
            this.servletName = requireText(servletName, "servlet-name");
            if (urlPatterns.isEmpty()) {
                throw new IllegalArgumentException("servlet-mapping must contain a url-pattern");
            }
            this.urlPatterns = immutableList(urlPatterns);
        }

        public String servletName() {
            return servletName;
        }

        public List<String> urlPatterns() {
            return urlPatterns;
        }
    }

    public static final class FilterMapping {
        private final String filterName;
        private final List<String> urlPatterns;
        private final List<String> servletNames;
        private final List<String> dispatcherTypes;

        public FilterMapping(String filterName, List<String> urlPatterns, List<String> servletNames,
                             List<String> dispatcherTypes) {
            this.filterName = requireText(filterName, "filter-name");
            if (urlPatterns.isEmpty() && servletNames.isEmpty()) {
                throw new IllegalArgumentException(
                        "filter-mapping must contain a url-pattern or servlet-name");
            }
            this.urlPatterns = immutableList(urlPatterns);
            this.servletNames = immutableList(servletNames);
            List<String> dispatchers = dispatcherTypes.isEmpty()
                    ? Collections.singletonList("REQUEST") : dispatcherTypes;
            this.dispatcherTypes = immutableList(dispatchers);
        }

        public String filterName() {
            return filterName;
        }

        public List<String> urlPatterns() {
            return urlPatterns;
        }

        public List<String> servletNames() {
            return servletNames;
        }

        public List<String> dispatcherTypes() {
            return dispatcherTypes;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value.trim();
    }
}
