package io.tinysc.integration;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseArtifactTest {
    private static final int JAVA_8_CLASS_MAJOR = 52;

    @Test
    void shadedLauncherContainsOnlyJava8ClassesAndNoJakartaServletApi() throws Exception {
        Path launcher = Paths.get(System.getProperty("tinysc.launcher.jar"));
        assertTrue(Files.isRegularFile(launcher), "shaded launcher was not built: " + launcher);

        List<String> violations = new ArrayList<String>();
        try (JarFile jar = new JarFile(launcher.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("jakarta/servlet/")) {
                    violations.add("Jakarta Servlet class: " + name);
                }
                if (name.startsWith("META-INF/versions/")) {
                    violations.add("multi-release entry: " + name);
                }
                if (name.endsWith(".class")) {
                    int major = classMajor(jar, entry);
                    if (major > JAVA_8_CLASS_MAJOR) {
                        violations.add("class major " + major + ": " + name);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private static int classMajor(JarFile jar, JarEntry entry) throws Exception {
        try (InputStream input = jar.getInputStream(entry);
             DataInputStream data = new DataInputStream(input)) {
            int magic = data.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IllegalStateException("invalid class entry: " + entry.getName());
            }
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}
