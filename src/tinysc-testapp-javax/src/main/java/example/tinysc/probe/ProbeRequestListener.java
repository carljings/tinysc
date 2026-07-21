package example.tinysc.probe;

import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;

public final class ProbeRequestListener implements ServletRequestListener {
    @Override
    public void requestInitialized(ServletRequestEvent event) {
        event.getServletRequest().setAttribute("probe.requestListener", "ok");
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
    }
}
