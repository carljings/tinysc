package io.tinysc.servlet.javax;

import io.tinysc.kernel.ContainerResponse;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TinyHttpServletResponse implements HttpServletResponse {
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final ContainerResponse response;
    private String characterEncoding = "ISO-8859-1";
    private String contentType;
    private Locale locale = Locale.getDefault();
    private int bufferSize = 8192;
    private TinyOutputStream outputStream;
    private PrintWriter writer;

    TinyHttpServletResponse(ContainerResponse response) {
        this.response = response;
    }

    @Override
    public void addCookie(Cookie cookie) {
        if (cookie == null) {
            throw new IllegalArgumentException("cookie must not be null");
        }
        StringBuilder value = new StringBuilder(cookie.getName()).append('=').append(cookie.getValue());
        if (cookie.getPath() != null) {
            value.append("; Path=").append(cookie.getPath());
        }
        if (cookie.getDomain() != null) {
            value.append("; Domain=").append(cookie.getDomain());
        }
        if (cookie.getMaxAge() >= 0) {
            value.append("; Max-Age=").append(cookie.getMaxAge());
        }
        if (cookie.getSecure()) {
            value.append("; Secure");
        }
        if (cookie.isHttpOnly()) {
            value.append("; HttpOnly");
        }
        response.addHeader("Set-Cookie", value.toString());
    }

    @Override
    public boolean containsHeader(String name) {
        return response.containsHeader(name);
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @SuppressWarnings("deprecation")
    @Override
    public String encodeUrl(String url) {
        return encodeURL(url);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String encodeRedirectUrl(String url) {
        return encodeRedirectURL(url);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        ensureNotCommitted();
        resetBuffer();
        setStatus(status);
        setContentType("text/plain");
        getWriter().write(message == null ? statusMessage(status) : message);
        flushBuffer();
    }

    @Override
    public void sendError(int status) throws IOException {
        sendError(status, statusMessage(status));
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        ensureNotCommitted();
        resetBuffer();
        setStatus(SC_FOUND);
        setHeader("Location", location);
        flushBuffer();
    }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, formatDate(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, formatDate(date));
    }

    @Override
    public void setHeader(String name, String value) {
        response.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        response.addHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    @Override
    public void setStatus(int status) {
        response.status(status);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setStatus(int status, String message) {
        setStatus(status);
    }

    @Override
    public int getStatus() {
        return response.status();
    }

    @Override
    public String getHeader(String name) {
        return response.firstHeader(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return Collections.unmodifiableList(new ArrayList<String>(response.headers().keySet()));
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (outputStream == null) {
            outputStream = new TinyOutputStream();
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(
                    response.bodyStream(), Charset.forName(characterEncoding)));
            updateContentTypeHeader();
        }
        return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        if (charset == null || isCommitted() || writer != null) {
            return;
        }
        Charset.forName(charset);
        characterEncoding = charset;
        updateContentTypeHeader();
    }

    @Override
    public void setContentLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("content length must not be negative");
        }
        setHeader("Content-Length", Integer.toString(length));
    }

    @Override
    public void setContentLengthLong(long length) {
        if (length < 0L) {
            throw new IllegalArgumentException("content length must not be negative");
        }
        setHeader("Content-Length", Long.toString(length));
    }

    @Override
    public void setContentType(String value) {
        if (isCommitted()) {
            return;
        }
        contentType = value;
        if (value != null) {
            String charset = extractCharset(value);
            if (charset != null && writer == null) {
                Charset.forName(charset);
                characterEncoding = charset;
            }
        }
        updateContentTypeHeader();
    }

    @Override
    public void setBufferSize(int size) {
        if (size <= 0 || response.bodySize() > 0 || isCommitted()) {
            throw new IllegalStateException("buffer size can only be set before response content");
        }
        bufferSize = size;
    }

    @Override
    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (outputStream != null) {
            outputStream.flush();
        }
        response.commit();
    }

    @Override
    public void resetBuffer() {
        response.resetBuffer();
    }

    @Override
    public boolean isCommitted() {
        return response.committed();
    }

    @Override
    public void reset() {
        response.reset();
        characterEncoding = "ISO-8859-1";
        contentType = null;
        locale = Locale.getDefault();
        outputStream = null;
        writer = null;
    }

    @Override
    public void setLocale(Locale value) {
        if (value != null && !isCommitted()) {
            locale = value;
            setHeader("Content-Language", value.toLanguageTag());
        }
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    void finish() {
        if (writer != null) {
            writer.flush();
        }
    }

    private void ensureNotCommitted() {
        if (isCommitted()) {
            throw new IllegalStateException("response is already committed");
        }
    }

    private void updateContentTypeHeader() {
        if (contentType == null) {
            return;
        }
        String value = contentType;
        if (writer != null && extractCharset(value) == null) {
            value = value + "; charset=" + characterEncoding;
        }
        response.setHeader("Content-Type", value);
    }

    private static String extractCharset(String value) {
        String[] parts = value.split(";");
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index].trim();
            if (part.regionMatches(true, 0, "charset=", 0, 8)) {
                String charset = part.substring(8).trim();
                if (charset.length() >= 2 && charset.startsWith("\"") && charset.endsWith("\"")) {
                    charset = charset.substring(1, charset.length() - 1);
                }
                return charset;
            }
        }
        return null;
    }

    private static String formatDate(long epochMillis) {
        return HTTP_DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }

    private static String statusMessage(int status) {
        switch (status) {
            case SC_BAD_REQUEST:
                return "Bad Request";
            case SC_FORBIDDEN:
                return "Forbidden";
            case SC_NOT_FOUND:
                return "Not Found";
            case SC_METHOD_NOT_ALLOWED:
                return "Method Not Allowed";
            case SC_INTERNAL_SERVER_ERROR:
                return "Internal Server Error";
            case SC_SERVICE_UNAVAILABLE:
                return "Service Unavailable";
            default:
                return Integer.toString(status);
        }
    }

    private final class TinyOutputStream extends ServletOutputStream {
        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("write listener must not be null");
            }
            try {
                listener.onWritePossible();
            } catch (IOException exception) {
                listener.onError(exception);
            }
        }

        @Override
        public void write(int value) throws IOException {
            response.bodyStream().write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            response.bodyStream().write(bytes, offset, length);
        }
    }
}
