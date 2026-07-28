package io.tinysc.http.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessLogHandlerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void logsCompletedResponseWithoutSensitiveRequestData() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(new AccessLogHandler(log));
        try {
            DefaultHttpRequest request = request("/hello?token=secret");
            request.headers().set(HttpHeaderNames.COOKIE, "session=secret-cookie");
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer secret-auth");
            passInbound(channel, request);

            writeResponse(channel, HttpResponseStatus.OK, "hello");
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        String line = onlyLine(log);
        assertTrue(line.contains("method=GET"), line);
        assertTrue(line.contains("path=/hello"), line);
        assertTrue(line.contains("proto=HTTP/1.1"), line);
        assertTrue(line.contains("status=200"), line);
        assertTrue(line.contains("bytes=5"), line);
        assertTrue(line.contains("ka=1"), line);
        assertTrue(line.contains("outcome=complete"), line);
        assertFalse(line.contains("token="), line);
        assertFalse(line.contains("secret-cookie"), line);
        assertFalse(line.contains("secret-auth"), line);
    }

    @Test
    void logsPipelineRejectionStatus() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(new AccessLogHandler(log));
        try {
            passInbound(channel, request("/busy"));
            writeResponse(channel, HttpResponseStatus.SERVICE_UNAVAILABLE, "full");
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        String line = onlyLine(log);
        assertTrue(line.contains("path=/busy"), line);
        assertTrue(line.contains("status=503"), line);
        assertTrue(line.contains("bytes=4"), line);
        assertTrue(line.contains("outcome=complete"), line);
    }

    @Test
    void ignoresInformationalResponseUntilFinalResponse() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(new AccessLogHandler(log));
        try {
            passInbound(channel, request("/continue"));
            writeResponse(channel, HttpResponseStatus.CONTINUE, "");
            writeResponse(channel, HttpResponseStatus.OK, "done");
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        List<String> lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("status=200"), lines.get(0));
        assertTrue(lines.get(0).contains("path=/continue"), lines.get(0));
    }

    @Test
    void logsStreamingResponseOnlyAfterLastContent() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(new AccessLogHandler(log));
        try {
            passInbound(channel, request("/stream"));

            DefaultHttpResponse headers = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            assertTrue(channel.writeOutbound(headers));
            ReferenceCountUtil.release(channel.readOutbound());

            DefaultHttpContent first = new DefaultHttpContent(
                    Unpooled.copiedBuffer("ab", StandardCharsets.UTF_8));
            assertTrue(channel.writeOutbound(first));
            ReferenceCountUtil.release(channel.readOutbound());

            DefaultLastHttpContent last = new DefaultLastHttpContent(
                    Unpooled.copiedBuffer("cd", StandardCharsets.UTF_8));
            assertTrue(channel.writeOutbound(last));
            ReferenceCountUtil.release(channel.readOutbound());
            channel.runPendingTasks();
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        String line = onlyLine(log);
        assertTrue(line.contains("path=/stream"), line);
        assertTrue(line.contains("bytes=4"), line);
        assertTrue(line.contains("outcome=complete"), line);
    }

    @Test
    void logsPipelinedResponsesInRequestOrder() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(new AccessLogHandler(log));
        try {
            passInbound(channel, request("/first"));
            passInbound(channel, request("/second"));
            writeResponse(channel, HttpResponseStatus.CREATED, "one");
            writeResponse(channel, HttpResponseStatus.ACCEPTED, "two");
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        List<String> lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("path=/first"), lines.get(0));
        assertTrue(lines.get(0).contains("status=201"), lines.get(0));
        assertTrue(lines.get(1).contains("path=/second"), lines.get(1));
        assertTrue(lines.get(1).contains("status=202"), lines.get(1));
    }

    @Test
    void logsAbortedRequestAndSanitizesPathControlCharacters() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(new AccessLogHandler(log));
        try {
            passInbound(channel, request("/safe path\r\nInjected?token=secret"));
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        List<String> lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains("path=/safe_path__Injected"), line);
        assertTrue(line.contains("status=0"), line);
        assertTrue(line.contains("bytes=0"), line);
        assertTrue(line.contains("outcome=aborted"), line);
        assertFalse(line.contains("token="), line);
    }

    @Test
    void logsWriteFailureAfterResponsePromiseFails() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(
                new FailingWriteHandler(), new AccessLogHandler(log));
        try {
            passInbound(channel, request("/write-failure"));
            FullHttpResponse response = response(HttpResponseStatus.OK, "body");
            ChannelPromise promise = channel.newPromise();
            channel.pipeline().writeAndFlush(response, promise);
            promise.awaitUninterruptibly();
            assertFalse(promise.isSuccess());
            channel.runPendingTasks();
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        String line = onlyLine(log);
        assertTrue(line.contains("status=200"), line);
        assertTrue(line.contains("bytes=4"), line);
        assertTrue(line.contains("outcome=write_failed"), line);
    }

    @Test
    void headerWriteFailureDoesNotLetLaterChunksMutatePublishedEvent() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(
                new FailFirstWriteHandler(), new AccessLogHandler(log));
        try {
            passInbound(channel, request("/stream-failure"));

            ChannelPromise headerPromise = channel.newPromise();
            channel.pipeline().writeAndFlush(new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK), headerPromise);
            headerPromise.awaitUninterruptibly();
            assertFalse(headerPromise.isSuccess());
            channel.runPendingTasks();

            DefaultHttpContent chunk = new DefaultHttpContent(
                    Unpooled.copiedBuffer("ab", StandardCharsets.UTF_8));
            assertTrue(channel.writeOutbound(chunk));
            ReferenceCountUtil.release(channel.readOutbound());

            DefaultLastHttpContent last = new DefaultLastHttpContent(
                    Unpooled.copiedBuffer("cd", StandardCharsets.UTF_8));
            assertTrue(channel.writeOutbound(last));
            ReferenceCountUtil.release(channel.readOutbound());
            channel.runPendingTasks();
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        List<String> lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains("path=/stream-failure"), line);
        assertTrue(line.contains("bytes=0"), line);
        assertTrue(line.contains("outcome=write_failed"), line);
    }

    @Test
    void logsResponseWithoutRequestMetadataUsingPlaceholders() throws Exception {
        AccessLog log = AccessLog.open(temporaryDirectory);
        EmbeddedChannel channel = new EmbeddedChannel(new AccessLogHandler(log));
        try {
            writeResponse(channel, HttpResponseStatus.BAD_REQUEST, "bad");
        } finally {
            channel.finishAndReleaseAll();
            log.close();
        }

        String line = onlyLine(log);
        assertTrue(line.contains("remote=- method=- path=- proto=-"), line);
        assertTrue(line.contains("status=400"), line);
    }

    private static DefaultHttpRequest request(String uri) {
        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/") {
            @Override
            public String uri() {
                return uri;
            }
        };
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        return request;
    }

    private static FullHttpResponse response(HttpResponseStatus status, String body) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
    }

    private static void passInbound(EmbeddedChannel channel, DefaultHttpRequest request) {
        assertTrue(channel.writeInbound(request));
        ReferenceCountUtil.release(channel.readInbound());
    }

    private static void writeResponse(EmbeddedChannel channel,
                                      HttpResponseStatus status, String body) {
        assertTrue(channel.writeOutbound(response(status, body)));
        ReferenceCountUtil.release(channel.readOutbound());
        channel.runPendingTasks();
    }

    private static String onlyLine(AccessLog log) throws IOException {
        List<String> lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        return lines.get(0);
    }

    private static final class FailingWriteHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext context, Object message,
                          ChannelPromise promise) {
            ReferenceCountUtil.release(message);
            promise.setFailure(new IOException("simulated write failure"));
        }
    }

    private static final class FailFirstWriteHandler extends ChannelOutboundHandlerAdapter {
        private boolean failed;

        @Override
        public void write(ChannelHandlerContext context, Object message,
                          ChannelPromise promise) {
            if (!failed) {
                failed = true;
                ReferenceCountUtil.release(message);
                promise.setFailure(new IOException("simulated first write failure"));
                return;
            }
            context.write(message, promise);
        }
    }
}
