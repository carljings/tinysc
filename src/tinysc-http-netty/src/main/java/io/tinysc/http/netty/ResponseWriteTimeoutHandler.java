package io.tinysc.http.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.WriteTimeoutHandler;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class ResponseWriteTimeoutHandler extends WriteTimeoutHandler {
    private static final Logger LOGGER = Logger.getLogger(
            ResponseWriteTimeoutHandler.class.getName());

    private final AtomicLong timeoutCount;

    ResponseWriteTimeoutHandler(long timeoutMillis, AtomicLong timeoutCount) {
        super(timeoutMillis, TimeUnit.MILLISECONDS);
        if (timeoutCount == null) {
            throw new IllegalArgumentException("timeoutCount is required");
        }
        this.timeoutCount = timeoutCount;
    }

    @Override
    protected void writeTimedOut(ChannelHandlerContext context) throws Exception {
        long count = timeoutCount.incrementAndGet();
        if ((count & (count - 1L)) == 0L) {
            LOGGER.warning("Response write timed out count=" + count);
        }
        super.writeTimedOut(context);
    }
}
