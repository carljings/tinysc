package io.tinysc.servlet.javax;

import io.tinysc.deployment.PreparedWebApp;
import io.tinysc.deployment.WarDeploymentManager;
import io.tinysc.kernel.ContainerExchange;
import io.tinysc.kernel.ContainerRequest;
import io.tinysc.kernel.LifecycleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.MultipartConfigElement;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void appliesDynamicMultipartConfigAndCleansTemporaryPartsAfterTheRequest()
            throws Exception {
        AtomicReference<Part> uploadedPart = new AtomicReference<Part>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "upload", new MultipartServlet(uploadedPart));
            servlet.setMultipartConfig(new MultipartConfigElement("", 64L, 1024L, 0));
            assertTrue(servlet.addMapping("/upload").isEmpty());
            fixture.start();

            ContainerExchange exchange = multipartExchange("/upload", "tiny-boundary",
                    "title", "monthly",
                    "document", "plan.txt", "text/plain", "123456789");
            fixture.runtime.service(exchange);

            assertEquals(200, exchange.response().status());
            assertEquals("title=monthly;titles=[monthly, monthly-again];"
                            + "file=plan.txt;size=9",
                    new String(exchange.response().bodyBytes(), StandardCharsets.UTF_8));
            assertTrue(uploadedPart.get() != null);
            assertEquals(0L, fileCount(servletTempDirectory(fixture.runtime)));
            assertThrows(IOException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    uploadedPart.get().getInputStream();
                }
            });
        }
    }

    @Test
    void appliesMultipartConfigAnnotationWhenNoExplicitConfigExists() throws Exception {
        AtomicReference<Part> uploadedPart = new AtomicReference<Part>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "annotated-upload", new MultipartServlet(uploadedPart));
            assertTrue(servlet.addMapping("/annotated-upload").isEmpty());
            fixture.start();

            ContainerExchange accepted = multipartExchange(
                    "/annotated-upload", "annotation-boundary",
                    "title", "m",
                    "document", "small.txt", "text/plain", "abc");
            fixture.runtime.service(accepted);

            assertEquals(200, accepted.response().status());
            assertEquals("title=m;titles=[m, m-again];"
                            + "file=small.txt;size=3",
                    new String(accepted.response().bodyBytes(), StandardCharsets.UTF_8));

            ContainerExchange rejected = multipartExchange(
                    "/annotated-upload", "annotation-limit-boundary",
                    "title", "m",
                    "document", "large.txt", "text/plain", "123456789");
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, new Executable() {
                        @Override
                        public void execute() throws Throwable {
                            fixture.runtime.service(rejected);
                        }
                    });
            assertTrue(failure.getMessage().contains("size limit exceeded"));
        }
    }

    @Test
    void cleansMultipartPartsWhenAsyncProcessingActuallyCompletes() throws Exception {
        AtomicReference<Part> uploadedPart = new AtomicReference<Part>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "async-upload", new AsyncMultipartServlet(uploadedPart));
            servlet.setAsyncSupported(true);
            servlet.setMultipartConfig(new MultipartConfigElement("", 64L, 1024L, 0));
            assertTrue(servlet.addMapping("/async-upload").isEmpty());
            fixture.start();

            ContainerExchange exchange = multipartExchange(
                    "/async-upload", "async-boundary",
                    "title", "monthly",
                    "document", "plan.txt", "text/plain", "content");
            fixture.runtime.service(exchange);

            assertTrue(uploadedPart.get() != null);
            assertEquals(0L, fileCount(servletTempDirectory(fixture.runtime)));
            assertThrows(IOException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    uploadedPart.get().getInputStream();
                }
            });
        }
    }

    @Test
    void propagatesParameterAccessFailureWhenConfiguredMultipartExceedsTheFileLimit()
            throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory)) {
            ServletRegistration.Dynamic servlet = fixture.runtime.addServlet(
                    "parameter-upload", new MultipartParameterServlet());
            servlet.setMultipartConfig(new MultipartConfigElement("", 4L, 1024L, 0));
            assertTrue(servlet.addMapping("/parameter-upload").isEmpty());
            fixture.start();

            ContainerExchange exchange = multipartExchange(
                    "/parameter-upload", "parameter-boundary",
                    "title", "monthly",
                    "document", "plan.txt", "text/plain", "content");
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, new Executable() {
                        @Override
                        public void execute() throws Throwable {
                            fixture.runtime.service(exchange);
                        }
                    });
            assertTrue(failure.getMessage().contains("size limit exceeded"));
        }
    }

    @Test
    void dispatchesConfiguredErrorPageForSendError404() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                statusErrorPage(404, "/errors/not-found"))) {
            mapServlet(fixture, "entry", "/entry",
                    new SendErrorServlet(404, "missing"));
            mapServlet(fixture, "not-found", "/errors/not-found",
                    new ErrorPageServlet("configured-404", null));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(404, exchange.response().status());
            assertEquals("configured-404:ERROR", body(exchange));
        }
    }

    @Test
    void doesNotDispatchErrorPageForSetStatus404() throws Exception {
        List<String> events = new ArrayList<String>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                statusErrorPage(404, "/errors/not-found"))) {
            mapServlet(fixture, "entry", "/entry",
                    new SetStatusServlet(404, "plain-404"));
            mapServlet(fixture, "not-found", "/errors/not-found",
                    new ErrorPageServlet("configured-404", events));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(404, exchange.response().status());
            assertEquals("plain-404", body(exchange));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void usesDefaultErrorPageWhenStatusIsUnmapped() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                defaultErrorPage("/errors/default"))) {
            mapServlet(fixture, "entry", "/entry",
                    new SendErrorServlet(418, "teapot"));
            mapServlet(fixture, "default-error", "/errors/default",
                    new ErrorPageServlet("default-error", null));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(418, exchange.response().status());
            assertEquals("default-error:ERROR", body(exchange));
        }
    }

    @Test
    void dispatchesClosestMatchingExceptionType() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                exceptionErrorPage(RuntimeException.class, "/errors/runtime"),
                exceptionErrorPage(IllegalArgumentException.class,
                        "/errors/illegal-argument"))) {
            mapServlet(fixture, "entry", "/entry",
                    new ThrowingServlet(new NumberFormatException("not a number")));
            mapServlet(fixture, "runtime-error", "/errors/runtime",
                    new ErrorPageServlet("runtime", null));
            mapServlet(fixture, "illegal-argument-error", "/errors/illegal-argument",
                    new ErrorPageServlet("illegal-argument", null));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(500, exchange.response().status());
            assertEquals("illegal-argument:ERROR", body(exchange));
        }
    }

    @Test
    void dispatchesServletExceptionRootCauseWhenOuterTypeIsUnmapped() throws Exception {
        IOException rootCause = new IOException("disk failed");
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                exceptionErrorPage(IOException.class, "/errors/io"))) {
            mapServlet(fixture, "entry", "/entry",
                    new ThrowingServlet(new ServletException("wrapped", rootCause)));
            mapServlet(fixture, "io-error", "/errors/io",
                    new ErrorPageServlet("io-root-cause", null));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(500, exchange.response().status());
            assertEquals("io-root-cause:ERROR", body(exchange));
        }
    }

    @Test
    void fallsBackFromExceptionToConfigured500Page() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                statusErrorPage(500, "/errors/server"))) {
            mapServlet(fixture, "entry", "/entry",
                    new ThrowingServlet(new IOException("unmapped")));
            mapServlet(fixture, "server-error", "/errors/server",
                    new ErrorPageServlet("configured-500", null));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(500, exchange.response().status());
            assertEquals("configured-500:ERROR", body(exchange));
        }
    }

    @Test
    void invokesOnlyErrorMappedFiltersDuringErrorDispatch() throws Exception {
        List<String> events = new ArrayList<String>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                statusErrorPage(404, "/errors/not-found"))) {
            mapServlet(fixture, "entry", "/entry",
                    new SendErrorServlet(404, "missing"));
            mapServlet(fixture, "not-found", "/errors/not-found",
                    new ErrorPageServlet("handler", events));

            FilterRegistration.Dynamic requestOnly = fixture.runtime.addFilter(
                    "request-only", new DispatcherRecordingFilter("request-only", events));
            requestOnly.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST), true, "/*");

            FilterRegistration.Dynamic errorOnly = fixture.runtime.addFilter(
                    "error-only", new DispatcherRecordingFilter("error-only", events));
            errorOnly.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.ERROR), true, "/errors/*");

            FilterRegistration.Dynamic forwardOnly = fixture.runtime.addFilter(
                    "forward-only", new DispatcherRecordingFilter("forward-only", events));
            forwardOnly.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.FORWARD), true, "/errors/*");
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(Arrays.asList(
                    "request-only:REQUEST",
                    "error-only:ERROR",
                    "handler:ERROR"), events);
        }
    }

    @Test
    void exposesStandardErrorRequestAttributes() throws Exception {
        IOException failure = new IOException("disk failed");
        AtomicReference<List<Object>> captured = new AtomicReference<List<Object>>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                exceptionErrorPage(IOException.class, "/errors/io"))) {
            mapServlet(fixture, "entry", "/boom", new ThrowingServlet(failure));
            mapServlet(fixture, "io-error", "/errors/io",
                    new AttributeCapturingErrorPageServlet("attributes", captured,
                            RequestDispatcher.ERROR_STATUS_CODE,
                            RequestDispatcher.ERROR_MESSAGE,
                            RequestDispatcher.ERROR_REQUEST_URI,
                            RequestDispatcher.ERROR_SERVLET_NAME,
                            RequestDispatcher.ERROR_EXCEPTION,
                            RequestDispatcher.ERROR_EXCEPTION_TYPE));
            fixture.start();

            ContainerExchange exchange = exchange("/boom");
            fixture.runtime.service(exchange);

            assertEquals(500, exchange.response().status());
            assertEquals("attributes:ERROR", body(exchange));
            assertEquals(Integer.valueOf(500), captured.get().get(0));
            assertEquals("disk failed", captured.get().get(1));
            assertEquals("/boom", captured.get().get(2));
            assertEquals("entry", captured.get().get(3));
            assertSame(failure, captured.get().get(4));
            assertSame(IOException.class, captured.get().get(5));
        }
    }

    @Test
    void doesNotOverwriteExistingForwardAttributes() throws Exception {
        AtomicReference<List<Object>> captured = new AtomicReference<List<Object>>();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                statusErrorPage(500, "/errors/server"))) {
            mapServlet(fixture, "entry", "/entry", new ForwardAttributesFailureServlet());
            mapServlet(fixture, "server-error", "/errors/server",
                    new AttributeCapturingErrorPageServlet("forward-attributes", captured,
                            RequestDispatcher.FORWARD_REQUEST_URI,
                            RequestDispatcher.FORWARD_CONTEXT_PATH,
                            RequestDispatcher.FORWARD_SERVLET_PATH,
                            RequestDispatcher.FORWARD_PATH_INFO,
                            RequestDispatcher.FORWARD_QUERY_STRING));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(Arrays.<Object>asList(
                    "/existing/request",
                    "/existing-context",
                    "/existing-servlet",
                    "/existing-path-info",
                    "existing=query"), captured.get());
        }
    }

    @Test
    void fallsBackTo500WithoutRecursingWhenErrorPageFails() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        try (RuntimeFixture fixture = RuntimeFixture.open(temporaryDirectory,
                statusErrorPage(404, "/errors/failing"),
                statusErrorPage(500, "/errors/failing"))) {
            mapServlet(fixture, "entry", "/entry",
                    new SendErrorServlet(404, "missing"));
            mapServlet(fixture, "failing-error", "/errors/failing",
                    new FailingErrorPageServlet(invocations));
            fixture.start();

            ContainerExchange exchange = exchange("/entry");
            fixture.runtime.service(exchange);

            assertEquals(1, invocations.get());
            assertEquals(500, exchange.response().status());
            assertTrue(exchange.response().firstHeader("Content-Type")
                    .startsWith("text/plain"));
            assertTrue(body(exchange).contains("Internal Server Error"));
            assertTrue(!body(exchange).contains("partial-error-page"));
        }
    }

    private static ContainerExchange exchange(String path) {
        return new ContainerExchange(ContainerRequest.builder()
                .method("GET")
                .rawUri(path)
                .path(path)
                .build());
    }

    private static ContainerExchange multipartExchange(
            String path, String boundary, String fieldName, String fieldValue,
            String fileField, String fileName, String contentType, String fileValue) {
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n"
                + fieldValue + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n"
                + fieldValue + "-again\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fileField
                + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n"
                + fileValue + "\r\n"
                + "--" + boundary + "--\r\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ContainerExchange(ContainerRequest.builder()
                .method("POST")
                .rawUri(path)
                .path(path)
                .addHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                .addHeader("Content-Length", Integer.toString(bytes.length))
                .body(bytes)
                .build());
    }

    private static String body(ContainerExchange exchange) {
        return new String(exchange.response().bodyBytes(), StandardCharsets.UTF_8);
    }

    private static String statusErrorPage(int status, String location) {
        return "<error-page><error-code>" + status + "</error-code>"
                + "<location>" + location + "</location></error-page>";
    }

    private static String exceptionErrorPage(
            Class<? extends Throwable> exceptionType, String location) {
        return "<error-page><exception-type>" + exceptionType.getName() + "</exception-type>"
                + "<location>" + location + "</location></error-page>";
    }

    private static String defaultErrorPage(String location) {
        return "<error-page><location>" + location + "</location></error-page>";
    }

    private static void mapServlet(RuntimeFixture fixture, String name, String path,
                                   HttpServlet servlet) {
        ServletRegistration.Dynamic registration = fixture.runtime.addServlet(name, servlet);
        assertTrue(registration.addMapping(path).isEmpty());
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

    private static final class SendErrorServlet extends HttpServlet {
        private final int status;
        private final String message;

        private SendErrorServlet(int status, String message) {
            this.status = status;
            this.message = message;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.sendError(status, message);
        }
    }

    private static final class SetStatusServlet extends HttpServlet {
        private final int status;
        private final String body;

        private SetStatusServlet(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.setStatus(status);
            response.getWriter().write(body);
        }
    }

    private static final class ThrowingServlet extends HttpServlet {
        private final Exception failure;

        private ThrowingServlet(Exception failure) {
            this.failure = failure;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            if (failure instanceof ServletException) {
                throw (ServletException) failure;
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            throw new ServletException(failure);
        }
    }

    private static final class ErrorPageServlet extends HttpServlet {
        private final String marker;
        private final List<String> events;

        private ErrorPageServlet(String marker, List<String> events) {
            this.marker = marker;
            this.events = events;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            String event = marker + ":" + request.getDispatcherType().name();
            if (events != null) {
                events.add(event);
            }
            response.getWriter().write(event);
        }
    }

    private static final class AttributeCapturingErrorPageServlet extends HttpServlet {
        private final String marker;
        private final AtomicReference<List<Object>> captured;
        private final String[] attributeNames;

        private AttributeCapturingErrorPageServlet(
                String marker, AtomicReference<List<Object>> captured,
                String... attributeNames) {
            this.marker = marker;
            this.captured = captured;
            this.attributeNames = attributeNames;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            List<Object> attributes = new ArrayList<Object>(attributeNames.length);
            for (String attributeName : attributeNames) {
                attributes.add(request.getAttribute(attributeName));
            }
            captured.set(attributes);
            response.getWriter().write(
                    marker + ":" + request.getDispatcherType().name());
        }
    }

    private static final class ForwardAttributesFailureServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException {
            request.setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, "/existing/request");
            request.setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, "/existing-context");
            request.setAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, "/existing-servlet");
            request.setAttribute(RequestDispatcher.FORWARD_PATH_INFO, "/existing-path-info");
            request.setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, "existing=query");
            throw new ServletException("forward target failed");
        }
    }

    private static final class FailingErrorPageServlet extends HttpServlet {
        private final AtomicInteger invocations;

        private FailingErrorPageServlet(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            invocations.incrementAndGet();
            response.setContentType("text/html");
            response.getWriter().write("partial-error-page");
            throw new ServletException("error page failed");
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

    @MultipartConfig(maxFileSize = 8L, maxRequestSize = 1024L, fileSizeThreshold = 0)
    private static final class MultipartServlet extends HttpServlet {
        private final AtomicReference<Part> uploadedPart;

        private MultipartServlet(AtomicReference<Part> uploadedPart) {
            this.uploadedPart = uploadedPart;
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            Part file = request.getPart("document");
            uploadedPart.set(file);
            PrintWriter writer = response.getWriter();
            writer.write("title=" + request.getParameter("title")
                    + ";titles=" + Arrays.toString(request.getParameterValues("title"))
                    + ";file=" + file.getSubmittedFileName()
                    + ";size=" + file.getSize());
        }
    }

    private static final class AsyncMultipartServlet extends HttpServlet {
        private final AtomicReference<Part> uploadedPart;

        private AsyncMultipartServlet(AtomicReference<Part> uploadedPart) {
            this.uploadedPart = uploadedPart;
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            uploadedPart.set(request.getPart("document"));
            AsyncContext async = request.startAsync();
            async.complete();
        }
    }

    private static final class MultipartParameterServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.getWriter().write(request.getParameter("title"));
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

        private static RuntimeFixture open(Path temporaryDirectory, String... errorPages)
                throws Exception {
            Path webRoot = temporaryDirectory.resolve("webapp");
            Files.createDirectories(webRoot.resolve("WEB-INF"));
            if (errorPages.length > 0) {
                StringBuilder xml = new StringBuilder(
                        "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"3.1\">");
                for (String errorPage : errorPages) {
                    xml.append(errorPage);
                }
                xml.append("</web-app>");
                Files.write(webRoot.resolve("WEB-INF/web.xml"),
                        xml.toString().getBytes(StandardCharsets.UTF_8));
            }
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

    private static Path servletTempDirectory(JavaxServletRuntime runtime) throws Exception {
        Field field = JavaxServletRuntime.class.getDeclaredField("servletContext");
        field.setAccessible(true);
        return ((TinyServletContext) field.get(runtime)).tempDirectory();
    }

    private static long fileCount(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }
}
