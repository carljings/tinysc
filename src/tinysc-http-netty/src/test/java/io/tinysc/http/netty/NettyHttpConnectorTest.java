package io.tinysc.http.netty;

import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.ServerConfig;
import io.tinysc.kernel.WebAppRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpConnectorTest {
    private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void servesOnBoundedApplicationWorker() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .workerThreads(2)
                .workerQueueCapacity(8)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, new EchoRuntime());
        try {
            int port = connector.start();
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/hello?name=tinysc").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            assertEquals(200, connection.getResponseCode());
            assertEquals("/hello?name=tinysc", readUtf8(connection.getInputStream()));
            assertTrue(connection.getHeaderField("X-Worker").startsWith("tinysc-worker-"));
            assertTrue(await(() -> connector.rawIngressReservationCount() == 0L, 2000L));
            assertEquals(0L, connector.rawIngressByteCount());
        } finally {
            connector.close();
        }
    }

    @Test
    void addsCurrentImfFixdateWhenApplicationDoesNotSetDate() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new EchoRuntime());
        long earliest = Instant.now().getEpochSecond() - 1L;
        try {
            int port = connector.start();
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/date").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("Connection", "close");

            assertEquals(200, connection.getResponseCode());
            String date = connection.getHeaderField("Date");
            assertTrue(date.matches(
                    "[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"),
                    date);
            long responseSecond = Instant.from(HTTP_DATE_FORMATTER.parse(date)).getEpochSecond();
            assertTrue(responseSecond >= earliest);
            assertTrue(responseSecond <= Instant.now().getEpochSecond() + 1L);
            readUtf8(connection.getInputStream());
            connection.disconnect();
        } finally {
            connector.close();
        }
    }

    @Test
    void preservesDateSetByApplication() throws Exception {
        String applicationDate = "Tue, 15 Nov 1994 08:12:31 GMT";
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new EchoRuntime(applicationDate));
        try {
            int port = connector.start();
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/date").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("Connection", "close");

            assertEquals(200, connection.getResponseCode());
            assertEquals(applicationDate, connection.getHeaderField("Date"));
            readUtf8(connection.getInputStream());
            connection.disconnect();
        } finally {
            connector.close();
        }
    }

    @Test
    void writesAccessLogForRealConnectorResponse() throws Exception {
        Path base = temporaryDirectory.resolve("access-log-base");
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .baseDirectory(base)
                .accessLogEnabled(true)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, new EchoRuntime());
        try {
            int port = connector.start();
            assertEquals(200, responseCode(port, "/logged?token=secret"));
        } finally {
            connector.close();
        }

        String access = new String(Files.readAllBytes(base.resolve("logs/access.log")),
                StandardCharsets.UTF_8);
        assertTrue(access.contains("method=GET"), access);
        assertTrue(access.contains("path=/logged"), access);
        assertTrue(access.contains("status=200"), access);
        assertTrue(access.contains("bytes=20"), access);
        assertTrue(access.contains("outcome=complete"), access);
        assertFalse(access.contains("token=secret"), access);
    }

    @Test
    void failsConnectorStartWhenAccessLogCannotBeOpened() throws Exception {
        Path base = temporaryDirectory.resolve("invalid-access-log-base");
        Files.createDirectories(base);
        Files.write(base.resolve("logs"), "not-a-directory".getBytes(StandardCharsets.UTF_8));
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder()
                        .port(0)
                        .baseDirectory(base)
                        .accessLogEnabled(true)
                        .build(),
                new EchoRuntime());

        assertThrows(IOException.class, connector::start);
        connector.close();
    }

    @Test
    void rejectsContentLengthTransferEncodingAmbiguity() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new EchoRuntime());
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                OutputStream output = socket.getOutputStream();
                output.write(("POST / HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Content-Length: 4\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n"
                        + "0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                assertTrue(reader.readLine().contains("400"));
            }
        } finally {
            connector.close();
        }
    }

    @Test
    void rejectsMalformedHostHeaderBeforeServletDispatch() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new EchoRuntime());
        try {
            int port = connector.start();
            assertEquals(400, rawResponseCode(port, "GET / HTTP/1.1\r\n"
                    + "Host: good.example bad.example\r\n"
                    + "Connection: close\r\n\r\n"));
        } finally {
            connector.close();
        }
    }

    @Test
    void rejectsAmbiguousRequestTargetsBeforeServletDispatch() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new EchoRuntime());
        try {
            int port = connector.start();
            assertEquals(400, rawResponseCode(port, "GET /path#fragment HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"));
            assertEquals(400, rawResponseCode(port, "GET * HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"));
            assertEquals(400, rawResponseCode(port, "GET /a\u0001b HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"));
            assertEquals(400, rawResponseCode(port, "GET /a\u007fb HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"));
            assertEquals(200, rawResponseCode(port,
                    "GET /path?value=a%2Fb HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"));
            assertEquals(200, rawResponseCode(port, "OPTIONS * HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"));
        } finally {
            connector.close();
        }
    }

    @Test
    void rejectsDuplicateContentLengthHeaders() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new EchoRuntime());
        try {
            int port = connector.start();
            assertEquals(400, rawResponseCode(port, "POST / HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Content-Length: 4\r\n"
                    + "Content-Length: 4\r\n"
                    + "Connection: close\r\n\r\ntest"));
        } finally {
            connector.close();
        }
    }

    @Test
    void completesDeferredExchangeFinishedBeforeListenerRegistration() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(),
                new ImmediateDeferredRuntime(null));
        try {
            int port = connector.start();

            assertEquals(200, responseCode(port, "/completed-before-listener"));
            assertTrue(await(() -> connector.inflightRequestCount() == 0L, 2000L));
        } finally {
            connector.close();
        }
    }

    @Test
    void failsDeferredExchangeFinishedBeforeListenerRegistration() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(),
                new ImmediateDeferredRuntime(new IllegalStateException("before listener")));
        try {
            int port = connector.start();

            assertEquals(500, responseCode(port, "/failed-before-listener"));
            assertTrue(await(() -> connector.inflightRequestCount() == 0L, 2000L));
        } finally {
            connector.close();
        }
    }

    @Test
    void completesDeferredExchangeAfterListenerRegistration() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), runtime);
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            int port = connector.start();
            Future<RawHttpResponse> response = client.submit(
                    () -> getResponse(port, "/completed-after-listener"));
            ContainerExchange exchange = runtime.takeExchange();
            CompletableFuture<Void> completion = exchange.completion();
            assertTrue(await(() -> completion.getNumberOfDependents() > 0, 2000L));

            exchange.complete();

            RawHttpResponse completed = response.get(2, TimeUnit.SECONDS);
            assertEquals(200, completed.status);
            assertEquals("/completed-after-listener", completed.body);
            assertTrue(await(() -> connector.inflightRequestCount() == 0L, 2000L));
        } finally {
            runtime.completeAll();
            client.shutdownNow();
            connector.close();
        }
    }

    @Test
    void preservesHeadContentLengthWithoutSendingBody() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new EchoRuntime());
        try {
            int port = connector.start();
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/head-check").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("HEAD");
            connection.setRequestProperty("Connection", "close");

            assertEquals(200, connection.getResponseCode());
            assertEquals(String.valueOf("/head-check".getBytes(StandardCharsets.UTF_8).length),
                    connection.getHeaderField("Content-Length"));
            assertEquals("", readUtf8(connection.getInputStream()));
            connection.disconnect();
        } finally {
            connector.close();
        }
    }

    @Test
    void failsDeferredExchangeAfterListenerRegistration() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), runtime);
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            int port = connector.start();
            Future<Integer> response = client.submit(
                    () -> responseCode(port, "/failed-after-listener"));
            ContainerExchange exchange = runtime.takeExchange();
            CompletableFuture<Void> completion = exchange.completion();
            assertTrue(await(() -> completion.getNumberOfDependents() > 0, 2000L));

            exchange.fail(new IllegalStateException("after listener"));

            assertEquals(500, response.get(2, TimeUnit.SECONDS).intValue());
            assertTrue(await(() -> connector.inflightRequestCount() == 0L, 2000L));
        } finally {
            runtime.completeAll();
            client.shutdownNow();
            connector.close();
        }
    }

    @Test
    void rendersSynchronousServiceFailureAndReleasesAdmission() throws Exception {
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), new FailingRuntime());
        try {
            int port = connector.start();

            assertEquals(500, responseCode(port, "/synchronous-failure"));
            assertTrue(await(() -> connector.inflightRequestCount() == 0L, 2000L));
            assertTrue(await(() -> connector.rawIngressReservationCount() == 0L, 2000L));
        } finally {
            connector.close();
        }
    }

    @Test
    void boundsInflightRequestsAcrossDeferredExchangesAndRecovers() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .workerMinThreads(1)
                .workerThreads(1)
                .workerIdleTimeoutMillis(50L)
                .workerQueueCapacity(1)
                .build();
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(config, runtime);
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            int port = connector.start();
            Future<Integer> first = clients.submit(() -> responseCode(port, "/block"));
            Future<Integer> second = clients.submit(() -> responseCode(port, "/block"));
            ContainerExchange firstExchange = runtime.takeExchange();
            ContainerExchange secondExchange = runtime.takeExchange();
            assertEquals(2L, connector.inflightRequestCount());

            assertEquals(503, responseCode(port, "/overload"));
            assertEquals(1L, connector.rejectedInflightRequestCount());

            firstExchange.complete();
            secondExchange.complete();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).intValue());
            assertEquals(200, second.get(2, TimeUnit.SECONDS).intValue());
            assertTrue(await(() -> connector.inflightRequestCount() == 0L, 2000L));

            Future<Integer> recovered = clients.submit(
                    () -> responseCode(port, "/after-overload"));
            runtime.takeExchange().complete();
            assertEquals(200, recovered.get(2, TimeUnit.SECONDS).intValue());
            assertTrue(await(() -> connector.workerPoolSize() == 1, 2000L));
        } finally {
            runtime.completeAll();
            clients.shutdownNow();
            connector.close();
        }
    }

    @Test
    void boundsInflightRequestBytesAndRecovers() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .maxRequestBodySize(8)
                .maxInflightRequestBytes(10L)
                .workerThreads(2)
                .workerQueueCapacity(2)
                .build();
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(config, runtime);
        ExecutorService clients = Executors.newSingleThreadExecutor();
        try {
            int port = connector.start();
            Future<Integer> first = clients.submit(
                    () -> postResponseCode(port, "/first", "123456"));
            ContainerExchange firstExchange = runtime.takeExchange();
            assertEquals(6L, connector.inflightRequestBytes());

            assertEquals(503, postResponseCode(port, "/overflow", "12345"));
            assertEquals(1L, connector.rejectedInflightRequestByteCount());
            assertEquals(6L, connector.inflightRequestBytes());

            firstExchange.complete();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).intValue());
            assertTrue(await(() -> connector.inflightRequestBytes() == 0L, 2000L));

            Future<Integer> recovered = clients.submit(
                    () -> postResponseCode(port, "/recovered", "12345"));
            runtime.takeExchange().complete();
            assertEquals(200, recovered.get(2, TimeUnit.SECONDS).intValue());
        } finally {
            runtime.completeAll();
            clients.shutdownNow();
            connector.close();
        }
    }

    @Test
    void reservesOnlyReceivedFixedLengthBodyAndRecoversAfterDisconnect() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .maxRequestBodySize(8)
                .maxRawIngressBytes(10L)
                .maxInflightRequestBytes(10L)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, new EchoRuntime());
        try {
            int port = connector.start();
            try (Socket first = new Socket("127.0.0.1", port)) {
                first.setSoTimeout(3000);
                writeAscii(first, "POST /first HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Content-Length: 8\r\n"
                        + "Connection: keep-alive\r\n\r\n");
                assertTrue(await(() -> connector.rawIngressReservationCount() == 1L, 2000L));
                assertEquals(0L, connector.rawIngressByteCount());

                writeAscii(first, "123456");
                assertTrue(await(() -> connector.rawIngressByteCount() == 6L, 2000L));

                try (Socket excess = new Socket("127.0.0.1", port)) {
                    excess.setSoTimeout(3000);
                    writeAscii(excess, "POST /excess HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Content-Length: 5\r\n"
                            + "Connection: close\r\n\r\n12345");
                    assertEquals(503, readResponse(excess.getInputStream()).status);
                }
                assertEquals(1L, connector.rejectedRawIngressCount());
            }

            assertTrue(await(() -> connector.rawIngressByteCount() == 0L, 2000L));
            assertTrue(await(() -> connector.rawIngressReservationCount() == 0L, 2000L));
            assertEquals(200, postResponseCode(port, "/recovered", "12345"));
        } finally {
            connector.close();
        }
    }

    @Test
    void boundsChunkedBodyBeforeAggregationAndRecovers() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .maxRequestBodySize(8)
                .maxRawIngressBytes(10L)
                .maxInflightRequestBytes(10L)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, new EchoRuntime());
        try {
            int port = connector.start();
            try (Socket first = new Socket("127.0.0.1", port)) {
                first.setSoTimeout(3000);
                writeAscii(first, "POST /first HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "6\r\n123456\r\n");
                assertTrue(await(() -> connector.rawIngressByteCount() == 6L, 2000L));

                try (Socket excess = new Socket("127.0.0.1", port)) {
                    excess.setSoTimeout(3000);
                    writeAscii(excess, "POST /excess HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n\r\n"
                            + "5\r\n12345\r\n");
                    assertEquals(503, readResponse(excess.getInputStream()).status);
                }
                assertEquals(1L, connector.rejectedRawIngressCount());
            }

            assertTrue(await(() -> connector.rawIngressByteCount() == 0L, 2000L));
            assertEquals(200, rawResponseCode(port, "POST /recovered HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n"
                    + "5\r\n12345\r\n0\r\n\r\n"));
        } finally {
            connector.close();
        }
    }

    @Test
    void rejectsChunkedBodyAbovePerRequestLimitBeforeRawCapacity() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .maxRequestBodySize(8)
                .maxRawIngressBytes(16L)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, new EchoRuntime());
        try {
            int port = connector.start();
            assertEquals(413, rawResponseCode(port, "POST /oversized HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n"
                    + "9\r\n123456789\r\n0\r\n\r\n"));
            assertEquals(0L, connector.rejectedRawIngressCount());
            assertTrue(await(() -> connector.rawIngressByteCount() == 0L, 2000L));
        } finally {
            connector.close();
        }
    }

    @Test
    void timesOutTrickledRequestBodyByTotalDeadline() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .maxRequestBodySize(8)
                .maxRawIngressBytes(16L)
                .requestReadTimeoutMillis(1000L)
                .requestBodyTimeoutMillis(200L)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, new EchoRuntime());
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                writeAscii(socket, "POST /slow HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Content-Length: 4\r\n"
                        + "Connection: close\r\n\r\n1");
                Thread.sleep(50L);
                writeAscii(socket, "2");

                assertEquals(408, readResponse(socket.getInputStream()).status);
            }
            assertTrue(await(() -> connector.rawIngressByteCount() == 0L, 2000L));
            assertEquals(1L, connector.requestBodyTimeoutCount());
        } finally {
            connector.close();
        }
    }

    @Test
    void closesConnectionsAboveLimitAndRecoversAfterRelease() throws Exception {
        ServerConfig config = ServerConfig.builder().port(0).maxConnections(1).build();
        NettyHttpConnector connector = new NettyHttpConnector(config, new EchoRuntime());
        try {
            int port = connector.start();
            try (Socket first = new Socket("127.0.0.1", port)) {
                assertTrue(await(() -> connector.activeConnectionCount() == 1L, 2000L));
                try (Socket excess = new Socket("127.0.0.1", port)) {
                    excess.setSoTimeout(2000);
                    assertEquals(-1, excess.getInputStream().read());
                }
                assertEquals(1L, connector.rejectedConnectionCount());
            }
            assertTrue(await(() -> connector.activeConnectionCount() == 0L, 2000L));
            assertEquals(200, responseCode(port, "/recovered"));
        } finally {
            connector.close();
        }
    }

    @Test
    void serializesThreePipelinedRequestsAndReleasesLeases() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), runtime);
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET /one HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        + "GET /two HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        + "GET /three HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                ContainerExchange one = runtime.takeExchange();
                assertEquals("/one", one.request().rawUri());
                assertNull(runtime.pollExchange(150L));

                one.complete();
                RawHttpResponse oneResponse = readResponse(socket.getInputStream());
                assertEquals(200, oneResponse.status);
                assertEquals("/one", oneResponse.body);

                ContainerExchange two = runtime.takeExchange();
                assertEquals("/two", two.request().rawUri());
                assertNull(runtime.pollExchange(150L));

                two.complete();
                RawHttpResponse twoResponse = readResponse(socket.getInputStream());
                assertEquals(200, twoResponse.status);
                assertEquals("/two", twoResponse.body);

                ContainerExchange three = runtime.takeExchange();
                assertEquals("/three", three.request().rawUri());
                three.complete();
                RawHttpResponse threeResponse = readResponse(socket.getInputStream());
                assertEquals(200, threeResponse.status);
                assertEquals("/three", threeResponse.body);
            }
            assertTrue(await(() -> connector.inflightRequestCount() == 0L, 2000L));
            assertEquals(0L, connector.inflightRequestBytes());
            assertTrue(await(() -> connector.rawIngressReservationCount() == 0L, 2000L));
            assertEquals(0L, connector.rawIngressByteCount());
        } finally {
            runtime.completeAll();
            connector.close();
        }
    }

    @Test
    void finishesCurrentResponseBeforeClosingForPipelinedBodyTimeout() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .maxRequestBodySize(8)
                .maxRawIngressBytes(16L)
                .requestReadTimeoutMillis(1000L)
                .requestBodyTimeoutMillis(100L)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, runtime);
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                writeAscii(socket, "GET /first HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        + "POST /slow HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Content-Length: 4\r\n"
                        + "Connection: close\r\n\r\n1");

                ContainerExchange first = runtime.takeExchange();
                assertEquals("/first", first.request().rawUri());
                assertTrue(await(() -> connector.requestBodyTimeoutCount() == 1L, 2000L));
                assertEquals(1L, connector.rawIngressByteCount());

                first.complete();
                RawHttpResponse firstResponse = readResponse(socket.getInputStream());
                assertEquals(200, firstResponse.status);
                assertEquals("/first", firstResponse.body);
                assertEquals(-1, socket.getInputStream().read());
            }
            assertTrue(await(() -> connector.rawIngressReservationCount() == 0L, 2000L));
            assertEquals(0L, connector.rawIngressByteCount());
        } finally {
            runtime.completeAll();
            connector.close();
        }
    }

    @Test
    void preservesCurrentResponseBeforePipelinedOversizedHeaderFailure() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .maxHeaderSize(128)
                .build();
        NettyHttpConnector connector = new NettyHttpConnector(config, runtime);
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                writeAscii(socket, "GET /first HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        + "GET /oversized HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "X-Large: " + repeated('a', 256) + "\r\n"
                        + "Connection: close\r\n\r\n");

                ContainerExchange first = runtime.takeExchange();
                assertEquals("/first", first.request().rawUri());
                first.complete();

                RawHttpResponse firstResponse = readResponse(socket.getInputStream());
                assertEquals(200, firstResponse.status);
                assertEquals("/first", firstResponse.body);
                assertNull(runtime.pollExchange(150L));
            }
        } finally {
            runtime.completeAll();
            connector.close();
        }
    }

    @Test
    void doesNotEmitPipelinedContinueBeforeCurrentResponse() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), runtime);
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                writeAscii(socket, "GET /first HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        + "POST /expect HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Content-Length: 4\r\n"
                        + "Expect: 100-continue\r\n"
                        + "Connection: close\r\n\r\n");

                ContainerExchange first = runtime.takeExchange();
                assertEquals("/first", first.request().rawUri());
                first.complete();

                RawHttpResponse firstResponse = readResponse(socket.getInputStream());
                assertEquals(200, firstResponse.status);
                assertEquals("/first", firstResponse.body);
                assertEquals(-1, socket.getInputStream().read());
                assertNull(runtime.pollExchange(150L));
            }
        } finally {
            runtime.completeAll();
            connector.close();
        }
    }

    @Test
    void waitsForDeferredExchangeBeforeStoppingTransport() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).shutdownGraceMillis(2000L).build(), runtime);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET /deferred HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                ContainerExchange exchange = runtime.takeExchange();

                Future<?> closing = closer.submit(() -> connector.close());
                Thread.sleep(100L);
                assertFalse(closing.isDone());

                exchange.complete();
                assertEquals(200, readResponse(socket.getInputStream()).status);
                closing.get(2, TimeUnit.SECONDS);
            }
        } finally {
            runtime.completeAll();
            closer.shutdownNow();
            connector.close();
        }
    }

    @Test
    void restartsReadTimeoutAfterDeferredRequestCompletes() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder()
                        .port(0)
                        .requestReadTimeoutMillis(50L)
                        .build(), runtime);
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET /slow HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                ContainerExchange exchange = runtime.takeExchange();
                Thread.sleep(150L);
                exchange.complete();

                RawHttpResponse response = readResponse(socket.getInputStream());
                assertEquals(200, response.status);
                assertEquals("/slow", response.body);

                socket.getOutputStream().write(("GET /next HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                ContainerExchange next = runtime.takeExchange();
                next.complete();
                RawHttpResponse nextResponse = readResponse(socket.getInputStream());
                assertEquals(200, nextResponse.status);
                assertEquals("/next", nextResponse.body);
                assertEquals(-1, socket.getInputStream().read());
            }
        } finally {
            runtime.completeAll();
            connector.close();
        }
    }

    private static String readUtf8(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int responseCode(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Connection", "close");
        return consumeResponse(connection);
    }

    private static RawHttpResponse getResponse(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Connection", "close");
        try {
            int status = connection.getResponseCode();
            return new RawHttpResponse(status, readUtf8(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static int postResponseCode(int port, String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Connection", "close");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.getOutputStream().write(bytes);
        return consumeResponse(connection);
    }

    private static int rawResponseCode(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            writeAscii(socket, request);
            return readResponse(socket.getInputStream()).status;
        }
    }

    private static void writeAscii(Socket socket, String value) throws Exception {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String repeated(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static int consumeResponse(HttpURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            InputStream response = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (response != null) {
                readUtf8(response);
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    private static RawHttpResponse readResponse(InputStream input) throws Exception {
        String statusLine = readAsciiLine(input);
        if (statusLine == null) {
            throw new IllegalStateException("connection closed before HTTP status");
        }
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        int contentLength = -1;
        String line;
        while ((line = readAsciiLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon))) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) {
            throw new IllegalStateException("response has no Content-Length");
        }
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < body.length) {
            int read = input.read(body, offset, body.length - offset);
            if (read < 0) {
                throw new IllegalStateException("connection closed before response body");
            }
            offset += read;
        }
        return new RawHttpResponse(status, new String(body, StandardCharsets.UTF_8));
    }

    private static String readAsciiLine(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) {
                return output.size() == 0 ? null
                        : new String(output.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (next == '\n') {
                return new String(output.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (next != '\r') {
                output.write(next);
            }
        }
    }

    private static boolean await(BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return condition.getAsBoolean();
    }

    private static final class EchoRuntime implements WebAppRuntime {
        private final String responseDate;

        private EchoRuntime() {
            this(null);
        }

        private EchoRuntime(String responseDate) {
            this.responseDate = responseDate;
        }

        @Override
        public void start() {
        }

        @Override
        public void service(ContainerExchange exchange) throws Exception {
            exchange.response().setHeader("Content-Type", "text/plain; charset=UTF-8");
            exchange.response().setHeader("X-Worker", Thread.currentThread().getName());
            if (responseDate != null) {
                exchange.response().setHeader("Date", responseDate);
            }
            exchange.response().bodyStream().write(
                    exchange.request().rawUri().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void stop(Duration gracePeriod) {
        }
    }

    private static final class ImmediateDeferredRuntime implements WebAppRuntime {
        private final Throwable failure;

        private ImmediateDeferredRuntime(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void start() {
        }

        @Override
        public void service(ContainerExchange exchange) throws Exception {
            exchange.defer();
            exchange.response().bodyStream().write(
                    exchange.request().rawUri().getBytes(StandardCharsets.UTF_8));
            if (failure == null) {
                exchange.complete();
            } else {
                exchange.fail(failure);
            }
        }

        @Override
        public void stop(Duration gracePeriod) {
        }
    }

    private static final class FailingRuntime implements WebAppRuntime {
        @Override
        public void start() {
        }

        @Override
        public void service(ContainerExchange exchange) throws Exception {
            throw new IOException("synchronous failure");
        }

        @Override
        public void stop(Duration gracePeriod) {
        }
    }

    private static final class DeferredRuntime implements WebAppRuntime {
        private final BlockingQueue<ContainerExchange> exchanges =
                new LinkedBlockingQueue<ContainerExchange>();

        @Override
        public void start() {
        }

        @Override
        public void service(ContainerExchange exchange) throws Exception {
            exchange.response().setHeader("Content-Type", "text/plain; charset=UTF-8");
            exchange.response().bodyStream().write(
                    exchange.request().rawUri().getBytes(StandardCharsets.UTF_8));
            exchange.defer();
            exchanges.add(exchange);
        }

        private ContainerExchange takeExchange() throws Exception {
            ContainerExchange exchange = exchanges.poll(2, TimeUnit.SECONDS);
            if (exchange == null) {
                throw new IllegalStateException("request did not reach runtime");
            }
            return exchange;
        }

        private ContainerExchange pollExchange(long timeoutMillis) throws InterruptedException {
            return exchanges.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void completeAll() {
            ContainerExchange exchange;
            while ((exchange = exchanges.poll()) != null) {
                exchange.complete();
            }
        }

        @Override
        public void stop(Duration gracePeriod) {
            completeAll();
        }
    }

    private static final class RawHttpResponse {
        private final int status;
        private final String body;

        private RawHttpResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
