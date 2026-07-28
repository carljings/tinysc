package io.tinysc.kernel;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContainerResponse {
    private static final ByteBuffer EMPTY_BODY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

    private int status = 200;
    private HeaderValues[] headers = new HeaderValues[8];
    private int headerCount;
    private final ResponseBody body = new ResponseBody(128);
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
        int index = findHeaderIndex(name);
        if (index >= 0) {
            headers[index].set(name, value);
            return;
        }
        ensureHeaderCapacity(headerCount + 1);
        headers[headerCount++] = new HeaderValues(name, value);
    }

    public void addHeader(String name, String value) {
        ensureNotCommitted();
        validateHeader(name, value);
        int index = findHeaderIndex(name);
        if (index >= 0) {
            headers[index].add(value);
            return;
        }
        ensureHeaderCapacity(headerCount + 1);
        headers[headerCount++] = new HeaderValues(name, value);
    }

    public boolean containsHeader(String name) {
        return findHeaderIndex(name) >= 0;
    }

    public String firstHeader(String name) {
        int index = findHeaderIndex(name);
        return index < 0 ? null : headers[index].firstValue();
    }

    public Map<String, List<String>> headers() {
        Map<String, List<String>> snapshot = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < headerCount; i++) {
            HeaderValues header = headers[i];
            snapshot.put(header.originalName, header.snapshotValues());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public void forEachHeader(HeaderConsumer consumer) {
        if (consumer == null) {
            throw new NullPointerException("consumer");
        }
        for (int i = 0; i < headerCount; i++) {
            headers[i].forEach(consumer);
        }
    }

    public OutputStream bodyStream() {
        return body;
    }

    public int bodySize() {
        return body.size();
    }

    public ByteBuffer bodyBuffer() {
        return body.readOnlyBuffer();
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
        clearHeaders();
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

    private int findHeaderIndex(String name) {
        validateHeaderName(name);
        for (int i = 0; i < headerCount; i++) {
            if (headers[i].matches(name)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureHeaderCapacity(int capacity) {
        if (capacity <= headers.length) {
            return;
        }
        HeaderValues[] expanded = new HeaderValues[headers.length << 1];
        System.arraycopy(headers, 0, expanded, 0, headerCount);
        headers = expanded;
    }

    private void clearHeaders() {
        for (int i = 0; i < headerCount; i++) {
            headers[i] = null;
        }
        headerCount = 0;
    }

    private static void validateHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("header name must not be empty");
        }
    }

    private static void validateHeader(String name, String value) {
        validateHeaderName(name);
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
        private String originalName;
        private String firstValue;
        private List<String> otherValues;

        private HeaderValues(String originalName, String firstValue) {
            this.originalName = originalName;
            this.firstValue = firstValue;
        }

        private boolean matches(String name) {
            return originalName.equalsIgnoreCase(name);
        }

        private void set(String name, String value) {
            originalName = name;
            firstValue = value;
            if (otherValues != null) {
                otherValues.clear();
            }
        }

        private void add(String value) {
            if (otherValues == null) {
                otherValues = new ArrayList<String>(1);
            }
            otherValues.add(value);
        }

        private String firstValue() {
            return firstValue;
        }

        private List<String> snapshotValues() {
            if (otherValues == null || otherValues.isEmpty()) {
                return Collections.singletonList(firstValue);
            }
            ArrayList<String> snapshot = new ArrayList<String>(1 + otherValues.size());
            snapshot.add(firstValue);
            snapshot.addAll(otherValues);
            return Collections.unmodifiableList(snapshot);
        }

        private void forEach(HeaderConsumer consumer) {
            consumer.accept(originalName, firstValue);
            if (otherValues == null) {
                return;
            }
            for (String value : otherValues) {
                consumer.accept(originalName, value);
            }
        }
    }

    private static final class ResponseBody extends ByteArrayOutputStream {
        private ResponseBody(int size) {
            super(size);
        }

        private ByteBuffer readOnlyBuffer() {
            if (count == 0) {
                return EMPTY_BODY_BUFFER.duplicate();
            }
            return ByteBuffer.wrap(buf, 0, count).asReadOnlyBuffer();
        }
    }

    @FunctionalInterface
    public interface HeaderConsumer {
        void accept(String name, String value);
    }
}
