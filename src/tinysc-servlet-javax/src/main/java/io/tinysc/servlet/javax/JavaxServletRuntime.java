package io.tinysc.servlet.javax;

import io.tinysc.deployment.PreparedWebApp;
import io.tinysc.deployment.model.WebAppDescriptor;
import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.LifecycleState;
import io.tinysc.kernel.NamedThreadFactory;
import io.tinysc.kernel.ServerConfig;
import io.tinysc.kernel.WebAppRuntime;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterRegistration;
import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.ServletSecurityElement;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class JavaxServletRuntime implements WebAppRuntime {
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final ThreadLocal<byte[]> COPY_BUFFER = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[8192];
        }
    };
    private static final ServletRequestListener[] EMPTY_REQUEST_LISTENERS =
            new ServletRequestListener[0];
    private static final ServletRequestAttributeListener[] EMPTY_REQUEST_ATTRIBUTE_LISTENERS =
            new ServletRequestAttributeListener[0];
    private static final CompiledFilter[] EMPTY_COMPILED_FILTERS = new CompiledFilter[0];
    private static final CompiledFilterMapping[] EMPTY_COMPILED_FILTER_MAPPINGS =
            new CompiledFilterMapping[0];
    private static final FilterSelection EMPTY_FILTER_SELECTION =
            new FilterSelection(EMPTY_COMPILED_FILTERS, true);

    private final PreparedWebApp application;
    private final String contextPath;
    private final WebAppDescriptor descriptor;
    private volatile ServletMapper mapper;
    private final Object lifecycleMonitor = new Object();
    private final Map<String, ServletHolder> servlets =
            new LinkedHashMap<String, ServletHolder>();
    private final Map<String, FilterHolder> filters =
            new LinkedHashMap<String, FilterHolder>();
    private final List<EventListener> listeners = new ArrayList<EventListener>();
    private final List<ServletContextListener> initializedContextListeners =
            new ArrayList<ServletContextListener>();
    private final List<Object> listenerDefinitions = new ArrayList<Object>();
    private final List<WebAppDescriptor.ServletMapping> servletMappings =
            new ArrayList<WebAppDescriptor.ServletMapping>();
    private final List<WebAppDescriptor.FilterMapping> filterMappings =
            new ArrayList<WebAppDescriptor.FilterMapping>();
    private final Servlet staticResourceServlet = new StaticResourceServlet();
    private volatile LifecycleState state = LifecycleState.NEW;
    private volatile ServletRequestListener[] requestListeners = EMPTY_REQUEST_LISTENERS;
    private volatile ServletRequestAttributeListener[] requestAttributeListeners =
            EMPTY_REQUEST_ATTRIBUTE_LISTENERS;
    private volatile CompiledFilterMapping[] compiledFilterMappings =
            EMPTY_COMPILED_FILTER_MAPPINGS;
    private TinyServletContext servletContext;
    private TinySessionManager sessionManager;
    private ScheduledExecutorService asyncScheduler;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private boolean componentsDestroyed;
    private volatile boolean waitingForRequests;
    private int beforeFilterMappingInsertPoint;

    public JavaxServletRuntime(PreparedWebApp application, String contextPath) {
        if (application == null) {
            throw new IllegalArgumentException("prepared web application is required");
        }
        this.application = application;
        this.contextPath = ServerConfig.normalizeContextPath(contextPath);
        descriptor = application.descriptor();
        servletMappings.addAll(descriptor.servletMappings());
        filterMappings.addAll(descriptor.filterMappings());
        mapper = new ServletMapper(servletMappings);
        listenerDefinitions.addAll(descriptor.listenerClasses());
        for (WebAppDescriptor.ServletDefinition definition : descriptor.servlets().values()) {
            servlets.put(definition.name(), new ServletHolder(definition));
        }
        for (WebAppDescriptor.FilterDefinition definition : descriptor.filters().values()) {
            filters.put(definition.name(), new FilterHolder(definition));
        }
    }

    @Override
    public void start() throws Exception {
        synchronized (lifecycleMonitor) {
            if (state != LifecycleState.NEW) {
                throw new IllegalStateException("runtime cannot start from state " + state);
            }
            state = LifecycleState.STARTING;
        }
        ClassLoader previous = enterWebAppClassLoader();
        try {
            asyncScheduler = Executors.newSingleThreadScheduledExecutor(
                    new NamedThreadFactory("tinysc-async-timeout-", true));
            servletContext = new TinyServletContext(contextPath, application.webRoot(),
                    application.classLoader(), application.resources(),
                    descriptor.contextParams(), this);
            sessionManager = new TinySessionManager(
                    servletContext, descriptor.sessionTimeoutMinutes(), this);
            initializeServletContainerInitializers();
            mapper = new ServletMapper(servletMappings);
            initializeListeners();
            initializeFilters();
            compiledFilterMappings = compileFilterMappings();
            initializeEagerServlets();
            servletContext.markInitialized();
            state = LifecycleState.RUNNING;
        } catch (Exception failure) {
            state = LifecycleState.FAILED;
            destroyComponents();
            throw failure;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Override
    public void service(ContainerExchange exchange) throws Exception {
        beginRequest();
        ClassLoader previous = enterWebAppClassLoader();
        TinyHttpServletRequest request = null;
        TinyHttpServletResponse response = new TinyHttpServletResponse(exchange.response());
        try {
            String requestPath = pathWithinContext(exchange.request().path());
            if (requestPath == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            ServletMappingResult mapping = mapper.map(requestPath);
            FilterSelection selection = matchingFilters(
                    mapping == null ? "" : mapping.servletName(),
                    requestPath, DispatcherType.REQUEST);
            ServletHolder mappedServlet = mapping == null ? null : servlets.get(mapping.servletName());
            if (mapping != null && mappedServlet == null) {
                throw new ServletException("mapping references unknown servlet: "
                        + mapping.servletName());
            }
            boolean asyncSupported = mappedServlet != null && mappedServlet.asyncSupported
                    && selection.asyncSupported;
            request = new TinyHttpServletRequest(exchange, response, servletContext,
                    sessionManager, mapping, requestPath,
                    mappedServlet == null ? null : mappedServlet.multipartConfig,
                    asyncSupported, asyncScheduler);
            fireRequestInitialized(request);
            Servlet servlet = mapping == null ? staticResourceServlet : mappedServlet.get();
            new ApplicationFilterChain(selection.filters, servlet).doFilter(request, response);
        } catch (Exception failure) {
            if (request != null && request.asyncContextInternal() != null
                    && !request.asyncContextInternal().isTerminal()) {
                request.asyncContextInternal().fail(failure);
            }
            throw failure;
        } finally {
            try {
                if (request != null) {
                    TinyAsyncContext asyncContext = request.asyncContextInternal();
                    if (asyncContext == null) {
                        finishRequest(request, response);
                    } else {
                        final TinyHttpServletRequest asyncRequest = request;
                        final TinyHttpServletResponse asyncResponse = response;
                        asyncContext.setCompletionAction(new Runnable() {
                            @Override
                            public void run() {
                                ClassLoader asyncPrevious = enterWebAppClassLoader();
                                try {
                                    finishRequest(asyncRequest, asyncResponse);
                                } finally {
                                    Thread.currentThread().setContextClassLoader(asyncPrevious);
                                }
                            }
                        });
                    }
                }
            } finally {
                if (request == null) {
                    response.finish();
                    endRequest();
                }
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Override
    public void stop(Duration gracePeriod) throws Exception {
        long graceMillis = gracePeriod == null ? 0L : Math.max(0L, gracePeriod.toMillis());
        long deadline = System.currentTimeMillis() + graceMillis;
        synchronized (lifecycleMonitor) {
            if (state == LifecycleState.STOPPED || state == LifecycleState.NEW) {
                state = LifecycleState.STOPPED;
                return;
            }
            if (state != LifecycleState.RUNNING && state != LifecycleState.FAILED) {
                throw new IllegalStateException("runtime cannot stop from state " + state);
            }
            state = LifecycleState.QUIESCING;
            while (activeRequests.get() > 0 && System.currentTimeMillis() < deadline) {
                waitingForRequests = true;
                if (activeRequests.get() == 0) {
                    waitingForRequests = false;
                    break;
                }
                try {
                    lifecycleMonitor.wait(Math.max(1L, deadline - System.currentTimeMillis()));
                } finally {
                    waitingForRequests = false;
                }
            }
            state = LifecycleState.STOPPING;
        }
        ClassLoader previous = enterWebAppClassLoader();
        try {
            destroyComponents();
            state = LifecycleState.STOPPED;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public LifecycleState state() {
        return state;
    }

    private void beginRequest() {
        LifecycleState current = state;
        if (current != LifecycleState.RUNNING) {
            throw new IllegalStateException("runtime is not accepting requests: " + current);
        }
        activeRequests.incrementAndGet();
        current = state;
        if (current != LifecycleState.RUNNING) {
            if (activeRequests.decrementAndGet() == 0) {
                signalRequestsDrained();
            }
            throw new IllegalStateException("runtime is not accepting requests: " + current);
        }
    }

    private void endRequest() {
        if (activeRequests.decrementAndGet() == 0) {
            signalRequestsDrained();
        }
    }

    private String pathWithinContext(String path) {
        if (contextPath.isEmpty()) {
            return path;
        }
        if (path.equals(contextPath)) {
            return "/";
        }
        return path.startsWith(contextPath + "/") ? path.substring(contextPath.length()) : null;
    }

    private void initializeServletContainerInitializers() throws Exception {
        ServletInitializerScanner scanner = new ServletInitializerScanner(
                application.webRoot(), application.classLoader());
        for (ServletInitializerScanner.Match match : scanner.scan()) {
            Set<Class<?>> classes = match.handledTypes();
            match.initializer().onStartup(classes.isEmpty() ? null : classes, servletContext);
        }
    }

    TinyRequestDispatcher requestDispatcher(String path) {
        return new TinyRequestDispatcher(this, path, null);
    }

    TinyRequestDispatcher namedDispatcher(String servletName) {
        return servlets.containsKey(servletName)
                ? new TinyRequestDispatcher(this, null, servletName) : null;
    }

    void dispatch(TinyHttpServletRequest originalRequest, ServletRequest request,
                  ServletResponse response, String targetPath, String servletName,
                  DispatcherType dispatcherType) throws ServletException, IOException {
        String path = targetPath;
        String query = null;
        ServletMappingResult mapping;
        if (servletName == null) {
            int queryStart = path.indexOf('?');
            if (queryStart >= 0) {
                query = path.substring(queryStart + 1);
                path = path.substring(0, queryStart);
            }
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("dispatcher path must start with /");
            }
            mapping = mapper.map(path);
        } else {
            path = originalRequest.getServletPath();
            query = originalRequest.getQueryString();
            mapping = new ServletMappingResult(servletName, "", path,
                    originalRequest.getPathInfo());
        }
        TinyHttpServletRequest.DispatchState previous =
                originalRequest.pushDispatch(path, query, mapping, dispatcherType);
        try {
            if (mapping == null) {
                FilterSelection selection = matchingFilters("", path, dispatcherType);
                new ApplicationFilterChain(selection.filters, staticResourceServlet)
                        .doFilter(request, response);
                return;
            }
            ServletHolder servlet = servlets.get(mapping.servletName());
            if (servlet == null) {
                throw new ServletException("dispatcher references unknown servlet: "
                        + mapping.servletName());
            }
            FilterSelection selection = matchingFilters(
                    mapping.servletName(), path, dispatcherType);
            new ApplicationFilterChain(selection.filters, servlet.get())
                    .doFilter(request, response);
        } finally {
            originalRequest.popDispatch(previous);
        }
    }

    ServletRegistration.Dynamic addServlet(String name, String className) {
        validateRegistration(name, className);
        if (servlets.containsKey(name)) {
            return null;
        }
        ServletHolder holder = new ServletHolder(name, className);
        servlets.put(name, holder);
        return new ServletDynamicRegistration(holder);
    }

    ServletRegistration.Dynamic addServlet(String name, Servlet servlet) {
        validateRegistration(name, servlet);
        if (servlets.containsKey(name)) {
            return null;
        }
        ServletHolder holder = new ServletHolder(name, servlet);
        servlets.put(name, holder);
        return new ServletDynamicRegistration(holder);
    }

    ServletRegistration.Dynamic addServlet(String name, Class<? extends Servlet> servletClass) {
        validateRegistration(name, servletClass);
        if (servlets.containsKey(name)) {
            return null;
        }
        ServletHolder holder = new ServletHolder(name, servletClass);
        servlets.put(name, holder);
        return new ServletDynamicRegistration(holder);
    }

    ServletRegistration getServletRegistration(String name) {
        ServletHolder holder = servlets.get(name);
        return holder == null ? null : new ServletDynamicRegistration(holder);
    }

    Map<String, ? extends ServletRegistration> getServletRegistrations() {
        Map<String, ServletRegistration> result = new LinkedHashMap<String, ServletRegistration>();
        for (ServletHolder holder : servlets.values()) {
            result.put(holder.name, new ServletDynamicRegistration(holder));
        }
        return Collections.unmodifiableMap(result);
    }

    FilterRegistration.Dynamic addFilter(String name, String className) {
        validateRegistration(name, className);
        if (filters.containsKey(name)) {
            return null;
        }
        FilterHolder holder = new FilterHolder(name, className);
        filters.put(name, holder);
        return new FilterDynamicRegistration(holder);
    }

    FilterRegistration.Dynamic addFilter(String name, Filter filter) {
        validateRegistration(name, filter);
        if (filters.containsKey(name)) {
            return null;
        }
        FilterHolder holder = new FilterHolder(name, filter);
        filters.put(name, holder);
        return new FilterDynamicRegistration(holder);
    }

    FilterRegistration.Dynamic addFilter(String name, Class<? extends Filter> filterClass) {
        validateRegistration(name, filterClass);
        if (filters.containsKey(name)) {
            return null;
        }
        FilterHolder holder = new FilterHolder(name, filterClass);
        filters.put(name, holder);
        return new FilterDynamicRegistration(holder);
    }

    FilterRegistration getFilterRegistration(String name) {
        FilterHolder holder = filters.get(name);
        return holder == null ? null : new FilterDynamicRegistration(holder);
    }

    Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        Map<String, FilterRegistration> result = new LinkedHashMap<String, FilterRegistration>();
        for (FilterHolder holder : filters.values()) {
            result.put(holder.name, new FilterDynamicRegistration(holder));
        }
        return Collections.unmodifiableMap(result);
    }

    void addListener(Object listener) {
        if (!(listener instanceof String) && !(listener instanceof Class<?>)
                && !(listener instanceof EventListener)) {
            throw new IllegalArgumentException("listener must implement java.util.EventListener");
        }
        listenerDefinitions.add(listener);
    }

    List<EventListener> listeners() {
        return listeners;
    }

    private static void validateRegistration(String name, Object value) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("registration name must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("registration value must not be null");
        }
    }

    private void initializeListeners() throws Exception {
        for (Object definition : listenerDefinitions) {
            Object value;
            if (definition instanceof String) {
                Class<?> type = application.classLoader().loadClass((String) definition);
                value = type.newInstance();
            } else if (definition instanceof Class<?>) {
                value = ((Class<?>) definition).newInstance();
            } else {
                value = definition;
            }
            if (!(value instanceof EventListener)) {
                throw new ServletException(value.getClass().getName()
                        + " is not a java.util.EventListener");
            }
            EventListener listener = (EventListener) value;
            listeners.add(listener);
        }
        ServletContextEvent event = new ServletContextEvent(servletContext);
        for (EventListener listener : listeners) {
            if (listener instanceof ServletContextListener) {
                ServletContextListener contextListener = (ServletContextListener) listener;
                contextListener.contextInitialized(event);
                initializedContextListeners.add(contextListener);
            }
        }
        requestListeners = requestListeners(listeners);
        requestAttributeListeners = requestAttributeListeners(listeners);
    }

    void fireContextAttributeAdded(String name, Object value) {
        ServletContextAttributeEvent event =
                new ServletContextAttributeEvent(servletContext, name, value);
        for (EventListener listener : listeners) {
            if (listener instanceof ServletContextAttributeListener) {
                ((ServletContextAttributeListener) listener).attributeAdded(event);
            }
        }
    }

    void fireContextAttributeRemoved(String name, Object value) {
        ServletContextAttributeEvent event =
                new ServletContextAttributeEvent(servletContext, name, value);
        for (EventListener listener : listeners) {
            if (listener instanceof ServletContextAttributeListener) {
                ((ServletContextAttributeListener) listener).attributeRemoved(event);
            }
        }
    }

    void fireContextAttributeReplaced(String name, Object oldValue) {
        ServletContextAttributeEvent event =
                new ServletContextAttributeEvent(servletContext, name, oldValue);
        for (EventListener listener : listeners) {
            if (listener instanceof ServletContextAttributeListener) {
                ((ServletContextAttributeListener) listener).attributeReplaced(event);
            }
        }
    }

    void fireRequestAttributeAdded(ServletRequest request, String name, Object value) {
        ServletRequestAttributeListener[] snapshot = requestAttributeListeners;
        if (snapshot.length == 0) {
            return;
        }
        ServletRequestAttributeEvent event =
                new ServletRequestAttributeEvent(servletContext, request, name, value);
        for (ServletRequestAttributeListener listener : snapshot) {
            listener.attributeAdded(event);
        }
    }

    void fireRequestAttributeRemoved(ServletRequest request, String name, Object value) {
        ServletRequestAttributeListener[] snapshot = requestAttributeListeners;
        if (snapshot.length == 0) {
            return;
        }
        ServletRequestAttributeEvent event =
                new ServletRequestAttributeEvent(servletContext, request, name, value);
        for (ServletRequestAttributeListener listener : snapshot) {
            listener.attributeRemoved(event);
        }
    }

    void fireRequestAttributeReplaced(ServletRequest request, String name, Object oldValue) {
        ServletRequestAttributeListener[] snapshot = requestAttributeListeners;
        if (snapshot.length == 0) {
            return;
        }
        ServletRequestAttributeEvent event =
                new ServletRequestAttributeEvent(servletContext, request, name, oldValue);
        for (ServletRequestAttributeListener listener : snapshot) {
            listener.attributeReplaced(event);
        }
    }

    void fireSessionCreated(TinyHttpSession session) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        for (EventListener listener : listeners) {
            if (listener instanceof HttpSessionListener) {
                ((HttpSessionListener) listener).sessionCreated(event);
            }
        }
    }

    void fireSessionDestroyed(TinyHttpSession session) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        List<EventListener> reversed = new ArrayList<EventListener>(listeners);
        Collections.reverse(reversed);
        for (EventListener listener : reversed) {
            if (listener instanceof HttpSessionListener) {
                ((HttpSessionListener) listener).sessionDestroyed(event);
            }
        }
    }

    void fireSessionAttributeAdded(TinyHttpSession session, String name, Object value) {
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(session, name, value);
        for (EventListener listener : listeners) {
            if (listener instanceof HttpSessionAttributeListener) {
                ((HttpSessionAttributeListener) listener).attributeAdded(event);
            }
        }
    }

    void fireSessionAttributeRemoved(TinyHttpSession session, String name, Object value) {
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(session, name, value);
        for (EventListener listener : listeners) {
            if (listener instanceof HttpSessionAttributeListener) {
                ((HttpSessionAttributeListener) listener).attributeRemoved(event);
            }
        }
    }

    void fireSessionAttributeReplaced(TinyHttpSession session, String name, Object oldValue) {
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(session, name, oldValue);
        for (EventListener listener : listeners) {
            if (listener instanceof HttpSessionAttributeListener) {
                ((HttpSessionAttributeListener) listener).attributeReplaced(event);
            }
        }
    }

    void fireSessionIdChanged(TinyHttpSession session, String oldId) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        for (EventListener listener : listeners) {
            if (listener instanceof HttpSessionIdListener) {
                ((HttpSessionIdListener) listener).sessionIdChanged(event, oldId);
            }
        }
    }

    private void fireRequestInitialized(ServletRequest request) {
        ServletRequestListener[] snapshot = requestListeners;
        if (snapshot.length == 0) {
            return;
        }
        ServletRequestEvent event = new ServletRequestEvent(servletContext, request);
        for (ServletRequestListener listener : snapshot) {
            listener.requestInitialized(event);
        }
    }

    private void fireRequestDestroyed(ServletRequest request) {
        ServletRequestListener[] snapshot = requestListeners;
        if (snapshot.length == 0) {
            return;
        }
        ServletRequestEvent event = new ServletRequestEvent(servletContext, request);
        for (int index = snapshot.length - 1; index >= 0; index--) {
            snapshot[index].requestDestroyed(event);
        }
    }

    private void initializeFilters() throws Exception {
        for (FilterHolder holder : filters.values()) {
            holder.initialize();
        }
    }

    private void initializeEagerServlets() throws Exception {
        List<ServletHolder> eager = new ArrayList<ServletHolder>();
        for (ServletHolder holder : servlets.values()) {
            Integer order = holder.loadOnStartup;
            if (order != null && order >= 0) {
                eager.add(holder);
            }
        }
        Collections.sort(eager, new Comparator<ServletHolder>() {
            @Override
            public int compare(ServletHolder left, ServletHolder right) {
                return left.loadOnStartup.compareTo(right.loadOnStartup);
            }
        });
        for (ServletHolder holder : eager) {
            holder.get();
        }
    }

    private FilterSelection matchingFilters(String servletName, String path,
                                            DispatcherType dispatcherType) {
        CompiledFilterMapping[] mappings = compiledFilterMappings;
        if (mappings.length == 0) {
            return EMPTY_FILTER_SELECTION;
        }
        FilterSelectionBuilder builder = new FilterSelectionBuilder(mappings.length);
        for (CompiledFilterMapping mapping : mappings) {
            if (mapping.matchesDispatcher(dispatcherType) && mapping.matchesUrlPattern(path)) {
                builder.add(mapping.filter);
            }
        }
        for (CompiledFilterMapping mapping : mappings) {
            if (mapping.matchesDispatcher(dispatcherType) && mapping.matchesServletName(servletName)) {
                builder.add(mapping.filter);
            }
        }
        return builder.build();
    }

    private static final class FilterSelectionBuilder {
        private final CompiledFilter[] selected;
        private int selectedCount;
        private boolean asyncSupported = true;

        private FilterSelectionBuilder(int capacity) {
            selected = new CompiledFilter[capacity];
        }

        private void add(CompiledFilter filter) {
            for (int index = 0; index < selectedCount; index++) {
                if (selected[index] == filter) {
                    return;
                }
            }
            selected[selectedCount++] = filter;
            asyncSupported &= filter.asyncSupported;
        }

        private FilterSelection build() {
            if (selectedCount == 0) {
                return EMPTY_FILTER_SELECTION;
            }
            CompiledFilter[] result = selectedCount == selected.length ? selected
                    : java.util.Arrays.copyOf(selected, selectedCount);
            return new FilterSelection(result, asyncSupported);
        }
    }

    private CompiledFilterMapping[] compileFilterMappings() {
        if (filterMappings.isEmpty()) {
            return EMPTY_COMPILED_FILTER_MAPPINGS;
        }
        Map<String, CompiledFilter> compiledFilters =
                new LinkedHashMap<String, CompiledFilter>(filters.size());
        List<CompiledFilterMapping> result = new ArrayList<CompiledFilterMapping>(
                filterMappings.size());
        for (WebAppDescriptor.FilterMapping mapping : filterMappings) {
            FilterHolder holder = filters.get(mapping.filterName());
            if (holder == null || holder.filter == null) {
                continue;
            }
            CompiledFilter compiled = compiledFilters.get(holder.name);
            if (compiled == null) {
                compiled = new CompiledFilter(holder.filter, holder.asyncSupported);
                compiledFilters.put(holder.name, compiled);
            }
            result.add(new CompiledFilterMapping(compiled, mapping.servletNames(),
                    mapping.urlPatterns(), mapping.dispatcherTypes()));
        }
        return result.isEmpty() ? EMPTY_COMPILED_FILTER_MAPPINGS
                : result.toArray(new CompiledFilterMapping[result.size()]);
    }

    private void finishRequest(TinyHttpServletRequest request,
                               TinyHttpServletResponse response) {
        try {
            try {
                fireRequestDestroyed(request);
            } finally {
                request.endRequest();
            }
        } finally {
            try {
                response.finish();
            } finally {
                endRequest();
            }
        }
    }

    private void serveStatic(String requestPath, HttpServletRequest request,
                             HttpServletResponse response)
            throws IOException {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
            response.setHeader("Allow", "GET, HEAD, POST");
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        String upper = requestPath.toUpperCase(Locale.ROOT);
        if (upper.equals("/WEB-INF") || upper.startsWith("/WEB-INF/")
                || upper.equals("/META-INF") || upper.startsWith("/META-INF/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        URL resourceUrl = staticResource(requestPath);
        if (resourceUrl == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String mimeType = servletContext.getMimeType(resourceUrl.getPath());
        if (mimeType != null) {
            response.setContentType(mimeType);
        }
        if ("jar".equals(resourceUrl.getProtocol())) {
            serveJarResource(resourceUrl, request, response);
            return;
        }
        serveFileResource(resourceUrl, request, response);
    }

    private void serveJarResource(URL resourceUrl, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        JarURLConnection location = (JarURLConnection) resourceUrl.openConnection();
        Path archivePath;
        try {
            archivePath = Paths.get(location.getJarFileURL().toURI());
        } catch (URISyntaxException exception) {
            throw new IOException("invalid resource JAR URL: " + resourceUrl, exception);
        }
        try (JarFile archive = new JarFile(archivePath.toFile())) {
            JarEntry entry = archive.getJarEntry(location.getEntryName());
            if (entry == null || entry.isDirectory()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            long modified = entry.getTime();
            if (modified > 0L) {
                response.setHeader("Last-Modified", HTTP_DATE.format(
                        java.time.Instant.ofEpochMilli(modified)
                                .atZone(ZoneOffset.UTC)));
                if (isNotModified(request, modified)) {
                    response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                    return;
                }
            }
            if (entry.getSize() >= 0L) {
                response.setContentLengthLong(entry.getSize());
            }
            if ("HEAD".equals(request.getMethod())) {
                return;
            }
            try (InputStream input = archive.getInputStream(entry)) {
                copy(input, response);
            }
        }
    }

    private void serveFileResource(URL resourceUrl, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        Path file;
        try {
            file = Paths.get(resourceUrl.toURI());
        } catch (URISyntaxException exception) {
            throw new IOException("invalid resource file URL: " + resourceUrl, exception);
        }
        FileTime modifiedTime = Files.getLastModifiedTime(file);
        long modified = modifiedTime.toMillis();
        if (modified > 0L) {
            response.setHeader("Last-Modified", HTTP_DATE.format(
                    java.time.Instant.ofEpochMilli(modified).atZone(ZoneOffset.UTC)));
            if (isNotModified(request, modified)) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }
        }
        long size = Files.size(file);
        if (size >= 0L) {
            response.setContentLengthLong(size);
        }
        if ("HEAD".equals(request.getMethod())) {
            return;
        }
        try (InputStream input = Files.newInputStream(file)) {
            copy(input, response);
        }
    }

    private static void copy(InputStream input, HttpServletResponse response)
            throws IOException {
        byte[] buffer = COPY_BUFFER.get();
        int read;
        while ((read = input.read(buffer)) >= 0) {
            response.getOutputStream().write(buffer, 0, read);
        }
    }

    private static boolean isNotModified(HttpServletRequest request, long modified) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        long ifModifiedSince;
        try {
            ifModifiedSince = request.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException invalidDate) {
            return false;
        }
        return ifModifiedSince >= 0L
                && modified / 1000L <= ifModifiedSince / 1000L;
    }

    private URL staticResource(String requestPath) throws IOException {
        Path root = servletContext.webRoot();
        Path file = root.resolve(requestPath.substring(1)).normalize();
        if (!file.startsWith(root)) {
            return null;
        }
        URL exact = servletContext.getResource(requestPath);
        boolean directory = Files.isDirectory(file) || requestPath.endsWith("/")
                || (exact != null && exact.toExternalForm().endsWith("/"));
        if (!directory && exact == null) {
            directory = servletContext.getResourcePaths(requestPath + "/") != null;
        }
        if (!directory) {
            return exact;
        }
        String base = requestPath.endsWith("/") ? requestPath : requestPath + "/";
        List<String> welcomeFiles = descriptor.welcomeFiles().isEmpty()
                ? java.util.Arrays.asList("index.html", "index.htm") : descriptor.welcomeFiles();
        for (String welcome : welcomeFiles) {
            URL candidate = servletContext.getResource(base + welcome);
            if (candidate != null && !candidate.toExternalForm().endsWith("/")) {
                return candidate;
            }
        }
        return null;
    }

    private void destroyComponents() {
        if (componentsDestroyed) {
            return;
        }
        componentsDestroyed = true;
        List<ServletHolder> servletValues = new ArrayList<ServletHolder>(servlets.values());
        Collections.reverse(servletValues);
        for (ServletHolder holder : servletValues) {
            holder.destroy();
        }
        List<FilterHolder> filterValues = new ArrayList<FilterHolder>(filters.values());
        Collections.reverse(filterValues);
        for (FilterHolder holder : filterValues) {
            holder.destroy();
        }
        if (servletContext != null) {
            ServletContextEvent event = new ServletContextEvent(servletContext);
            List<ServletContextListener> reversed =
                    new ArrayList<ServletContextListener>(initializedContextListeners);
            Collections.reverse(reversed);
            for (ServletContextListener listener : reversed) {
                try {
                    listener.contextDestroyed(event);
                } catch (RuntimeException failure) {
                    servletContext.log("ServletContextListener destroy failed", failure);
                }
            }
        }
        initializedContextListeners.clear();
        listeners.clear();
        if (sessionManager != null) {
            sessionManager.clear();
        }
        if (asyncScheduler != null) {
            asyncScheduler.shutdownNow();
            asyncScheduler = null;
        }
        if (servletContext != null) {
            try {
                servletContext.close();
            } catch (IOException failure) {
                servletContext.log("Cannot remove web application temp directory", failure);
            }
        }
    }

    private void signalRequestsDrained() {
        if (!waitingForRequests) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (waitingForRequests && activeRequests.get() == 0) {
                lifecycleMonitor.notifyAll();
            }
        }
    }

    private static ServletRequestListener[] requestListeners(List<EventListener> listeners) {
        List<ServletRequestListener> result = new ArrayList<ServletRequestListener>();
        for (EventListener listener : listeners) {
            if (listener instanceof ServletRequestListener) {
                result.add((ServletRequestListener) listener);
            }
        }
        return result.isEmpty() ? EMPTY_REQUEST_LISTENERS
                : result.toArray(new ServletRequestListener[result.size()]);
    }

    private static ServletRequestAttributeListener[] requestAttributeListeners(
            List<EventListener> listeners) {
        List<ServletRequestAttributeListener> result =
                new ArrayList<ServletRequestAttributeListener>();
        for (EventListener listener : listeners) {
            if (listener instanceof ServletRequestAttributeListener) {
                result.add((ServletRequestAttributeListener) listener);
            }
        }
        return result.isEmpty() ? EMPTY_REQUEST_ATTRIBUTE_LISTENERS
                : result.toArray(new ServletRequestAttributeListener[result.size()]);
    }

    private ClassLoader enterWebAppClassLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(application.classLoader());
        return previous;
    }

    private final class ServletHolder {
        private String jspFile;
        private final String name;
        private String className;
        private Class<? extends Servlet> servletClass;
        private volatile Servlet servlet;
        private final Map<String, String> initParameters = new LinkedHashMap<String, String>();
        private Integer loadOnStartup;
        private boolean asyncSupported;
        private boolean initialized;
        private String runAsRole;
        private MultipartConfigElement multipartConfig;

        private ServletHolder(WebAppDescriptor.ServletDefinition definition) {
            name = definition.name();
            className = definition.className();
            jspFile = definition.jspFile();
            initParameters.putAll(definition.initParams());
            loadOnStartup = definition.loadOnStartup();
            asyncSupported = definition.asyncSupported();
            WebAppDescriptor.MultipartConfigDefinition configured =
                    definition.multipartConfig();
            if (configured != null) {
                multipartConfig = new MultipartConfigElement(
                        configured.location(), configured.maxFileSize(),
                        configured.maxRequestSize(), configured.fileSizeThreshold());
            }
        }

        private ServletHolder(String name, String className) {
            this.name = name;
            this.className = className;
        }

        private ServletHolder(String name, Class<? extends Servlet> servletClass) {
            this.name = name;
            this.className = servletClass.getName();
            this.servletClass = servletClass;
        }

        private ServletHolder(String name, Servlet servlet) {
            this.name = name;
            this.className = servlet.getClass().getName();
            this.servlet = servlet;
        }

        private synchronized Servlet get() throws ServletException {
            if (initialized) {
                return servlet;
            }
            if (jspFile != null) {
                throw new ServletException("JSP is not supported by tinysc 1.0 alpha: " + jspFile);
            }
            try {
                if (servlet == null) {
                    Class<?> type = servletClass != null ? servletClass
                            : application.classLoader().loadClass(className);
                    Object value = type.newInstance();
                    if (!(value instanceof Servlet)) {
                        throw new ServletException(className + " is not a Servlet");
                    }
                    servlet = (Servlet) value;
                }
                servlet.init(new TinyServletConfig(name, servletContext, initParameters));
                initialized = true;
                return servlet;
            } catch (ServletException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ServletException("cannot initialize servlet " + name, exception);
            }
        }

        private synchronized void destroy() {
            if (initialized && servlet != null) {
                try {
                    servlet.destroy();
                } catch (RuntimeException failure) {
                    servletContext.log("Servlet destroy failed: " + name, failure);
                } finally {
                    servlet = null;
                    initialized = false;
                }
            }
        }
    }

    private abstract class AbstractRegistration {
        abstract String name();

        abstract String className();

        abstract Map<String, String> initParameters();

        public String getName() {
            return name();
        }

        public String getClassName() {
            return className();
        }

        public boolean setInitParameter(String name, String value) {
            if (name == null || value == null) {
                throw new IllegalArgumentException("init parameter name and value are required");
            }
            if (initParameters().containsKey(name)) {
                return false;
            }
            initParameters().put(name, value);
            return true;
        }

        public String getInitParameter(String name) {
            return initParameters().get(name);
        }

        public Set<String> setInitParameters(Map<String, String> parameters) {
            Set<String> conflicts = new LinkedHashSet<String>();
            for (String name : parameters.keySet()) {
                if (initParameters().containsKey(name)) {
                    conflicts.add(name);
                }
            }
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (!conflicts.contains(entry.getKey())) {
                    setInitParameter(entry.getKey(), entry.getValue());
                }
            }
            return conflicts;
        }

        public Map<String, String> getInitParameters() {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(initParameters()));
        }
    }

    private final class ServletDynamicRegistration extends AbstractRegistration
            implements ServletRegistration.Dynamic {
        private final ServletHolder holder;

        private ServletDynamicRegistration(ServletHolder holder) {
            this.holder = holder;
        }

        @Override
        String name() {
            return holder.name;
        }

        @Override
        String className() {
            return holder.className;
        }

        @Override
        Map<String, String> initParameters() {
            return holder.initParameters;
        }

        @Override
        public void setAsyncSupported(boolean value) {
            holder.asyncSupported = value;
        }

        @Override
        public Set<String> addMapping(String... patterns) {
            if (patterns == null || patterns.length == 0) {
                throw new IllegalArgumentException("at least one servlet mapping is required");
            }
            Set<String> conflicts = new LinkedHashSet<String>();
            for (String pattern : patterns) {
                for (WebAppDescriptor.ServletMapping existing : servletMappings) {
                    if (!existing.servletName().equals(holder.name)
                            && existing.urlPatterns().contains(pattern)) {
                        conflicts.add(pattern);
                    }
                }
            }
            if (conflicts.isEmpty()) {
                servletMappings.add(new WebAppDescriptor.ServletMapping(
                        holder.name, java.util.Arrays.asList(patterns)));
            }
            return conflicts;
        }

        @Override
        public Collection<String> getMappings() {
            List<String> result = new ArrayList<String>();
            for (WebAppDescriptor.ServletMapping mapping : servletMappings) {
                if (mapping.servletName().equals(holder.name)) {
                    result.addAll(mapping.urlPatterns());
                }
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public String getRunAsRole() {
            return holder.runAsRole;
        }

        @Override
        public void setLoadOnStartup(int value) {
            holder.loadOnStartup = value;
        }

        @Override
        public Set<String> setServletSecurity(ServletSecurityElement constraint) {
            throw new UnsupportedOperationException(
                    "programmatic servlet security is not supported yet");
        }

        @Override
        public void setMultipartConfig(MultipartConfigElement config) {
            if (config == null) {
                throw new IllegalArgumentException("multipart config must not be null");
            }
            holder.multipartConfig = config;
        }

        @Override
        public void setRunAsRole(String roleName) {
            holder.runAsRole = roleName;
        }
    }

    private final class FilterDynamicRegistration extends AbstractRegistration
            implements FilterRegistration.Dynamic {
        private final FilterHolder holder;

        private FilterDynamicRegistration(FilterHolder holder) {
            this.holder = holder;
        }

        @Override
        String name() {
            return holder.name;
        }

        @Override
        String className() {
            return holder.className;
        }

        @Override
        Map<String, String> initParameters() {
            return holder.initParameters;
        }

        @Override
        public void setAsyncSupported(boolean value) {
            holder.asyncSupported = value;
        }

        @Override
        public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes,
                                              boolean isMatchAfter, String... servletNames) {
            WebAppDescriptor.FilterMapping mapping = new WebAppDescriptor.FilterMapping(
                    holder.name, Collections.<String>emptyList(),
                    java.util.Arrays.asList(servletNames), dispatcherNames(dispatcherTypes));
            addFilterMapping(mapping, isMatchAfter);
        }

        @Override
        public Collection<String> getServletNameMappings() {
            List<String> result = new ArrayList<String>();
            for (WebAppDescriptor.FilterMapping mapping : filterMappings) {
                if (mapping.filterName().equals(holder.name)) {
                    result.addAll(mapping.servletNames());
                }
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes,
                                             boolean isMatchAfter, String... patterns) {
            WebAppDescriptor.FilterMapping mapping = new WebAppDescriptor.FilterMapping(
                    holder.name, java.util.Arrays.asList(patterns),
                    Collections.<String>emptyList(), dispatcherNames(dispatcherTypes));
            addFilterMapping(mapping, isMatchAfter);
        }

        @Override
        public Collection<String> getUrlPatternMappings() {
            List<String> result = new ArrayList<String>();
            for (WebAppDescriptor.FilterMapping mapping : filterMappings) {
                if (mapping.filterName().equals(holder.name)) {
                    result.addAll(mapping.urlPatterns());
                }
            }
            return Collections.unmodifiableList(result);
        }

        private void addFilterMapping(WebAppDescriptor.FilterMapping mapping, boolean after) {
            if (after) {
                filterMappings.add(mapping);
            } else {
                filterMappings.add(beforeFilterMappingInsertPoint, mapping);
                beforeFilterMappingInsertPoint++;
            }
        }
    }

    private static List<String> dispatcherNames(EnumSet<DispatcherType> types) {
        if (types == null || types.isEmpty()) {
            return Collections.singletonList(DispatcherType.REQUEST.name());
        }
        List<String> result = new ArrayList<String>();
        for (DispatcherType type : types) {
            result.add(type.name());
        }
        return result;
    }

    private final class FilterHolder {
        private final String name;
        private String className;
        private Class<? extends Filter> filterClass;
        private Filter filter;
        private final Map<String, String> initParameters = new LinkedHashMap<String, String>();
        private boolean asyncSupported;

        private FilterHolder(WebAppDescriptor.FilterDefinition definition) {
            name = definition.name();
            className = definition.className();
            initParameters.putAll(definition.initParams());
            asyncSupported = definition.asyncSupported();
        }

        private FilterHolder(String name, String className) {
            this.name = name;
            this.className = className;
        }

        private FilterHolder(String name, Class<? extends Filter> filterClass) {
            this.name = name;
            this.className = filterClass.getName();
            this.filterClass = filterClass;
        }

        private FilterHolder(String name, Filter filter) {
            this.name = name;
            this.className = filter.getClass().getName();
            this.filter = filter;
        }

        private void initialize() throws Exception {
            if (filter == null) {
                Class<?> type = filterClass != null ? filterClass
                        : application.classLoader().loadClass(className);
                Object value = type.newInstance();
                if (!(value instanceof Filter)) {
                    throw new ServletException(className + " is not a Filter");
                }
                filter = (Filter) value;
            }
            filter.init(new TinyFilterConfig(
                    name, servletContext, initParameters));
        }

        private void destroy() {
            if (filter != null) {
                try {
                    filter.destroy();
                } catch (RuntimeException failure) {
                    servletContext.log("Filter destroy failed: " + name, failure);
                } finally {
                    filter = null;
                }
            }
        }
    }

    private static final class CompiledFilter {
        private final Filter filter;
        private final boolean asyncSupported;

        private CompiledFilter(Filter filter, boolean asyncSupported) {
            this.filter = filter;
            this.asyncSupported = asyncSupported;
        }
    }

    private static final class CompiledFilterMapping {
        private final CompiledFilter filter;
        private final String[] servletNames;
        private final String[] urlPatterns;
        private final DispatcherType[] dispatcherTypes;

        private CompiledFilterMapping(CompiledFilter filter,
                                      List<String> servletNames, List<String> urlPatterns,
                                      List<String> dispatcherTypes) {
            this.filter = filter;
            this.servletNames = servletNames.isEmpty() ? null
                    : servletNames.toArray(new String[servletNames.size()]);
            this.urlPatterns = urlPatterns.isEmpty() ? null
                    : urlPatterns.toArray(new String[urlPatterns.size()]);
            this.dispatcherTypes = new DispatcherType[dispatcherTypes.size()];
            for (int index = 0; index < dispatcherTypes.size(); index++) {
                this.dispatcherTypes[index] = DispatcherType.valueOf(dispatcherTypes.get(index));
            }
        }

        private boolean matchesDispatcher(DispatcherType dispatcherType) {
            for (DispatcherType candidate : dispatcherTypes) {
                if (candidate == dispatcherType) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesServletName(String servletName) {
            if (servletNames == null) {
                return false;
            }
            for (String candidate : servletNames) {
                if (candidate.equals(servletName)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesUrlPattern(String path) {
            if (urlPatterns != null) {
                for (String pattern : urlPatterns) {
                    if (ServletMapper.matchesUrlPattern(pattern, path)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static final class ApplicationFilterChain implements FilterChain {
        private final CompiledFilter[] filters;
        private final Servlet servlet;
        private int index;

        private ApplicationFilterChain(CompiledFilter[] filters, Servlet servlet) {
            this.filters = filters;
            this.servlet = servlet;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
                throws IOException, ServletException {
            if (index < filters.length) {
                filters[index++].filter.doFilter(request, response, this);
            } else {
                servlet.service(request, response);
            }
        }
    }

    private final class StaticResourceServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            serveStatic(request.getServletPath(), request, response);
        }
    }

    private static final class FilterSelection {
        private final CompiledFilter[] filters;
        private final boolean asyncSupported;

        private FilterSelection(CompiledFilter[] filters, boolean asyncSupported) {
            this.filters = filters;
            this.asyncSupported = asyncSupported;
        }
    }
}
