package io.tinysc.servlet.javax;

import io.tinysc.deployment.model.WebAppDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServletMapperTest {
    @Test
    void appliesServletSpecificationMappingPrecedence() {
        ServletMapper mapper = new ServletMapper(Arrays.asList(
                mapping("default", "/"),
                mapping("extension", "*.do"),
                mapping("path", "/api/*"),
                mapping("long-path", "/api/admin/*"),
                mapping("exact", "/api/admin/login.do")));

        assertEquals("exact", mapper.map("/api/admin/login.do").servletName());
        assertEquals("long-path", mapper.map("/api/admin/users.do").servletName());
        assertEquals("path", mapper.map("/api/status.do").servletName());
        assertEquals("extension", mapper.map("/status.do").servletName());
        assertEquals("default", mapper.map("/readme.txt").servletName());
    }

    @Test
    void exposesServletPathAndPathInfoForPathMapping() {
        ServletMapper mapper = new ServletMapper(Arrays.asList(mapping("api", "/api/*")));

        ServletMappingResult nested = mapper.map("/api/users/7");
        assertEquals("/api", nested.servletPath());
        assertEquals("/users/7", nested.pathInfo());

        ServletMappingResult root = mapper.map("/api");
        assertEquals("/api", root.servletPath());
        assertNull(root.pathInfo());
    }

    private static WebAppDescriptor.ServletMapping mapping(String name, String pattern) {
        return new WebAppDescriptor.ServletMapping(name, Arrays.asList(pattern));
    }
}
