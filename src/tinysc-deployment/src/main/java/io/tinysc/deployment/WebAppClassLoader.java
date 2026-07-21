package io.tinysc.deployment;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class WebAppClassLoader extends URLClassLoader {
    private static final Logger LOGGER = Logger.getLogger(WebAppClassLoader.class.getName());
    private static final long THREAD_DRAIN_MILLIS = 100L;
    private static final long KNOWN_RUNTIME_STOP_MILLIS = 400L;
    private static final long THREAD_STOP_MILLIS = 500L;
    private static final String[] PARENT_FIRST_PREFIXES = {
            "javax.xml.",
            "javax.naming.",
            "javax.management.",
            "javax.crypto.",
            "javax.net.",
            "javax.security.",
            "org.xml.sax.",
            "org.w3c.dom.",
            "org.ietf.jgss."
    };
    private static final String[] CONTAINER_ONLY_PREFIXES = {
            "java.",
            "javax.servlet.",
            "io.tinysc."
    };
    private final AtomicBoolean closed = new AtomicBoolean();

    private WebAppClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public static WebAppClassLoader create(Path webRoot, ClassLoader parent)
            throws IOException {
        List<URL> urls = new ArrayList<URL>();
        Path classes = webRoot.resolve("WEB-INF").resolve("classes");
        if (Files.isDirectory(classes)) {
            urls.add(classes.toUri().toURL());
        }
        Path libraries = webRoot.resolve("WEB-INF").resolve("lib");
        if (Files.isDirectory(libraries)) {
            List<Path> jars = new ArrayList<Path>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(libraries, "*.jar")) {
                for (Path jar : stream) {
                    jars.add(jar);
                }
            }
            Collections.sort(jars);
            for (Path jar : jars) {
                urls.add(jar.toUri().toURL());
            }
        }
        return new WebAppClassLoader(urls.toArray(new URL[urls.size()]), parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (hasPrefix(name, CONTAINER_ONLY_PREFIXES)) {
                    loaded = getParent().loadClass(name);
                } else if (isParentFirst(name)) {
                    try {
                        loaded = getParent().loadClass(name);
                    } catch (ClassNotFoundException notInParent) {
                        loaded = findClass(name);
                    }
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInWebApp) {
                        loaded = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        URL resource = findResource(name);
        return resource == null ? getParent().getResource(name) : resource;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Set<URL> resources = new LinkedHashSet<URL>();
        Enumeration<URL> childResources = findResources(name);
        while (childResources.hasMoreElements()) {
            resources.add(childResources.nextElement());
        }
        Enumeration<URL> parentResources = getParent().getResources(name);
        while (parentResources.hasMoreElements()) {
            resources.add(parentResources.nextElement());
        }
        return Collections.enumeration(resources);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Thread> ownedThreads = ownedThreads();
        awaitThreads(ownedThreads, THREAD_DRAIN_MILLIS);
        stopKnownThreadOwners(ownedThreads);
        awaitThreads(ownedThreads, KNOWN_RUNTIME_STOP_MILLIS);
        for (Thread thread : ownedThreads) {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
        awaitThreads(ownedThreads, THREAD_STOP_MILLIS);
        for (Thread thread : ownedThreads) {
            if (thread.isAlive()) {
                try {
                    thread.setContextClassLoader(getParent());
                } catch (SecurityException ignored) {
                    // The warning remains useful even when a security manager blocks cleanup.
                }
                LOGGER.warning("Web application thread did not stop: " + thread.getName());
            }
        }
        super.close();
    }

    private void stopKnownThreadOwners(List<Thread> threads) {
        boolean hasLog4j2Thread = false;
        for (Thread thread : threads) {
            if (thread.isAlive() && thread.getName().startsWith("Log4j2-")) {
                hasLog4j2Thread = true;
                break;
            }
        }
        if (!hasLog4j2Thread) {
            return;
        }
        try {
            Class<?> logManager = Class.forName(
                    "org.apache.logging.log4j.LogManager", false, this);
            Object loggerContext = logManager
                    .getMethod("getContext", ClassLoader.class, boolean.class)
                    .invoke(null, this, false);
            loggerContext.getClass().getMethod("close").invoke(loggerContext);
        } catch (ClassNotFoundException ignored) {
            // A similarly named thread is not sufficient reason to fail cleanup.
        } catch (ReflectiveOperationException failure) {
            LOGGER.warning("Could not shut down Log4j2 cleanly: " + failure.getMessage());
        }
    }

    private List<Thread> ownedThreads() {
        List<Thread> result = new ArrayList<Thread>();
        Thread current = Thread.currentThread();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != current && thread.isAlive()
                    && thread.getContextClassLoader() == this) {
                result.add(thread);
            }
        }
        return result;
    }

    private static void awaitThreads(List<Thread> threads, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Thread thread : threads) {
            while (thread.isAlive()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return;
                }
                try {
                    long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                    int nanos = (int) (remainingNanos
                            - TimeUnit.MILLISECONDS.toNanos(millis));
                    thread.join(millis, nanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static boolean isParentFirst(String name) {
        return hasPrefix(name, PARENT_FIRST_PREFIXES);
    }

    private static boolean hasPrefix(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
