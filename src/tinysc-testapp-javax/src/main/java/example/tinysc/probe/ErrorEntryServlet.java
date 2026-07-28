package example.tinysc.probe;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class ErrorEntryServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo();
        if ("/send-error-404".equals(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "probe-missing");
            return;
        }
        if ("/set-status-404".equals(path)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("text/plain");
            response.getWriter().write("plain-status-404");
            return;
        }
        if ("/runtime-exception".equals(path)) {
            throw new RuntimeException("probe-runtime");
        }
        if ("/servlet-io-exception".equals(path)) {
            throw new ServletException("probe-servlet", new IOException("probe-io"));
        }
        if ("/failing-error-page".equals(path)) {
            throw new UnsupportedOperationException("probe-failing-error-page");
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
