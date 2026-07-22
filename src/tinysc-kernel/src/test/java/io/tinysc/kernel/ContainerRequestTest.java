package io.tinysc.kernel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerRequestTest {
    @Test
    void usesLoopbackDefaultsForRemoteAndLocalAddresses() {
        ContainerRequest request = ContainerRequest.builder()
                .method("GET")
                .rawUri("/")
                .path("/")
                .build();

        InetSocketAddress loopback = new InetSocketAddress("127.0.0.1", 0);
        assertEquals(loopback, request.remoteAddress());
        assertEquals(loopback, request.localAddress());
    }

    @Test
    void snapshotsHeadersWhenBuilderIsReused() {
        ContainerRequest.Builder builder = ContainerRequest.builder()
                .method("GET")
                .rawUri("/")
                .path("/")
                .addHeader("X-Test", "first");
        ContainerRequest first = builder.build();

        builder.addHeader("x-test", "second");
        ContainerRequest second = builder.build();

        assertEquals(Arrays.asList("first"), first.headerValues("X-TEST"));
        assertEquals(Arrays.asList("first", "second"), second.headerValues("x-test"));
    }

    @Test
    void buildsLowercaseDeeplyImmutableHeaderViewOnDemand() throws Exception {
        ContainerRequest request = ContainerRequest.builder()
                .method("GET")
                .rawUri("/")
                .path("/")
                .addHeader("X-Test", "first")
                .addHeader("X-Other", "middle")
                .addHeader("x-TEST", "second")
                .build();

        assertNull(headersView(request));
        assertEquals("first", request.firstHeader("x-test"));
        assertEquals(Arrays.asList("first", "second"), request.headerValues("X-TeSt"));
        assertNull(headersView(request));

        Map<String, List<String>> headers = request.headers();

        assertSame(headers, headersView(request));
        assertSame(headers, request.headers());
        assertEquals(Arrays.asList("x-test", "x-other"), new ArrayList<String>(headers.keySet()));
        assertEquals(Arrays.asList("first", "second"), headers.get("x-test"));
        assertEquals(Collections.singletonList("middle"), headers.get("x-other"));
        assertThrows(UnsupportedOperationException.class,
                () -> headers.put("x-new", Collections.singletonList("value")));
        assertThrows(UnsupportedOperationException.class,
                () -> headers.get("x-test").add("third"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.headerValues("x-test").add("third"));
    }

    @Test
    void validatesHeaderNameAndValueWhenAdded() {
        ContainerRequest.Builder builder = ContainerRequest.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.addHeader(null, "value"));
        assertThrows(IllegalArgumentException.class, () -> builder.addHeader("", "value"));
        assertThrows(NullPointerException.class, () -> builder.addHeader("X-Test", null));
    }

    @Test
    void keepsRequestBodyImmutableWhenInputsAndReturnedCopiesChange() {
        byte[] source = new byte[]{1, 2, 3};
        ContainerRequest request = ContainerRequest.builder()
                .method("POST")
                .rawUri("/")
                .path("/")
                .body(source)
                .build();

        source[0] = 9;
        byte[] returned = request.bodyBytes();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, request.bodyBytes());
    }

    @Test
    void copiesRemainingByteBufferWithoutChangingItsPosition() {
        byte[] source = new byte[]{1, 2, 3};
        ByteBuffer buffer = ByteBuffer.wrap(source);
        buffer.position(1);

        ContainerRequest request = ContainerRequest.builder()
                .method("POST")
                .rawUri("/")
                .path("/")
                .bodyBuffers(new ByteBuffer[]{buffer})
                .build();
        source[1] = 9;

        assertEquals(1, buffer.position());
        assertArrayEquals(new byte[]{2, 3}, request.bodyBytes());
    }

    private Object headersView(ContainerRequest request) throws Exception {
        Field field = ContainerRequest.class.getDeclaredField("headersView");
        field.setAccessible(true);
        return field.get(request);
    }
}
