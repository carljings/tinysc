package example.tinysc.probe;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
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
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
