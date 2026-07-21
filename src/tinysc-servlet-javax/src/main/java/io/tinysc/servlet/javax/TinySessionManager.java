package io.tinysc.servlet.javax;

import javax.servlet.ServletContext;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class TinySessionManager {
    private final ServletContext context;
    private final JavaxServletRuntime runtime;
    private final int defaultMaxInactiveSeconds;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, TinyHttpSession> sessions =
            new ConcurrentHashMap<String, TinyHttpSession>();

    TinySessionManager(ServletContext context, int timeoutMinutes, JavaxServletRuntime runtime) {
        this.context = context;
        this.runtime = runtime;
        this.defaultMaxInactiveSeconds = timeoutMinutes <= 0 ? 0 : timeoutMinutes * 60;
    }

    TinyHttpSession find(String id) {
        if (id == null) {
            return null;
        }
        TinyHttpSession session = sessions.get(id);
        if (session != null && session.expired(System.currentTimeMillis())) {
            invalidate(session);
            return null;
        }
        if (session != null) {
            session.beginAccess();
        }
        return session;
    }

    TinyHttpSession create() {
        while (true) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            TinyHttpSession session = new TinyHttpSession(
                    this, context, runtime, id, defaultMaxInactiveSeconds);
            if (sessions.putIfAbsent(id, session) == null) {
                runtime.fireSessionCreated(session);
                return session;
            }
        }
    }

    String changeId(TinyHttpSession session) {
        session.checkValid();
        String oldId = session.id();
        sessions.remove(session.id(), session);
        while (true) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (sessions.putIfAbsent(id, session) == null) {
                session.changeId(id);
                runtime.fireSessionIdChanged(session, oldId);
                return id;
            }
        }
    }

    void invalidate(TinyHttpSession session) {
        sessions.remove(session.id(), session);
        session.invalidateInternal();
        runtime.fireSessionDestroyed(session);
    }

    void clear() {
        for (TinyHttpSession session : sessions.values()) {
            invalidate(session);
        }
    }
}
