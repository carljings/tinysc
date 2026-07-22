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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsAndRecoversAfterElasticWorkerPoolSaturates() throws Exception {
        ServerConfig config = ServerConfig.builder()
                .port(0)
                .workerMinThreads(1)
                .workerThreads(2)
                .workerIdleTimeoutMillis(50L)
                .workerQueueCapacity(1)
                .build();
        BlockingRuntime runtime = new BlockingRuntime(2);
        NettyHttpConnector connector = new NettyHttpConnector(config, runtime);
        ExecutorService clients = Executors.newFixedThreadPool(3);
        try {
            int port = connector.start();
            Future<Integer> first = clients.submit(() -> responseCode(port, "/block"));
            Future<Integer> second = clients.submit(() -> responseCode(port, "/block"));
            assertTrue(runtime.awaitEntered(2, TimeUnit.SECONDS));

            Future<Integer> queued = clients.submit(() -> responseCode(port, "/block"));
            assertTrue(await(() -> connector.queuedRequestCount() == 1, 2000L));

            assertEquals(503, responseCode(port, "/overload"));
            assertEquals(1L, connector.rejectedRequestCount());
            assertEquals(2, connector.activeWorkerCount());

            runtime.release();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).intValue());
            assertEquals(200, second.get(2, TimeUnit.SECONDS).intValue());
            assertEquals(200, queued.get(2, TimeUnit.SECONDS).intValue());
            assertEquals(200, responseCode(port, "/after-overload"));
            assertTrue(await(() -> connector.workerPoolSize() == 1, 2000L));
        } finally {
            runtime.release();
            clients.shutdownNow();
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

    private static final class BlockingRuntime implements WebAppRuntime {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingRuntime(int blockedRequests) {
            entered = new CountDownLatch(blockedRequests);
        }

        @Override
        public void start() {
        }

        @Override
        public void service(ContainerExchange exchange) throws Exception {
            if ("/block".equals(exchange.request().path())) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test request did not release");
                }
            }
            exchange.response().setHeader("Content-Type", "text/plain; charset=UTF-8");
            exchange.response().bodyStream().write("ok".getBytes(StandardCharsets.UTF_8));
        }

        private boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        private void release() {
            release.countDown();
        }

        @Override
        public void stop(Duration gracePeriod) {
            release();
        }
    }
}
