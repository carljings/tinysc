package io.tinysc.http.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;

final class AccessLogHandler extends ChannelDuplexHandler {
    private final AccessLog accessLog;
    private final Deque<AccessLog.Event> requests = new ArrayDeque<AccessLog.Event>();
    private AccessLog.Event streamingResponse;
    private String remote;

    AccessLogHandler(AccessLog accessLog) {
        if (accessLog == null) {
            throw new IllegalArgumentException("accessLog is required");
        }
        this.accessLog = accessLog;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) message;
            requests.addLast(new AccessLog.Event(
                    System.currentTimeMillis(),
                    System.nanoTime(),
                    remote(context),
                    request.method().name(),
                    pathWithoutQuery(request.uri()),
                    request.protocolVersion().text(),
                    HttpUtil.isKeepAlive(request)));
        }
        context.fireChannelRead(message);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message,
                      ChannelPromise promise) throws Exception {
        AccessLog.Event completing = null;
        if (message instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) message;
            if (response.status().code() >= 200) {
                if (streamingResponse != null) {
                    complete(streamingResponse, "write_failed");
                }
                AccessLog.Event event = nextRequest();
                event.begin(response.status().code());
                if (message instanceof HttpContent) {
                    event.addBytes(((HttpContent) message).content().readableBytes());
                }
                if (message instanceof LastHttpContent) {
                    completing = event;
                    streamingResponse = null;
                } else {
                    streamingResponse = event;
                    promise.addListener(completed -> {
                        if (!completed.isSuccess()) {
                            clearStreamingResponse(event);
                            complete(event, "write_failed");
                        }
                    });
                }
            }
        } else if (streamingResponse != null && message instanceof HttpContent) {
            streamingResponse.addBytes(((HttpContent) message).content().readableBytes());
            if (message instanceof LastHttpContent) {
                completing = streamingResponse;
                streamingResponse = null;
            }
        }

        if (completing != null) {
            AccessLog.Event event = completing;
            promise.addListener(completed -> complete(event,
                    completed.isSuccess() ? "complete" : "write_failed"));
        }
        context.write(message, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (streamingResponse != null) {
            complete(streamingResponse, "write_failed");
            streamingResponse = null;
        }
        AccessLog.Event request;
        while ((request = requests.pollFirst()) != null) {
            complete(request, 0, "aborted");
        }
        context.fireChannelInactive();
    }

    private AccessLog.Event nextRequest() {
        AccessLog.Event event = requests.pollFirst();
        return event == null ? missingRequest() : event;
    }

    private void complete(AccessLog.Event event, String outcome) {
        if (event.complete(outcome)) {
            accessLog.offer(event);
        }
    }

    private void complete(AccessLog.Event event, int status, String outcome) {
        if (event.complete(status, outcome)) {
            accessLog.offer(event);
        }
    }

    private void clearStreamingResponse(AccessLog.Event event) {
        if (streamingResponse == event) {
            streamingResponse = null;
        }
    }

    private static AccessLog.Event missingRequest() {
        return new AccessLog.Event(System.currentTimeMillis(), System.nanoTime(),
                "-", "-", "-", "-", false);
    }

    private String remote(ChannelHandlerContext context) {
        if (remote == null) {
            remote = remoteAddress(context.channel().remoteAddress());
        }
        return remote;
    }

    private static String remoteAddress(SocketAddress value) {
        if (!(value instanceof InetSocketAddress)) {
            return value == null ? "-" : value.toString();
        }
        InetSocketAddress address = (InetSocketAddress) value;
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return address.getHostString();
    }

    private static String pathWithoutQuery(String uri) {
        if (uri == null) {
            return "-";
        }
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        return path.isEmpty() ? "-" : path;
    }
}
