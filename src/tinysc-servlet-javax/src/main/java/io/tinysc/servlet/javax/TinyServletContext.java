package io.tinysc.servlet.javax;

import io.tinysc.deployment.WebAppClassLoader;
import io.tinysc.deployment.WebAppResources;

import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.Cookie;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

final class TinyServletContext implements ServletContext, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TinyServletContext.class.getName());

    private final String contextPath;
    private final Path webRoot;
    private final WebAppClassLoader classLoader;
    private final WebAppResources resources;
    private final JavaxServletRuntime registry;
    private final Map<String, String> initParameters;
    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();
    private final Map<String, String> mimeTypeCache = new ConcurrentHashMap<String, String>();
    private final TinySessionCookieConfig sessionCookieConfig;
    private final Set<String> roles = new LinkedHashSet<String>();
    private final Path tempDirectory;
    private volatile boolean initialized;

    TinyServletContext(String contextPath, Path webRoot, WebAppClassLoader classLoader,
                       WebAppResources resources,
                       Map<String, String> initParameters, JavaxServletRuntime registry)
            throws IOException {
        this.contextPath = contextPath;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.classLoader = classLoader;
        this.resources = resources;
        this.registry = registry;
        this.initParameters = new LinkedHashMap<String, String>(initParameters);
        sessionCookieConfig = new TinySessionCookieConfig();
        tempDirectory = Files.createTempDirectory("tinysc-webapp-");
        attributes.put(ServletContext.TEMPDIR, tempDirectory.toFile());
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    Path tempDirectory() {
        return tempDirectory;
    }

    @Override
    public ServletContext getContext(String uriPath) {
        return contextPath.equals(uriPath) ? this : null;
    }

    @Override
    public int getMajorVersion() {
        return 3;
    }

    @Override
    public int getMinorVersion() {
        return 1;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 3;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 1;
    }

    @Override
    public String getMimeType(String file) {
        String cached = mimeTypeCache.get(file);
        if (cached != null) {
            return cached;
        }
        try {
            Path candidate = webRoot.resolve(file).normalize();
            if (candidate.startsWith(webRoot)) {
                String detected = Files.probeContentType(candidate);
                if (detected != null) {
                    String existing = mimeTypeCache.putIfAbsent(file, detected);
                    return existing == null ? detected : existing;
                }
            }
        } catch (IOException ignored) {
            // Fall through to deterministic mappings.
        }
        String lower = file.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            String detected = "text/html";
            String existing = mimeTypeCache.putIfAbsent(file, detected);
            return existing == null ? detected : existing;
        }
        if (lower.endsWith(".css")) {
            String detected = "text/css";
            String existing = mimeTypeCache.putIfAbsent(file, detected);
            return existing == null ? detected : existing;
        }
        if (lower.endsWith(".js")) {
            String detected = "application/javascript";
            String existing = mimeTypeCache.putIfAbsent(file, detected);
            return existing == null ? detected : existing;
        }
        if (lower.endsWith(".json")) {
            String detected = "application/json";
            String existing = mimeTypeCache.putIfAbsent(file, detected);
            return existing == null ? detected : existing;
        }
        if (lower.endsWith(".png")) {
            String detected = "image/png";
            String existing = mimeTypeCache.putIfAbsent(file, detected);
            return existing == null ? detected : existing;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            String detected = "image/jpeg";
            String existing = mimeTypeCache.putIfAbsent(file, detected);
            return existing == null ? detected : existing;
        }
        if (lower.endsWith(".svg")) {
            String detected = "image/svg+xml";
            String existing = mimeTypeCache.putIfAbsent(file, detected);
            return existing == null ? detected : existing;
        }
        return null;
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        return resources.getResourcePaths(path);
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        if (path == null || !path.startsWith("/")) {
            throw new MalformedURLException("resource path must start with /");
        }
        return resources.getResource(path);
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        try {
            URL resource = getResource(path);
            if (resource == null) {
                return null;
            }
            if ("jar".equals(resource.getProtocol())) {
                return openJarResource(resource);
            }
            URLConnection connection = resource.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException exception) {
            return null;
        }
    }

    private InputStream openJarResource(URL resource) throws IOException {
        JarURLConnection location = (JarURLConnection) resource.openConnection();
        Path archivePath;
        try {
            archivePath = Paths.get(location.getJarFileURL().toURI());
        } catch (URISyntaxException exception) {
            throw new IOException("invalid resource JAR URL: " + resource, exception);
        }
        final JarFile archive = new JarFile(archivePath.toFile());
        JarEntry entry = archive.getJarEntry(location.getEntryName());
        if (entry == null || entry.isDirectory()) {
            archive.close();
            return null;
        }
        final InputStream input;
        try {
            input = archive.getInputStream(entry);
        } catch (IOException failure) {
            archive.close();
            throw failure;
        }
        return new FilterInputStream(input) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    archive.close();
                }
            }
        };
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        return registry.requestDispatcher(path);
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        return registry.namedDispatcher(name);
    }

    @SuppressWarnings("deprecation")
    @Override
    public Servlet getServlet(String name) {
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Enumeration<Servlet> getServlets() {
        return Collections.enumeration(Collections.<Servlet>emptyList());
    }

    @SuppressWarnings("deprecation")
    @Override
    public Enumeration<String> getServletNames() {
        return Collections.enumeration(Collections.<String>emptyList());
    }

    @Override
    public void log(String message) {
        LOGGER.info(message);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void log(Exception exception, String message) {
        log(message, exception);
    }

    @Override
    public void log(String message, Throwable throwable) {
        LOGGER.log(Level.WARNING, message, throwable);
    }

    @Override
    public String getRealPath(String path) {
        Path resource = resolveResource(path);
        return resource != null && Files.exists(resource) ? resource.toString() : null;
    }

    @Override
    public String getServerInfo() {
        return "tinysc/1.0.0-alpha";
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        ensureMutable();
        if (initParameters.containsKey(name)) {
            return false;
        }
        initParameters.put(name, value);
        return true;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            removeAttribute(name);
        } else {
            Object oldValue = attributes.put(name, value);
            if (oldValue == null) {
                registry.fireContextAttributeAdded(name, value);
            } else {
                registry.fireContextAttributeReplaced(name, oldValue);
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        if (oldValue != null) {
            registry.fireContextAttributeRemoved(name, oldValue);
        }
    }

    @Override
    public String getServletContextName() {
        return contextPath.isEmpty() ? "ROOT" : contextPath.substring(1);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String name, String className) {
        ensureMutable();
        return registry.addServlet(name, className);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String name, Servlet servlet) {
        ensureMutable();
        return registry.addServlet(name, servlet);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String name, Class<? extends Servlet> servletClass) {
        ensureMutable();
        return registry.addServlet(name, servletClass);
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> type) throws ServletException {
        return instantiate(type);
    }

    @Override
    public ServletRegistration getServletRegistration(String name) {
        return registry.getServletRegistration(name);
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return registry.getServletRegistrations();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String name, String className) {
        ensureMutable();
        return registry.addFilter(name, className);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String name, Filter filter) {
        ensureMutable();
        return registry.addFilter(name, filter);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String name, Class<? extends Filter> filterClass) {
        ensureMutable();
        return registry.addFilter(name, filterClass);
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> type) throws ServletException {
        return instantiate(type);
    }

    @Override
    public FilterRegistration getFilterRegistration(String name) {
        return registry.getFilterRegistration(name);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return registry.getFilterRegistrations();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return sessionCookieConfig;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> modes) {
        ensureMutable();
        if (!Collections.singleton(SessionTrackingMode.COOKIE).equals(modes)) {
            throw new IllegalArgumentException("tinysc 1.0 alpha supports COOKIE session tracking only");
        }
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return Collections.singleton(SessionTrackingMode.COOKIE);
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return Collections.singleton(SessionTrackingMode.COOKIE);
    }

    @Override
    public void addListener(String className) {
        ensureMutable();
        registry.addListener(className);
    }

    @Override
    public <T extends EventListener> void addListener(T listener) {
        ensureMutable();
        registry.addListener(listener);
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        ensureMutable();
        registry.addListener(listenerClass);
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> type) throws ServletException {
        return instantiate(type);
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public void declareRoles(String... roleNames) {
        ensureMutable();
        for (String role : roleNames) {
            if (role == null || role.trim().isEmpty()) {
                throw new IllegalArgumentException("role must not be empty");
            }
            roles.add(role);
        }
    }

    @Override
    public String getVirtualServerName() {
        return "tinysc";
    }

    void markInitialized() {
        initialized = true;
        sessionCookieConfig.initialized = true;
    }

    Path webRoot() {
        return webRoot;
    }

    JavaxServletRuntime registry() {
        return registry;
    }

    String sessionCookieName() {
        return sessionCookieConfig.name;
    }

    Cookie sessionCookie(String id, boolean requestSecure) {
        Cookie cookie = new Cookie(sessionCookieConfig.name, id);
        if (sessionCookieConfig.domain != null) {
            cookie.setDomain(sessionCookieConfig.domain);
        }
        if (sessionCookieConfig.path != null) {
            cookie.setPath(sessionCookieConfig.path);
        }
        if (sessionCookieConfig.comment != null) {
            cookie.setComment(sessionCookieConfig.comment);
        }
        cookie.setHttpOnly(sessionCookieConfig.httpOnly);
        cookie.setSecure(sessionCookieConfig.secure || requestSecure);
        cookie.setMaxAge(sessionCookieConfig.maxAge);
        return cookie;
    }

    @Override
    public void close() throws IOException {
        if (!Files.exists(tempDirectory)) {
            return;
        }
        Files.walkFileTree(tempDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path resolveResource(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        Path resolved = webRoot.resolve(path.substring(1)).normalize();
        return resolved.startsWith(webRoot) ? resolved : null;
    }

    private void ensureMutable() {
        if (initialized) {
            throw new IllegalStateException("ServletContext has already been initialized");
        }
    }

    private static <T> T instantiate(Class<T> type) throws ServletException {
        try {
            return type.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new ServletException("cannot instantiate " + type.getName(), exception);
        }
    }

    private final class TinySessionCookieConfig implements SessionCookieConfig {
        private String name = "JSESSIONID";
        private String domain;
        private String path = contextPath.isEmpty() ? "/" : contextPath;
        private String comment;
        private boolean httpOnly = true;
        private boolean secure;
        private int maxAge = -1;
        private boolean initialized;

        @Override
        public void setName(String value) {
            mutable();
            name = value;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setDomain(String value) {
            mutable();
            domain = value;
        }

        @Override
        public String getDomain() {
            return domain;
        }

        @Override
        public void setPath(String value) {
            mutable();
            path = value;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public void setComment(String value) {
            mutable();
            comment = value;
        }

        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public void setHttpOnly(boolean value) {
            mutable();
            httpOnly = value;
        }

        @Override
        public boolean isHttpOnly() {
            return httpOnly;
        }

        @Override
        public void setSecure(boolean value) {
            mutable();
            secure = value;
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public void setMaxAge(int value) {
            mutable();
            maxAge = value;
        }

        @Override
        public int getMaxAge() {
            return maxAge;
        }

        private void mutable() {
            if (initialized) {
                throw new IllegalStateException("session cookie config is already initialized");
            }
        }
    }
}
