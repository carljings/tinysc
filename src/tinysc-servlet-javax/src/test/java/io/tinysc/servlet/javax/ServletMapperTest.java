package io.tinysc.servlet.javax;

import io.tinysc.deployment.model.WebAppDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void reusesThePrebuiltResultForExactMappings() {
        ServletMapper mapper = new ServletMapper(Arrays.asList(
                mapping("root", ""),
                mapping("login", "/login")));

        ServletMappingResult first = mapper.map("/login");
        ServletMappingResult second = mapper.map("/login");
        ServletMappingResult root = mapper.map("/");

        assertSame(first, second);
        assertEquals("/login", first.servletPath());
        assertNull(first.pathInfo());
        assertSame(root, mapper.map("/"));
        assertEquals("root", root.servletName());
        assertEquals("", root.servletPath());
    }

    @Test
    void observesPathAndExtensionPatternBoundaries() {
        ServletMapper mapper = new ServletMapper(Arrays.asList(
                mapping("default", "/"),
                mapping("api", "/api/*"),
                mapping("action", "*.do")));

        assertEquals("api", mapper.map("/api").servletName());
        assertEquals("api", mapper.map("/api/").servletName());
        assertEquals("/", mapper.map("/api/").pathInfo());
        assertEquals("default", mapper.map("/api2").servletName());
        assertEquals("default", mapper.map("/api.v2").servletName());

        assertEquals("action", mapper.map("/submit.do").servletName());
        assertEquals("action", mapper.map("/nested/submit.do").servletName());
        assertEquals("default", mapper.map("/submit.DO").servletName());
        assertEquals("default", mapper.map("/submit.do/more").servletName());
        assertEquals("default", mapper.map("/submit.do.more").servletName());
    }

    @Test
    void keepsPathMappingsAheadOfTheDefaultMappingAtTheRoot() {
        ServletMapper mapper = new ServletMapper(Arrays.asList(
                mapping("default", "/"),
                mapping("all", "/*")));

        ServletMappingResult result = mapper.map("/");

        assertEquals("all", result.servletName());
        assertEquals("", result.servletPath());
        assertEquals("/", result.pathInfo());
    }

    private static WebAppDescriptor.ServletMapping mapping(String name, String pattern) {
        return new WebAppDescriptor.ServletMapping(name, Arrays.asList(pattern));
    }
}
