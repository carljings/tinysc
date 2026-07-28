package example.tinysc.probe;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class UploadProbeServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        Part document = request.getPart("document");
        response.setContentType("text/plain");
        response.getWriter().write("title=" + request.getParameter("title")
                + ";file=" + document.getSubmittedFileName()
                + ";size=" + document.getSize()
                + ";type=" + document.getContentType()
                + ";payload=" + read(document));
    }

    private static String read(Part part) throws IOException {
        try (InputStream input = part.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), "UTF-8");
        }
    }
}
