package io.tinysc.kernel;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ContainerRequest {
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final String[] EMPTY_HEADER_PAIRS = new String[0];
    private static final InetSocketAddress DEFAULT_ADDRESS = new InetSocketAddress("127.0.0.1", 0);
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
    private final String[] headerPairs;
    private final byte[] body;
    private volatile Map<String, List<String>> headersView;

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
        int headerPairLength = builder.headerCount * 2;
        headerPairs = headerPairLength == 0
                ? EMPTY_HEADER_PAIRS
                : Arrays.copyOf(builder.headerPairs, headerPairLength);
        body = builder.body == null ? EMPTY_BODY : builder.body;
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
        Map<String, List<String>> result = headersView;
        if (result == null) {
            result = createHeadersView();
            headersView = result;
        }
        return result;
    }

    public String firstHeader(String name) {
        String requestedName = requireText(name, "header name");
        for (int index = 0; index < headerPairs.length; index += 2) {
            if (headerPairs[index].equalsIgnoreCase(requestedName)) {
                return headerPairs[index + 1];
            }
        }
        return null;
    }

    public List<String> headerValues(String name) {
        String requestedName = requireText(name, "header name");
        String firstValue = null;
        List<String> values = null;
        for (int index = 0; index < headerPairs.length; index += 2) {
            if (!headerPairs[index].equalsIgnoreCase(requestedName)) {
                continue;
            }
            String value = headerPairs[index + 1];
            if (firstValue == null) {
                firstValue = value;
            } else {
                if (values == null) {
                    values = new ArrayList<String>(2);
                    values.add(firstValue);
                }
                values.add(value);
            }
        }
        if (firstValue == null) {
            return Collections.emptyList();
        }
        if (values == null) {
            return Collections.singletonList(firstValue);
        }
        return Collections.unmodifiableList(values);
    }

    public int bodyLength() {
        return body.length;
    }

    public byte[] bodyBytes() {
        return body.length == 0 ? EMPTY_BODY : body.clone();
    }

    public InputStream bodyStream() {
        return new ByteArrayInputStream(body);
    }

    public static Builder builder() {
        return new Builder();
    }

    private Map<String, List<String>> createHeadersView() {
        if (headerPairs.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
        for (int index = 0; index < headerPairs.length; index += 2) {
            String name = normalizeHeaderName(headerPairs[index]);
            List<String> values = copy.get(name);
            if (values == null) {
                values = new ArrayList<String>(1);
                copy.put(name, values);
            }
            values.add(headerPairs[index + 1]);
        }
        for (Map.Entry<String, List<String>> entry : copy.entrySet()) {
            List<String> values = entry.getValue();
            if (values.size() == 1) {
                entry.setValue(Collections.singletonList(values.get(0)));
            } else {
                entry.setValue(Collections.unmodifiableList(values));
            }
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
        private InetSocketAddress remoteAddress = DEFAULT_ADDRESS;
        private InetSocketAddress localAddress = DEFAULT_ADDRESS;
        private String[] headerPairs = EMPTY_HEADER_PAIRS;
        private int headerCount;
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
            String checkedName = requireText(name, "header name");
            String checkedValue = Objects.requireNonNull(value, "header value");
            int offset = headerCount * 2;
            if (offset == headerPairs.length) {
                int newLength = headerPairs.length == 0 ? 8 : headerPairs.length * 2;
                headerPairs = Arrays.copyOf(headerPairs, newLength);
            }
            headerPairs[offset] = checkedName;
            headerPairs[offset + 1] = checkedValue;
            headerCount++;
            return this;
        }

        public Builder body(byte[] value) {
            if (value == null) {
                body = null;
            } else if (value.length == 0) {
                body = EMPTY_BODY;
            } else {
                body = value.clone();
            }
            return this;
        }

        public Builder bodyBuffers(ByteBuffer[] values) {
            if (values == null) {
                body = null;
                return this;
            }
            long length = 0L;
            for (ByteBuffer value : values) {
                if (value == null) {
                    throw new IllegalArgumentException("body buffer must not be null");
                }
                length += value.remaining();
                if (length > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("body is too large");
                }
            }
            if (length == 0L) {
                body = EMPTY_BODY;
                return this;
            }
            body = new byte[(int) length];
            int offset = 0;
            for (ByteBuffer value : values) {
                ByteBuffer source = value.duplicate();
                int remaining = source.remaining();
                source.get(body, offset, remaining);
                offset += remaining;
            }
            return this;
        }

        public ContainerRequest build() {
            return new ContainerRequest(this);
        }
    }
}
