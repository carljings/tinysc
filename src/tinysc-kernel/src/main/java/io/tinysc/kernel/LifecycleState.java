package io.tinysc.kernel;

public enum LifecycleState {
    NEW,
    INITIALIZING,
    STARTING,
    RUNNING,
    QUIESCING,
    STOPPING,
    STOPPED,
    FAILED
}
