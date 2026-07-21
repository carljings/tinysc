package io.tinysc.kernel;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ContainerExchange {
    private final ContainerRequest request;
    private final ContainerResponse response;
    private final Executor asyncExecutor;
    private final AtomicBoolean deferred = new AtomicBoolean();
    private final CompletableFuture<Void> completion = new CompletableFuture<Void>();

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
        deferred.set(true);
    }

    public boolean deferred() {
        return deferred.get();
    }

    public void executeAsync(Runnable task) {
        asyncExecutor.execute(task);
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public void complete() {
        completion.complete(null);
    }

    public void fail(Throwable failure) {
        completion.completeExceptionally(failure);
    }
}
