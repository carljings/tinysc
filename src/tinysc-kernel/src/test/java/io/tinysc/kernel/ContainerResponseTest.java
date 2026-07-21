package io.tinysc.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
