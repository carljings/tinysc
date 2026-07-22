package io.tinysc.http.netty;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.NetUtil;

import java.util.List;

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
        if (hosts.size() > 1 || (!hosts.isEmpty() && !isValidHostHeader(hosts.get(0)))) {
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
        validateTarget(request.method().name(), request.uri());
    }

    private static void validateTarget(String method, String target)
            throws RequestValidationException {
        if (target == null || target.isEmpty()) {
            throw new RequestValidationException("request target is empty");
        }
        if ("*".equals(target)) {
            if ("OPTIONS".equals(method)) {
                return;
            }
            throw new RequestValidationException(
                    "asterisk-form request target is only valid for OPTIONS");
        }
        if (target.charAt(0) != '/') {
            throw new RequestValidationException("only origin-form request targets are supported");
        }
        int queryStart = target.indexOf('?');
        int pathEnd = queryStart < 0 ? target.length() : queryStart;
        for (int index = 0; index < target.length(); index++) {
            char character = target.charAt(index);
            if (character <= 0x20 || character == 0x7f
                    || character == '\\' || character == '#') {
                throw new RequestValidationException(
                        "request target contains an illegal character");
            }
            if (character == '%') {
                if (index + 2 >= target.length() || !isHex(target.charAt(index + 1))
                        || !isHex(target.charAt(index + 2))) {
                    throw new RequestValidationException("request target contains invalid percent encoding");
                }
                if (index < pathEnd) {
                    int decoded = hexValue(target.charAt(index + 1)) * 16
                            + hexValue(target.charAt(index + 2));
                    if (decoded == 0 || decoded == '/' || decoded == '\\') {
                        throw new RequestValidationException(
                                "encoded path separator or NUL is not allowed");
                    }
                }
                index += 2;
            }
        }
    }

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static int hexValue(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return value - 'A' + 10;
    }

    private static boolean isValidHostHeader(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String port = null;
        if (value.charAt(0) == '[') {
            int closing = value.indexOf(']');
            if (closing <= 1 || !NetUtil.isValidIpV6Address(value.substring(1, closing))) {
                return false;
            }
            if (closing + 1 < value.length()) {
                if (value.charAt(closing + 1) != ':') {
                    return false;
                }
                port = value.substring(closing + 2);
            }
        } else {
            int firstColon = value.indexOf(':');
            if (firstColon >= 0) {
                if (firstColon == 0 || firstColon != value.lastIndexOf(':')) {
                    return false;
                }
                port = value.substring(firstColon + 1);
                value = value.substring(0, firstColon);
            }
            if (!isValidRegName(value)) {
                return false;
            }
        }
        return port == null || isValidPort(port);
    }

    private static boolean isValidRegName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (isAlphaNumeric(character)
                    || "-._~!$&'()*+;=".indexOf(character) >= 0) {
                continue;
            }
            if (character == '%' && index + 2 < value.length()
                    && isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))) {
                index += 2;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isValidPort(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int port = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
            port = port * 10 + character - '0';
            if (port > 65535) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAlphaNumeric(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }
}
