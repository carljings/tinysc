package io.tinysc.servlet.javax;

import io.tinysc.deployment.PreparedWebApp;
import io.tinysc.deployment.WarDeploymentManager;
import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.ContainerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.http.Cookie;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EventListener;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinyHttpServletRequestTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesAttributesOnlyWhenTheFirstValueIsSetAndPreservesListenerEvents()
            throws Exception {
        RecordingAttributeListener listener = new RecordingAttributeListener();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory, listener)) {
            TinyHttpServletRequest request = fixture.request(requestBuilder().build());

            assertNull(attributes(request));
            assertNull(request.getAttribute("missing"));
            assertFalse(request.getAttributeNames().hasMoreElements());
            request.removeAttribute("missing");
            request.setAttribute("missing", null);
            assertNull(attributes(request));

            request.setAttribute("key", "first");
            assertNotNull(attributes(request));
            request.setAttribute("key", "second");
            request.removeAttribute("key");

            assertEquals(Arrays.asList(
                    "added:key=first",
                    "replaced:key=first",
                    "removed:key=second"), listener.events);
        }
    }

    @Test
    void preservesAttributeListenerSnapshotAfterMutableListenerListIsCleared()
            throws Exception {
        RecordingAttributeListener listener = new RecordingAttributeListener();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory, listener)) {
            fixture.clearListeners();
            TinyHttpServletRequest request = fixture.request(requestBuilder().build());

            request.setAttribute("key", "first");
            request.setAttribute("key", "second");
            request.removeAttribute("key");

            assertEquals(Arrays.asList(
                    "added:key=first",
                    "replaced:key=first",
                    "removed:key=second"), listener.events);
        }
    }

    @Test
    void returnsNullForMissingCookiesAndKeepsValidCookiesAroundInvalidOnes()
            throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            TinyHttpServletRequest withoutCookies = fixture.request(requestBuilder().build());
            assertNull(withoutCookies.getCookies());

            TinyHttpServletRequest withCookies = fixture.request(requestBuilder()
                    .addHeader("Cookie", "first=one; invalid name=ignored; second=two")
                    .addHeader("Cookie", "third=three")
                    .build());

            Cookie[] cookies = withCookies.getCookies();
            assertNotNull(cookies);
            assertEquals(Arrays.asList("first=one", "second=two", "third=three"),
                    cookieValues(cookies));
            assertNotSame(cookies, withCookies.getCookies());
        }
    }

    @Test
    void parsesRequestedSessionCookieOnlyWhenSessionApiIsUsed() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            TinyHttpServletRequest request = fixture.request(requestBuilder()
                    .addHeader("Cookie", "JSESSIONID=session-id")
                    .build());

            assertFalse(booleanField(request, "cookiesParsed"));
            assertEquals("session-id", request.getRequestedSessionId());
            assertTrue(booleanField(request, "cookiesParsed"));
            assertTrue(request.isRequestedSessionIdFromCookie());
        }
    }

    @Test
    void newServerSessionDoesNotBecomeAClientRequestedSession() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            TinyHttpServletRequest request = fixture.request(requestBuilder().build());

            assertNotNull(request.getSession());
            assertNull(request.getRequestedSessionId());
            assertFalse(request.isRequestedSessionIdFromCookie());

            request.changeSessionId();
            assertNull(request.getRequestedSessionId());
            assertFalse(request.isRequestedSessionIdFromCookie());
        }
    }

    @Test
    void changedClientSessionKeepsRequestedCookieSemantics() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            String originalId = fixture.sessions.create().getId();
            TinyHttpServletRequest request = fixture.request(requestBuilder()
                    .addHeader("Cookie", "JSESSIONID=" + originalId)
                    .build());

            assertNotNull(request.getSession(false));
            String changedId = request.changeSessionId();
            assertEquals(changedId, request.getRequestedSessionId());
            assertTrue(request.isRequestedSessionIdFromCookie());
        }
    }

    @Test
    void usesAnEmptyRequestAttributeListenerSnapshotWhenNoListenersWereRegistered()
            throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRequestAttributeListener[] listeners =
                    RuntimeFixture.field(fixture.runtime, "requestAttributeListeners",
                            ServletRequestAttributeListener[].class);
            assertEquals(0, listeners.length);
        }
    }

    @Test
    void rejectsMultipartAccessWithoutServletConfiguration() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            final TinyHttpServletRequest request = fixture.request(requestBuilder()
                    .method("POST")
                    .addHeader("Content-Type", "multipart/form-data; boundary=missing-config")
                    .build());

            assertNull(request.getParameter("field"));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    new Executable() {
                        @Override
                        public void execute() throws Throwable {
                            request.getParts();
                        }
                    });
            assertTrue(failure.getMessage().contains("not configured"));
        }
    }

    private static ContainerRequest.Builder requestBuilder() {
        return ContainerRequest.builder()
                .method("GET")
                .rawUri("/")
                .path("/");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(TinyHttpServletRequest request)
            throws Exception {
        Field field = TinyHttpServletRequest.class.getDeclaredField("attributes");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(request);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static List<String> cookieValues(Cookie[] cookies) {
        List<String> values = new ArrayList<String>();
        for (Cookie cookie : cookies) {
            values.add(cookie.getName() + "=" + cookie.getValue());
        }
        return values;
    }

    private static final class RecordingAttributeListener
            implements ServletRequestAttributeListener {
        private final List<String> events = new ArrayList<String>();

        @Override
        public void attributeAdded(ServletRequestAttributeEvent event) {
            events.add("added:" + event.getName() + "=" + event.getValue());
        }

        @Override
        public void attributeRemoved(ServletRequestAttributeEvent event) {
            events.add("removed:" + event.getName() + "=" + event.getValue());
        }

        @Override
        public void attributeReplaced(ServletRequestAttributeEvent event) {
            events.add("replaced:" + event.getName() + "=" + event.getValue());
        }
    }

    private static final class RuntimeFixture implements AutoCloseable {
        private final PreparedWebApp application;
        private final JavaxServletRuntime runtime;
        private final TinyServletContext context;
        private final TinySessionManager sessions;

        private RuntimeFixture(PreparedWebApp application, JavaxServletRuntime runtime,
                               TinyServletContext context, TinySessionManager sessions) {
            this.application = application;
            this.runtime = runtime;
            this.context = context;
            this.sessions = sessions;
        }

        private static RuntimeFixture open(Path temporaryDirectory) throws Exception {
            return open(temporaryDirectory, new EventListener[0]);
        }

        private static RuntimeFixture open(Path temporaryDirectory,
                                           EventListener... listeners) throws Exception {
            Path webRoot = temporaryDirectory.resolve("webapp");
            Files.createDirectories(webRoot.resolve("WEB-INF"));
            PreparedWebApp application = new WarDeploymentManager().prepare(
                    webRoot, temporaryDirectory.resolve("base"), "",
                    TinyHttpServletRequestTest.class.getClassLoader());
            JavaxServletRuntime runtime = new JavaxServletRuntime(application, "");
            try {
                for (EventListener listener : listeners) {
                    runtime.addListener(listener);
                }
                runtime.start();
                return new RuntimeFixture(application, runtime,
                        field(runtime, "servletContext", TinyServletContext.class),
                        field(runtime, "sessionManager", TinySessionManager.class));
            } catch (Exception failure) {
                application.close();
                throw failure;
            }
        }

        private TinyHttpServletRequest request(ContainerRequest containerRequest) {
            ContainerExchange exchange = new ContainerExchange(containerRequest);
            return new TinyHttpServletRequest(exchange,
                    new TinyHttpServletResponse(exchange.response()), context, sessions,
                    null, containerRequest.path(), false, null);
        }

        @SuppressWarnings("unchecked")
        private void clearListeners() throws Exception {
            field(runtime, "listeners", List.class).clear();
        }

        private static <T> T field(Object target, String name, Class<T> type)
                throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        }

        @Override
        public void close() throws Exception {
            try {
                runtime.stop(Duration.ZERO);
            } finally {
                application.close();
            }
        }
    }
}
