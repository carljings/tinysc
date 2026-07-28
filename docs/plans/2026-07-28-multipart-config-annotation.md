# `@MultipartConfig` Annotation Support Implementation Plan

> Execute this plan task by task with test-first checkpoints.

**Goal:** Let an already registered Servlet use `@MultipartConfig` when neither `web.xml` nor SCI supplies an explicit multipart configuration, while preserving Tomcat 8.5.100 precedence.

**Architecture:** Keep annotation handling inside `JavaxServletRuntime.ServletHolder`; do not add a WAR-wide annotation scanner. Resolve and cache the annotation lazily before constructing the mapped request, so startup cost remains unchanged and the request receives the effective configuration. An explicit descriptor or SCI configuration always wins as one complete configuration.

**Tech Stack:** Java 8, Servlet 3.1 (`javax.servlet`), JUnit 5, Maven, Probe WAR, Apache Tomcat 8.5.100.

---

## Assumptions and boundaries

- `@MultipartConfig` configures a Servlet that was already registered by `web.xml` or SCI; this slice does not implement `@WebServlet` discovery.
- Precedence is `web.xml` or SCI explicit configuration, then `@MultipartConfig`, then no multipart support. Configurations are not merged field by field.
- Tomcat 8.5.100 reads this annotation during Servlet loading even when `metadata-complete=true`. TinySC will match that legacy-container behavior and document that this point is a Tomcat compatibility choice, not a claim of strict Servlet 3.1 metadata-complete conformance.
- Forward/async/error dispatch multipart remapping is a separate lifecycle slice because `TinyHttpServletRequest` currently snapshots the entry Servlet configuration.
- No remote deployment or restart is part of this plan.

### Task 1: Pin behavior with runtime tests

**Files:**

- Modify: `src/tinysc-servlet-javax/src/test/java/io/tinysc/servlet/javax/JavaxServletRuntimeTest.java`

**Step 1: Add a conflicting annotation**

Annotate the existing test `MultipartServlet` with an 8-byte part limit. Keep the existing SCI test's explicit 64-byte limit and upload a 9-byte file so the conflict directly proves that SCI configuration wins.

**Step 2: Add the annotation-only test**

Register the same Servlet without calling `setMultipartConfig()`. Verify a small upload succeeds and a 9-byte upload fails with the existing size-limit exception.

**Step 3: Run the focused test and verify the new test fails**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp \
  -pl src/tinysc-servlet-javax -am \
  -Dtest=JavaxServletRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the annotation-only request fails because TinySC reports that multipart configuration is absent.

### Task 2: Implement the minimum holder fallback

**Files:**

- Modify: `src/tinysc-servlet-javax/src/main/java/io/tinysc/servlet/javax/JavaxServletRuntime.java`

**Step 1: Add holder resolution state**

Add a cached resolution flag beside `ServletHolder.multipartConfig`. Descriptor-created holders start resolved only when `<multipart-config>` is present.

**Step 2: Resolve the effective configuration**

Add a synchronized method that:

1. returns the cached explicit or annotation configuration when already resolved;
2. gets the actual registered Servlet class without instantiating a second Servlet;
3. reads `javax.servlet.annotation.MultipartConfig` with `Class#getAnnotation`;
4. converts it with `new MultipartConfigElement(annotation)`;
5. caches both presence and absence.

**Step 3: Preserve explicit SCI precedence**

Route `ServletRegistration.Dynamic#setMultipartConfig()` through a holder method that stores the complete explicit value and marks resolution complete.

**Step 4: Supply the effective value to the request**

Call the holder resolver before creating `TinyHttpServletRequest`.

**Step 5: Run the focused test**

Run the Task 1 Maven command.

Expected: all `JavaxServletRuntimeTest` cases pass.

### Task 3: Extend the real Probe WAR

**Files:**

- Modify: `src/tinysc-testapp-javax/src/main/java/example/tinysc/probe/UploadProbeServlet.java`
- Modify: `src/tinysc-testapp-javax/src/main/webapp/WEB-INF/web.xml`
- Modify: `src/tinysc-integration-tests/src/test/java/io/tinysc/integration/ProbeWarIntegrationTest.java`

**Step 1: Add the Probe annotation**

Annotate `UploadProbeServlet` with an 8-byte file limit.

**Step 2: Register an annotation-only endpoint**

Keep `/upload` with its explicit 64-byte descriptor configuration, and register the same class under `/annotated-upload` without `<multipart-config>`.

**Step 3: Register an empty-config endpoint**

Register the same annotated class under `/empty-config-upload` with an empty `<multipart-config/>` to prove that XML defaults still count as explicit configuration.

**Step 4: Add HTTP assertions**

- `/upload` with 9 bytes remains `200`, proving descriptor precedence over the 8-byte annotation.
- `/annotated-upload` with 7 bytes returns `200` and the expected body.
- `/annotated-upload` with 9 bytes returns `500`.
- `/empty-config-upload` with 9 bytes returns `200`.

**Step 5: Run the focused integration test**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp \
  -pl src/tinysc-integration-tests -am \
  -Dtest=ProbeWarIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false verify
```

Expected: `ProbeWarIntegrationTest` passes.

### Task 4: Produce local Tomcat differential evidence

**Files:**

- Modify: `docs/acceptance/2026-07-28-multipart-probe-differential.md`
- Store private raw output only under: `work/benchmark/results/`

**Step 1: Build once on Java 8**

Run:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 1.8)" mvn -B -ntp clean verify
```

Expected: Maven reports `BUILD SUCCESS`.

**Step 2: Run identical requests**

Start isolated local TinySC and Tomcat 8.5.100 instances with the same Probe WAR. Send the non-empty descriptor, empty descriptor, and annotation-only success/oversize requests with identical bodies.

**Step 3: Compare results**

Expected:

- descriptor override request: both `200`, identical core body;
- annotation-only 7-byte request: both `200`, identical core body;
- annotation-only 9-byte request: both `500`.
- empty descriptor request: both `200`, identical core body.

Record artifact hashes and raw logs under ignored `work/`; publish only anonymized evidence under `docs/acceptance/`.

### Task 5: Update public documentation and verify the branch

**Files:**

- Modify: `README.md`
- Modify: `docs/configuration.md`
- Modify: `docs/testing.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/changelog.md`
- Modify: `docs/adr/0012-bounded-multipart-parsing.md`
- Create: `docs/adr/0014-tomcat-compatible-multipart-annotation.md`

**Step 1: State the supported contract**

Document annotation fallback, explicit configuration precedence, the Tomcat-compatible metadata-complete choice, and the unchanged streaming/dispatch limitations.

**Step 2: Update test totals only from reports**

Sum current Surefire report counts after `clean verify`; do not estimate.

**Step 3: Run repository checks**

Run:

```bash
git diff --check -- . ':!AGENTS.md'
git status --short
rg -n '192\.168\.|internal-only|__LOCAL__' \
  README.md docs src .github --glob '!docs/acceptance/*legacy-war*'
```

Expected: no whitespace errors, `AGENTS.md` remains untracked, and no new private endpoint or application identifier is introduced.

**Step 4: Review, publish, and merge**

Request independent code, security, and verification reviews. Commit only intended files, push `codex/multipart-config-annotation`, open a public pull request, wait for CI, and merge only after all checks pass.
