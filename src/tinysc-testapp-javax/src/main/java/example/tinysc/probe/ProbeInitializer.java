package example.tinysc.probe;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.annotation.HandlesTypes;
import java.util.EnumSet;
import java.util.Set;

@HandlesTypes(ProbeMarker.class)
public final class ProbeInitializer implements ServletContainerInitializer {
    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext context) throws ServletException {
        boolean found = classes != null && classes.contains(ProbeMarkerImpl.class);
        context.setAttribute("probe.sci", found ? "ok" : "missing");
        context.addListener(new ProbeRequestListener());

        ServletRegistration.Dynamic servlet =
                context.addServlet("dynamicProbe", DynamicProbeServlet.class);
        servlet.setLoadOnStartup(2);
        servlet.addMapping("/dynamic");

        FilterRegistration.Dynamic filter =
                context.addFilter("dynamicProbeFilter", DynamicProbeFilter.class);
        filter.addMappingForUrlPatterns(
                EnumSet.of(DispatcherType.REQUEST), true, "/dynamic");
    }
}
