package io.tinysc.http.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawIngressAdmissionTest {
    @Test
    void releasesEmptyGetAfterAggregationWithoutReservingBytes() {
        RawIngressController controller = new RawIngressController(10L);
        RawIngressAdmission admission = new RawIngressAdmission(
                controller, 10, 1000L, new AtomicLong());
        EmbeddedChannel channel = newAdmissionChannel(admission, 10);
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

            assertFalse(channel.writeInbound(request));
            assertEquals(1L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());

            assertTrue(channel.writeInbound(new DefaultLastHttpContent()));
            FullHttpRequest aggregated = channel.readInbound();
            assertNotNull(aggregated);
            assertEquals(0, aggregated.content().readableBytes());
            aggregated.release();
            assertEquals(0L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void reservesKnownLengthBodyAsContentArrivesAndReleasesAfterAggregation() {
        RawIngressController controller = new RawIngressController(10L);
        RawIngressAdmission admission = new RawIngressAdmission(
                controller, 10, 1000L, new AtomicLong());
        EmbeddedChannel channel = newAdmissionChannel(admission, 10);
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 10);

            assertFalse(channel.writeInbound(request));
            assertEquals(1L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());

            assertFalse(channel.writeInbound(new DefaultHttpContent(
                    Unpooled.wrappedBuffer(new byte[3]))));
            assertEquals(3L, controller.reservedBytes());

            assertTrue(channel.writeInbound(new DefaultLastHttpContent(
                    Unpooled.wrappedBuffer(new byte[2]))));
            FullHttpRequest aggregated = channel.readInbound();
            assertNotNull(aggregated);
            assertEquals(5, aggregated.content().readableBytes());
            aggregated.release();
            assertEquals(0L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void releasesReceivedBytesWhenIncrementalReservationFails() {
        RawIngressController controller = new RawIngressController(4L);
        RawIngressAdmission admission = new RawIngressAdmission(
                controller, 10, 1000L, new AtomicLong());
        EmbeddedChannel channel = new EmbeddedChannel(admission);
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 8);

            assertTrue(channel.writeInbound(request));
            ReferenceCountUtil.release(channel.readInbound());
            assertEquals(1L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());

            assertTrue(channel.writeInbound(new DefaultHttpContent(
                    Unpooled.wrappedBuffer(new byte[4]))));
            ReferenceCountUtil.release(channel.readInbound());
            assertEquals(4L, controller.reservedBytes());

            assertFalse(channel.writeInbound(new DefaultHttpContent(
                    Unpooled.wrappedBuffer(new byte[1]))));
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
            response.release();
            assertEquals(1L, controller.rejectedReservations());
            assertEquals(0L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsOversizedDeclaredBodyBeforeReservingCapacity() {
        RawIngressController controller = new RawIngressController(10L);
        RawIngressAdmission admission = new RawIngressAdmission(
                controller, 8, 1000L, new AtomicLong());
        EmbeddedChannel channel = newAdmissionChannel(admission, 8);
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 9);

            assertFalse(channel.writeInbound(request));
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, response.status());
            response.release();
            assertEquals(0L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void releasesReceivedBytesOnClientDisconnect() {
        RawIngressController controller = new RawIngressController(10L);
        RawIngressAdmission admission = new RawIngressAdmission(
                controller, 10, 1000L, new AtomicLong());
        EmbeddedChannel channel = new EmbeddedChannel(admission);
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 10);

            assertTrue(channel.writeInbound(request));
            ReferenceCountUtil.release(channel.readInbound());
            assertTrue(channel.writeInbound(new DefaultHttpContent(
                    Unpooled.wrappedBuffer(new byte[3]))));
            ReferenceCountUtil.release(channel.readInbound());
            assertEquals(3L, controller.reservedBytes());

            channel.close().syncUninterruptibly();
            assertEquals(0L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void releasesReceivedBytesOnBodyTimeout() {
        RawIngressController controller = new RawIngressController(10L);
        AtomicLong bodyTimeoutCount = new AtomicLong();
        RawIngressAdmission admission = new RawIngressAdmission(
                controller, 10, 1000L, bodyTimeoutCount);
        EmbeddedChannel channel = new EmbeddedChannel(admission);
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 10);

            assertTrue(channel.writeInbound(request));
            ReferenceCountUtil.release(channel.readInbound());
            assertTrue(channel.writeInbound(new DefaultHttpContent(
                    Unpooled.wrappedBuffer(new byte[3]))));
            ReferenceCountUtil.release(channel.readInbound());
            assertEquals(3L, controller.reservedBytes());

            channel.advanceTimeBy(1000L, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            assertEquals(HttpResponseStatus.REQUEST_TIMEOUT, response.status());
            response.release();
            assertEquals(1L, bodyTimeoutCount.get());
            assertEquals(0L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void matchesLeaseWhenDecoderFailureAggregatesBeforeLastContent() {
        RawIngressController controller = new RawIngressController(16L);
        RawIngressAdmission admission = new RawIngressAdmission(
                controller, 8, 1000L, new AtomicLong());
        EmbeddedChannel channel = newAdmissionChannel(admission, 8);
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            request.setDecoderResult(DecoderResult.failure(
                    new IllegalArgumentException("malformed request")));

            assertTrue(channel.writeInbound(request));
            FullHttpRequest aggregated = channel.readInbound();
            assertNotNull(aggregated);
            assertFalse(aggregated.decoderResult().isSuccess());
            aggregated.release();
            assertEquals(0L, controller.activeReservations());
            assertEquals(0L, controller.reservedBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel newAdmissionChannel(RawIngressAdmission admission,
                                                       int maxRequestBodySize) {
        return new EmbeddedChannel(
                admission,
                new HttpObjectAggregator(maxRequestBodySize, true),
                new FlowControlHandler(),
                admission.releaseHandler());
    }
}
