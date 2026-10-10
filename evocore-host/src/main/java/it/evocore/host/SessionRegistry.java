package it.evocore.host;

import android.util.Base64;
import android.os.SystemClock;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class SessionRegistry {
    static final class Session {
        final String token;
        final int uid;
        final String packageName;
        final String namespace;
        final String scope;
        final Set<String> capabilities;
        final int protocolVersion;
        final long grantEpoch;
        final long absoluteExpiresAt;
        volatile long expiresAt;

        Session(String token, CallerVerifier.Result caller, int protocolVersion, long grantEpoch,
                long expiresAt, long absoluteExpiresAt) {
            this.token = token;
            this.uid = caller.uid;
            this.packageName = caller.packageName;
            this.namespace = caller.namespace;
            this.scope = caller.scope();
            this.capabilities = caller.capabilities;
            this.protocolVersion = protocolVersion;
            this.grantEpoch = grantEpoch;
            this.expiresAt = expiresAt;
            this.absoluteExpiresAt = absoluteExpiresAt;
        }

        boolean permits(String capability) {
            return CapabilityPolicy.permits(capabilities, capability);
        }
    }

    private static final long SESSION_TTL_MS = 30L * 60L * 1000L;
    private static final long SESSION_ABSOLUTE_TTL_MS = 8L * 60L * 60L * 1000L;
    private static final long NONCE_TTL_MS = 10L * 60L * 1000L;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> recentNonces = new ConcurrentHashMap<>();

    synchronized Session open(CallerVerifier.Result caller, String nonce) {
        return open(caller, nonce, 2, 1L);
    }

    synchronized Session open(CallerVerifier.Result caller, String nonce, int protocolVersion,
                              long grantEpoch) {
        long now = SystemClock.elapsedRealtime();
        cleanup(now);
        String cleanNonce = nonce == null ? "" : nonce.trim();
        if (cleanNonce.length() < 24 || cleanNonce.length() > 128 || recentNonces.containsKey(cleanNonce)) return null;
        recentNonces.put(cleanNonce, now + NONCE_TTL_MS);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        long absolute = now + SESSION_ABSOLUTE_TTL_MS;
        Session session = new Session(token, caller, protocolVersion, grantEpoch,
                now + SESSION_TTL_MS, absolute);
        sessions.put(token, session);
        return session;
    }

    Session verify(String token, int uid, String packageName, String namespace) {
        if (token == null || token.isEmpty()) return null;
        Session session = sessions.get(token);
        long now = SystemClock.elapsedRealtime();
        if (session == null || session.expiresAt <= now || session.absoluteExpiresAt <= now || session.uid != uid ||
                !session.packageName.equals(packageName) || !session.namespace.equals(namespace)) {
            sessions.remove(token);
            return null;
        }
        session.expiresAt = Math.min(now + SESSION_TTL_MS, session.absoluteExpiresAt);
        return session;
    }

    void close(String token, int uid, String packageName) {
        Session session = token == null ? null : sessions.get(token);
        if (session != null && session.uid == uid && session.packageName.equals(packageName)) {
            sessions.remove(token, session);
        }
    }

    void invalidatePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (packageName.equals(entry.getValue().packageName)) {
                sessions.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void cleanup(long now) {
        Iterator<Map.Entry<String, Session>> sessionsIterator = sessions.entrySet().iterator();
        while (sessionsIterator.hasNext()) {
            Session session = sessionsIterator.next().getValue();
            if (session.expiresAt <= now || session.absoluteExpiresAt <= now) sessionsIterator.remove();
        }
        Iterator<Map.Entry<String, Long>> nonceIterator = recentNonces.entrySet().iterator();
        while (nonceIterator.hasNext()) {
            if (nonceIterator.next().getValue() <= now) nonceIterator.remove();
        }
    }
}
