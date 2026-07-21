package io.tinysc.deployment;

public final class DeploymentException extends Exception {
    public DeploymentException(String message) {
        super(message);
    }

    public DeploymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
