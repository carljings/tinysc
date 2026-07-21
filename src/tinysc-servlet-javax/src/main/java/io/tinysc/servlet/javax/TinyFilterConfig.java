package io.tinysc.servlet.javax;

import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

final class TinyFilterConfig implements FilterConfig {
    private final String name;
    private final ServletContext context;
    private final Map<String, String> initParameters;

    TinyFilterConfig(String name, ServletContext context, Map<String, String> initParameters) {
        this.name = name;
        this.context = context;
        this.initParameters = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(initParameters));
    }

    @Override
    public String getFilterName() {
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
