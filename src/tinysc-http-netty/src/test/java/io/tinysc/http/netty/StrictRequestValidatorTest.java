package io.tinysc.http.netty;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictRequestValidatorTest {
    private final StrictRequestValidator validator = new StrictRequestValidator();

    @Test
    void acceptsSupportedHostForms() {
        assertValid("example.com");
        assertValid("example.com:8049");
        assertValid("internal_host");
        assertValid("127.0.0.1");
        assertValid("[::1]");
        assertValid("[2001:db8::1]:443");
    }

    @Test
    void rejectsAmbiguousOrMalformedHostForms() {
        assertInvalid("good.example bad.example");
        assertInvalid("good.example,bad.example");
        assertInvalid("user@good.example");
        assertInvalid("good.example:");
        assertInvalid("good.example:abc");
        assertInvalid("good.example:65536");
        assertInvalid("::1");
        assertInvalid("[::1]suffix");
        assertInvalid("[invalid]");
    }

    @Test
    void acceptsOnlySupportedRequestTargetForms() {
        assertTargetValid(HttpMethod.GET, "/path?value=one%20two");
        assertTargetValid(HttpMethod.GET,
                "/path?redirect=https%3A%2F%2Fexample.com%2Fnext");
        assertTargetValid(HttpMethod.OPTIONS, "*");

        assertTargetInvalid(HttpMethod.GET, "*");
        assertTargetInvalid(HttpMethod.POST, "*");
        assertTargetInvalid(HttpMethod.GET, "/path#fragment");
        assertTargetInvalid(HttpMethod.GET, "/path\u0001control");
        assertTargetInvalid(HttpMethod.GET, "/path\u007fcontrol");
        assertTargetInvalid(HttpMethod.GET, "/path\\separator");
        assertTargetInvalid(HttpMethod.GET, "/path%2Fseparator");
        assertTargetInvalid(HttpMethod.GET, "/path%5cseparator");
        assertTargetInvalid(HttpMethod.GET, "/path%00separator");
    }

    private void assertValid(String host) {
        FullHttpRequest request = request(host);
        try {
            assertDoesNotThrow(() -> validator.validate(request), host);
        } finally {
            request.release();
        }
    }

    private void assertInvalid(String host) {
        FullHttpRequest request = request(host);
        try {
            assertThrows(RequestValidationException.class,
                    () -> validator.validate(request), host);
        } finally {
            request.release();
        }
    }

    private static FullHttpRequest request(String host) {
        return request(HttpMethod.GET, "/", host);
    }

    private void assertTargetValid(HttpMethod method, String target) {
        FullHttpRequest request = request(method, target, "example.com");
        try {
            assertDoesNotThrow(() -> validator.validate(request), method + " " + target);
        } finally {
            request.release();
        }
    }

    private void assertTargetInvalid(HttpMethod method, String target) {
        FullHttpRequest request = request(method, target, "example.com");
        try {
            assertThrows(RequestValidationException.class,
                    () -> validator.validate(request), method + " " + target);
        } finally {
            request.release();
        }
    }

    private static FullHttpRequest request(HttpMethod method, String target, String host) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, target, Unpooled.buffer(0));
        request.headers().set(HttpHeaderNames.HOST, host);
        return request;
    }
}
