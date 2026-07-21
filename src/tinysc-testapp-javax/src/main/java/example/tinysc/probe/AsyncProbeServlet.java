package example.tinysc.probe;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class AsyncProbeServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        final AsyncContext async = request.startAsync();
        async.setTimeout(2000L);
        async.start(new Runnable() {
            @Override
            public void run() {
                try {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("text/plain");
                    response.getWriter().println("async=ok");
                    response.getWriter().println("thread=" + Thread.currentThread().getName());
                    async.complete();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        });
    }
}
