package io.tinysc.http.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class RawIngressAdmission extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = Logger.getLogger(RawIngressAdmission.class.getName());

    private final RawIngressController controller;
    private final int maxRequestBodySize;
    private final long requestBodyTimeoutMillis;
    private final AtomicLong bodyTimeoutCount;
    private final Deque<RawIngressController.Lease> completed =
            new ArrayDeque<RawIngressController.Lease>();
    private final ChannelHandler releaseHandler = new ReleaseHandler();
    private RawIngressController.Lease current;
    private ScheduledFuture<?> bodyTimeout;
    private HttpVersion protocolVersion = HttpVersion.HTTP_1_1;
    private long receivedBodyBytes;
    private boolean rejecting;
    private volatile boolean closeAfterCurrentResponse;

    RawIngressAdmission(RawIngressController controller, int maxRequestBodySize,
                        long requestBodyTimeoutMillis, AtomicLong bodyTimeoutCount) {
        if (controller == null || bodyTimeoutCount == null) {
            throw new IllegalArgumentException("controller and bodyTimeoutCount are required");
        }
        if (maxRequestBodySize <= 0 || requestBodyTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("request limits must be positive");
        }
        this.controller = controller;
        this.maxRequestBodySize = maxRequestBodySize;
        this.requestBodyTimeoutMillis = requestBodyTimeoutMillis;
        this.bodyTimeoutCount = bodyTimeoutCount;
    }

    ChannelHandler releaseHandler() {
        return releaseHandler;
    }

    boolean mustCloseAfterCurrentResponse() {
        return closeAfterCurrentResponse;
    }

    void deferCloseAfterCurrentResponse() {
        rejecting = true;
        cancelBodyTimeout();
        closeAfterCurrentResponse = true;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (rejecting) {
            ReferenceCountUtil.release(message);
            return;
        }
        boolean forwarded = false;
        try {
            if (message instanceof HttpRequest) {
                beginRequest(context, (HttpRequest) message);
            }
            if (!rejecting && message instanceof HttpContent) {
                acceptContent(context, (HttpContent) message);
            }
            if (rejecting) {
                return;
            }
            forwarded = true;
            context.fireChannelRead(message);
        } finally {
            if (!forwarded) {
                ReferenceCountUtil.release(message);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        try {
            super.channelInactive(context);
        } finally {
            cleanup();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) throws Exception {
        try {
            super.handlerRemoved(context);
        } finally {
            cleanup();
        }
    }

    private void beginRequest(ChannelHandlerContext context, HttpRequest request) {
        if (current != null) {
            reject(context, HttpResponseStatus.BAD_REQUEST,
                    "request framing is invalid\n", false);
            return;
        }
        protocolVersion = request.protocolVersion();
        if (!context.channel().config().isAutoRead()
                && request.headers().contains(HttpHeaderNames.EXPECT)) {
            reject(context, HttpResponseStatus.EXPECTATION_FAILED,
                    "pipelined expectation is not supported\n", false);
            return;
        }
        boolean chunked = HttpUtil.isTransferEncodingChunked(request);
        if (chunked && HttpUtil.isContentLengthSet(request)) {
            reject(context, HttpResponseStatus.BAD_REQUEST,
                    "request must not contain both Content-Length and Transfer-Encoding\n",
                    false);
            return;
        }
        long contentLength = chunked ? -1L : contentLength(request);
        if (contentLength > maxRequestBodySize) {
            reject(context, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "request exceeds configured limit\n", false);
            return;
        }
        receivedBodyBytes = 0L;
        current = controller.tryAcquire(0L);
        if (current == null) {
            recordAdmissionRejection();
            reject(context, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "tinysc raw ingress capacity is full\n", false);
            return;
        }
        if (!request.decoderResult().isSuccess() && !(request instanceof HttpContent)) {
            completeCurrent();
            return;
        }
        if (chunked || contentLength > 0L) {
            bodyTimeout = context.executor().schedule(
                    () -> onBodyTimeout(context),
                    requestBodyTimeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void acceptContent(ChannelHandlerContext context, HttpContent content) {
        if (current == null) {
            reject(context, HttpResponseStatus.BAD_REQUEST,
                    "request framing is invalid\n", false);
            return;
        }
        int bytes = content.content().readableBytes();
        if (bytes > maxRequestBodySize - receivedBodyBytes) {
            reject(context, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "request exceeds configured limit\n", false);
            return;
        }
        if (bytes != 0 && !current.tryReserve(bytes)) {
            recordAdmissionRejection();
            reject(context, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "tinysc raw ingress capacity is full\n", false);
            return;
        }
        receivedBodyBytes += bytes;
        if (content instanceof LastHttpContent) {
            completeCurrent();
        }
    }

    private void onBodyTimeout(ChannelHandlerContext context) {
        if (current == null || rejecting) {
            return;
        }
        long count = bodyTimeoutCount.incrementAndGet();
        if ((count & (count - 1L)) == 0L) {
            LOGGER.warning("Request body deadline exceeded count=" + count);
        }
        reject(context, HttpResponseStatus.REQUEST_TIMEOUT,
                "request body deadline exceeded\n", true);
    }

    private void reject(ChannelHandlerContext context, HttpResponseStatus status,
                        String message, boolean timeoutTaskIsRunning) {
        boolean canRespondInOrder = context.channel().config().isAutoRead();
        rejecting = true;
        context.channel().config().setAutoRead(false);
        if (!timeoutTaskIsRunning) {
            cancelBodyTimeout();
        } else {
            bodyTimeout = null;
        }
        if (canRespondInOrder) {
            writeText(context, protocolVersion, status, message);
        } else {
            closeAfterCurrentResponse = true;
        }
    }

    private void recordAdmissionRejection() {
        long count = controller.rejectedReservations();
        if ((count & (count - 1L)) == 0L) {
            LOGGER.warning("Raw ingress limit reached"
                    + " rejected=" + count
                    + " reservations=" + controller.activeReservations()
                    + " bytes=" + controller.reservedBytes());
        }
    }

    private void cleanup() {
        cancelBodyTimeout();
        closeCurrent();
        closeCompleted();
    }

    private void completeCurrent() {
        cancelBodyTimeout();
        completed.addLast(current);
        current = null;
        receivedBodyBytes = 0L;
    }

    private void closeCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
        receivedBodyBytes = 0L;
    }

    private void closeCompleted() {
        RawIngressController.Lease lease;
        while ((lease = completed.pollFirst()) != null) {
            lease.close();
        }
    }

    private void cancelBodyTimeout() {
        if (bodyTimeout != null) {
            bodyTimeout.cancel(false);
            bodyTimeout = null;
        }
    }

    private static long contentLength(HttpRequest request) {
        try {
            return HttpUtil.getContentLength(request, -1L);
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    private static void writeText(ChannelHandlerContext context, HttpVersion protocolVersion,
                                  HttpResponseStatus status, String message) {
        byte[] body = message.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                protocolVersion, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ChannelFuture future = context.writeAndFlush(response);
        future.addListener(ChannelFutureListener.CLOSE);
    }

    private final class ReleaseHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof FullHttpRequest)) {
                context.fireChannelRead(message);
                return;
            }
            RawIngressController.Lease lease = completed.pollFirst();
            if (lease == null) {
                ReferenceCountUtil.release(message);
                context.close();
                return;
            }
            try {
                context.fireChannelRead(message);
            } finally {
                lease.close();
            }
        }
    }
}
