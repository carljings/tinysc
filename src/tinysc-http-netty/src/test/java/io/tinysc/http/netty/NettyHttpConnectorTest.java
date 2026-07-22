package io.tinysc.http.netty;

import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.ServerConfig;
import io.tinysc.kernel.WebAppRuntime;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpConnectorTest {
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
        } finally {
            connector.close();
        }
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
    void serializesPipelinedRequestsOnSameChannel() throws Exception {
        DeferredRuntime runtime = new DeferredRuntime();
        NettyHttpConnector connector = new NettyHttpConnector(
                ServerConfig.builder().port(0).build(), runtime);
        try {
            int port = connector.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET /first HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        + "GET /second HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                ContainerExchange first = runtime.takeExchange();
                assertEquals("/first", first.request().rawUri());
                assertNull(runtime.pollExchange(150L));

                first.complete();
                RawHttpResponse firstResponse = readResponse(socket.getInputStream());
                assertEquals(200, firstResponse.status);
                assertEquals("/first", firstResponse.body);

                ContainerExchange second = runtime.takeExchange();
                assertEquals("/second", second.request().rawUri());
                second.complete();
                RawHttpResponse secondResponse = readResponse(socket.getInputStream());
                assertEquals(200, secondResponse.status);
                assertEquals("/second", secondResponse.body);
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
    void doesNotApplyReadTimeoutWhileDeferredRequestIsExecuting() throws Exception {
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
        @Override
        public void start() {
        }

        @Override
        public void service(ContainerExchange exchange) throws Exception {
            exchange.response().setHeader("Content-Type", "text/plain; charset=UTF-8");
            exchange.response().setHeader("X-Worker", Thread.currentThread().getName());
            exchange.response().bodyStream().write(
                    exchange.request().rawUri().getBytes(StandardCharsets.UTF_8));
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
