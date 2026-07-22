package io.tinysc.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ContainerRequestTest {
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
}
