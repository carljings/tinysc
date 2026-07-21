package io.tinysc.servlet.javax;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

final class TinyServletConfig implements ServletConfig {
    private final String name;
    private final ServletContext context;
    private final Map<String, String> initParameters;

    TinyServletConfig(String name, ServletContext context, Map<String, String> initParameters) {
        this.name = name;
        this.context = context;
        this.initParameters = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(initParameters));
    }

    @Override
    public String getServletName() {
        return name;
    }

    @Override
    public ServletContext getServletContext() {
        return context;
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
}
