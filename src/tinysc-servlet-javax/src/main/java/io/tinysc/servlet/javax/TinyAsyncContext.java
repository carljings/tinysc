package io.tinysc.servlet.javax;

import io.tinysc.kernel.ContainerExchange;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class TinyAsyncContext implements AsyncContext {
    private final ContainerExchange exchange;
    private final ServletContext servletContext;
    private final ServletRequest request;
    private final ServletResponse response;
    private final boolean originalRequestAndResponse;
    private final ScheduledExecutorService scheduler;
    private final ClassLoader webAppClassLoader;
    private final List<ListenerRegistration> listeners =
            new ArrayList<ListenerRegistration>();
    private long timeoutMillis = 30000L;
    private ScheduledFuture<?> timeoutTask;
    private Runnable completionAction;
    private Throwable failure;
    private boolean terminal;
    private boolean finished;

    TinyAsyncContext(ContainerExchange exchange, ServletContext servletContext,
                     ServletRequest request, ServletResponse response,
                     boolean originalRequestAndResponse, ScheduledExecutorService scheduler,
                     ClassLoader webAppClassLoader) {
        this.exchange = exchange;
        this.servletContext = servletContext;
        this.request = request;
        this.response = response;
        this.originalRequestAndResponse = originalRequestAndResponse;
        this.scheduler = scheduler;
        this.webAppClassLoader = webAppClassLoader;
        scheduleTimeout();
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return originalRequestAndResponse;
    }

    @Override
    public void dispatch() {
        throw new UnsupportedOperationException("async dispatch is not implemented yet");
    }

    @Override
    public void dispatch(String path) {
        throw new UnsupportedOperationException("async dispatch is not implemented yet");
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        throw new UnsupportedOperationException("cross-context async dispatch is not supported");
    }

    @Override
    public void complete() {
        terminate(null);
    }

    @Override
    public void start(final Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("async runnable must not be null");
        }
        ensureActive();
        try {
            exchange.executeAsync(new Runnable() {
                @Override
                public void run() {
                    Thread thread = Thread.currentThread();
                    ClassLoader previous = thread.getContextClassLoader();
                    thread.setContextClassLoader(webAppClassLoader);
                    try {
                        runnable.run();
                    } catch (Throwable error) {
                        fireError(error);
                        terminate(error);
                    } finally {
                        thread.setContextClassLoader(previous);
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            fireError(rejected);
            terminate(rejected);
        }
    }

    @Override
    public synchronized void addListener(AsyncListener listener) {
        addListener(listener, request, response);
    }

    @Override
    public synchronized void addListener(AsyncListener listener, ServletRequest suppliedRequest,
                                         ServletResponse suppliedResponse) {
        ensureActive();
        if (listener == null || suppliedRequest == null || suppliedResponse == null) {
            throw new IllegalArgumentException("async listener, request and response are required");
        }
        listeners.add(new ListenerRegistration(listener, suppliedRequest, suppliedResponse));
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> type) throws ServletException {
        try {
            return type.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new ServletException("cannot instantiate async listener " + type.getName(), exception);
        }
    }

    @Override
    public synchronized void setTimeout(long timeout) {
        ensureActive();
        if (timeout < 0L) {
            throw new IllegalArgumentException("async timeout must not be negative");
        }
        timeoutMillis = timeout;
        scheduleTimeout();
    }

    @Override
    public synchronized long getTimeout() {
        return timeoutMillis;
    }

    synchronized void setCompletionAction(Runnable action) {
        if (completionAction != null) {
            throw new IllegalStateException("async completion action is already registered");
        }
        completionAction = action;
        finishIfReady();
    }

    synchronized boolean isTerminal() {
        return terminal;
    }

    void fail(Throwable error) {
        fireError(error);
        terminate(error);
    }

    private synchronized void terminate(Throwable error) {
        if (terminal) {
            throw new IllegalStateException("async request has already completed");
        }
        terminal = true;
        failure = error;
        cancelTimeout();
        finishIfReady();
    }

    private void finishIfReady() {
        if (!terminal || completionAction == null || finished) {
            return;
        }
        finished = true;
        Throwable completionFailure = failure;
        if (completionFailure == null) {
            fireComplete();
        }
        try {
            completionAction.run();
        } catch (Throwable cleanupFailure) {
            if (completionFailure == null) {
                completionFailure = cleanupFailure;
            } else {
                completionFailure.addSuppressed(cleanupFailure);
            }
        }
        if (completionFailure == null) {
            exchange.complete();
        } else {
            exchange.fail(completionFailure);
        }
    }

    private synchronized void scheduleTimeout() {
        cancelTimeout();
        if (timeoutMillis > 0L && !terminal) {
            timeoutTask = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    onTimeout();
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void onTimeout() {
        List<ListenerRegistration> snapshot = listenerSnapshot();
        for (ListenerRegistration registration : snapshot) {
            try {
                registration.listener.onTimeout(registration.event(this, null));
            } catch (IOException exception) {
                fireError(exception);
            }
        }
        synchronized (this) {
            if (terminal) {
                return;
            }
        }
        try {
            if (!response.isCommitted() && response instanceof HttpServletResponse) {
                ((HttpServletResponse) response).sendError(
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Async request timed out");
            }
            terminate(null);
        } catch (IOException exception) {
            terminate(exception);
        }
    }

    private void fireComplete() {
        for (ListenerRegistration registration : listenerSnapshot()) {
            try {
                registration.listener.onComplete(registration.event(this, null));
            } catch (IOException ignored) {
                // Completion continues so one listener cannot strand the connection.
            }
        }
    }

    private void fireError(Throwable error) {
        for (ListenerRegistration registration : listenerSnapshot()) {
            try {
                registration.listener.onError(registration.event(this, error));
            } catch (IOException ignored) {
                // The original async failure remains authoritative.
            }
        }
    }

    private synchronized List<ListenerRegistration> listenerSnapshot() {
        return new ArrayList<ListenerRegistration>(listeners);
    }

    private synchronized void ensureActive() {
        if (terminal) {
            throw new IllegalStateException("async request has already completed");
        }
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
    }

    private static final class ListenerRegistration {
        private final AsyncListener listener;
        private final ServletRequest request;
        private final ServletResponse response;

        private ListenerRegistration(AsyncListener listener, ServletRequest request,
                                     ServletResponse response) {
            this.listener = listener;
            this.request = request;
            this.response = response;
        }

        private AsyncEvent event(AsyncContext context, Throwable error) {
            return new AsyncEvent(context, request, response, error);
        }
    }
}
