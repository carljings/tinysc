package io.tinysc.deployment;

import io.tinysc.deployment.model.WebAppDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsDoctypeAndExternalEntities() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE web-app [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<web-app version=\"3.0\"><context-param><param-name>x</param-name>"
                + "<param-value>&xxe;</param-value></context-param></web-app>";

        assertThrows(DeploymentException.class,
                () -> parser.parse(stream(xml), "xxe-web.xml"));
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
