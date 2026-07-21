package io.tinysc.http.netty;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpVersion;

import java.util.List;
import java.util.Locale;

final class StrictRequestValidator {
    void validate(FullHttpRequest request) throws RequestValidationException {
        if (!request.decoderResult().isSuccess()) {
            throw new RequestValidationException("malformed HTTP request");
        }
        if (HttpVersion.HTTP_1_1.equals(request.protocolVersion())
                && !request.headers().contains(HttpHeaderNames.HOST)) {
            throw new RequestValidationException("HTTP/1.1 requires exactly one Host header");
        }
        List<String> hosts = request.headers().getAll(HttpHeaderNames.HOST);
        if (hosts.size() > 1 || (!hosts.isEmpty() && containsInvalidHeaderValue(hosts.get(0)))) {
            throw new RequestValidationException("request contains an invalid Host header");
        }
        boolean hasContentLength = request.headers().contains(HttpHeaderNames.CONTENT_LENGTH);
        boolean hasTransferEncoding = request.headers().contains(HttpHeaderNames.TRANSFER_ENCODING);
        if (hasContentLength && hasTransferEncoding) {
            throw new RequestValidationException(
                    "Content-Length and Transfer-Encoding must not be combined");
        }
        if (hasTransferEncoding) {
            List<String> encodings = request.headers().getAll(HttpHeaderNames.TRANSFER_ENCODING);
            if (encodings.size() != 1
                    || !HttpHeaderValues.CHUNKED.toString().equalsIgnoreCase(encodings.get(0).trim())) {
                throw new RequestValidationException("unsupported Transfer-Encoding");
            }
        }
        validateTarget(request.uri());
    }

    private static void validateTarget(String target) throws RequestValidationException {
        if (target == null || target.isEmpty()) {
            throw new RequestValidationException("request target is empty");
        }
        if ("*".equals(target)) {
            return;
        }
        if (target.charAt(0) != '/') {
            throw new RequestValidationException("only origin-form request targets are supported");
        }
        if (target.indexOf('\0') >= 0 || target.indexOf('\\') >= 0
                || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0) {
            throw new RequestValidationException("request target contains an illegal character");
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.contains("%00") || lower.contains("%2f") || lower.contains("%5c")) {
            throw new RequestValidationException("encoded path separator or NUL is not allowed");
        }
        for (int index = 0; index < target.length(); index++) {
            if (target.charAt(index) == '%') {
                if (index + 2 >= target.length() || !isHex(target.charAt(index + 1))
                        || !isHex(target.charAt(index + 2))) {
                    throw new RequestValidationException("request target contains invalid percent encoding");
                }
                index += 2;
            }
        }
    }

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static boolean containsInvalidHeaderValue(String value) {
        return value == null || value.isEmpty() || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }
}
