package io.tinysc.http.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.CharsetUtil;
import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.ContainerRequest;
import io.tinysc.kernel.ContainerResponse;
import io.tinysc.kernel.NamedThreadFactory;
import io.tinysc.kernel.ServerConfig;
import io.tinysc.kernel.WebAppRuntime;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NettyHttpConnector implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(NettyHttpConnector.class.getName());

    private final ServerConfig config;
    private final WebAppRuntime runtime;
    private final AtomicBoolean started = new AtomicBoolean();
    private EventLoopGroup acceptorGroup;
    private EventLoopGroup ioGroup;
    private ThreadPoolExecutor applicationExecutor;
    private Channel serverChannel;

    public NettyHttpConnector(ServerConfig config, WebAppRuntime runtime) {
        if (config == null || runtime == null) {
            throw new IllegalArgumentException("config and runtime are required");
        }
        this.config = config;
        this.runtime = runtime;
    }

    public synchronized int start() throws InterruptedException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("connector is already started");
        }
        acceptorGroup = new MultiThreadIoEventLoopGroup(
                1, new NamedThreadFactory("tinysc-acceptor-", false), NioIoHandler.newFactory());
        ioGroup = new MultiThreadIoEventLoopGroup(
                config.ioThreads(),
                new NamedThreadFactory("tinysc-io-", false), NioIoHandler.newFactory());
        applicationExecutor = new ThreadPoolExecutor(
                config.workerThreads(), config.workerThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(config.workerQueueCapacity()),
                new NamedThreadFactory("tinysc-worker-", false),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            HttpDecoderConfig decoderConfig = new HttpDecoderConfig()
                    .setMaxInitialLineLength(config.maxInitialLineLength())
                    .setMaxHeaderSize(config.maxHeaderSize())
                    .setMaxChunkSize(8192)
                    .setValidateHeaders(true)
                    .setAllowDuplicateContentLengths(false);
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(acceptorGroup, ioGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 256)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast("read-timeout", new ReadTimeoutHandler(30));
                            channel.pipeline().addLast("http-codec", new HttpServerCodec(decoderConfig));
                            channel.pipeline().addLast("http-aggregate",
                                    new HttpObjectAggregator(config.maxRequestBodySize(), true));
                            channel.pipeline().addLast("request", new RequestHandler());
                        }
                    });
            ChannelFuture bound = bootstrap.bind(config.bindAddress(), config.port()).sync();
            serverChannel = bound.channel();
            return boundPort();
        } catch (RuntimeException exception) {
            stopAfterFailedStart();
            throw exception;
        } catch (InterruptedException exception) {
            stopAfterFailedStart();
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    public int boundPort() {
        Channel channel = serverChannel;
        if (channel == null) {
            throw new IllegalStateException("connector is not bound");
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public void await() throws InterruptedException {
        Channel channel = serverChannel;
        if (channel == null) {
            throw new IllegalStateException("connector is not started");
        }
        channel.closeFuture().sync();
    }

    @Override
    public synchronized void close() {
        if (!started.getAndSet(false)) {
            return;
        }
        long graceMillis = config.shutdownGraceMillis();
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(graceMillis);
            serverChannel = null;
        }
        shutdownExecutor(applicationExecutor, graceMillis);
        applicationExecutor = null;
        shutdownEventLoop(ioGroup, graceMillis);
        ioGroup = null;
        shutdownEventLoop(acceptorGroup, graceMillis);
        acceptorGroup = null;
    }

    private void stopAfterFailedStart() {
        started.set(false);
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
            serverChannel = null;
        }
        shutdownExecutor(applicationExecutor, 0L);
        applicationExecutor = null;
        shutdownEventLoop(ioGroup, 0L);
        ioGroup = null;
        shutdownEventLoop(acceptorGroup, 0L);
        acceptorGroup = null;
    }

    private static void shutdownExecutor(ThreadPoolExecutor executor, long graceMillis) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdownEventLoop(EventLoopGroup group, long graceMillis) {
        if (group != null) {
            group.shutdownGracefully(0L, Math.max(1L, graceMillis), TimeUnit.MILLISECONDS)
                    .awaitUninterruptibly();
        }
    }

    private final class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final StrictRequestValidator validator = new StrictRequestValidator();

        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            final boolean keepAlive = HttpUtil.isKeepAlive(request);
            try {
                validator.validate(request);
                final ContainerRequest containerRequest = toContainerRequest(context, request);
                context.channel().config().setAutoRead(false);
                try {
                    applicationExecutor.execute(() -> service(
                            context, request.protocolVersion(), containerRequest, keepAlive));
                } catch (RejectedExecutionException rejected) {
                    context.channel().config().setAutoRead(true);
                    writeText(context, request.protocolVersion(), HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "tinysc worker queue is full\n", false);
                }
            } catch (RequestValidationException exception) {
                writeText(context, request.protocolVersion(), HttpResponseStatus.BAD_REQUEST,
                        exception.getMessage() + "\n", false);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            if (cause instanceof TooLongFrameException) {
                writeText(context, io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                        HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                        "request exceeds configured limit\n", false);
            } else {
                context.close();
            }
        }
    }

    private void service(ChannelHandlerContext context,
                         io.netty.handler.codec.http.HttpVersion protocolVersion,
                         ContainerRequest request, boolean keepAlive) {
        ContainerExchange exchange = new ContainerExchange(
                request, new ContainerResponse(), applicationExecutor);
        try {
            runtime.service(exchange);
            if (!exchange.deferred()) {
                exchange.complete();
            }
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError) {
                exchange.fail(failure);
                throw (VirtualMachineError) failure;
            }
            if (failure instanceof ThreadDeath) {
                exchange.fail(failure);
                throw (ThreadDeath) failure;
            }
            logApplicationFailure(request, failure);
            renderFailure(exchange.response());
            exchange.complete();
        }
        exchange.completion().whenComplete((ignored, failure) -> {
            if (failure != null) {
                logApplicationFailure(request, failure);
                renderFailure(exchange.response());
            }
            context.executor().execute(() -> {
                writeResponse(context, protocolVersion, request.method(),
                        exchange.response(), keepAlive);
                context.channel().config().setAutoRead(true);
                context.read();
            });
        });
    }

    private static void logApplicationFailure(ContainerRequest request, Throwable failure) {
        LOGGER.log(Level.SEVERE,
                "Application request failed: " + request.method() + " " + request.path(),
                failure);
    }

    private static void renderFailure(ContainerResponse response) {
        if (response.committed()) {
            return;
        }
        response.reset();
        response.status(500);
        response.setHeader("Content-Type", "text/plain; charset=UTF-8");
        try {
            response.bodyStream().write("Internal Server Error\n"
                    .getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ContainerRequest toContainerRequest(ChannelHandlerContext context,
                                                       FullHttpRequest request) {
        String uri = request.uri();
        int queryStart = uri.indexOf('?');
        String path = queryStart < 0 ? uri : uri.substring(0, queryStart);
        String query = queryStart < 0 ? null : uri.substring(queryStart + 1);
        InetSocketAddress local = (InetSocketAddress) context.channel().localAddress();
        InetSocketAddress remote = (InetSocketAddress) context.channel().remoteAddress();
        Host host = parseHost(request.headers().get(HttpHeaderNames.HOST), local);
        ContainerRequest.Builder builder = ContainerRequest.builder()
                .method(request.method().name())
                .rawUri(uri)
                .path(path)
                .query(query)
                .protocol(request.protocolVersion().text())
                .scheme("http")
                .serverName(host.name)
                .serverPort(host.port)
                .remoteAddress(remote)
                .localAddress(local);
        for (Map.Entry<String, String> header : request.headers()) {
            builder.addHeader(header.getKey(), header.getValue());
        }
        byte[] body = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), body);
        return builder.body(body).build();
    }

    private static Host parseHost(String value, InetSocketAddress fallback) {
        if (value == null || value.isEmpty()) {
            return new Host(fallback.getHostString(), fallback.getPort());
        }
        if (value.charAt(0) == '[') {
            int closing = value.indexOf(']');
            if (closing > 0) {
                String name = value.substring(1, closing);
                int port = closing + 1 < value.length() && value.charAt(closing + 1) == ':'
                        ? parsePort(value.substring(closing + 2), fallback.getPort())
                        : fallback.getPort();
                return new Host(name, port);
            }
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return new Host(value.substring(0, firstColon),
                    parsePort(value.substring(firstColon + 1), fallback.getPort()));
        }
        return new Host(value, fallback.getPort());
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void writeResponse(ChannelHandlerContext context,
                                      io.netty.handler.codec.http.HttpVersion protocolVersion,
                                      String requestMethod, ContainerResponse source,
                                      boolean keepAlive) {
        byte[] body = source.bodyBytes();
        byte[] transmittedBody = "HEAD".equals(requestMethod) ? new byte[0] : body;
        HttpResponseStatus status = HttpResponseStatus.valueOf(source.status());
        FullHttpResponse response = new DefaultFullHttpResponse(
                protocolVersion, status, Unpooled.wrappedBuffer(transmittedBody));
        for (Map.Entry<String, List<String>> header : source.headers().entrySet()) {
            for (String value : header.getValue()) {
                response.headers().add(header.getKey(), value);
            }
        }
        if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        }
        if (keepAlive) {
            HttpUtil.setKeepAlive(response, true);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        source.commit();
        ChannelFuture future = context.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }
    }

    private static void writeText(ChannelHandlerContext context,
                                  io.netty.handler.codec.http.HttpVersion protocolVersion,
                                  HttpResponseStatus status, String message, boolean keepAlive) {
        byte[] body = message.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                protocolVersion, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        if (!keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        ChannelFuture future = context.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }
    }

    private static final class Host {
        private final String name;
        private final int port;

        private Host(String name, int port) {
            this.name = name;
            this.port = port;
        }
    }
}
