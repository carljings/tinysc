package io.tinysc.servlet.javax;

import io.tinysc.kernel.ContainerResponse;
import org.junit.jupiter.api.Test;

import javax.servlet.http.Cookie;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinyHttpServletResponseTest {
    @Test
    void sendErrorRecordsPendingErrorAndKeepsFallbackCommitted() throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);

        response.sendError(404, "missing");

        TinyHttpServletResponse.PendingError error = response.pendingError();
        assertNotNull(error);
        assertEquals(404, error.status());
        assertEquals("missing", error.message());
        assertEquals(404, response.getStatus());
        assertTrue(response.isCommitted());
        assertEquals("text/plain", response.getContentType());
        assertEquals("text/plain; charset=ISO-8859-1",
                response.getHeader("Content-Type"));
        assertArrayEquals("missing".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
    }

    @Test
    void sendErrorWithoutMessageRecordsNullButUsesStatusPhraseFallback() throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);

        response.sendError(404);

        assertNotNull(response.pendingError());
        assertNull(response.pendingError().message());
        assertArrayEquals("Not Found".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
    }

    @Test
    void setStatusAndOrdinaryFlushDoNotCreatePendingError() throws Exception {
        ContainerResponse statusContainer = new ContainerResponse();
        TinyHttpServletResponse statusResponse = new TinyHttpServletResponse(statusContainer);

        statusResponse.setStatus(404);

        assertNull(statusResponse.pendingError());
        assertFalse(statusResponse.isCommitted());

        ContainerResponse flushContainer = new ContainerResponse();
        TinyHttpServletResponse flushResponse = new TinyHttpServletResponse(flushContainer);
        flushResponse.getWriter().write("ordinary");
        flushResponse.flushBuffer();

        assertNull(flushResponse.pendingError());
        assertTrue(flushResponse.isCommitted());
    }

    @Test
    void preErrorWriterDataDoesNotLeakIntoCustomErrorResponse() throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);
        response.getWriter().write("original");

        response.sendError(404, "fallback");
        response.prepareErrorDispatch();
        response.getWriter().write("custom");
        response.finish();

        assertArrayEquals("custom".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
        assertNull(response.pendingError());
        assertFalse(response.isCommitted());
    }

    @Test
    void restoresOriginalContentTypeBeforeCustomErrorDispatch() throws Exception {
        ContainerResponse configuredContainer = new ContainerResponse();
        TinyHttpServletResponse configuredResponse =
                new TinyHttpServletResponse(configuredContainer);
        configuredResponse.setContentType("application/json");

        configuredResponse.sendError(500, "fallback");
        assertEquals("text/plain; charset=ISO-8859-1",
                configuredResponse.getHeader("Content-Type"));

        configuredResponse.prepareErrorDispatch();

        assertEquals("application/json", configuredResponse.getContentType());
        assertEquals("application/json",
                configuredResponse.getHeader("Content-Type"));

        ContainerResponse absentContainer = new ContainerResponse();
        TinyHttpServletResponse absentResponse =
                new TinyHttpServletResponse(absentContainer);
        absentResponse.sendError(500, "fallback");

        absentResponse.prepareErrorDispatch();

        assertNull(absentResponse.getContentType());
        assertFalse(absentResponse.containsHeader("Content-Type"));
    }

    @Test
    void sendErrorAfterOutputStreamDoesNotSwitchToWriter() throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);
        response.getOutputStream().write("original".getBytes(StandardCharsets.ISO_8859_1));

        response.sendError(500, "fallback");

        assertTrue(response.isCommitted());
        assertNotNull(response.pendingError());
        assertArrayEquals("fallback".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
    }

    @Test
    void ignoresApplicationWritesAfterSendError() throws Exception {
        ContainerResponse writerContainer = new ContainerResponse();
        TinyHttpServletResponse writerResponse =
                new TinyHttpServletResponse(writerContainer);
        PrintWriter writer = writerResponse.getWriter();
        writer.write("original");

        writerResponse.sendError(404, "fallback");
        writer.write("ignored");
        writerResponse.finish();

        assertArrayEquals("fallback".getBytes(StandardCharsets.ISO_8859_1),
                writerContainer.bodyBytes());

        ContainerResponse streamContainer = new ContainerResponse();
        TinyHttpServletResponse streamResponse =
                new TinyHttpServletResponse(streamContainer);
        javax.servlet.ServletOutputStream stream = streamResponse.getOutputStream();
        stream.write("original".getBytes(StandardCharsets.ISO_8859_1));

        streamResponse.sendError(500, "fallback");
        stream.write("ignored".getBytes(StandardCharsets.ISO_8859_1));

        assertArrayEquals("fallback".getBytes(StandardCharsets.ISO_8859_1),
                streamContainer.bodyBytes());
    }

    @Test
    void errorDispatchCanChooseOutputStreamAfterOriginalWriter() throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);
        response.getWriter().write("original");
        response.sendError(500, "fallback");

        response.prepareErrorDispatch();
        response.getOutputStream().write("custom".getBytes(StandardCharsets.ISO_8859_1));

        assertArrayEquals("custom".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
    }

    @Test
    void errorDispatchCanChooseWriterAfterOriginalOutputStream() throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);
        response.getOutputStream().write("original".getBytes(StandardCharsets.ISO_8859_1));
        response.sendError(500, "fallback");

        response.prepareErrorDispatch();
        response.getWriter().write("custom");
        response.finish();

        assertArrayEquals("custom".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
    }

    @Test
    void errorRewritePreservesHeadersAndCookiesButDropsLengthAndBody() throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);
        response.setHeader("X-Test", "preserved");
        response.addCookie(new Cookie("first", "one"));
        response.addCookie(new Cookie("second", "two"));
        response.setContentLength(128);
        response.getOutputStream().write("original".getBytes(StandardCharsets.ISO_8859_1));

        response.sendError(404, "fallback");
        response.prepareErrorDispatch();
        response.getOutputStream().write("custom".getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(404, response.getStatus());
        assertEquals("preserved", response.getHeader("X-Test"));
        assertEquals(Arrays.asList("first=one", "second=two"),
                response.getHeaders("Set-Cookie"));
        assertFalse(response.containsHeader("content-length"));
        assertArrayEquals("custom".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
    }

    @Test
    void internalServerErrorFallbackReplacesPartialHandlerResponseWithoutRecursion()
            throws Exception {
        ContainerResponse container = new ContainerResponse();
        TinyHttpServletResponse response = new TinyHttpServletResponse(container);
        response.sendError(404, "initial");
        response.prepareErrorDispatch();
        response.getWriter().write("partial handler output");
        response.sendError(503, "nested error");

        response.renderInternalServerErrorFallback();

        assertEquals(500, response.getStatus());
        assertTrue(response.isCommitted());
        assertNull(response.pendingError());
        assertEquals("text/plain; charset=ISO-8859-1",
                response.getHeader("Content-Type"));
        assertArrayEquals("Internal Server Error".getBytes(StandardCharsets.ISO_8859_1),
                container.bodyBytes());
    }
}
