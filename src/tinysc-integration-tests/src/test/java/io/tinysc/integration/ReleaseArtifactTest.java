package io.tinysc.integration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    @Test
    void shadedLauncherContainsProjectAndThirdPartyLicenseTerms() throws Exception {
        Path launcher = Paths.get(System.getProperty("tinysc.launcher.jar"));
        assertTrue(Files.isRegularFile(launcher), "shaded launcher was not built: " + launcher);

        try (JarFile jar = new JarFile(launcher.toFile())) {
            String license = readUtf8(jar, "META-INF/LICENSE");
            String notice = readUtf8(jar, "META-INF/NOTICE");
            String inventory = readUtf8(jar, "META-INF/licenses/README.md");
            String asmLicense = readUtf8(jar, "META-INF/licenses/ASM-9.8-LICENSE.txt");
            String servletLicense = readUtf8(
                    jar, "META-INF/licenses/javax.servlet-api-3.1.0-LICENSE.txt");

            assertTrue(license.contains("Apache License"));
            assertTrue(license.contains("Version 2.0"));
            assertTrue(notice.contains("TinySC contributors"));
            assertTrue(notice.contains("Apache Commons Compress"));
            assertTrue(notice.contains("Apache Commons FileUpload"));
            assertTrue(notice.contains("Apache Commons IO"));
            assertTrue(notice.contains("Apache Commons Codec"));
            assertTrue(notice.contains("Apache Commons Lang"));
            assertTrue(inventory.contains("Netty"));
            assertTrue(inventory.contains("4.2.16.Final"));
            assertTrue(inventory.contains("JCTools Core"));
            assertTrue(inventory.contains("4.0.6"));
            assertTrue(inventory.contains("ASM"));
            assertTrue(inventory.contains("9.8"));
            assertTrue(inventory.contains("javax.servlet-api"));
            assertTrue(inventory.contains("3.1.0"));
            assertTrue(asmLicense.contains("Copyright (c) 2000-2011 INRIA, France Telecom"));
            assertTrue(servletLicense.contains(
                    "COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0"));
            assertTrue(servletLicense.contains("\"CLASSPATH\" EXCEPTION TO THE GPL VERSION 2"));
            assertTrue(jar.getJarEntry("META-INF/LICENSE.txt") == null,
                    "generic dependency LICENSE.txt must not win the shade merge");
            assertTrue(jar.getJarEntry("META-INF/NOTICE.txt") == null,
                    "generic dependency NOTICE.txt must not win the shade merge");
        }
    }

    private static String readUtf8(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        assertTrue(entry != null, "missing shaded launcher resource: " + name);
        try (InputStream input = jar.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
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
