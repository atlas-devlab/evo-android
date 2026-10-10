package it.evocore.telemetry;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

final class TelemetryClient {
    interface SessionListener {
        void onSessionStateChanged(boolean active);
    }

    private static final int MAX_PENDING_EVENTS = 32;
    private final Context context;
    private final SessionListener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "EvoTelemetryClient");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean binding = new AtomicBoolean();
    private final ArrayDeque<PendingEvent> pending = new ArrayDeque<>();
    private volatile ITelemetryCollector remote;
    private volatile boolean bound;
    private volatile boolean sessionActive;
    private volatile boolean closed;

    TelemetryClient(Context context, SessionListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void connect() {
        if (closed || remote != null || !binding.compareAndSet(false, true)) return;
        execute(() -> {
            if (!trustedCollector() || !collectorEnabled()) {
                binding.set(false);
                return;
            }
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    TelemetryProtocol.LAB_PACKAGE, TelemetryProtocol.COLLECTOR_CLASS));
            try {
                // EvoDashboard enables this component only during an explicit measurement.
                boolean accepted = context.bindService(
                        intent, connection, Context.BIND_AUTO_CREATE);
                bound = accepted;
                if (!accepted) {
                    binding.set(false);
                }
            } catch (Exception ignored) {
                binding.set(false);
            }
        });
    }

    void emit(String type, JSONObject payload) {
        if (!TelemetryProtocol.isValidType(type) || closed) return;
        PendingEvent event = new PendingEvent(type,
                android.os.SystemClock.elapsedRealtimeNanos(),
                TelemetryProtocol.boundedPayload(payload == null ? "{}" : payload.toString()));
        execute(() -> {
            ITelemetryCollector collector = remote;
            if (collector == null) {
                if (binding.get()) enqueue(event);
                return;
            }
            if (!refreshSession(collector)) return;
            try {
                collector.publishEvent(event.type, event.elapsedNanos, event.payloadJson);
            } catch (RemoteException dead) {
                detach();
            }
        });
    }

    boolean isSessionActive() {
        return sessionActive;
    }

    boolean refreshSession() {
        ITelemetryCollector collector = remote;
        return collector != null && refreshSession(collector);
    }

    private boolean refreshSession(ITelemetryCollector collector) {
        try {
            String id = collector.getActiveSessionId();
            boolean active = id != null && !id.isEmpty();
            setSessionActive(active);
            if (!active) {
                clearPending();
                detach();
            }
            return active;
        } catch (RemoteException dead) {
            detach();
            return false;
        }
    }

    void close() {
        closed = true;
        clearPending();
        detach();
        io.shutdownNow();
    }

    private void detach() {
        remote = null;
        setSessionActive(false);
        boolean shouldUnbind = bound || binding.getAndSet(false);
        bound = false;
        try {
            if (shouldUnbind) context.unbindService(connection);
        } catch (Exception ignored) {
        }
    }

    private void enqueue(PendingEvent event) {
        synchronized (pending) {
            while (pending.size() >= MAX_PENDING_EVENTS) pending.removeFirst();
            pending.addLast(event);
        }
    }

    private void flushPending(ITelemetryCollector collector) {
        List<PendingEvent> events;
        synchronized (pending) {
            events = new ArrayList<>(pending);
            pending.clear();
        }
        for (PendingEvent event : events) {
            try {
                collector.publishEvent(event.type, event.elapsedNanos, event.payloadJson);
            } catch (RemoteException dead) {
                detach();
                return;
            }
        }
    }

    private void clearPending() {
        synchronized (pending) {
            pending.clear();
        }
    }

    private void setSessionActive(boolean active) {
        if (sessionActive == active) return;
        sessionActive = active;
        if (listener != null) listener.onSessionStateChanged(active);
    }

    private void execute(Runnable runnable) {
        if (closed) return;
        try {
            io.execute(runnable);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private boolean trustedCollector() {
        try {
            return context.getPackageManager().checkSignatures(
                    context.getPackageName(), TelemetryProtocol.LAB_PACKAGE)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean collectorEnabled() {
        try {
            ComponentName component = new ComponentName(
                    TelemetryProtocol.LAB_PACKAGE, TelemetryProtocol.COLLECTOR_CLASS);
            return context.getPackageManager().getComponentEnabledSetting(component)
                    == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception ignored) {
            return false;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            remote = ITelemetryCollector.Stub.asInterface(service);
            bound = true;
            binding.set(false);
            execute(() -> {
                ITelemetryCollector collector = remote;
                if (collector != null && refreshSession(collector)) {
                    flushPending(collector);
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            bound = false;
            binding.set(false);
            setSessionActive(false);
            clearPending();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            detach();
        }
    };

    private static final class PendingEvent {
        final String type;
        final long elapsedNanos;
        final String payloadJson;

        PendingEvent(String type, long elapsedNanos, String payloadJson) {
            this.type = type;
            this.elapsedNanos = elapsedNanos;
            this.payloadJson = payloadJson;
        }
    }
}
