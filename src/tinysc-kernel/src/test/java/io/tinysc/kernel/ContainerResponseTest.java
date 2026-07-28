package io.tinysc.kernel;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerResponseTest {
    @Test
    void rejectsResponseSplitting() {
        ContainerResponse response = new ContainerResponse();

        assertThrows(IllegalArgumentException.class,
                () -> response.setHeader("X-Test", "safe\r\nInjected: true"));
    }

    @Test
    void treatsHeaderNamesAsCaseInsensitive() {
        ContainerResponse response = new ContainerResponse();
        response.setHeader("Content-Type", "text/plain");

        assertEquals("text/plain", response.firstHeader("content-type"));
    }

    @Test
    void traversesHeaderValuesWithoutChangingSnapshotSemantics() {
        ContainerResponse response = new ContainerResponse();
        response.setHeader("X-Test", "first");
        response.addHeader("x-test", "second");
        Map<String, List<String>> snapshot = response.headers();

        List<String> visited = new ArrayList<String>();
        response.forEachHeader((name, value) -> visited.add(name + "=" + value));

        assertEquals(Arrays.asList("X-Test=first", "X-Test=second"), visited);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("X-Other", Arrays.asList("value")));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.get("X-Test").add("third"));

        response.addHeader("X-Test", "third");
        assertEquals(Arrays.asList("first", "second"), snapshot.get("X-Test"));
    }

    @Test
    void setHeaderReusesOriginalPositionAndLatestCase() {
        ContainerResponse response = new ContainerResponse();
        response.setHeader("X-First", "one");
        response.setHeader("X-Second", "two");
        response.addHeader("x-first", "three");
        response.setHeader("x-FIRST", "four");
        response.addHeader("X-FIRST", "five");

        List<String> visited = new ArrayList<String>();
        response.forEachHeader((name, value) -> visited.add(name + "=" + value));

        assertEquals(Arrays.asList("x-FIRST=four", "x-FIRST=five", "X-Second=two"), visited);
        assertEquals(Arrays.asList("four", "five"), response.headers().get("x-FIRST"));
        assertEquals("four", response.firstHeader("X-FIRST"));
    }

    @Test
    void resetClearsHeadersAndBodyWithoutAffectingSnapshot() throws Exception {
        ContainerResponse response = new ContainerResponse();
        response.setHeader("X-Test", "one");
        response.addHeader("X-Test", "two");
        response.bodyStream().write(new byte[]{7});
        Map<String, List<String>> snapshot = response.headers();

        response.reset();

        assertEquals(200, response.status());
        assertTrue(response.headers().isEmpty());
        assertEquals(0, response.bodyBuffer().remaining());
        assertEquals(Arrays.asList("one", "two"), snapshot.get("X-Test"));
    }

    @Test
    void exposesReadOnlyBodyBufferLimitedToWrittenBytes() throws Exception {
        ContainerResponse response = new ContainerResponse();
        response.bodyStream().write(new byte[]{1, 2, 3});

        ByteBuffer buffer = response.bodyBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        assertTrue(buffer.isReadOnly());
        assertArrayEquals(new byte[]{1, 2, 3}, bytes);
        assertThrows(ReadOnlyBufferException.class,
                () -> response.bodyBuffer().put((byte) 9));
    }

    @Test
    void keepsExistingBodyBufferLimitWhenMoreBytesAreAppended() throws Exception {
        ContainerResponse response = new ContainerResponse();
        response.bodyStream().write(new byte[]{1, 2, 3});
        ByteBuffer first = response.bodyBuffer();

        response.bodyStream().write(new byte[]{4, 5});

        byte[] firstBytes = new byte[first.remaining()];
        first.get(firstBytes);
        assertArrayEquals(new byte[]{1, 2, 3}, firstBytes);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, response.bodyBytes());
    }

    @Test
    void keepsBodyBytesDefensiveAndResetsBodyBuffer() throws Exception {
        ContainerResponse response = new ContainerResponse();
        response.bodyStream().write(new byte[]{4, 5, 6});

        byte[] copy = response.bodyBytes();
        copy[0] = 9;
        assertArrayEquals(new byte[]{4, 5, 6}, response.bodyBytes());

        response.resetBuffer();
        assertEquals(0, response.bodyBuffer().remaining());
    }

    @Test
    void preparesCommittedBufferedResponseForErrorBodyRewrite() throws Exception {
        ContainerResponse response = new ContainerResponse();
        response.status(404);
        response.setHeader("X-Test", "preserved");
        response.setHeader("content-length", "128");
        response.addHeader("Set-Cookie", "first=one");
        response.addHeader("set-cookie", "second=two");
        response.bodyStream().write(new byte[]{1, 2, 3});
        response.commit();

        response.prepareErrorBodyRewrite();

        assertFalse(response.committed());
        assertEquals(404, response.status());
        assertEquals("preserved", response.firstHeader("X-Test"));
        assertEquals(Arrays.asList("first=one", "second=two"),
                response.headers().get("Set-Cookie"));
        assertFalse(response.containsHeader("CONTENT-LENGTH"));
        assertArrayEquals(new byte[0], response.bodyBytes());

        response.setHeader("X-After", "writable");
        response.bodyStream().write(9);
        assertEquals("writable", response.firstHeader("X-After"));
        assertArrayEquals(new byte[]{9}, response.bodyBytes());
    }

    @Test
    void restoresContentTypeWhilePreparingErrorBodyRewrite() {
        ContainerResponse response = new ContainerResponse();
        response.setHeader("Content-Type", "text/plain");
        response.setHeader("Content-Length", "8");
        response.commit();

        response.prepareErrorBodyRewrite("application/json; charset=UTF-8");

        assertFalse(response.committed());
        assertEquals("application/json; charset=UTF-8",
                response.firstHeader("content-type"));
        assertFalse(response.containsHeader("content-length"));
    }

}
