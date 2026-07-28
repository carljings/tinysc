package example.tinysc.probe;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class ErrorViewServlet extends HttpServlet {
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String view = request.getPathInfo().substring(1);
        if ("failing-error-page".equals(view)) {
            response.getWriter().write("partial-error-page");
            throw new ServletException("probe-error-view");
        }

        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain");
        response.getWriter().write(
                "ERROR_VIEW=" + view
                        + ";ERROR_DISPATCHER_TYPE=" + request.getDispatcherType().name()
                        + ";ERROR_STATUS_CODE=" + attribute(
                                request, RequestDispatcher.ERROR_STATUS_CODE)
                        + ";ERROR_MESSAGE=" + attribute(
                                request, RequestDispatcher.ERROR_MESSAGE)
                        + ";ERROR_REQUEST_URI=" + attribute(
                                request, RequestDispatcher.ERROR_REQUEST_URI)
                        + ";ERROR_SERVLET_NAME=" + attribute(
                                request, RequestDispatcher.ERROR_SERVLET_NAME)
                        + ";ERROR_EXCEPTION=" + exception(request)
                        + ";ERROR_EXCEPTION_TYPE=" + exceptionType(request));
    }

    private static String attribute(HttpServletRequest request, String name) {
        return String.valueOf(request.getAttribute(name));
    }

    private static String exception(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (!(value instanceof Throwable)) {
            return String.valueOf(value);
        }
        Throwable failure = (Throwable) value;
        return failure.getClass().getName() + ":" + failure.getMessage();
    }

    private static String exceptionType(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE);
        if (value instanceof Class<?>) {
            return ((Class<?>) value).getName();
        }
        return String.valueOf(value);
    }
}
