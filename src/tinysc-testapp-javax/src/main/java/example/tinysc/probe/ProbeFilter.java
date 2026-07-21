package example.tinysc.probe;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class ProbeFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setAttribute("probe.filter", "ok");
        ((HttpServletResponse) response).setHeader("X-TinySC-Filter", "applied");
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if (httpRequest.getDispatcherType() == DispatcherType.FORWARD) {
            ((HttpServletResponse) response).setHeader(
                    "X-TinySC-Forward-Filter", "applied");
        }
        if (httpRequest.getDispatcherType() == DispatcherType.REQUEST
                && "/static-forward".equals(httpRequest.getServletPath())) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        if (httpRequest.getDispatcherType() == DispatcherType.REQUEST
                && "/jar-static-forward".equals(httpRequest.getServletPath())) {
            request.getRequestDispatcher("/jar-resource.html").forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
