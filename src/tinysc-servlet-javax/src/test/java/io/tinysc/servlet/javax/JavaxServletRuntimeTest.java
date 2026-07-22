package io.tinysc.servlet.javax;

import io.tinysc.deployment.PreparedWebApp;
import io.tinysc.deployment.WarDeploymentManager;
import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.ContainerRequest;
import io.tinysc.kernel.LifecycleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EventListener;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaxServletRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invokesEachMatchingFilterOnceInFirstMappingOrderAndCombinesAsyncSupport()
            throws Exception {
        List<String> events = new ArrayList<String>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "target", new AsyncStateServlet(events));
            servlet.setAsyncSupported(true);
            assertTrue(servlet.addMapping("/work").isEmpty());

            FilterRegistration.Dynamic first = fixture.runtime.addFilter(
                    "first", new RecordingFilter("first", events));
            first.setAsyncSupported(true);
            first.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/work");

            FilterRegistration.Dynamic second = fixture.runtime.addFilter(
                    "second", new RecordingFilter("second", events));
            second.setAsyncSupported(false);
            second.addMappingForServletNames(
                    EnumSet.of(DispatcherType.REQUEST), true, "target");

            first.addMappingForServletNames(
                    EnumSet.of(DispatcherType.REQUEST), true, "target");
            fixture.start();

            fixture.runtime.service(exchange("/work"));

            assertEquals(Arrays.asList("first", "second", "servlet:false"), events);
        }
    }

    @Test
    void appliesForwardFilterMappingsOnceInDeclarationOrder() throws Exception {
        List<String> events = new ArrayList<String>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic entry = fixture.runtime.addServlet(
                    "entry", new ForwardingServlet("/target", events));
            assertTrue(entry.addMapping("/entry").isEmpty());

            ServletRegistration.Dynamic target = fixture.runtime.addServlet(
                    "target", new DispatcherTypeServlet(events));
            target.setAsyncSupported(true);
            assertTrue(target.addMapping("/target").isEmpty());

            FilterRegistration.Dynamic shared = fixture.runtime.addFilter(
                    "shared", new DispatcherRecordingFilter("shared", events));
            shared.addMappingForUrlPatterns(EnumSet.of(DispatcherType.FORWARD), true, "/target");
            shared.addMappingForServletNames(
                    EnumSet.of(DispatcherType.FORWARD), true, "target");

            FilterRegistration.Dynamic named = fixture.runtime.addFilter(
                    "named", new DispatcherRecordingFilter("named", events));
            named.setAsyncSupported(false);
            named.addMappingForServletNames(
                    EnumSet.of(DispatcherType.FORWARD), true, "target");

            FilterRegistration.Dynamic requestOnly = fixture.runtime.addFilter(
                    "request-only", new DispatcherRecordingFilter("request-only", events));
            requestOnly.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST), true, "/target");

            fixture.start();

            fixture.runtime.service(exchange("/entry"));

            assertEquals(Arrays.asList(
                    "entry:REQUEST",
                    "shared:FORWARD",
                    "named:FORWARD",
                    "target:FORWARD:false"), events);
        }
    }

    @Test
    void invokesSameFilterInstanceOncePerRegistrationName() throws Exception {
        List<String> events = new ArrayList<String>();
        RecordingFilter shared = new RecordingFilter("shared", events);
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "target", new AsyncStateServlet(events));
            servlet.setAsyncSupported(true);
            assertTrue(servlet.addMapping("/work").isEmpty());

            FilterRegistration.Dynamic first = fixture.runtime.addFilter("first", shared);
            first.setAsyncSupported(true);
            first.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/work");
            first.addMappingForServletNames(
                    EnumSet.of(DispatcherType.REQUEST), true, "target");

            FilterRegistration.Dynamic second = fixture.runtime.addFilter("second", shared);
            second.setAsyncSupported(false);
            second.addMappingForServletNames(
                    EnumSet.of(DispatcherType.REQUEST), true, "target");

            fixture.start();

            fixture.runtime.service(exchange("/work"));

            assertEquals(Arrays.asList(
                    "shared",
                    "shared",
                    "servlet:false"), events);
        }
    }

    @Test
    void appliesUrlPatternMappingsBeforeServletNameMappings() throws Exception {
        List<String> events = new ArrayList<String>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "target", new AsyncStateServlet(events));
            servlet.setAsyncSupported(true);
            assertTrue(servlet.addMapping("/work").isEmpty());

            FilterRegistration.Dynamic namedFirst = fixture.runtime.addFilter(
                    "named-first", new RecordingFilter("named-first", events));
            namedFirst.addMappingForServletNames(
                    EnumSet.of(DispatcherType.REQUEST), true, "target");

            FilterRegistration.Dynamic urlSecond = fixture.runtime.addFilter(
                    "url-second", new RecordingFilter("url-second", events));
            urlSecond.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST), true, "/work");
            urlSecond.addMappingForServletNames(
                    EnumSet.of(DispatcherType.REQUEST), true, "target");

            FilterRegistration.Dynamic namedThird = fixture.runtime.addFilter(
                    "named-third", new RecordingFilter("named-third", events));
            namedThird.addMappingForServletNames(
                    EnumSet.of(DispatcherType.REQUEST), true, "target");

            fixture.start();

            fixture.runtime.service(exchange("/work"));

            assertEquals(Arrays.asList(
                    "url-second",
                    "named-first",
                    "named-third",
                    "servlet:false"), events);
        }
    }

    @Test
    void preservesProgrammaticPrependCallOrder() throws Exception {
        List<String> events = new ArrayList<String>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "target", new AsyncStateServlet(events));
            servlet.setAsyncSupported(true);
            assertTrue(servlet.addMapping("/work").isEmpty());

            FilterRegistration.Dynamic trailing = fixture.runtime.addFilter(
                    "trailing", new RecordingFilter("trailing", events));
            trailing.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST), true, "/work");

            FilterRegistration.Dynamic first = fixture.runtime.addFilter(
                    "first", new RecordingFilter("first", events));
            first.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST), false, "/work");

            FilterRegistration.Dynamic second = fixture.runtime.addFilter(
                    "second", new RecordingFilter("second", events));
            second.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST), false, "/work");

            FilterRegistration.Dynamic third = fixture.runtime.addFilter(
                    "third", new RecordingFilter("third", events));
            third.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST), false, "/work");

            fixture.start();

            fixture.runtime.service(exchange("/work"));

            assertEquals(Arrays.asList(
                    "first",
                    "second",
                    "third",
                    "trailing",
                    "servlet:false"), events);
        }
    }

    @Test
    void stopWaitsForAcceptedRequestsAndRejectsRequestsAfterQuiesce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> requestFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<Throwable>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "target", new BlockingServlet(entered, release, null));
            assertTrue(servlet.addMapping("/work").isEmpty());
            fixture.start();

            Thread requestThread = serviceInThread(fixture.runtime, "/work", requestFailure);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            Thread stopThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        fixture.runtime.stop(Duration.ofSeconds(5));
                    } catch (Throwable failure) {
                        stopFailure.set(failure);
                    }
                }
            }, "tinysc-runtime-stop");
            stopThread.start();

            assertTrue(awaitState(fixture.runtime, LifecycleState.QUIESCING, 5000L));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    new Executable() {
                        @Override
                        public void execute() throws Throwable {
                            fixture.runtime.service(exchange("/work"));
                        }
                    });
            assertTrue(failure.getMessage().contains("QUIESCING"));
            assertTrue(stopThread.isAlive());

            release.countDown();
            requestThread.join(5000L);
            stopThread.join(5000L);

            assertNull(requestFailure.get());
            assertNull(stopFailure.get());
            assertEquals(LifecycleState.STOPPED, fixture.runtime.state());
        }
    }

    @Test
    void preservesRequestListenerOrderAfterGraceTimeoutClearsListenerList() throws Exception {
        List<String> events = new ArrayList<String>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> requestFailure = new AtomicReference<Throwable>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "target", new BlockingServlet(entered, release, events));
            assertTrue(servlet.addMapping("/work").isEmpty());
            fixture.runtime.addListener(new RecordingRequestListener("first", events));
            fixture.runtime.addListener(new RecordingRequestListener("second", events));
            fixture.start();

            Thread requestThread = serviceInThread(fixture.runtime, "/work", requestFailure);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.runtime.stop(Duration.ZERO);
            assertEquals(LifecycleState.STOPPED, fixture.runtime.state());
            assertTrue(listeners(fixture.runtime).isEmpty());
            release.countDown();
            requestThread.join(5000L);

            assertEquals(Arrays.asList(
                    "initialized:first",
                    "initialized:second",
                    "servlet",
                    "destroyed:second",
                    "destroyed:first"), events);
            assertNull(requestFailure.get());
        }
    }

    private static ContainerExchange exchange(String path) {
        return new ContainerExchange(ContainerRequest.builder()
                .method("GET")
                .rawUri(path)
                .path(path)
                .build());
    }

    private static final class RecordingFilter implements Filter {
        private final String name;
        private final List<String> events;

        private RecordingFilter(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                             FilterChain chain) throws IOException, ServletException {
            events.add(name);
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }

    private static final class AsyncStateServlet extends HttpServlet {
        private final List<String> events;

        private AsyncStateServlet(List<String> events) {
            this.events = events;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            events.add("servlet:" + request.isAsyncSupported());
        }
    }

    private static final class ForwardingServlet extends HttpServlet {
        private final String targetPath;
        private final List<String> events;

        private ForwardingServlet(String targetPath, List<String> events) {
            this.targetPath = targetPath;
            this.events = events;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            events.add("entry:" + request.getDispatcherType().name());
            request.getRequestDispatcher(targetPath).forward(request, response);
        }
    }

    private static final class DispatcherRecordingFilter implements Filter {
        private final String name;
        private final List<String> events;

        private DispatcherRecordingFilter(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                             FilterChain chain) throws IOException, ServletException {
            events.add(name + ":" + request.getDispatcherType().name());
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }

    private static final class DispatcherTypeServlet extends HttpServlet {
        private final List<String> events;

        private DispatcherTypeServlet(List<String> events) {
            this.events = events;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            events.add("target:" + request.getDispatcherType().name()
                    + ":" + request.isAsyncSupported());
        }
    }

    private static final class BlockingServlet extends HttpServlet {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final List<String> events;

        private BlockingServlet(CountDownLatch entered, CountDownLatch release,
                                List<String> events) {
            this.entered = entered;
            this.release = release;
            this.events = events;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            if (events != null) {
                events.add("servlet");
            }
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class RecordingRequestListener implements ServletRequestListener {
        private final String name;
        private final List<String> events;

        private RecordingRequestListener(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void requestDestroyed(ServletRequestEvent event) {
            events.add("destroyed:" + name);
        }

        @Override
        public void requestInitialized(ServletRequestEvent event) {
            events.add("initialized:" + name);
        }
    }

    private static final class RuntimeFixture implements AutoCloseable {
        private final PreparedWebApp application;
        private final JavaxServletRuntime runtime;
        private boolean started;

        private RuntimeFixture(PreparedWebApp application, JavaxServletRuntime runtime) {
            this.application = application;
            this.runtime = runtime;
        }

        private static RuntimeFixture open(Path temporaryDirectory) throws Exception {
            Path webRoot = temporaryDirectory.resolve("webapp");
            Files.createDirectories(webRoot.resolve("WEB-INF"));
            PreparedWebApp application = new WarDeploymentManager().prepare(
                    webRoot, temporaryDirectory.resolve("base"), "",
                    JavaxServletRuntimeTest.class.getClassLoader());
            return new RuntimeFixture(application, new JavaxServletRuntime(application, ""));
        }

        private void start() throws Exception {
            runtime.start();
            started = true;
        }

        @Override
        public void close() throws Exception {
            try {
                if (started) {
                    runtime.stop(Duration.ZERO);
                }
            } finally {
                application.close();
            }
        }
    }

    private static Thread serviceInThread(final JavaxServletRuntime runtime, final String path,
                                          final AtomicReference<Throwable> failure) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.service(exchange(path));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }
        }, "tinysc-runtime-service");
        thread.start();
        return thread;
    }

    private static boolean awaitState(JavaxServletRuntime runtime, LifecycleState expected,
                                      long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (runtime.state() == expected) {
                return true;
            }
            Thread.sleep(10L);
        }
        return runtime.state() == expected;
    }

    @SuppressWarnings("unchecked")
    private static List<EventListener> listeners(JavaxServletRuntime runtime) throws Exception {
        Field field = JavaxServletRuntime.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return (List<EventListener>) field.get(runtime);
    }
}
