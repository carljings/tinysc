package io.tinysc.kernel;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ContainerExchange {
    private static final int COMPLETION_PENDING = 0;
    private static final int COMPLETION_SUCCEEDED = 1;
    private static final int COMPLETION_FAILED = 2;

    private final ContainerRequest request;
    private final ContainerResponse response;
    private final Executor asyncExecutor;
    private volatile boolean deferred;
    private int completionState = COMPLETION_PENDING;
    private Throwable completionFailure;
    private CompletableFuture<Void> completion;

    public ContainerExchange(ContainerRequest request) {
        this(request, new ContainerResponse(), new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        });
    }

    public ContainerExchange(ContainerRequest request, ContainerResponse response) {
        this(request, response, new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        });
    }

    public ContainerExchange(ContainerRequest request, ContainerResponse response,
                             Executor asyncExecutor) {
        this.request = Objects.requireNonNull(request, "request");
        this.response = Objects.requireNonNull(response, "response");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
    }

    public ContainerRequest request() {
        return request;
    }

    public ContainerResponse response() {
        return response;
    }

    public void defer() {
        deferred = true;
    }

    public boolean deferred() {
        return deferred;
    }

    public void executeAsync(Runnable task) {
        asyncExecutor.execute(task);
    }

    public CompletableFuture<Void> completion() {
        CompletableFuture<Void> current;
        int state;
        Throwable failure;
        synchronized (this) {
            if (completion == null) {
                completion = new CompletableFuture<Void>();
            }
            current = completion;
            state = completionState;
            failure = completionFailure;
        }
        if (state == COMPLETION_SUCCEEDED) {
            current.complete(null);
        } else if (state == COMPLETION_FAILED) {
            current.completeExceptionally(failure);
        }
        return current;
    }

    public void complete() {
        CompletableFuture<Void> current;
        synchronized (this) {
            if (completionState != COMPLETION_PENDING) {
                return;
            }
            completionState = COMPLETION_SUCCEEDED;
            current = completion;
        }
        if (current != null) {
            current.complete(null);
        }
    }

    public void fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        CompletableFuture<Void> current;
        synchronized (this) {
            if (completionState != COMPLETION_PENDING) {
                return;
            }
            completionState = COMPLETION_FAILED;
            completionFailure = failure;
            current = completion;
        }
        if (current != null) {
            current.completeExceptionally(failure);
        }
    }
}
