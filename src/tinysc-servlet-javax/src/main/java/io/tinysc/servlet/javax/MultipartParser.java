package io.tinysc.servlet.javax;

import io.tinysc.kernel.ContainerRequest;
import org.apache.commons.fileupload.FileCountLimitExceededException;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.InvalidFileNameException;
import org.apache.commons.fileupload.MultipartStream;
import org.apache.commons.fileupload.UploadContext;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class MultipartParser {
    private static final int MAX_PARTS = 50;
    private static final int MAX_PART_HEADER_BYTES = 512;
    private static final String DEFAULT_ENCODING = "ISO-8859-1";

    private MultipartParser() {
    }

    static boolean isMultipart(ContainerRequest request) {
        Objects.requireNonNull(request, "request");
        String contentType = request.firstHeader("Content-Type");
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String mediaType = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return "multipart/form-data".equals(mediaType.trim().toLowerCase(Locale.ROOT));
    }

    static List<TinyPart> parse(ContainerRequest request, MultipartConfigElement config,
                                Path servletTempDirectory, String characterEncoding)
            throws IOException, ServletException {
        Objects.requireNonNull(request, "request");
        if (config == null) {
            throw new IllegalStateException("multipart processing is not configured");
        }
        if (!isMultipart(request)) {
            throw new ServletException("request content type is not multipart/form-data");
        }
        validateConfig(config);
        String encoding = validateEncoding(characterEncoding);
        Path location = resolveLocation(config.getLocation(), servletTempDirectory);

        DiskFileItemFactory factory =
                new DiskFileItemFactory(config.getFileSizeThreshold(), location.toFile());
        factory.setDefaultCharset(encoding);
        FileUpload upload = new FileUpload(factory);
        upload.setHeaderEncoding(encoding);
        upload.setSizeMax(config.getMaxRequestSize());
        upload.setFileSizeMax(config.getMaxFileSize());
        upload.setFileCountMax(MAX_PARTS);
        upload.setPartHeaderSizeMax(MAX_PART_HEADER_BYTES);

        List<FileItem> items;
        try {
            items = upload.parseRequest(new RequestContext(request, encoding));
        } catch (FileUploadException exception) {
            rethrow(exception);
            throw new AssertionError("unreachable");
        }

        List<TinyPart> parts = new ArrayList<TinyPart>(items.size());
        try {
            for (FileItem item : items) {
                parts.add(new TinyPart(item, location));
            }
        } catch (InvalidFileNameException exception) {
            deleteItems(items);
            throw new ServletException("multipart request contains an invalid file name",
                    exception);
        } catch (RuntimeException exception) {
            deleteItems(items);
            throw exception;
        }
        return Collections.unmodifiableList(parts);
    }

    private static void validateConfig(MultipartConfigElement config) {
        if (config.getFileSizeThreshold() < 0) {
            throw new IllegalStateException(
                    "multipart fileSizeThreshold must not be negative");
        }
        if (config.getMaxFileSize() < -1L) {
            throw new IllegalStateException("multipart maxFileSize must be -1 or greater");
        }
        if (config.getMaxRequestSize() < -1L) {
            throw new IllegalStateException("multipart maxRequestSize must be -1 or greater");
        }
    }

    private static String validateEncoding(String characterEncoding) throws ServletException {
        String encoding = characterEncoding == null || characterEncoding.isEmpty()
                ? DEFAULT_ENCODING
                : characterEncoding;
        try {
            Charset.forName(encoding);
            return encoding;
        } catch (IllegalCharsetNameException exception) {
            throw new ServletException("invalid multipart character encoding: " + encoding,
                    exception);
        } catch (UnsupportedCharsetException exception) {
            throw new ServletException("unsupported multipart character encoding: " + encoding,
                    exception);
        }
    }

    private static Path resolveLocation(String configuredLocation, Path servletTempDirectory)
            throws IOException {
        Path configured;
        try {
            configured = configuredLocation == null || configuredLocation.isEmpty()
                    ? null
                    : Paths.get(configuredLocation);
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("invalid multipart location: " + configuredLocation,
                    exception);
        }

        Path location;
        if (configured != null && configured.isAbsolute()) {
            location = configured.normalize();
        } else {
            if (servletTempDirectory == null) {
                throw new IllegalStateException(
                        "servlet temporary directory is not configured");
            }
            Path base = servletTempDirectory.toAbsolutePath().normalize();
            location = configured == null ? base : base.resolve(configured).normalize();
            if (!location.startsWith(base)) {
                throw new IllegalStateException(
                        "relative multipart location escapes the servlet temporary directory: "
                                + configuredLocation);
            }
        }
        Files.createDirectories(location);
        if (!Files.isDirectory(location)) {
            throw new IOException("multipart location is not a directory: " + location);
        }
        return location.toRealPath();
    }

    private static void rethrow(FileUploadException exception)
            throws IOException, ServletException {
        if (exception instanceof FileUploadBase.SizeLimitExceededException) {
            FileUploadBase.SizeLimitExceededException sizeException =
                    (FileUploadBase.SizeLimitExceededException) exception;
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("Header section")) {
                throw new IllegalStateException(
                        "multipart part header limit exceeded: permitted="
                                + MAX_PART_HEADER_BYTES,
                        exception);
            }
            throw new IllegalStateException(
                    "multipart request size limit exceeded: actual="
                            + sizeException.getActualSize()
                            + ", permitted=" + sizeException.getPermittedSize(),
                    exception);
        }
        if (exception instanceof FileUploadBase.FileSizeLimitExceededException) {
            FileUploadBase.FileSizeLimitExceededException sizeException =
                    (FileUploadBase.FileSizeLimitExceededException) exception;
            throw new IllegalStateException(
                    "multipart part size limit exceeded for field "
                            + sizeException.getFieldName() + ": actual="
                            + sizeException.getActualSize()
                            + ", permitted=" + sizeException.getPermittedSize(),
                    exception);
        }
        if (exception instanceof FileCountLimitExceededException) {
            throw new IllegalStateException(
                    "multipart request exceeds the container limit of " + MAX_PARTS + " parts",
                    exception);
        }
        if (exception instanceof FileUploadBase.InvalidContentTypeException) {
            throw new ServletException("request content type is not valid multipart/form-data",
                    exception);
        }

        Throwable cause = exception.getCause();
        if (cause instanceof MultipartStream.MalformedStreamException
                || cause instanceof MultipartStream.IllegalBoundaryException) {
            throw new ServletException("malformed multipart request", exception);
        }
        if (cause instanceof IOException) {
            throw (IOException) cause;
        }
        throw new ServletException("malformed multipart request", exception);
    }

    private static void deleteItems(List<FileItem> items) {
        for (FileItem item : items) {
            try {
                item.delete();
            } catch (RuntimeException ignored) {
                // Best effort cleanup while preserving the parsing failure.
            }
        }
    }

    private static final class RequestContext implements UploadContext {
        private final ContainerRequest request;
        private final String characterEncoding;

        private RequestContext(ContainerRequest request, String characterEncoding) {
            this.request = request;
            this.characterEncoding = characterEncoding;
        }

        @Override
        public String getCharacterEncoding() {
            return characterEncoding;
        }

        @Override
        public int getContentLength() {
            return request.bodyLength();
        }

        @Override
        public long contentLength() {
            return request.bodyLength();
        }

        @Override
        public String getContentType() {
            return request.firstHeader("Content-Type");
        }

        @Override
        public InputStream getInputStream() {
            return request.bodyStream();
        }
    }
}
