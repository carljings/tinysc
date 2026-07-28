package io.tinysc.http.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.TimeUnit;

final class PausableReadTimeoutHandler extends ReadTimeoutHandler {
    private volatile boolean suspended;

    PausableReadTimeoutHandler(long timeout, TimeUnit unit) {
        super(timeout, unit);
    }

    void suspendTimeout() {
        suspended = true;
    }

    void resumeTimeout() {
        suspended = false;
        resetReadTimeout();
    }

    @Override
    protected void readTimedOut(ChannelHandlerContext context) throws Exception {
        if (suspended) {
            return;
        }
        super.readTimedOut(context);
    }
}
