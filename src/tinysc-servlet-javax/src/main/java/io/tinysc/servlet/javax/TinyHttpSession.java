package io.tinysc.servlet.javax;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class TinyHttpSession implements HttpSession {
    private final TinySessionManager manager;
    private final ServletContext context;
    private final JavaxServletRuntime runtime;
    private final long creationTime = System.currentTimeMillis();
    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();
    private volatile String id;
    private volatile long lastAccessedTime = creationTime;
    private volatile int maxInactiveInterval;
    private volatile boolean valid = true;
    private volatile boolean isNew = true;

    TinyHttpSession(TinySessionManager manager, ServletContext context,
                    JavaxServletRuntime runtime, String id,
                    int maxInactiveInterval) {
        this.manager = manager;
        this.context = context;
        this.runtime = runtime;
        this.id = id;
        this.maxInactiveInterval = maxInactiveInterval;
    }

    @Override
    public long getCreationTime() {
        checkValid();
        return creationTime;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public long getLastAccessedTime() {
        checkValid();
        return lastAccessedTime;
    }

    @Override
    public ServletContext getServletContext() {
        return context;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        checkValid();
        maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        checkValid();
        return maxInactiveInterval;
    }

    @SuppressWarnings("deprecation")
    @Override
    public HttpSessionContext getSessionContext() {
        checkValid();
        return new HttpSessionContext() {
            @Override
            public HttpSession getSession(String sessionId) {
                return null;
            }

            @Override
            public Enumeration<String> getIds() {
                return Collections.emptyEnumeration();
            }
        };
    }

    @Override
    public Object getAttribute(String name) {
        checkValid();
        return attributes.get(name);
    }

    @SuppressWarnings("deprecation")
    @Override
    public Object getValue(String name) {
        return getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(attributes.keySet());
    }

    @SuppressWarnings("deprecation")
    @Override
    public String[] getValueNames() {
        checkValid();
        return attributes.keySet().toArray(new String[attributes.size()]);
    }

    @Override
    public void setAttribute(String name, Object value) {
        checkValid();
        if (value == null) {
            removeAttribute(name);
        } else {
            Object oldValue = attributes.put(name, value);
            if (oldValue instanceof HttpSessionBindingListener) {
                ((HttpSessionBindingListener) oldValue).valueUnbound(
                        new HttpSessionBindingEvent(this, name, oldValue));
            }
            if (value instanceof HttpSessionBindingListener) {
                ((HttpSessionBindingListener) value).valueBound(
                        new HttpSessionBindingEvent(this, name, value));
            }
            if (oldValue == null) {
                runtime.fireSessionAttributeAdded(this, name, value);
            } else {
                runtime.fireSessionAttributeReplaced(this, name, oldValue);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void putValue(String name, Object value) {
        setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        checkValid();
        Object oldValue = attributes.remove(name);
        if (oldValue != null) {
            if (oldValue instanceof HttpSessionBindingListener) {
                ((HttpSessionBindingListener) oldValue).valueUnbound(
                        new HttpSessionBindingEvent(this, name, oldValue));
            }
            runtime.fireSessionAttributeRemoved(this, name, oldValue);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void removeValue(String name) {
        removeAttribute(name);
    }

    @Override
    public void invalidate() {
        checkValid();
        manager.invalidate(this);
    }

    @Override
    public boolean isNew() {
        checkValid();
        return isNew;
    }

    boolean expired(long now) {
        return valid && maxInactiveInterval > 0
                && now - lastAccessedTime > maxInactiveInterval * 1000L;
    }

    void beginAccess() {
        checkValid();
        lastAccessedTime = System.currentTimeMillis();
    }

    void endAccess() {
        if (valid) {
            isNew = false;
        }
    }

    String id() {
        return id;
    }

    void changeId(String value) {
        id = value;
    }

    void invalidateInternal() {
        if (!valid) {
            return;
        }
        for (String name : attributes.keySet().toArray(new String[attributes.size()])) {
            removeAttribute(name);
        }
        valid = false;
    }

    void checkValid() {
        if (!valid) {
            throw new IllegalStateException("session has been invalidated");
        }
    }
}
