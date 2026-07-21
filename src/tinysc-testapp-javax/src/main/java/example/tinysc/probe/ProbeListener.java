package example.tinysc.probe;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public final class ProbeListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        event.getServletContext().setAttribute("probe.listener", "ok");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        event.getServletContext().removeAttribute("probe.listener");
    }
}
