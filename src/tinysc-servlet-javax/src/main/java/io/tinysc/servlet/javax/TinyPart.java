package io.tinysc.servlet.javax;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.disk.DiskFileItem;

import javax.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class TinyPart implements Part {
    private final FileItem item;
    private final Path location;
    private final String contentType;
    private final String name;
    private final String submittedFileName;
    private final long size;
    private final boolean formField;
    private final Map<String, List<String>> headers;
    private final List<String> headerNames;

    TinyPart(FileItem item, Path location) {
        this.item = Objects.requireNonNull(item, "item");
        this.location = Objects.requireNonNull(location, "location")
                .toAbsolutePath().normalize();
        contentType = item.getContentType();
        name = item.getFieldName();
        submittedFileName = item.getName();
        size = item.getSize();
        formField = item.isFormField();

        HeaderSnapshot snapshot = snapshot(item.getHeaders());
        headers = snapshot.headers;
        headerNames = snapshot.names;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return item.getInputStream();
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getSubmittedFileName() {
        return submittedFileName;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void write(String fileName) throws IOException {
        Path target = resolveTarget(fileName);
        try {
            item.write(target.toFile());
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("failed to write multipart part to " + target, exception);
        }
    }

    @Override
    public void delete() throws IOException {
        File storedFile = null;
        boolean storedOnDisk = false;
        if (item instanceof DiskFileItem) {
            DiskFileItem diskItem = (DiskFileItem) item;
            storedFile = diskItem.getStoreLocation();
            storedOnDisk = !diskItem.isInMemory()
                    && storedFile != null
                    && storedFile.exists();
        }
        try {
            item.delete();
        } catch (RuntimeException exception) {
            throw new IOException("failed to delete multipart temporary storage", exception);
        }
        if (storedOnDisk && storedFile.exists()) {
            throw new IOException("failed to delete multipart temporary storage: " + storedFile);
        }
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(normalizeHeaderName(name));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        List<String> values = headers.get(normalizeHeaderName(name));
        return values == null ? Collections.<String>emptyList() : values;
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headerNames;
    }

    boolean isFormField() {
        return formField;
    }

    String stringValue(String encoding) throws IOException {
        return encoding == null || encoding.isEmpty()
                ? item.getString()
                : item.getString(encoding);
    }

    private Path resolveTarget(String fileName) throws IOException {
        if (fileName == null || fileName.isEmpty()) {
            throw new IOException("multipart destination file name must not be empty");
        }

        Path requested;
        try {
            requested = Paths.get(fileName);
        } catch (InvalidPathException exception) {
            throw new IOException("invalid multipart destination path: " + fileName, exception);
        }
        if (requested.isAbsolute()) {
            return requested.normalize();
        }

        Path target = location.resolve(requested).normalize();
        if (!target.startsWith(location)) {
            throw new IOException(
                    "relative multipart destination escapes the configured location: "
                            + fileName);
        }

        Path realLocation = location.toRealPath();
        Path parent = target.getParent();
        if (parent == null || !parent.toRealPath().startsWith(realLocation)) {
            throw new IOException(
                    "relative multipart destination escapes the configured location: "
                            + fileName);
        }
        return target;
    }

    private static HeaderSnapshot snapshot(FileItemHeaders source) {
        if (source == null) {
            return new HeaderSnapshot(Collections.<String, List<String>>emptyMap(),
                    Collections.<String>emptyList());
        }

        Map<String, List<String>> mutableHeaders =
                new LinkedHashMap<String, List<String>>();
        List<String> names = new ArrayList<String>();
        Iterator<String> headerNames = source.getHeaderNames();
        while (headerNames.hasNext()) {
            String originalName = headerNames.next();
            String normalizedName = normalizeHeaderName(originalName);
            List<String> values = mutableHeaders.get(normalizedName);
            if (values == null) {
                values = new ArrayList<String>();
                mutableHeaders.put(normalizedName, values);
                names.add(originalName);
            }
            Iterator<String> headerValues = source.getHeaders(originalName);
            while (headerValues.hasNext()) {
                values.add(headerValues.next());
            }
        }

        Map<String, List<String>> immutableHeaders =
                new LinkedHashMap<String, List<String>>(mutableHeaders.size());
        for (Map.Entry<String, List<String>> entry : mutableHeaders.entrySet()) {
            immutableHeaders.put(entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
        }
        return new HeaderSnapshot(Collections.unmodifiableMap(immutableHeaders),
                Collections.unmodifiableList(names));
    }

    private static String normalizeHeaderName(String name) {
        return Objects.requireNonNull(name, "header name").toLowerCase(Locale.ROOT);
    }

    private static final class HeaderSnapshot {
        private final Map<String, List<String>> headers;
        private final List<String> names;

        private HeaderSnapshot(Map<String, List<String>> headers, List<String> names) {
            this.headers = headers;
            this.names = names;
        }
    }
}
