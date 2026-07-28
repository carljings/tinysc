package io.tinysc.servlet.javax;

import io.tinysc.kernel.ContainerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartParserTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesUtf8FieldsBinaryFilesHeadersQuotedBoundaryAndRepeatedNames()
            throws Exception {
        String boundary = "tiny-quoted-boundary";
        byte[] binary = new byte[]{0, 1, 2, 13, 10, (byte) 0x80, (byte) 0xff};
        byte[] body = multipart(boundary,
                field("message", "你好".getBytes(StandardCharsets.UTF_8),
                        "Content-Type: text/plain; charset=UTF-8",
                        "X-Test: first",
                        "X-Test: second"),
                file("upload", "payload.bin", "application/octet-stream", binary),
                field("tag", "one".getBytes(StandardCharsets.UTF_8)),
                field("tag", "two".getBytes(StandardCharsets.UTF_8)));
        ContainerRequest request = request(
                "Multipart/Form-Data; boundary=\"" + boundary + "\"", body);

        assertTrue(MultipartParser.isMultipart(request));
        List<TinyPart> parts = MultipartParser.parse(request,
                config("", -1L, -1L, 1024), temporaryDirectory, "UTF-8");

        assertEquals(4, parts.size());
        TinyPart message = parts.get(0);
        assertTrue(message.isFormField());
        assertEquals("message", message.getName());
        assertNull(message.getSubmittedFileName());
        assertEquals("你好", message.stringValue("UTF-8"));
        assertEquals("text/plain; charset=UTF-8", message.getContentType());
        assertEquals("first", message.getHeader("X-Test"));
        assertEquals(Arrays.asList("first", "second"),
                new ArrayList<String>(message.getHeaders("x-test")));
        assertTrue(containsIgnoreCase(message.getHeaderNames(), "content-disposition"));

        TinyPart upload = parts.get(1);
        assertFalse(upload.isFormField());
        assertEquals("upload", upload.getName());
        assertEquals("payload.bin", upload.getSubmittedFileName());
        assertEquals("application/octet-stream", upload.getContentType());
        assertEquals(binary.length, upload.getSize());
        assertArrayEquals(binary, readAll(upload.getInputStream()));

        assertEquals("tag", parts.get(2).getName());
        assertEquals("one", parts.get(2).stringValue("UTF-8"));
        assertEquals("tag", parts.get(3).getName());
        assertEquals("two", parts.get(3).stringValue("UTF-8"));
    }

    @Test
    void detectsMultipartUsingContentTypeRatherThanHttpMethod() {
        ContainerRequest request = ContainerRequest.builder()
                .method("PUT")
                .rawUri("/upload")
                .path("/upload")
                .addHeader("Content-Type", "MULTIPART/FORM-DATA; boundary=value")
                .build();

        assertTrue(MultipartParser.isMultipart(request));
        assertFalse(MultipartParser.isMultipart(ContainerRequest.builder()
                .method("POST")
                .rawUri("/upload")
                .path("/upload")
                .addHeader("Content-Type", "text/plain")
                .build()));
    }

    @Test
    void rejectsMissingConfigAndNonMultipartContentType() throws Exception {
        byte[] body = multipart("boundary", field("value", bytes("data")));
        ContainerRequest multipart = request("multipart/form-data; boundary=boundary", body);

        assertThrows(IllegalStateException.class,
                () -> MultipartParser.parse(multipart, null, temporaryDirectory, "UTF-8"));
        assertThrows(ServletException.class,
                () -> MultipartParser.parse(request("text/plain", body),
                        config("", -1L, -1L, 0), temporaryDirectory, "UTF-8"));
    }

    @Test
    void rejectsMissingBoundaryAndMalformedBodyAsServletErrors() throws Exception {
        ContainerRequest missingBoundary =
                request("multipart/form-data", bytes("not multipart"));
        assertThrows(ServletException.class,
                () -> MultipartParser.parse(missingBoundary,
                        config("", -1L, -1L, 0), temporaryDirectory, "UTF-8"));

        byte[] truncated = bytes("--broken\r\n"
                + "Content-Disposition: form-data; name=\"value\"\r\n"
                + "\r\n"
                + "unfinished");
        assertThrows(ServletException.class,
                () -> MultipartParser.parse(
                        request("multipart/form-data; boundary=broken", truncated),
                        config("", -1L, -1L, 0), temporaryDirectory, "UTF-8"));
    }

    @Test
    void mapsRequestAndFileSizeLimitsToIllegalStateException() throws Exception {
        String boundary = "size-boundary";
        byte[] body = multipart(boundary,
                file("upload", "payload.bin", "application/octet-stream",
                        new byte[]{1, 2, 3, 4}));
        ContainerRequest request =
                request("multipart/form-data; boundary=" + boundary, body);

        IllegalStateException requestLimit = assertThrows(IllegalStateException.class,
                () -> MultipartParser.parse(request,
                        config("", -1L, body.length - 1L, 0),
                        temporaryDirectory, "UTF-8"));
        assertTrue(requestLimit.getMessage().contains("request size limit"));

        IllegalStateException fileLimit = assertThrows(IllegalStateException.class,
                () -> MultipartParser.parse(request,
                        config("", 3L, -1L, 0), temporaryDirectory, "UTF-8"));
        assertTrue(fileLimit.getMessage().contains("part size limit"));
    }

    @Test
    void rejectsMoreThanFiftyParts() throws Exception {
        String boundary = "count-boundary";
        BodyPart[] fields = new BodyPart[51];
        for (int index = 0; index < fields.length; index++) {
            fields[index] = field("field-" + index, bytes(""));
        }
        byte[] body = multipart(boundary, fields);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MultipartParser.parse(
                        request("multipart/form-data; boundary=" + boundary, body),
                        config("", -1L, -1L, 0), temporaryDirectory, "UTF-8"));

        assertTrue(failure.getMessage().contains("50 parts"));
    }

    @Test
    void rejectsPartHeadersLargerThanFiveHundredTwelveBytes() throws Exception {
        String boundary = "header-boundary";
        char[] value = new char[600];
        Arrays.fill(value, 'x');
        byte[] body = multipart(boundary,
                field("value", bytes("data"), "X-Large: " + new String(value)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MultipartParser.parse(
                        request("multipart/form-data; boundary=" + boundary, body),
                        config("", -1L, -1L, 0), temporaryDirectory, "UTF-8"));
        assertTrue(failure.getMessage().contains("part header limit"));
    }

    @Test
    void storesPartsAboveThresholdOnDiskAndDeleteRemovesTemporaryFile()
            throws Exception {
        String boundary = "disk-boundary";
        Path uploadDirectory = temporaryDirectory.resolve("uploads");
        byte[] body = multipart(boundary,
                file("upload", "payload.bin", "application/octet-stream",
                        new byte[]{1, 2, 3, 4}));

        List<TinyPart> parts = MultipartParser.parse(
                request("multipart/form-data; boundary=" + boundary, body),
                config("uploads", -1L, -1L, 2), temporaryDirectory, "UTF-8");

        assertEquals(1L, countEntries(uploadDirectory));
        parts.get(0).delete();
        assertEquals(0L, countEntries(uploadDirectory));
    }

    @Test
    void writesRelativeAndAbsoluteDestinationsButRejectsRelativeEscape()
            throws Exception {
        String boundary = "write-boundary";
        Path uploadDirectory = temporaryDirectory.resolve("uploads");
        Files.createDirectories(uploadDirectory.resolve("nested"));
        byte[] payload = new byte[]{4, 3, 2, 1};
        List<TinyPart> parts = MultipartParser.parse(
                request("multipart/form-data; boundary=" + boundary,
                        multipart(boundary,
                                file("upload", "payload.bin",
                                        "application/octet-stream", payload))),
                config(uploadDirectory.toString(), -1L, -1L, 1024),
                temporaryDirectory, "UTF-8");
        TinyPart part = parts.get(0);

        part.write("nested/relative.bin");
        assertArrayEquals(payload,
                Files.readAllBytes(uploadDirectory.resolve("nested/relative.bin")));

        Path absolute = temporaryDirectory.resolve("absolute.bin").toAbsolutePath();
        part.write(absolute.toString());
        assertArrayEquals(payload, Files.readAllBytes(absolute));

        assertThrows(IOException.class, () -> part.write("../escaped.bin"));
        assertFalse(Files.exists(temporaryDirectory.resolve("escaped.bin")));
    }

    @Test
    void rejectsRelativeWriteThroughSymlinkOutsideLocation() throws Exception {
        String boundary = "symlink-boundary";
        Path uploadDirectory = temporaryDirectory.resolve("uploads");
        Path outsideDirectory = temporaryDirectory.resolve("outside");
        Files.createDirectories(uploadDirectory);
        Files.createDirectories(outsideDirectory);
        Files.createSymbolicLink(uploadDirectory.resolve("link"), outsideDirectory);
        TinyPart part = MultipartParser.parse(
                request("multipart/form-data; boundary=" + boundary,
                        multipart(boundary,
                                file("upload", "payload.bin",
                                        "application/octet-stream", bytes("data")))),
                config(uploadDirectory.toString(), -1L, -1L, 1024),
                temporaryDirectory, "UTF-8").get(0);

        assertThrows(IOException.class, () -> part.write("link/escaped.bin"));
        assertFalse(Files.exists(outsideDirectory.resolve("escaped.bin")));
    }

    @Test
    void mapsInvalidConfigurationEncodingAndLocationErrorsExplicitly()
            throws Exception {
        String boundary = "config-boundary";
        ContainerRequest request = request("multipart/form-data; boundary=" + boundary,
                multipart(boundary, field("value", bytes("data"))));

        assertThrows(IllegalStateException.class,
                () -> MultipartParser.parse(request,
                        config("", -1L, -1L, -1), temporaryDirectory, "UTF-8"));
        assertThrows(ServletException.class,
                () -> MultipartParser.parse(request,
                        config("", -1L, -1L, 0), temporaryDirectory, "bad encoding"));

        Path servletTemp = temporaryDirectory.resolve("servlet-temp");
        Path escapedLocation = temporaryDirectory.resolve("escaped-location");
        assertThrows(IllegalStateException.class,
                () -> MultipartParser.parse(request,
                        config("../escaped-location", -1L, -1L, 0),
                        servletTemp, "UTF-8"));
        assertFalse(Files.exists(escapedLocation));

        Path regularFile = temporaryDirectory.resolve("not-a-directory");
        Files.write(regularFile, bytes("data"));
        assertThrows(IOException.class,
                () -> MultipartParser.parse(request,
                        config(regularFile.toString(), -1L, -1L, 0),
                        temporaryDirectory, "UTF-8"));
    }

    private static MultipartConfigElement config(String location, long maxFileSize,
                                                  long maxRequestSize, int threshold) {
        return new MultipartConfigElement(location, maxFileSize, maxRequestSize, threshold);
    }

    private static ContainerRequest request(String contentType, byte[] body) {
        return ContainerRequest.builder()
                .method("POST")
                .rawUri("/upload")
                .path("/upload")
                .addHeader("Content-Type", contentType)
                .body(body)
                .build();
    }

    private static BodyPart field(String name, byte[] value, String... headers) {
        return new BodyPart("form-data; name=\"" + name + "\"", value, headers);
    }

    private static BodyPart file(String name, String fileName, String contentType,
                                 byte[] value) {
        return new BodyPart("form-data; name=\"" + name + "\"; filename=\""
                + fileName + "\"", value, "Content-Type: " + contentType);
    }

    private static byte[] multipart(String boundary, BodyPart... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (BodyPart part : parts) {
            writeAscii(output, "--" + boundary + "\r\n");
            writeAscii(output, "Content-Disposition: " + part.contentDisposition + "\r\n");
            for (String header : part.headers) {
                writeAscii(output, header + "\r\n");
            }
            writeAscii(output, "\r\n");
            output.write(part.value);
            writeAscii(output, "\r\n");
        }
        writeAscii(output, "--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value)
            throws IOException {
        output.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64];
            int read;
            while ((read = source.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean containsIgnoreCase(Collection<String> values, String expected) {
        for (String value : values) {
            if (expected.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static long countEntries(Path directory) throws IOException {
        long count = 0L;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path ignored : entries) {
                count++;
            }
        }
        return count;
    }

    private static final class BodyPart {
        private final String contentDisposition;
        private final byte[] value;
        private final List<String> headers;

        private BodyPart(String contentDisposition, byte[] value, String... headers) {
            this.contentDisposition = contentDisposition;
            this.value = value;
            this.headers = Arrays.asList(headers);
        }
    }
}
