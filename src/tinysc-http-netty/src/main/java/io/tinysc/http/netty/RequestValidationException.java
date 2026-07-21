package io.tinysc.http.netty;

final class RequestValidationException extends Exception {
    RequestValidationException(String message) {
        super(message);
    }
}
