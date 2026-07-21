package io.tinysc.kernel;

import java.time.Duration;

public interface WebAppRuntime {
    void start() throws Exception;

    void service(ContainerExchange exchange) throws Exception;

    void stop(Duration gracePeriod) throws Exception;
}
