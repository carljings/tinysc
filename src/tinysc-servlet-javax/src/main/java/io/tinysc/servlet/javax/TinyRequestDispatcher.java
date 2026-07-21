package io.tinysc.servlet.javax;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import java.io.IOException;

final class TinyRequestDispatcher implements RequestDispatcher {
    private final JavaxServletRuntime runtime;
    private final String path;
    private final String servletName;

    TinyRequestDispatcher(JavaxServletRuntime runtime, String path, String servletName) {
        this.runtime = runtime;
        this.path = path;
        this.servletName = servletName;
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        if (response.isCommitted()) {
            throw new IllegalStateException("cannot forward a committed response");
        }
        response.resetBuffer();
        runtime.dispatch(unwrap(request), request, response, path, servletName,
                DispatcherType.FORWARD);
        if (!unwrap(request).isAsyncStarted()) {
            response.flushBuffer();
        }
    }

    @Override
    public void include(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        runtime.dispatch(unwrap(request), request, response, path, servletName,
                DispatcherType.INCLUDE);
    }

    private static TinyHttpServletRequest unwrap(ServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper) {
            current = ((ServletRequestWrapper) current).getRequest();
        }
        if (!(current instanceof TinyHttpServletRequest)) {
            throw new IllegalArgumentException("request was not created by tinysc");
        }
        return (TinyHttpServletRequest) current;
    }
}
