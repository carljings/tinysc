package example.tinysc.probe;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BenchmarkServlet extends HttpServlet {
    private static final byte[] BODY = (
            "tinysc-probe-response-0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef0123456789abcdef\n")
            .getBytes(StandardCharsets.US_ASCII);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/plain");
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
    }
}
