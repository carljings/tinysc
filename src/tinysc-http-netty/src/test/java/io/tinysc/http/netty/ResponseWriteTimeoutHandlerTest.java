package io.tinysc.http.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseWriteTimeoutHandlerTest {
    @Test
    void closesChannelAndCountsTimedOutWrite() {
        AtomicLong timeouts = new AtomicLong();
        RequestAdmissionController admission = new RequestAdmissionController(1L, 1L, 1L);
        RequestAdmissionController.RequestLease lease = admission.tryAcquireRequest(1L);
        ExceptionRecorder exceptions = new ExceptionRecorder();
        EmbeddedChannel channel = new EmbeddedChannel(
                new StalledWriteHandler(),
                new ResponseWriteTimeoutHandler(25L, timeouts),
                exceptions);
        try {
            ChannelFuture write = channel.writeAndFlush(
                    Unpooled.wrappedBuffer(new byte[]{1}));
            write.addListener(ignored -> lease.close());
            assertFalse(write.isDone());

            channel.advanceTimeBy(26L, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();

            assertTrue(write.isDone());
            assertFalse(write.isSuccess());
            assertFalse(channel.isOpen());
            assertEquals(1L, timeouts.get());
            assertEquals(0L, admission.activeRequests());
            assertEquals(0L, admission.activeRequestBytes());
            assertSame(WriteTimeoutException.INSTANCE, exceptions.cause);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class StalledWriteHandler extends ChannelOutboundHandlerAdapter {
        private ChannelPromise pendingWrite;

        @Override
        public void write(ChannelHandlerContext context, Object message,
                          ChannelPromise promise) {
            ReferenceCountUtil.release(message);
            pendingWrite = promise;
        }

        @Override
        public void close(ChannelHandlerContext context, ChannelPromise promise) {
            if (pendingWrite != null) {
                pendingWrite.tryFailure(WriteTimeoutException.INSTANCE);
                pendingWrite = null;
            }
            context.close(promise);
        }
    }

    private static final class ExceptionRecorder extends ChannelInboundHandlerAdapter {
        private Throwable cause;

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            this.cause = cause;
        }
    }
}
