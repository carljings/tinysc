package io.tinysc.deployment;

import io.tinysc.deployment.model.WebAppDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebXmlParserTest {
    private final WebXmlParser parser = new WebXmlParser();

    @Test
    void parsesOrderedServletAndFilterMetadata() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\" version=\"3.0\">"
                + "<context-param><param-name>mode</param-name><param-value>test</param-value></context-param>"
                + "<listener><listener-class>probe.StartupListener</listener-class></listener>"
                + "<filter><filter-name>trace</filter-name><filter-class>probe.TraceFilter</filter-class>"
                + "<async-supported>true</async-supported></filter>"
                + "<filter-mapping><filter-name>trace</filter-name><url-pattern>/*</url-pattern>"
                + "<dispatcher>request</dispatcher></filter-mapping>"
                + "<servlet><servlet-name>hello</servlet-name><servlet-class>probe.HelloServlet</servlet-class>"
                + "<load-on-startup>1</load-on-startup></servlet>"
                + "<servlet-mapping><servlet-name>hello</servlet-name><url-pattern>/hello</url-pattern>"
                + "</servlet-mapping><session-config><session-timeout>60</session-timeout></session-config>"
                + "</web-app>";

        WebAppDescriptor descriptor = parser.parse(stream(xml), "test-web.xml");

        assertEquals("3.0", descriptor.version());
        assertEquals("test", descriptor.contextParams().get("mode"));
        assertEquals("probe.StartupListener", descriptor.listenerClasses().get(0));
        assertTrue(descriptor.filters().get("trace").asyncSupported());
        assertEquals("REQUEST", descriptor.filterMappings().get(0).dispatcherTypes().get(0));
        assertEquals(Integer.valueOf(1), descriptor.servlets().get("hello").loadOnStartup());
        assertEquals("/hello", descriptor.servletMappings().get(0).urlPatterns().get(0));
        assertEquals(60, descriptor.sessionTimeoutMinutes());
    }

    @Test
    void acceptsEmptyParameterValuesUsedByLegacyWars() throws Exception {
        String xml = "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"3.1\">"
                + "<context-param><param-name>context.empty</param-name><param-value/></context-param>"
                + "<filter><filter-name>upload</filter-name><filter-class>example.Upload</filter-class>"
                + "<init-param><param-name>directory</param-name><param-value></param-value></init-param>"
                + "</filter></web-app>";

        WebAppDescriptor descriptor = parser.parse(stream(xml), "empty-values.xml");

        assertEquals("", descriptor.contextParams().get("context.empty"));
        assertEquals("", descriptor.filters().get("upload").initParams().get("directory"));
    }

    @Test
    void parsesCompleteMultipartConfig() throws Exception {
        String xml = "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"3.1\">"
                + "<servlet><servlet-name>upload</servlet-name>"
                + "<servlet-class>example.UploadServlet</servlet-class>"
                + "<multipart-config><location>/var/tmp/uploads</location>"
                + "<max-file-size>1048576</max-file-size>"
                + "<max-request-size>2097152</max-request-size>"
                + "<file-size-threshold>4096</file-size-threshold>"
                + "</multipart-config></servlet></web-app>";

        WebAppDescriptor descriptor = parser.parse(stream(xml), "multipart-complete.xml");
        WebAppDescriptor.MultipartConfigDefinition multipart =
                descriptor.servlets().get("upload").multipartConfig();

        assertEquals("/var/tmp/uploads", multipart.location());
        assertEquals(1048576L, multipart.maxFileSize());
        assertEquals(2097152L, multipart.maxRequestSize());
        assertEquals(4096, multipart.fileSizeThreshold());
    }

    @Test
    void appliesMultipartConfigDefaults() throws Exception {
        String xml = "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"3.1\">"
                + "<servlet><servlet-name>upload</servlet-name>"
                + "<servlet-class>example.UploadServlet</servlet-class>"
                + "<multipart-config/></servlet></web-app>";

        WebAppDescriptor descriptor = parser.parse(stream(xml), "multipart-defaults.xml");
        WebAppDescriptor.MultipartConfigDefinition multipart =
                descriptor.servlets().get("upload").multipartConfig();

        assertEquals("", multipart.location());
        assertEquals(-1L, multipart.maxFileSize());
        assertEquals(-1L, multipart.maxRequestSize());
        assertEquals(0, multipart.fileSizeThreshold());
    }

    @Test
    void rejectsInvalidMultipartConfigLimits() {
        String[] invalidConfigs = {
                "<max-file-size>-2</max-file-size>",
                "<max-request-size>-2</max-request-size>",
                "<file-size-threshold>-1</file-size-threshold>"
        };

        for (String invalidConfig : invalidConfigs) {
            String xml = "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"3.1\">"
                    + "<servlet><servlet-name>upload</servlet-name>"
                    + "<servlet-class>example.UploadServlet</servlet-class>"
                    + "<multipart-config>" + invalidConfig + "</multipart-config>"
                    + "</servlet></web-app>";

            assertThrows(DeploymentException.class,
                    () -> parser.parse(stream(xml), "multipart-invalid.xml"));
        }
    }

    @Test
    void parsesOrderedStatusExceptionAndDefaultErrorPages() throws Exception {
        String xml = webApp(
                "<error-page><error-code>404</error-code>"
                        + "<location>/errors/not-found</location></error-page>"
                        + "<error-page><exception-type>java.io.IOException</exception-type>"
                        + "<location>/errors/io</location></error-page>"
                        + "<error-page><location>/errors/default</location></error-page>");

        WebAppDescriptor descriptor = parser.parse(stream(xml), "error-pages.xml");
        List<WebAppDescriptor.ErrorPageDefinition> pages = descriptor.errorPages();

        assertEquals(3, pages.size());
        assertEquals(Integer.valueOf(404), pages.get(0).errorCode());
        assertNull(pages.get(0).exceptionType());
        assertEquals("/errors/not-found", pages.get(0).location());
        assertNull(pages.get(1).errorCode());
        assertEquals("java.io.IOException", pages.get(1).exceptionType());
        assertEquals("/errors/io", pages.get(1).location());
        assertNull(pages.get(2).errorCode());
        assertNull(pages.get(2).exceptionType());
        assertEquals("/errors/default", pages.get(2).location());
    }

    @Test
    void acceptsThreeDigitNonzeroErrorCodeWithLeadingZeros() throws Exception {
        WebAppDescriptor descriptor = parser.parse(stream(webApp(
                "<error-page><error-code>001</error-code>"
                        + "<location>/errors/one</location></error-page>")), "error-code.xml");

        assertEquals(Integer.valueOf(1), descriptor.errorPages().get(0).errorCode());
    }

    @Test
    void rejectsErrorPageWithBothSelectors() {
        assertInvalidErrorPages(
                "<error-page><error-code>500</error-code>"
                        + "<exception-type>java.lang.Exception</exception-type>"
                        + "<location>/errors/server</location></error-page>");
    }

    @Test
    void rejectsMalformedErrorCodes() {
        String[] invalidCodes = {"99", "1000", "000", "-01", "4x4"};

        for (String invalidCode : invalidCodes) {
            assertInvalidErrorPages(
                    "<error-page><error-code>" + invalidCode + "</error-code>"
                            + "<location>/errors/status</location></error-page>");
        }
    }

    @Test
    void rejectsMissingBlankRelativeOrRepeatedErrorPageLocations() {
        String[] invalidPages = {
                "<error-page><error-code>404</error-code></error-page>",
                "<error-page><error-code>404</error-code><location/></error-page>",
                "<error-page><error-code>404</error-code><location> </location></error-page>",
                "<error-page><error-code>404</error-code>"
                        + "<location>errors/not-found</location></error-page>",
                "<error-page><error-code>404</error-code>"
                        + "<location>/errors/one</location><location>/errors/two</location>"
                        + "</error-page>"
        };

        for (String invalidPage : invalidPages) {
            assertInvalidErrorPages(invalidPage);
        }
    }

    @Test
    void rejectsDuplicateErrorPageSelectors() {
        String[] duplicatePages = {
                "<error-page><error-code>404</error-code>"
                        + "<location>/errors/first</location></error-page>"
                        + "<error-page><error-code>404</error-code>"
                        + "<location>/errors/second</location></error-page>",
                "<error-page><exception-type>java.io.IOException</exception-type>"
                        + "<location>/errors/first</location></error-page>"
                        + "<error-page><exception-type>java.io.IOException</exception-type>"
                        + "<location>/errors/second</location></error-page>",
                "<error-page><location>/errors/first</location></error-page>"
                        + "<error-page><location>/errors/second</location></error-page>"
        };

        for (String duplicatePage : duplicatePages) {
            assertInvalidErrorPages(duplicatePage);
        }
    }

    @Test
    void rejectsDoctypeAndExternalEntities() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE web-app [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<web-app version=\"3.0\"><context-param><param-name>x</param-name>"
                + "<param-value>&xxe;</param-value></context-param></web-app>";

        assertThrows(DeploymentException.class,
                () -> parser.parse(stream(xml), "xxe-web.xml"));
    }

    private void assertInvalidErrorPages(String errorPages) {
        assertThrows(DeploymentException.class,
                () -> parser.parse(stream(webApp(errorPages)), "invalid-error-pages.xml"));
    }

    private static String webApp(String contents) {
        return "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"3.1\">"
                + contents + "</web-app>";
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
