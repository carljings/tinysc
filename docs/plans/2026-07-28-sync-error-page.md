# Synchronous Servlet Error Page Implementation Plan

**Goal:** Add the Servlet 3.1 synchronous `<error-page>` path for `sendError` and uncaught Servlet exceptions, including `DispatcherType.ERROR`, standard error attributes, and a non-recursive fallback.

**Architecture:** Keep deployment metadata namespace-neutral as immutable error-page definitions. `sendError` records an error while preserving TinySC's current committed fallback response; after the application chain returns, `JavaxServletRuntime` selects a configured page, prepares the buffered response for one internal rewrite, sets the standard `RequestDispatcher.ERROR_*` attributes, and reuses the existing dispatcher/filter machinery with `DispatcherType.ERROR`. The first slice is deliberately limited to synchronous requests whose bytes have not been written to the network.

**Tech Stack:** Java 8, Servlet 3.1 (`javax.servlet`), JUnit 5, Maven, Probe WAR, Apache Tomcat 8.5.100.

---

## Assumptions and boundaries

- Match `sendError` by exact status code, then the selector-free default page.
- Match an exception by its nearest declared superclass. For `ServletException`, try the outer exception first, then its root cause; if neither matches, try status `500`, then the default page.
- `setStatus(...)` alone never starts error dispatch.
- A successful error dispatch keeps the original error status unless the error resource changes it.
- Preserve ordinary response headers and cookies when entering a custom error page, but discard the previous body and stale `Content-Length`.
- A failing error page is not dispatched recursively. TinySC records the failure and emits one safe `500` fallback.
- Async error dispatch, committed-response include/CLOSE_NOW behavior, JSP error pages, and nested dispatch compatibility beyond preserving existing `FORWARD_*` attributes are out of scope.
- No remote or production deployment is part of this plan.

### Task 1: Parse and validate `<error-page>`

**Files:**

- Modify: `src/tinysc-deployment/src/main/java/io/tinysc/deployment/model/WebAppDescriptor.java`
- Modify: `src/tinysc-deployment/src/main/java/io/tinysc/deployment/WebXmlParser.java`
- Modify: `src/tinysc-deployment/src/test/java/io/tinysc/deployment/WebXmlParserTest.java`

**Step 1: Write failing parser tests**

Add tests for:

- ordered status, exception, and selector-free default definitions;
- both selectors in one entry;
- non-three-digit, zero, negative, and non-numeric status codes;
- missing, blank, or relative locations;
- duplicate status, exception, and default definitions.

Expected model examples:

```java
assertEquals(Integer.valueOf(404), pages.get(0).errorCode());
assertNull(pages.get(0).exceptionType());
assertEquals("/errors/not-found", pages.get(0).location());
assertEquals("java.io.IOException", pages.get(1).exceptionType());
assertNull(pages.get(2).errorCode());
assertNull(pages.get(2).exceptionType());
```

**Step 2: Run the focused tests and confirm failure**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp \
  -pl src/tinysc-deployment -am \
  -Dtest=WebXmlParserTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation or assertions fail because `ErrorPageDefinition` and `errorPages()` do not exist.

**Step 3: Add the neutral immutable model**

Add `List<ErrorPageDefinition>` to `WebAppDescriptor`, a builder method, and this value shape:

```java
public static final class ErrorPageDefinition {
    private final Integer errorCode;
    private final String exceptionType;
    private final String location;
}
```

Require exactly zero or one selector, a non-empty absolute location, status range `1..999`, and unique keys in each selector space. Keep class names as strings; the deployment module must not depend on `javax.servlet` or load application classes.

**Step 4: Parse the XML**

For each `<error-page>`:

- require exactly one non-empty `<location>` beginning with `/`;
- reject simultaneous `<error-code>` and `<exception-type>`;
- accept neither selector as the default definition;
- require the raw error code to match `[0-9]{3}` and its parsed value to be non-zero.

Wrap invalid metadata through the existing `DeploymentException` path.

**Step 5: Re-run the focused tests**

Expected: all `WebXmlParserTest` cases pass.

### Task 2: Add a narrow buffered-response rewrite

**Files:**

- Modify: `src/tinysc-kernel/src/main/java/io/tinysc/kernel/ContainerResponse.java`
- Modify: `src/tinysc-kernel/src/test/java/io/tinysc/kernel/ContainerResponseTest.java`
- Modify: `src/tinysc-servlet-javax/src/main/java/io/tinysc/servlet/javax/TinyHttpServletResponse.java`
- Create: `src/tinysc-servlet-javax/src/test/java/io/tinysc/servlet/javax/TinyHttpServletResponseTest.java`

**Step 1: Write failing kernel tests**

Pin one purpose-built container operation that:

- makes the buffered response internally writable again;
- preserves status, ordinary headers, and repeated `Set-Cookie`;
- clears the body;
- removes `Content-Length` case-insensitively.

Do not add a general-purpose public `uncommit()` method.

**Step 2: Write failing Servlet response tests**

Cover:

- `sendError(404, "missing")` records status/message and remains logically committed;
- `setStatus(404)` and ordinary `flushBuffer()` do not create a pending error;
- pre-error writer data does not leak into a custom error response;
- calling `sendError` after `getOutputStream()` does not switch to `getWriter()` internally;
- the error rewrite permits the error resource to choose writer or output stream anew;
- original headers/cookies survive while `Content-Length` and the original body do not.

**Step 3: Run focused tests and confirm failure**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp \
  -pl src/tinysc-servlet-javax -am \
  -Dtest=ContainerResponseTest,TinyHttpServletResponseTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the new APIs and pending error state are absent.

**Step 4: Implement the minimum state transition**

Add a narrow `ContainerResponse` operation for container-owned buffered rewriting. In `TinyHttpServletResponse`:

1. flush any pre-existing writer;
2. clear the buffered body and stale length;
3. store an immutable pending error containing status and message;
4. create the default fallback body without calling `getWriter()`;
5. commit the logical application response as today;
6. expose package-private lookup and `prepareErrorDispatch()` methods;
7. on preparation, clear the old writer/output-stream selection and pending marker while preserving status and allowed headers.

**Step 5: Re-run focused tests**

Expected: all kernel and Servlet response tests pass.

### Task 3: Select and dispatch configured error pages

**Files:**

- Modify: `src/tinysc-servlet-javax/src/main/java/io/tinysc/servlet/javax/JavaxServletRuntime.java`
- Modify: `src/tinysc-servlet-javax/src/test/java/io/tinysc/servlet/javax/JavaxServletRuntimeTest.java`

**Step 1: Write failing runtime tests**

Add isolated tests named for these behaviors:

- `dispatchesConfiguredErrorPageForSendError404`
- `doesNotDispatchErrorPageForSetStatus404`
- `usesDefaultErrorPageWhenStatusIsUnmapped`
- `dispatchesClosestMatchingExceptionType`
- `dispatchesServletExceptionRootCauseWhenOuterTypeIsUnmapped`
- `fallsBackFromExceptionToConfigured500Page`
- `invokesOnlyErrorMappedFiltersDuringErrorDispatch`
- `exposesStandardErrorRequestAttributes`
- `doesNotOverwriteExistingForwardAttributes`
- `fallsBackTo500WithoutRecursingWhenErrorPageFails`

**Step 2: Run the focused runtime test and confirm failure**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp \
  -pl src/tinysc-servlet-javax -am \
  -Dtest=JavaxServletRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: configured error pages are ignored and uncaught exceptions still leave the runtime.

**Step 3: Compile an error-page registry**

At runtime construction, index status definitions and the default page, and retain exception class names for nearest-superclass lookup. Match by walking the thrown class hierarchy; do not instantiate declared exception classes.

**Step 4: Handle `sendError` after the request chain**

Before request destruction:

1. detect the response's pending error;
2. select exact status then default;
3. capture original URI and mapped servlet name;
4. prepare the response once;
5. set `ERROR_STATUS_CODE`, `ERROR_MESSAGE`, `ERROR_REQUEST_URI`, and `ERROR_SERVLET_NAME`;
6. call the existing `dispatch(..., DispatcherType.ERROR)` directly.

Do not call `TinyRequestDispatcher.forward()`, because it applies FORWARD semantics and flushes.

**Step 5: Handle uncaught synchronous exceptions**

For non-fatal throwables and an uncommitted response:

1. match the outer throwable by nearest type;
2. for `ServletException`, match its root cause if the outer type is unmapped;
3. otherwise try configured status `500`, then default;
4. set all six standard error attributes, including `ERROR_EXCEPTION` and `ERROR_EXCEPTION_TYPE`;
5. dispatch once with `DispatcherType.ERROR`.

Keep the existing async failure path unchanged. Rethrow fatal VM errors and `ThreadDeath`.

**Step 6: Add the recursion fuse**

If the error resource throws or calls `sendError` again, log the custom-handler failure, clear its partial output, and render one safe plain-text `500` response. Never call error selection recursively.

**Step 7: Re-run runtime tests**

Expected: all `JavaxServletRuntimeTest` cases pass, including existing async, multipart, filter-order, listener, and shutdown cases.

### Task 4: Prove the behavior through a real WAR

**Files:**

- Create: `src/tinysc-testapp-javax/src/main/java/example/tinysc/probe/ErrorEntryServlet.java`
- Create: `src/tinysc-testapp-javax/src/main/java/example/tinysc/probe/ErrorViewServlet.java`
- Create: `src/tinysc-testapp-javax/src/main/java/example/tinysc/probe/ErrorDispatchFilter.java`
- Modify: `src/tinysc-testapp-javax/src/main/webapp/WEB-INF/web.xml`
- Modify: `src/tinysc-integration-tests/src/test/java/io/tinysc/integration/ProbeWarIntegrationTest.java`

**Step 1: Add deterministic Probe endpoints**

Use one entry servlet with actions for:

- `sendError(404, "probe-missing")`;
- `setStatus(404)` without `sendError`;
- `RuntimeException`;
- `ServletException` wrapping `IOException`;
- a failing error handler.

Use Servlet classes rather than JSP. The error view must echo dispatcher type and the standard attributes in a stable single-line body. Add an ERROR-only filter that emits a deterministic header.

**Step 2: Declare the Probe mappings**

Add status `404`, exception, status `500`, and default definitions to `WEB-INF/web.xml`, along with an ERROR-only filter mapping.

**Step 3: Add integration assertions**

Verify over HTTP:

- original status is preserved;
- custom body and ERROR filter header appear;
- exception/root-cause matching is correct;
- `setStatus(404)` does not use the custom page;
- `Content-Length` equals the actual final body;
- the failing error resource terminates as one `500` response.

**Step 4: Run focused integration verification**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp \
  -pl src/tinysc-integration-tests -am \
  -Dtest=ProbeWarIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false verify
```

Expected: `ProbeWarIntegrationTest` passes without timeout or recursive log growth.

### Task 5: Produce Tomcat 8.5.100 differential evidence

**Files:**

- Create: `docs/acceptance/2026-07-28-error-page-probe-differential.md`
- Store private raw output only under: `work/benchmark/results/`

**Step 1: Build once on Java 8**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp clean verify
```

Expected: Maven reports `BUILD SUCCESS`.

**Step 2: Run the same artifact in both containers**

Start isolated local TinySC and Tomcat 8.5.100 instances with the exact same Probe WAR and JDK. Compare:

- `sendError(404)`;
- plain `setStatus(404)`;
- direct runtime exception;
- `ServletException` root-cause match;
- failing custom error resource.

**Step 3: Record only stable parity**

Compare status, Probe body fields, dispatcher type, ERROR-filter marker, and relevant error attributes. Do not require identical default HTML, server headers, header ordering, or stack-trace formatting. Record JDK/container/artifact hashes and anonymize local paths.

### Task 6: Document, review, and publish

**Files:**

- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/configuration.md`
- Modify: `docs/testing.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/changelog.md`
- Create: `docs/adr/0015-synchronous-error-page-dispatch.md`

**Step 1: Document the supported contract**

State precisely that TinySC supports synchronous, pre-wire-commit `web.xml` error pages, ERROR filters, superclass/root-cause matching, and standard error attributes. Keep async error dispatch, committed-response recovery, JSP, and full Servlet 3.1 conformance listed as open.

**Step 2: Recalculate test totals**

After the full clean build, sum Surefire report counts. Never estimate or carry forward the previous number.

**Step 3: Run repository hygiene checks**

Run:

```bash
git diff --check -- . ':!AGENTS.md'
git status --short
rg -n '192\.168\.|private-app-a|private-app-b|__LOCAL__' \
  README.md docs src .github --glob '!docs/acceptance/*legacy-war*'
```

Expected: no whitespace errors, `AGENTS.md` remains untracked and unstaged, and no private endpoint, user identity, or application-specific detail is introduced.

**Step 4: Request independent reviews**

Run code, security, and completion-evidence reviews. Resolve only findings that trace to this slice.

**Step 5: Publish through a focused pull request**

Commit only intended files on `codex/sync-error-page`, push the branch, open a public PR, wait for CI, and merge only after all required checks pass.
