package example.tinysc.probe;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;

public final class ProbeServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession();
        Integer count = (Integer) session.getAttribute("count");
        count = count == null ? 1 : count + 1;
        session.setAttribute("count", count);

        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain");
        PrintWriter writer = response.getWriter();
        writer.println("tinysc=ok");
        writer.println("filter=" + request.getAttribute("probe.filter"));
        writer.println("listener=" + getServletContext().getAttribute("probe.listener"));
        writer.println("contextPath=" + request.getContextPath());
        writer.println("servletPath=" + request.getServletPath());
        writer.println("pathInfo=" + request.getPathInfo());
        writer.println("name=" + request.getParameter("name"));
        writer.println("session=" + count);
        writer.println("thread=" + Thread.currentThread().getName());
    }
}
