package io.tinysc.http.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
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
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.CharsetUtil;
import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.ContainerRequest;
import io.tinysc.kernel.ContainerResponse;
import io.tinysc.kernel.NamedThreadFactory;
import io.tinysc.kernel.ServerConfig;
import io.tinysc.kernel.WebAppRuntime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NettyHttpConnector implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(NettyHttpConnector.class.getName());
    private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);
    private static final HttpDateCache HTTP_DATE_CACHE = new HttpDateCache();
    private static final DateHeaderHandler DATE_HEADER_HANDLER = new DateHeaderHandler();

    private final ServerConfig config;
    private final WebAppRuntime runtime;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong requestBodyTimeouts = new AtomicLong();
    private final AtomicLong responseWriteTimeouts = new AtomicLong();
    private EventLoopGroup acceptorGroup;
    private EventLoopGroup ioGroup;
    private BoundedElasticExecutor applicationExecutor;
    private RequestAdmissionController admissionController;
    private RawIngressController rawIngressController;
    private AccessLog accessLog;
    private Channel serverChannel;

    public NettyHttpConnector(ServerConfig config, WebAppRuntime runtime) {
        if (config == null || runtime == null) {
            throw new IllegalArgumentException("config and runtime are required");
        }
        this.config = config;
        this.runtime = runtime;
    }

    public synchronized int start() throws IOException, InterruptedException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("connector is already started");
        }
        try {
            accessLog = config.accessLogEnabled() ? AccessLog.open(config.baseDirectory()) : null;
            final AccessLog pipelineAccessLog = accessLog;
            acceptorGroup = new MultiThreadIoEventLoopGroup(
                    1, new NamedThreadFactory("tinysc-acceptor-", false),
                    NioIoHandler.newFactory());
            ioGroup = new MultiThreadIoEventLoopGroup(
                    config.ioThreads(),
                    new NamedThreadFactory("tinysc-io-", false), NioIoHandler.newFactory());
            rejectedRequests.set(0L);
            requestBodyTimeouts.set(0L);
            responseWriteTimeouts.set(0L);
            admissionController = new RequestAdmissionController(
                    config.maxConnections(), config.maxInflightRequests(),
                    config.maxInflightRequestBytes());
            rawIngressController = new RawIngressController(config.maxRawIngressBytes());
            accepting.set(true);
            applicationExecutor = new BoundedElasticExecutor(
                    config.workerMinThreads(), config.workerThreads(),
                    config.workerIdleTimeoutMillis(), config.workerQueueCapacity(),
                    new NamedThreadFactory("tinysc-worker-", false));
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
                            channel.pipeline().addLast("read-timeout",
                                    new PausableReadTimeoutHandler(
                                            config.requestReadTimeoutMillis(),
                                            TimeUnit.MILLISECONDS));
                            channel.pipeline().addLast("http-codec", new HttpServerCodec(decoderConfig));
                            channel.pipeline().addLast("date-header", DATE_HEADER_HANDLER);
                            if (pipelineAccessLog != null) {
                                channel.pipeline().addLast("access-log",
                                        new AccessLogHandler(pipelineAccessLog));
                            }
                            channel.pipeline().addLast("write-timeout",
                                    new ResponseWriteTimeoutHandler(
                                            config.responseWriteTimeoutMillis(),
                                            responseWriteTimeouts));
                            RawIngressAdmission rawIngress = new RawIngressAdmission(
                                    rawIngressController, config.maxRequestBodySize(),
                                    config.requestBodyTimeoutMillis(), requestBodyTimeouts);
                            channel.pipeline().addLast("raw-ingress", rawIngress);
                            channel.pipeline().addLast("http-aggregate",
                                    new HttpObjectAggregator(config.maxRequestBodySize(), true));
                            channel.pipeline().addLast("flow-control", new FlowControlHandler());
                            channel.pipeline().addLast("raw-ingress-release",
                                    rawIngress.releaseHandler());
                            channel.pipeline().addLast("request", new RequestHandler(rawIngress));
                        }
                    });
            ChannelFuture bound = bootstrap.bind(config.bindAddress(), config.port()).sync();
            serverChannel = bound.channel();
            return boundPort();
        } catch (IOException exception) {
            stopAfterFailedStart();
            throw exception;
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

    int workerPoolSize() {
        BoundedElasticExecutor executor = applicationExecutor;
        return executor == null ? 0 : executor.getPoolSize();
    }

    int activeWorkerCount() {
        BoundedElasticExecutor executor = applicationExecutor;
        return executor == null ? 0 : executor.getActiveCount();
    }

    int queuedRequestCount() {
        BoundedElasticExecutor executor = applicationExecutor;
        return executor == null ? 0 : executor.getQueue().size();
    }

    long rejectedRequestCount() {
        return rejectedRequests.get();
    }

    long activeConnectionCount() {
        RequestAdmissionController controller = admissionController;
        return controller == null ? 0L : controller.activeConnections();
    }

    long inflightRequestCount() {
        RequestAdmissionController controller = admissionController;
        return controller == null ? 0L : controller.activeRequests();
    }

    long inflightRequestBytes() {
        RequestAdmissionController controller = admissionController;
        return controller == null ? 0L : controller.activeRequestBytes();
    }

    long rejectedConnectionCount() {
        RequestAdmissionController controller = admissionController;
        return controller == null ? 0L : controller.rejectedConnections();
    }

    long rejectedInflightRequestCount() {
        RequestAdmissionController controller = admissionController;
        return controller == null ? 0L : controller.rejectedRequests();
    }

    long rejectedInflightRequestByteCount() {
        RequestAdmissionController controller = admissionController;
        return controller == null ? 0L : controller.rejectedRequestBytes();
    }

    long rawIngressReservationCount() {
        RawIngressController controller = rawIngressController;
        return controller == null ? 0L : controller.activeReservations();
    }

    long rawIngressByteCount() {
        RawIngressController controller = rawIngressController;
        return controller == null ? 0L : controller.reservedBytes();
    }

    long rejectedRawIngressCount() {
        RawIngressController controller = rawIngressController;
        return controller == null ? 0L : controller.rejectedReservations();
    }

    long requestBodyTimeoutCount() {
        return requestBodyTimeouts.get();
    }

    long responseWriteTimeoutCount() {
        return responseWriteTimeouts.get();
    }

    @Override
    public synchronized void close() {
        if (!started.getAndSet(false)) {
            return;
        }
        accepting.set(false);
        long graceMillis = config.shutdownGraceMillis();
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(graceMillis);
            serverChannel = null;
        }
        awaitInflightRequests(graceMillis);
        shutdownExecutor(applicationExecutor, graceMillis);
        applicationExecutor = null;
        shutdownEventLoop(ioGroup, graceMillis);
        ioGroup = null;
        shutdownEventLoop(acceptorGroup, graceMillis);
        acceptorGroup = null;
        admissionController = null;
        rawIngressController = null;
        closeAccessLog();
    }

    private void stopAfterFailedStart() {
        started.set(false);
        accepting.set(false);
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
        admissionController = null;
        rawIngressController = null;
        closeAccessLog();
    }

    private void closeAccessLog() {
        AccessLog current = accessLog;
        accessLog = null;
        if (current != null) {
            current.close();
        }
    }

    private void awaitInflightRequests(long graceMillis) {
        RequestAdmissionController controller = admissionController;
        if (controller == null) {
            return;
        }
        try {
            if (!controller.awaitNoRequests(graceMillis)) {
                LOGGER.warning("Shutdown grace expired with inflight requests"
                        + " requests=" + controller.activeRequests()
                        + " requestBytes=" + controller.activeRequestBytes());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
        private final RawIngressAdmission rawIngress;
        private RequestAdmissionController.ConnectionLease connectionLease;

        private RequestHandler(RawIngressAdmission rawIngress) {
            this.rawIngress = rawIngress;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) throws Exception {
            RequestAdmissionController controller = admissionController;
            if (!accepting.get() || controller == null) {
                context.close();
                return;
            }
            connectionLease = controller.tryAcquireConnection();
            if (connectionLease == null) {
                recordRejectedConnection();
                context.close();
                return;
            }
            super.channelActive(context);
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            if (connectionLease != null) {
                connectionLease.close();
                connectionLease = null;
            }
            super.channelInactive(context);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            final boolean keepAlive = HttpUtil.isKeepAlive(request);
            pauseReads(context);
            try {
                if (!accepting.get()) {
                    writeText(context, request.protocolVersion(),
                            HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "tinysc is stopping\n", false);
                    return;
                }
                validator.validate(request);
                RequestAdmissionController controller = admissionController;
                final RequestAdmissionController.RequestLease requestLease = controller == null
                        ? null : controller.tryAcquireRequest(request.content().readableBytes());
                if (requestLease == null) {
                    recordRejectedAdmission();
                    writeText(context, request.protocolVersion(),
                            HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "tinysc request capacity is full\n", false);
                    return;
                }
                final ContainerRequest containerRequest;
                try {
                    containerRequest = toContainerRequest(context, request);
                } catch (RuntimeException conversionFailure) {
                    requestLease.close();
                    throw conversionFailure;
                }
                final io.netty.handler.codec.http.HttpVersion protocolVersion =
                        request.protocolVersion();
                try {
                    applicationExecutor.execute(() -> service(
                            context, protocolVersion, containerRequest, keepAlive, requestLease,
                            rawIngress));
                } catch (RejectedExecutionException rejected) {
                    requestLease.close();
                    recordRejectedRequest();
                    writeText(context, protocolVersion, HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "tinysc worker queue is full\n", false);
                }
            } catch (RequestValidationException exception) {
                writeText(context, request.protocolVersion(), HttpResponseStatus.BAD_REQUEST,
                        exception.getMessage() + "\n", false);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            if (!context.channel().config().isAutoRead()) {
                rawIngress.deferCloseAfterCurrentResponse();
                return;
            }
            if (cause instanceof TooLongFrameException) {
                writeText(context, io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                        HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                        "request exceeds configured limit\n", false);
            } else {
                context.close();
            }
        }
    }

    private static void pauseReads(ChannelHandlerContext context) {
        context.channel().config().setAutoRead(false);
        if (context.pipeline().context("read-timeout") != null) {
            ChannelHandlerContext timeoutContext = context.pipeline().context("read-timeout");
            if (timeoutContext.handler() instanceof PausableReadTimeoutHandler) {
                ((PausableReadTimeoutHandler) timeoutContext.handler()).suspendTimeout();
            }
        }
    }

    private void resumeReads(ChannelHandlerContext context) {
        if (!context.channel().isActive()) {
            return;
        }
        if (context.pipeline().context("read-timeout") != null) {
            ChannelHandlerContext timeoutContext = context.pipeline().context("read-timeout");
            if (timeoutContext.handler() instanceof PausableReadTimeoutHandler) {
                ((PausableReadTimeoutHandler) timeoutContext.handler()).resumeTimeout();
            }
        }
        context.channel().config().setAutoRead(true);
    }

    private void recordRejectedRequest() {
        long rejected = rejectedRequests.incrementAndGet();
        if ((rejected & (rejected - 1L)) == 0L) {
            LOGGER.warning("Worker pool saturated"
                    + " rejected=" + rejected
                    + " active=" + activeWorkerCount()
                    + " pool=" + workerPoolSize()
                    + " queued=" + queuedRequestCount());
        }
    }

    private void recordRejectedConnection() {
        long rejected = rejectedConnectionCount();
        if ((rejected & (rejected - 1L)) == 0L) {
            LOGGER.warning("Connection limit reached"
                    + " rejected=" + rejected
                    + " active=" + activeConnectionCount()
                    + " limit=" + config.maxConnections());
        }
    }

    private void recordRejectedAdmission() {
        long requestRejected = rejectedInflightRequestCount();
        long byteRejected = rejectedInflightRequestByteCount();
        long rejected = requestRejected + byteRejected;
        if ((rejected & (rejected - 1L)) == 0L) {
            LOGGER.warning("Request admission limit reached"
                    + " rejectedRequests=" + requestRejected
                    + " rejectedBytes=" + byteRejected
                    + " activeRequests=" + inflightRequestCount()
                    + " activeRequestBytes=" + inflightRequestBytes());
        }
    }

    private void service(ChannelHandlerContext context,
                         io.netty.handler.codec.http.HttpVersion protocolVersion,
                         ContainerRequest request, boolean keepAlive,
                         RequestAdmissionController.RequestLease requestLease,
                         RawIngressAdmission rawIngress) {
        ContainerExchange exchange = new ContainerExchange(
                request, new ContainerResponse(), applicationExecutor);
        try {
            runtime.service(exchange);
            if (!exchange.deferred()) {
                exchange.complete();
                finishRequestSafely(context, protocolVersion, request, exchange.response(),
                        keepAlive, requestLease, rawIngress);
                return;
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
            finishRequestSafely(context, protocolVersion, request, exchange.response(),
                    keepAlive, requestLease, rawIngress);
            return;
        }
        exchange.completion().whenComplete((ignored, failure) -> {
            if (failure != null) {
                logApplicationFailure(request, failure);
                renderFailure(exchange.response());
            }
            finishRequest(context, protocolVersion, request, exchange.response(),
                    keepAlive, requestLease, rawIngress);
        });
    }

    private void finishRequestSafely(ChannelHandlerContext context,
                                     io.netty.handler.codec.http.HttpVersion protocolVersion,
                                     ContainerRequest request, ContainerResponse response,
                                     boolean keepAlive,
                                     RequestAdmissionController.RequestLease requestLease,
                                     RawIngressAdmission rawIngress) {
        try {
            finishRequest(context, protocolVersion, request, response,
                    keepAlive, requestLease, rawIngress);
        } catch (RuntimeException failure) {
            requestLease.close();
            context.close();
            LOGGER.log(Level.SEVERE,
                    "Response processing failed: " + request.method() + " " + request.path(),
                    failure);
        }
    }

    private void finishRequest(ChannelHandlerContext context,
                               io.netty.handler.codec.http.HttpVersion protocolVersion,
                               ContainerRequest request, ContainerResponse response,
                               boolean keepAlive,
                               RequestAdmissionController.RequestLease requestLease,
                               RawIngressAdmission rawIngress) {
        final ChannelFuture writeFuture;
        try {
            writeFuture = writeResponse(
                    context, protocolVersion, request.method(), response, keepAlive);
        } catch (RuntimeException writeFailure) {
            requestLease.close();
            context.close();
            throw writeFailure;
        }
        writeFuture.addListener(completed -> {
            requestLease.close();
            if (!completed.isSuccess() || !keepAlive || !accepting.get()
                    || rawIngress.mustCloseAfterCurrentResponse()) {
                context.close();
                return;
            }
            resumeReads(context);
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
        int bodyLength = request.content().readableBytes();
        if (bodyLength != 0) {
            builder.bodyBuffers(request.content().nioBuffers(
                    request.content().readerIndex(), bodyLength));
        }
        return builder.build();
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

    private static ChannelFuture writeResponse(ChannelHandlerContext context,
                                               io.netty.handler.codec.http.HttpVersion protocolVersion,
                                               String requestMethod, ContainerResponse source,
                                               boolean keepAlive) {
        ByteBuffer body = source.bodyBuffer();
        int bodyLength = body.remaining();
        HttpResponseStatus status = HttpResponseStatus.valueOf(source.status());
        FullHttpResponse response = new DefaultFullHttpResponse(
                protocolVersion, status,
                "HEAD".equals(requestMethod) ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        source.forEachHeader((name, value) -> response.headers().add(name, value));
        if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bodyLength);
        }
        if (keepAlive) {
            HttpUtil.setKeepAlive(response, true);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        source.commit();
        return context.writeAndFlush(response);
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

    @ChannelHandler.Sharable
    private static final class DateHeaderHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext context, Object message,
                          ChannelPromise promise) throws Exception {
            if (message instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) message;
                if (!response.headers().contains(HttpHeaderNames.DATE)) {
                    response.headers().set(HttpHeaderNames.DATE, HTTP_DATE_CACHE.current());
                }
            }
            context.write(message, promise);
        }
    }

    private static final class HttpDateCache {
        private volatile long epochSecond = Long.MIN_VALUE;
        private String value;

        private String current() {
            long currentSecond = System.currentTimeMillis() / 1000L;
            long cachedSecond = epochSecond;
            if (cachedSecond == currentSecond) {
                return value;
            }
            synchronized (this) {
                if (epochSecond != currentSecond) {
                    value = HTTP_DATE_FORMATTER.format(Instant.ofEpochSecond(currentSecond));
                    epochSecond = currentSecond;
                }
                return value;
            }
        }
    }
}
