package io.tinysc.kernel;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContainerResponse {
    private int status = 200;
    private final Map<String, HeaderValues> headers = new LinkedHashMap<String, HeaderValues>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
    private boolean committed;

    public int status() {
        return status;
    }

    public void status(int value) {
        ensureNotCommitted();
        if (value < 100 || value > 999) {
            throw new IllegalArgumentException("status must be between 100 and 999");
        }
        status = value;
    }

    public void setHeader(String name, String value) {
        ensureNotCommitted();
        validateHeader(name, value);
        HeaderValues header = new HeaderValues(name);
        header.values.add(value);
        headers.put(normalize(name), header);
    }

    public void addHeader(String name, String value) {
        ensureNotCommitted();
        validateHeader(name, value);
        String normalized = normalize(name);
        HeaderValues header = headers.get(normalized);
        if (header == null) {
            header = new HeaderValues(name);
            headers.put(normalized, header);
        }
        header.values.add(value);
    }

    public boolean containsHeader(String name) {
        return headers.containsKey(normalize(name));
    }

    public String firstHeader(String name) {
        HeaderValues header = headers.get(normalize(name));
        return header == null || header.values.isEmpty() ? null : header.values.get(0);
    }

    public Map<String, List<String>> headers() {
        Map<String, List<String>> snapshot = new LinkedHashMap<String, List<String>>();
        for (HeaderValues header : headers.values()) {
            snapshot.put(header.originalName,
                    Collections.unmodifiableList(new ArrayList<String>(header.values)));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public OutputStream bodyStream() {
        return body;
    }

    public int bodySize() {
        return body.size();
    }

    public byte[] bodyBytes() {
        return body.toByteArray();
    }

    public void resetBuffer() {
        ensureNotCommitted();
        body.reset();
    }

    public void reset() {
        ensureNotCommitted();
        status = 200;
        headers.clear();
        body.reset();
    }

    public boolean committed() {
        return committed;
    }

    public void commit() {
        committed = true;
    }

    private void ensureNotCommitted() {
        if (committed) {
            throw new IllegalStateException("response is already committed");
        }
    }

    private static String normalize(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("header name must not be empty");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validateHeader(String name, String value) {
        normalize(name);
        if (value == null) {
            throw new IllegalArgumentException("header value must not be null");
        }
        if (hasLineBreak(name) || hasLineBreak(value)) {
            throw new IllegalArgumentException("header name and value must not contain CR or LF");
        }
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static final class HeaderValues {
        private final String originalName;
        private final List<String> values = new ArrayList<String>();

        private HeaderValues(String originalName) {
            this.originalName = originalName;
        }
    }
}
