package io.tinysc.integration;

import io.tinysc.kernel.ServerConfig;
import io.tinysc.launcher.TinyScServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

            Response forwardedStatic = get(port, "/probe/static-forward", null);
            assertEquals(200, forwardedStatic.status);
            assertEquals("applied", forwardedStatic.filterHeader);
            assertEquals("applied", forwardedStatic.forwardFilterHeader);
            assertTrue(forwardedStatic.body.contains("tinysc static resource ok"));

            Response jarStatic = get(port, "/probe/jar-resource.html", null);
            assertEquals(200, jarStatic.status);
            assertEquals("applied", jarStatic.filterHeader);
            assertTrue(jarStatic.body.contains("tinysc resource JAR ok"));

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
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = input == null ? "" : read(input);
        return new Response(status, body, connection.getHeaderField("X-TinySC-Filter"),
                connection.getHeaderField("X-TinySC-Forward-Filter"),
                connection.getHeaderField("Set-Cookie"),
                connection.getHeaderField("Content-Length"));
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
        private final String sessionCookie;
        private final String contentLength;

        private Response(int status, String body, String filterHeader,
                         String forwardFilterHeader, String sessionCookie, String contentLength) {
            this.status = status;
            this.body = body;
            this.filterHeader = filterHeader;
            this.forwardFilterHeader = forwardFilterHeader;
            this.sessionCookie = sessionCookie;
            this.contentLength = contentLength;
        }
    }
}
