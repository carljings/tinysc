package io.tinysc.integration;

import io.tinysc.kernel.ServerConfig;
import io.tinysc.launcher.TinyScServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeWarIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startsWarAndServesFilterServletSessionAndStaticResource() throws Exception {
        Path war = Paths.get(System.getProperty("tinysc.probe.war")).toAbsolutePath();
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .contextPath("/probe")
                .baseDirectory(temporaryDirectory.resolve("base"))
                .workerThreads(2)
                .workerQueueCapacity(8)
                .build();

        try (TinyScServer server = new TinyScServer(config, war)) {
            int port = server.start();

            Response first = get(port, "/probe/hello/world?name=Alice", null);
            assertEquals(200, first.status);
            assertEquals("applied", first.filterHeader);
            assertTrue(first.body.contains("tinysc=ok"));
            assertTrue(first.body.contains("filter=ok"));
            assertTrue(first.body.contains("listener=ok"));
            assertTrue(first.body.contains("contextPath=/probe"));
            assertTrue(first.body.contains("servletPath=/hello"));
            assertTrue(first.body.contains("pathInfo=/world"));
            assertTrue(first.body.contains("name=Alice"));
            assertTrue(first.body.contains("session=1"));
            assertTrue(first.body.contains("thread=tinysc-worker-"));
            assertTrue(first.body.contains("resourceJar=true"));
            assertTrue(first.body.contains("resourceJarStream=true"));
            assertTrue(first.body.contains("resourceJarPaths=true"));
            assertTrue(first.body.contains("classesResource=false"));
            assertTrue(first.body.contains("shadowedJarResource=false"));
            assertTrue(first.body.contains("resourceJarRealPath=true"));
            assertNotNull(first.sessionCookie);

            Response second = get(port, "/probe/hello/world?name=Bob",
                    first.sessionCookie.split(";", 2)[0]);
            assertEquals(200, second.status);
            assertTrue(second.body.contains("name=Bob"));
            assertTrue(second.body.contains("session=2"));

            Response staticPage = get(port, "/probe/", null);
            assertEquals(200, staticPage.status);
            assertEquals("applied", staticPage.filterHeader);
            assertTrue(staticPage.body.contains("tinysc static resource ok"));
            assertNotNull(staticPage.lastModified);

            Response cachedStaticPage = request(port, "GET", "/probe/", null,
                    "If-Modified-Since", staticPage.lastModified);
            assertEquals(304, cachedStaticPage.status);
            assertEquals("", cachedStaticPage.body);

            Response forwardedStatic = get(port, "/probe/static-forward", null);
            assertEquals(200, forwardedStatic.status);
            assertEquals("applied", forwardedStatic.filterHeader);
            assertEquals("applied", forwardedStatic.forwardFilterHeader);
            assertTrue(forwardedStatic.body.contains("tinysc static resource ok"));

            Response jarStatic = get(port, "/probe/jar-resource.html", null);
            assertEquals(200, jarStatic.status);
            assertEquals("applied", jarStatic.filterHeader);
            assertTrue(jarStatic.body.contains("tinysc resource JAR ok"));
            assertNotNull(jarStatic.lastModified);

            Response cachedJarStatic = request(port, "GET",
                    "/probe/jar-resource.html", null,
                    "If-Modified-Since", jarStatic.lastModified);
            assertEquals(304, cachedJarStatic.status);
            assertEquals("", cachedJarStatic.body);

            Response postedStaticTpl = request(port, "POST",
                    "/probe/manual-declaration.tpl", null);
            assertEquals(200, postedStaticTpl.status);
            assertEquals("applied", postedStaticTpl.filterHeader);
            assertTrue(postedStaticTpl.body.contains("tinysc filesystem tpl resource ok"));

            Response conditionalPostedStaticTpl = request(port, "POST",
                    "/probe/manual-declaration.tpl", null,
                    "If-Modified-Since", staticPage.lastModified);
            assertEquals(200, conditionalPostedStaticTpl.status);
            assertTrue(conditionalPostedStaticTpl.body
                    .contains("tinysc filesystem tpl resource ok"));

            Response postedJarTpl = request(port, "POST",
                    "/probe/jar-manual-declaration.tpl", null);
            assertEquals(200, postedJarTpl.status);
            assertEquals("applied", postedJarTpl.filterHeader);
            assertTrue(postedJarTpl.body.contains("tinysc resource JAR tpl ok"));

            assertEquals(404, request(port, "POST", "/probe/missing.tpl", null).status);
            Response mappedPost = request(port, "POST", "/probe/hello/world", null);
            assertEquals(405, mappedPost.status);
            assertEquals("applied", mappedPost.filterHeader);

            Response upload = multipart(port, "/probe/upload", "probe-boundary",
                    "title", "monthly", "document", "plan.txt", "text/plain", "content");
            assertEquals(200, upload.status);
            assertEquals("applied", upload.filterHeader);
            assertEquals("title=monthly;file=plan.txt;size=7;"
                    + "type=text/plain;payload=content", upload.body);

            Response descriptorOverride = multipart(port, "/probe/upload",
                    "descriptor-override-boundary",
                    "title", "monthly", "document", "override.txt", "text/plain",
                    "123456789");
            assertEquals(200, descriptorOverride.status);
            assertEquals("title=monthly;file=override.txt;size=9;"
                    + "type=text/plain;payload=123456789", descriptorOverride.body);

            Response oversizedUpload = multipart(port, "/probe/upload", "limit-boundary",
                    "title", "monthly", "document", "large.txt", "text/plain", repeat('x', 65));
            assertEquals(500, oversizedUpload.status);

            Response annotatedUpload = multipart(port, "/probe/annotated-upload",
                    "annotation-boundary",
                    "title", "monthly", "document", "annotated.txt", "text/plain", "content");
            assertEquals(200, annotatedUpload.status);
            assertEquals("applied", annotatedUpload.filterHeader);
            assertEquals("title=monthly;file=annotated.txt;size=7;"
                    + "type=text/plain;payload=content", annotatedUpload.body);

            Response oversizedAnnotatedUpload = multipart(port, "/probe/annotated-upload",
                    "annotation-limit-boundary",
                    "title", "monthly", "document", "large.txt", "text/plain", "123456789");
            assertEquals(500, oversizedAnnotatedUpload.status);

            Response emptyDescriptorOverride = multipart(port, "/probe/empty-config-upload",
                    "empty-descriptor-boundary",
                    "title", "monthly", "document", "empty-config.txt", "text/plain",
                    "123456789");
            assertEquals(200, emptyDescriptorOverride.status);
            assertEquals("title=monthly;file=empty-config.txt;size=9;"
                    + "type=text/plain;payload=123456789", emptyDescriptorOverride.body);

            Response forwardedJarStatic = get(port, "/probe/jar-static-forward", null);
            assertEquals(200, forwardedJarStatic.status);
            assertEquals("applied", forwardedJarStatic.filterHeader);
            assertEquals("applied", forwardedJarStatic.forwardFilterHeader);
            assertTrue(forwardedJarStatic.body.contains("tinysc resource JAR ok"));

            Response jarStaticHead = request(port, "HEAD", "/probe/jar-resource.html", null);
            assertEquals(200, jarStaticHead.status);
            assertEquals("", jarStaticHead.body);
            assertTrue(Integer.parseInt(jarStaticHead.contentLength) > 0);

            Response jarWelcome = get(port, "/probe/jar-dir/", null);
            assertEquals(200, jarWelcome.status);
            assertTrue(jarWelcome.body.contains("tinysc resource JAR welcome ok"));

            Response staticHead = request(port, "HEAD", "/probe/", null);
            assertEquals(200, staticHead.status);
            assertEquals("", staticHead.body);
            assertTrue(Integer.parseInt(staticHead.contentLength) > 0);

            Response benchmark = get(port, "/probe/benchmark", null);
            assertEquals(200, benchmark.status);
            assertEquals("applied", benchmark.filterHeader);
            assertTrue(benchmark.body.startsWith("tinysc-probe-response-"));
            assertEquals(benchmark.body.getBytes(StandardCharsets.US_ASCII).length,
                    Integer.parseInt(benchmark.contentLength));

            Response dynamic = get(port, "/probe/dynamic", null);
            assertEquals(200, dynamic.status);
            assertTrue(dynamic.body.contains("sci=ok"));
            assertTrue(dynamic.body.contains("dynamicFilter=ok"));
            assertTrue(dynamic.body.contains("requestListener=ok"));

            Response async = get(port, "/probe/async", null);
            assertEquals(200, async.status);
            assertTrue(async.body.contains("async=ok"));
            assertTrue(async.body.contains("thread=tinysc-worker-"));

            Response forwarded = get(port, "/probe/forward", null);
            assertEquals(200, forwarded.status);
            assertEquals("applied", forwarded.filterHeader);
            assertTrue(forwarded.body.contains("pathInfo=/forwarded"));
            assertTrue(forwarded.body.contains("name=Forward"));

            Response sendError404 = get(
                    port, "/probe/error/send-error-404", null);
            assertEquals(404, sendError404.status);
            assertEquals("applied", sendError404.errorFilterHeader);
            assertEquals(errorBody(
                    "status-404", 404, "probe-missing",
                    "/probe/error/send-error-404", null, null), sendError404.body);
            assertContentLengthMatches(sendError404);

            Response setStatus404 = get(
                    port, "/probe/error/set-status-404", null);
            assertEquals(404, setStatus404.status);
            assertEquals(null, setStatus404.errorFilterHeader);
            assertEquals("plain-status-404", setStatus404.body);
            assertContentLengthMatches(setStatus404);

            Response runtimeException = get(
                    port, "/probe/error/runtime-exception", null);
            assertEquals(500, runtimeException.status);
            assertEquals("applied", runtimeException.errorFilterHeader);
            assertEquals(errorBody(
                    "runtime-exception", 500, "probe-runtime",
                    "/probe/error/runtime-exception",
                    "java.lang.RuntimeException:probe-runtime",
                    "java.lang.RuntimeException"), runtimeException.body);
            assertContentLengthMatches(runtimeException);

            Response wrappedIOException = get(
                    port, "/probe/error/servlet-io-exception", null);
            assertEquals(500, wrappedIOException.status);
            assertEquals("applied", wrappedIOException.errorFilterHeader);
            assertEquals(errorBody(
                    "io-root-cause", 500, "probe-servlet",
                    "/probe/error/servlet-io-exception",
                    "java.io.IOException:probe-io",
                    "java.io.IOException"), wrappedIOException.body);
            assertContentLengthMatches(wrappedIOException);

            Response failingErrorPage = get(
                    port, "/probe/error/failing-error-page", null);
            assertEquals(500, failingErrorPage.status);
            assertEquals("applied", failingErrorPage.errorFilterHeader);
            assertEquals("Internal Server Error", failingErrorPage.body);
            assertContentLengthMatches(failingErrorPage);

            assertEquals(404, get(port, "/probe/WEB-INF/web.xml", null).status);
            assertEquals(404, get(port, "/probe/WEB-INF/jar-secret.txt", null).status);
            assertEquals(404, get(port, "/probe/classes-only.html", null).status);
            assertEquals(404, get(port, "/probe/jar-shadow/index.html", null).status);
            assertEquals(404, get(port, "/outside", null).status);
            assertTrue(server.readyMillis() >= 0L);
            assertTrue(server.prepareMillis() >= 0L);
        }
    }

    private static Response get(int port, String path, String cookie) throws IOException {
        return request(port, "GET", path, cookie);
    }

    private static Response request(int port, String method, String path, String cookie)
            throws IOException {
        return request(port, method, path, cookie, null, null);
    }

    private static Response request(int port, String method, String path, String cookie,
                                    String headerName, String headerValue)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        if (headerName != null) {
            connection.setRequestProperty(headerName, headerValue);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = input == null ? "" : read(input);
        return new Response(status, body, connection.getHeaderField("X-TinySC-Filter"),
                connection.getHeaderField("X-TinySC-Forward-Filter"),
                connection.getHeaderField("X-TinySC-Error-Filter"),
                connection.getHeaderField("Set-Cookie"),
                connection.getHeaderField("Content-Length"),
                connection.getHeaderField("Last-Modified"));
    }

    private static Response multipart(
            int port, String path, String boundary,
            String fieldName, String fieldValue,
            String fileField, String fileName, String contentType, String fileValue)
            throws IOException {
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n"
                + fieldValue + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fileField
                + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n"
                + fileValue + "\r\n"
                + "--" + boundary + "--\r\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty(
                "Content-Type", "multipart/form-data; boundary=\"" + boundary + "\"");
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = input == null ? "" : read(input);
        return new Response(status, responseBody,
                connection.getHeaderField("X-TinySC-Filter"),
                connection.getHeaderField("X-TinySC-Forward-Filter"),
                connection.getHeaderField("X-TinySC-Error-Filter"),
                connection.getHeaderField("Set-Cookie"),
                connection.getHeaderField("Content-Length"),
                connection.getHeaderField("Last-Modified"));
    }

    private static String errorBody(
            String view, int status, String message, String requestUri,
            String exception, String exceptionType) {
        return "ERROR_VIEW=" + view
                + ";ERROR_DISPATCHER_TYPE=ERROR"
                + ";ERROR_STATUS_CODE=" + status
                + ";ERROR_MESSAGE=" + message
                + ";ERROR_REQUEST_URI=" + requestUri
                + ";ERROR_SERVLET_NAME=errorEntryServlet"
                + ";ERROR_EXCEPTION=" + exception
                + ";ERROR_EXCEPTION_TYPE=" + exceptionType;
    }

    private static void assertContentLengthMatches(Response response) {
        assertNotNull(response.contentLength);
        assertEquals(response.body.getBytes(StandardCharsets.UTF_8).length,
                Integer.parseInt(response.contentLength));
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

    private static String read(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Response {
        private final int status;
        private final String body;
        private final String filterHeader;
        private final String forwardFilterHeader;
        private final String errorFilterHeader;
        private final String sessionCookie;
        private final String contentLength;
        private final String lastModified;

        private Response(int status, String body, String filterHeader,
                         String forwardFilterHeader, String errorFilterHeader,
                         String sessionCookie, String contentLength, String lastModified) {
            this.status = status;
            this.body = body;
            this.filterHeader = filterHeader;
            this.forwardFilterHeader = forwardFilterHeader;
            this.errorFilterHeader = errorFilterHeader;
            this.sessionCookie = sessionCookie;
            this.contentLength = contentLength;
            this.lastModified = lastModified;
        }
    }
}
