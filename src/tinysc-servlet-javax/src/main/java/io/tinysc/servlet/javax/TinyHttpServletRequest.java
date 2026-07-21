package io.tinysc.servlet.javax;

import io.tinysc.kernel.ContainerRequest;
import io.tinysc.kernel.ContainerExchange;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

final class TinyHttpServletRequest implements HttpServletRequest {
    private final ContainerRequest request;
    private final ContainerExchange exchange;
    private final TinyHttpServletResponse response;
    private final TinyServletContext context;
    private final TinySessionManager sessions;
    private ServletMappingResult mapping;
    private String requestPath;
    private final boolean asyncSupported;
    private final ScheduledExecutorService asyncScheduler;
    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    private String characterEncoding = "ISO-8859-1";
    private String currentQuery;
    private DispatcherType dispatcherType = DispatcherType.REQUEST;
    private Map<String, String[]> parameters;
    private ServletInputStream inputStream;
    private BufferedReader reader;
    private Cookie[] cookies;
    private boolean cookiesParsed;
    private String requestedSessionId;
    private TinyHttpSession session;
    private TinyAsyncContext asyncContext;

    TinyHttpServletRequest(ContainerExchange exchange, TinyHttpServletResponse response,
                           TinyServletContext context, TinySessionManager sessions,
                           ServletMappingResult mapping, String requestPath,
                           boolean asyncSupported, ScheduledExecutorService asyncScheduler) {
        this.exchange = exchange;
        this.request = exchange.request();
        this.response = response;
        this.context = context;
        this.sessions = sessions;
        this.mapping = mapping;
        this.requestPath = requestPath;
        currentQuery = request.query();
        this.asyncSupported = asyncSupported;
        this.asyncScheduler = asyncScheduler;
        this.requestedSessionId = findRequestedSessionId();
    }

    @Override
    public String getAuthType() {
        return null;
    }

    @Override
    public Cookie[] getCookies() {
        parseCookies();
        return cookies == null ? null : cookies.clone();
    }

    @Override
    public long getDateHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1L;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("invalid HTTP date header: " + name, exception);
        }
    }

    @Override
    public String getHeader(String name) {
        return request.firstHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(request.headerValues(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(request.headers().keySet());
    }

    @Override
    public int getIntHeader(String name) {
        String value = getHeader(name);
        return value == null ? -1 : Integer.parseInt(value);
    }

    @Override
    public String getMethod() {
        return request.method();
    }

    @Override
    public String getPathInfo() {
        return mapping == null ? null : mapping.pathInfo();
    }

    @Override
    public String getPathTranslated() {
        String pathInfo = getPathInfo();
        return pathInfo == null ? null : context.getRealPath(pathInfo);
    }

    @Override
    public String getContextPath() {
        return context.getContextPath();
    }

    @Override
    public String getQueryString() {
        return currentQuery;
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getRequestedSessionId() {
        return requestedSessionId;
    }

    @Override
    public String getRequestURI() {
        return dispatcherType == DispatcherType.REQUEST
                ? request.path() : context.getContextPath() + requestPath;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuilder value = new StringBuilder(request.scheme()).append("://")
                .append(request.serverName());
        if (!("http".equals(request.scheme()) && request.serverPort() == 80)
                && !("https".equals(request.scheme()) && request.serverPort() == 443)) {
            value.append(':').append(request.serverPort());
        }
        value.append(getRequestURI());
        return new StringBuffer(value.toString());
    }

    @Override
    public String getServletPath() {
        return mapping == null ? requestPath : mapping.servletPath();
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (session == null && requestedSessionId != null) {
            session = sessions.find(requestedSessionId);
        }
        if (session == null && create) {
            session = sessions.create();
            requestedSessionId = session.getId();
            response.addCookie(context.sessionCookie(requestedSessionId, isSecure()));
        }
        return session;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public String changeSessionId() {
        TinyHttpSession current = (TinyHttpSession) getSession(false);
        if (current == null) {
            throw new IllegalStateException("request has no session");
        }
        String id = sessions.changeId(current);
        requestedSessionId = id;
        response.addCookie(context.sessionCookie(id, isSecure()));
        return id;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return requestedSessionId != null && sessions.find(requestedSessionId) != null;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return requestedSessionId != null;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }

    @Override
    public boolean authenticate(HttpServletResponse servletResponse) {
        return getUserPrincipal() != null;
    }

    @Override
    public void login(String username, String password) throws ServletException {
        throw new ServletException("programmatic login is not configured");
    }

    @Override
    public void logout() {
    }

    @Override
    public Collection<Part> getParts() throws ServletException {
        throw new ServletException("multipart processing is not configured");
    }

    @Override
    public Part getPart(String name) throws ServletException {
        throw new ServletException("multipart processing is not configured");
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws ServletException {
        throw new ServletException("HTTP upgrade is not supported by tinysc 1.0 alpha");
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
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        if (parameters != null || reader != null) {
            return;
        }
        try {
            Charset.forName(encoding);
        } catch (RuntimeException exception) {
            throw new UnsupportedEncodingException(encoding);
        }
        characterEncoding = encoding;
    }

    @Override
    public int getContentLength() {
        return request.bodyLength();
    }

    @Override
    public long getContentLengthLong() {
        return request.bodyLength();
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public ServletInputStream getInputStream() {
        if (reader != null) {
            throw new IllegalStateException("getReader() has already been called");
        }
        if (inputStream == null) {
            inputStream = new TinyInputStream(request.bodyBytes());
        }
        return inputStream;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameters().get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters().keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = parameters().get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> copy = new LinkedHashMap<String, String[]>();
        for (Map.Entry<String, String[]> entry : parameters().entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String getProtocol() {
        return request.protocol();
    }

    @Override
    public String getScheme() {
        return request.scheme();
    }

    @Override
    public String getServerName() {
        return request.serverName();
    }

    @Override
    public int getServerPort() {
        return request.serverPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (inputStream != null) {
            throw new IllegalStateException("getInputStream() has already been called");
        }
        if (reader == null) {
            reader = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(request.bodyBytes()), characterEncoding));
        }
        return reader;
    }

    @Override
    public String getRemoteAddr() {
        return request.remoteAddress().getAddress().getHostAddress();
    }

    @Override
    public String getRemoteHost() {
        return request.remoteAddress().getHostString();
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            removeAttribute(name);
        } else {
            Object oldValue = attributes.put(name, value);
            if (oldValue == null) {
                context.registry().fireRequestAttributeAdded(this, name, value);
            } else {
                context.registry().fireRequestAttributeReplaced(this, name, oldValue);
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        if (oldValue != null) {
            context.registry().fireRequestAttributeRemoved(this, name, oldValue);
        }
    }

    @Override
    public Locale getLocale() {
        Enumeration<Locale> locales = getLocales();
        return locales.hasMoreElements() ? locales.nextElement() : Locale.getDefault();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        String header = getHeader("Accept-Language");
        List<Locale> result = new ArrayList<Locale>();
        if (header != null) {
            for (String value : header.split(",")) {
                String tag = value.split(";", 2)[0].trim();
                if (!tag.isEmpty() && !"*".equals(tag)) {
                    result.add(Locale.forLanguageTag(tag));
                }
            }
        }
        if (result.isEmpty()) {
            result.add(Locale.getDefault());
        }
        return Collections.enumeration(result);
    }

    @Override
    public boolean isSecure() {
        return "https".equalsIgnoreCase(request.scheme());
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return context.getRequestDispatcher(path);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getRealPath(String path) {
        return context.getRealPath(path);
    }

    @Override
    public int getRemotePort() {
        return request.remoteAddress().getPort();
    }

    @Override
    public String getLocalName() {
        return request.localAddress().getHostString();
    }

    @Override
    public String getLocalAddr() {
        return request.localAddress().getAddress().getHostAddress();
    }

    @Override
    public int getLocalPort() {
        return request.localAddress().getPort();
    }

    @Override
    public ServletContext getServletContext() {
        return context;
    }

    @Override
    public AsyncContext startAsync() {
        return startAsync(this, response);
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        if (!asyncSupported) {
            throw new IllegalStateException("the servlet chain does not support async processing");
        }
        if (servletRequest == null || servletResponse == null) {
            throw new IllegalArgumentException("async request and response are required");
        }
        if (asyncContext != null && !asyncContext.isTerminal()) {
            throw new IllegalStateException("async processing has already started");
        }
        exchange.defer();
        asyncContext = new TinyAsyncContext(exchange, context, servletRequest, servletResponse,
                servletRequest == this && servletResponse == response,
                asyncScheduler, context.getClassLoader());
        return asyncContext;
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncContext != null && !asyncContext.isTerminal();
    }

    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    @Override
    public AsyncContext getAsyncContext() {
        if (asyncContext == null) {
            throw new IllegalStateException("async processing has not started");
        }
        return asyncContext;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return dispatcherType;
    }

    void endRequest() {
        if (session != null) {
            session.endAccess();
        }
    }

    TinyAsyncContext asyncContextInternal() {
        return asyncContext;
    }

    DispatchState pushDispatch(String path, String query, ServletMappingResult newMapping,
                               DispatcherType newDispatcherType) {
        DispatchState previous = new DispatchState(
                requestPath, currentQuery, mapping, dispatcherType, parameters);
        if (newDispatcherType == DispatcherType.FORWARD
                && getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) == null) {
            setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, getRequestURI());
            setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, getContextPath());
            setAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, getServletPath());
            setAttribute(RequestDispatcher.FORWARD_PATH_INFO, getPathInfo());
            setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, getQueryString());
        }
        requestPath = path;
        currentQuery = query;
        mapping = newMapping;
        dispatcherType = newDispatcherType;
        parameters = null;
        return previous;
    }

    void popDispatch(DispatchState previous) {
        requestPath = previous.requestPath;
        currentQuery = previous.query;
        mapping = previous.mapping;
        dispatcherType = previous.dispatcherType;
        parameters = previous.parameters;
    }

    private Map<String, String[]> parameters() {
        if (parameters == null) {
            Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();
            parseParameterString(currentQuery, "UTF-8", values);
            String type = getContentType();
            if ("POST".equalsIgnoreCase(request.method()) && type != null
                    && type.toLowerCase(Locale.ROOT)
                    .startsWith("application/x-www-form-urlencoded")) {
                parseParameterString(new String(request.bodyBytes(), Charset.forName(characterEncoding)),
                        characterEncoding, values);
            }
            Map<String, String[]> built = new LinkedHashMap<String, String[]>();
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                built.put(entry.getKey(), entry.getValue().toArray(new String[entry.getValue().size()]));
            }
            parameters = Collections.unmodifiableMap(built);
        }
        return parameters;
    }

    private static void parseParameterString(String value, String encoding,
                                             Map<String, List<String>> target) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (String pair : value.split("&", -1)) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                String name = URLDecoder.decode(rawName, encoding);
                String decoded = URLDecoder.decode(rawValue, encoding);
                List<String> values = target.get(name);
                if (values == null) {
                    values = new ArrayList<String>();
                    target.put(name, values);
                }
                values.add(decoded);
            } catch (UnsupportedEncodingException impossible) {
                throw new IllegalArgumentException(impossible);
            }
        }
    }

    private String findRequestedSessionId() {
        parseCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (context.sessionCookieName().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void parseCookies() {
        if (cookiesParsed) {
            return;
        }
        cookiesParsed = true;
        List<Cookie> result = new ArrayList<Cookie>();
        for (String header : request.headerValues("Cookie")) {
            for (String token : header.split(";")) {
                int equals = token.indexOf('=');
                if (equals > 0) {
                    String name = token.substring(0, equals).trim();
                    String value = token.substring(equals + 1).trim();
                    try {
                        result.add(new Cookie(name, value));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid individual cookies do not make the entire request unusable.
                    }
                }
            }
        }
        cookies = result.isEmpty() ? null : result.toArray(new Cookie[result.size()]);
    }

    private static final class TinyInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private TinyInputStream(byte[] body) {
            input = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("read listener must not be null");
            }
            try {
                if (isFinished()) {
                    listener.onAllDataRead();
                } else {
                    listener.onDataAvailable();
                }
            } catch (IOException exception) {
                listener.onError(exception);
            }
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return input.read(bytes, offset, length);
        }
    }

    static final class DispatchState {
        private final String requestPath;
        private final String query;
        private final ServletMappingResult mapping;
        private final DispatcherType dispatcherType;
        private final Map<String, String[]> parameters;

        private DispatchState(String requestPath, String query, ServletMappingResult mapping,
                              DispatcherType dispatcherType, Map<String, String[]> parameters) {
            this.requestPath = requestPath;
            this.query = query;
            this.mapping = mapping;
            this.dispatcherType = dispatcherType;
            this.parameters = parameters;
        }
    }
}
