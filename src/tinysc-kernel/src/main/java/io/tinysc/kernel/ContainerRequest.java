package io.tinysc.kernel;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ContainerRequest {
    private final String method;
    private final String rawUri;
    private final String path;
    private final String query;
    private final String protocol;
    private final String scheme;
    private final String serverName;
    private final int serverPort;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    private ContainerRequest(Builder builder) {
        method = requireText(builder.method, "method");
        rawUri = requireText(builder.rawUri, "rawUri");
        path = requireText(builder.path, "path");
        query = builder.query;
        protocol = requireText(builder.protocol, "protocol");
        scheme = requireText(builder.scheme, "scheme");
        serverName = requireText(builder.serverName, "serverName");
        if (builder.serverPort < 0 || builder.serverPort > 65535) {
            throw new IllegalArgumentException("serverPort must be between 0 and 65535");
        }
        serverPort = builder.serverPort;
        remoteAddress = Objects.requireNonNull(builder.remoteAddress, "remoteAddress");
        localAddress = Objects.requireNonNull(builder.localAddress, "localAddress");
        headers = immutableHeaders(builder.headers);
        body = builder.body == null ? new byte[0] : builder.body;
    }

    public String method() {
        return method;
    }

    public String rawUri() {
        return rawUri;
    }

    public String path() {
        return path;
    }

    public String query() {
        return query;
    }

    public String protocol() {
        return protocol;
    }

    public String scheme() {
        return scheme;
    }

    public String serverName() {
        return serverName;
    }

    public int serverPort() {
        return serverPort;
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public InetSocketAddress localAddress() {
        return localAddress;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public String firstHeader(String name) {
        List<String> values = headers.get(normalizeHeaderName(name));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<String> headerValues(String name) {
        List<String> values = headers.get(normalizeHeaderName(name));
        return values == null ? Collections.<String>emptyList() : values;
    }

    public int bodyLength() {
        return body.length;
    }

    public byte[] bodyBytes() {
        return body.clone();
    }

    public InputStream bodyStream() {
        return new ByteArrayInputStream(body);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String name = normalizeHeaderName(entry.getKey());
            List<String> values = copy.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                copy.put(name, values);
            }
            values.addAll(entry.getValue());
        }
        for (Map.Entry<String, List<String>> entry : copy.entrySet()) {
            entry.setValue(Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeHeaderName(String name) {
        return requireText(name, "header name").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }

    public static final class Builder {
        private String method;
        private String rawUri;
        private String path;
        private String query;
        private String protocol = "HTTP/1.1";
        private String scheme = "http";
        private String serverName = "localhost";
        private int serverPort = 80;
        private InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 0);
        private InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 0);
        private final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        private byte[] body;

        private Builder() {
        }

        public Builder method(String value) {
            method = value;
            return this;
        }

        public Builder rawUri(String value) {
            rawUri = value;
            return this;
        }

        public Builder path(String value) {
            path = value;
            return this;
        }

        public Builder query(String value) {
            query = value;
            return this;
        }

        public Builder protocol(String value) {
            protocol = value;
            return this;
        }

        public Builder scheme(String value) {
            scheme = value;
            return this;
        }

        public Builder serverName(String value) {
            serverName = value;
            return this;
        }

        public Builder serverPort(int value) {
            serverPort = value;
            return this;
        }

        public Builder remoteAddress(InetSocketAddress value) {
            remoteAddress = value;
            return this;
        }

        public Builder localAddress(InetSocketAddress value) {
            localAddress = value;
            return this;
        }

        public Builder addHeader(String name, String value) {
            String normalized = normalizeHeaderName(name);
            List<String> values = headers.get(normalized);
            if (values == null) {
                values = new ArrayList<String>();
                headers.put(normalized, values);
            }
            values.add(Objects.requireNonNull(value, "header value"));
            return this;
        }

        public Builder body(byte[] value) {
            body = value == null ? null : value.clone();
            return this;
        }

        public ContainerRequest build() {
            return new ContainerRequest(this);
        }
    }
}
