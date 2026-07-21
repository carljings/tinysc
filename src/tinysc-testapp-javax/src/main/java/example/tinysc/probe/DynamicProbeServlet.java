package example.tinysc.probe;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class DynamicProbeServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain");
        response.getWriter().println("sci=" + getServletContext().getAttribute("probe.sci"));
        response.getWriter().println("dynamicFilter="
                + request.getAttribute("probe.dynamicFilter"));
        response.getWriter().println("requestListener="
                + request.getAttribute("probe.requestListener"));
    }
}
