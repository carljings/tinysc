package io.tinysc.launcher;

import io.tinysc.deployment.InspectionReport;
import io.tinysc.deployment.WarInspector;
import io.tinysc.kernel.ServerConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TinyScMain {
    private TinyScMain() {
    }

    public static void main(String[] arguments) {
        int exitCode;
        try {
            exitCode = run(arguments, System.out, System.err);
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] arguments, PrintStream output, PrintStream error) throws Exception {
        if (arguments.length == 2 && "inspect".equals(arguments[0])) {
            inspect(Paths.get(arguments[1]), output);
            return 0;
        }
        if (arguments.length > 0 && "start".equals(arguments[0])) {
            try {
                return start(parseOptions(arguments, 1), output, error);
            } catch (IllegalArgumentException invalidArguments) {
                error.println("Invalid arguments: " + invalidArguments.getMessage());
                usage(error);
                return 2;
            }
        }
        usage(error);
        return 2;
    }

    private static int start(Map<String, String> options, PrintStream output, PrintStream error)
            throws Exception {
        String warValue = options.remove("war");
        if (warValue == null) {
            error.println("Missing required option: --war");
            usage(error);
            return 2;
        }
        Path war = Paths.get(warValue);
        String contextPath = option(options, "context-path", "");
        int workerThreads = integerOption(options, "workers",
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        ServerConfig.Builder configBuilder = ServerConfig.builder()
                .bindAddress(option(options, "bind", "127.0.0.1"))
                .port(integerOption(options, "port", 8080))
                .contextPath(contextPath)
                .baseDirectory(Paths.get(option(options, "base", ".")))
                .ioThreads(integerOption(options, "io-threads", Math.max(1, Math.min(4,
                        Runtime.getRuntime().availableProcessors()))))
                .maxConnections(positiveIntegerOption(options, "max-connections", 1024))
                .maxInflightRequestBytes(positiveLongOption(
                        options, "max-inflight-request-bytes", 64L * 1024L * 1024L))
                .maxRawIngressBytes(positiveLongOption(
                        options, "max-raw-ingress-bytes", 64L * 1024L * 1024L))
                .requestReadTimeoutMillis(positiveLongOption(
                        options, "request-read-timeout", 30000L))
                .requestBodyTimeoutMillis(positiveLongOption(
                        options, "request-body-timeout", 300000L))
                .responseWriteTimeoutMillis(positiveLongOption(
                        options, "response-write-timeout", 30000L))
                .accessLogEnabled(booleanOption(options, "access-log", true))
                .workerThreads(workerThreads)
                .workerQueueCapacity(integerOption(options, "worker-queue", 100))
                .workerIdleTimeoutMillis(idleTimeoutMillisOption(options, "worker-idle-timeout",
                        60000L));
        String minWorkers = options.remove("min-workers");
        if (minWorkers != null) {
            configBuilder.workerMinThreads(positiveIntegerOption("min-workers", minWorkers));
        }
        ServerConfig config = configBuilder.build();
        if (!options.isEmpty()) {
            throw new IllegalArgumentException("Unknown option: --"
                    + options.keySet().iterator().next());
        }

        final LauncherLog log;
        try {
            log = LauncherLog.open(config.baseDirectory(), output, error);
        } catch (IOException failure) {
            error.println("Unable to initialize file logging under "
                    + config.baseDirectory() + ": " + failure.getMessage());
            return 1;
        }
        log.install();
        log.output().println("tinysc log file=" + log.path()
                + " maxBytes=" + LauncherLog.DEFAULT_MAX_BYTES
                + " backups=" + LauncherLog.DEFAULT_BACKUPS);
        if (config.accessLogEnabled()) {
            log.output().println("tinysc access log file="
                    + config.baseDirectory().resolve("logs/access.log"));
        }
        log.output().println("tinysc starting"
                + " at=" + Instant.now()
                + " war=" + war.toAbsolutePath().normalize()
                + " base=" + config.baseDirectory()
                + " context=" + (config.contextPath().isEmpty() ? "/" : config.contextPath())
                + " maxConnections=" + config.maxConnections()
                + " maxInflightRequests=" + config.maxInflightRequests()
                + " maxInflightRequestBytes=" + config.maxInflightRequestBytes()
                + " maxRawIngressBytes=" + config.maxRawIngressBytes()
                + " requestReadTimeoutMs=" + config.requestReadTimeoutMillis()
                + " requestBodyTimeoutMs=" + config.requestBodyTimeoutMillis()
                + " responseWriteTimeoutMs=" + config.responseWriteTimeoutMillis()
                + " accessLog=" + config.accessLogEnabled()
                + " ioThreads=" + config.ioThreads()
                + " workerMin=" + config.workerMinThreads()
                + " workerMax=" + config.workerThreads()
                + " workerQueue=" + config.workerQueueCapacity()
                + " workerIdleMs=" + config.workerIdleTimeoutMillis());

        final TinyScServer server = new TinyScServer(config, war);
        ServerShutdown shutdown = null;
        try {
            int port = server.start();
            shutdown = new ServerShutdown(server, log, error);
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "tinysc-shutdown"));
            log.output().println("tinysc ready"
                    + " bind=" + config.bindAddress()
                    + " port=" + port
                    + " context=" + (config.contextPath().isEmpty() ? "/" : config.contextPath())
                    + " readyMs=" + server.readyMillis()
                    + " prepareMs=" + server.prepareMillis()
                    + " expansionCacheHit=" + server.expansionCacheHit()
                    + " sha256=" + server.sourceSha256());
            server.await();
            shutdown.run();
            return 0;
        } catch (Exception failure) {
            if (shutdown == null) {
                try {
                    server.close();
                } catch (Exception closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                failure.printStackTrace(log.error());
                try {
                    log.close();
                } catch (IOException closeFailure) {
                    closeFailure.printStackTrace(error);
                }
            } else {
                failure.printStackTrace(log.error());
                shutdown.run();
            }
            return 1;
        }
    }

    private static final class ServerShutdown implements Runnable {
        private final TinyScServer server;
        private final LauncherLog log;
        private final PrintStream fallbackError;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private ServerShutdown(TinyScServer server, LauncherLog log,
                               PrintStream fallbackError) {
            this.server = server;
            this.log = log;
            this.fallbackError = fallbackError;
        }

        @Override
        public void run() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            log.output().println("tinysc shutdown requested at=" + Instant.now());
            try {
                server.close();
            } catch (Exception failure) {
                failure.printStackTrace(log.error());
            } finally {
                log.output().println("tinysc stopped at=" + Instant.now());
                try {
                    log.close();
                } catch (IOException failure) {
                    failure.printStackTrace(fallbackError);
                }
            }
        }
    }

    private static void inspect(Path war, PrintStream output) throws Exception {
        InspectionReport report = new WarInspector().inspect(war);
        output.println("WAR: " + report.source());
        output.println("SHA-256: " + report.sha256());
        output.println("Size: " + report.sourceBytes() + " bytes");
        output.println("Bundled class bytecode: " + bytecodeVersion(report));
        output.println("Servlet namespace: "
                + report.namespace().name().toLowerCase(java.util.Locale.ROOT));
        output.println("web.xml: " + report.webXmlVersion());
        output.println("Recommended runtime: " + report.recommendedRuntime());
        for (String warning : report.warnings()) {
            output.println("Warning: " + warning);
        }
    }

    private static String bytecodeVersion(InspectionReport report) {
        String minimum = InspectionReport.javaVersionForClassMajor(report.minimumClassMajor());
        String maximum = InspectionReport.javaVersionForClassMajor(report.maximumClassMajor());
        if (minimum.equals(maximum)) {
            return maximum;
        }
        return minimum + ".." + maximum;
    }

    private static Map<String, String> parseOptions(String[] arguments, int offset) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = offset; index < arguments.length; index++) {
            String option = arguments[index];
            if (!option.startsWith("--") || option.length() == 2 || index + 1 >= arguments.length) {
                throw new IllegalArgumentException("Expected --name value, found: " + option);
            }
            String name = option.substring(2);
            if (result.put(name, arguments[++index]) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + name);
            }
        }
        return result;
    }

    private static String option(Map<String, String> options, String name, String defaultValue) {
        String value = options.remove(name);
        return value == null ? defaultValue : value;
    }

    private static int integerOption(Map<String, String> options, String name, int defaultValue) {
        String value = options.remove(name);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static int positiveIntegerOption(String name, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("Option --" + name + " must be positive");
        }
        return parsed;
    }

    private static int positiveIntegerOption(Map<String, String> options, String name,
                                             int defaultValue) {
        String value = options.remove(name);
        return value == null ? defaultValue : positiveIntegerOption(name, value);
    }

    private static long positiveLongOption(Map<String, String> options, String name,
                                           long defaultValue) {
        String value = options.remove(name);
        if (value == null) {
            return defaultValue;
        }
        long parsed = Long.parseLong(value);
        if (parsed <= 0L) {
            throw new IllegalArgumentException("Option --" + name + " must be positive");
        }
        return parsed;
    }

    private static boolean booleanOption(Map<String, String> options, String name,
                                         boolean defaultValue) {
        String value = options.remove(name);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("Option --" + name + " must be true or false");
    }

    private static long idleTimeoutMillisOption(Map<String, String> options, String name,
                                                long defaultValue) {
        String value = options.remove(name);
        if (value == null) {
            return defaultValue;
        }
        long seconds = Long.parseLong(value);
        if (seconds <= 0) {
            throw new IllegalArgumentException("Option --" + name + " must be positive");
        }
        try {
            return Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Option --" + name + " is too large");
        }
    }

    private static void usage(PrintStream output) {
        output.println("Usage:");
        output.println("  tinysc inspect <app.war>");
        output.println("  tinysc start --war <app.war> [--port 8080] [--bind 127.0.0.1]");
        output.println("               [--context-path /app] [--base .]");
        output.println("               [--io-threads N] [--workers N] [--min-workers N]");
        output.println("               [--worker-queue N] [--worker-idle-timeout SECONDS]");
        output.println("               [--max-connections N]");
        output.println("               [--max-inflight-request-bytes BYTES]");
        output.println("               [--max-raw-ingress-bytes BYTES]");
        output.println("               [--request-read-timeout MILLISECONDS]");
        output.println("               [--request-body-timeout MILLISECONDS]");
        output.println("               [--response-write-timeout MILLISECONDS]");
        output.println("               [--access-log true|false]");
    }
}
