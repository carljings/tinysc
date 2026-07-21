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
}
