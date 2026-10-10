package it.evocore.telemetry;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Debug;
import android.os.Process;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TelemetryRuntime {
    private static final Object LOCK = new Object();
    private static TelemetryClient client;
    private static ScheduledExecutorService sampler;
    private static Context appContext;
    private static int owners;

    private TelemetryRuntime() {
    }

    static void prepare(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
        }
    }

    /**
     * Activates private telemetry for a foreground component. The collector is probed once and
     * immediately released when EvoDashboard has no active measurement.
     */
    public static void start(Context context) {
        if (context == null) return;
        TelemetryClient value;
        boolean firstOwner;
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            firstOwner = owners++ == 0;
            if (client == null) {
                client = new TelemetryClient(appContext,
                        TelemetryRuntime::onSessionStateChanged);
            }
            value = client;
        }
        value.connect();
        if (firstOwner) {
            event("process.started", processIdentity(appContext));
            emitPreviousExit(appContext);
        }
    }

    /** Backwards-compatible alias for clients compiled against the first telemetry kit. */
    public static void initialize(Context context) {
        start(context);
    }

    public static void stop() {
        TelemetryClient toClose = null;
        synchronized (LOCK) {
            if (owners == 0) return;
            owners--;
            if (owners > 0) return;
            stopSamplerLocked();
            toClose = client;
            client = null;
        }
        if (toClose != null) toClose.close();
    }

    /** Rechecks EvoDashboard after a foreground component becomes visible again. */
    public static void refreshSession() {
        TelemetryClient value;
        synchronized (LOCK) {
            value = client;
        }
        if (value != null) value.connect();
    }

    public static void event(String type, JSONObject payload) {
        TelemetryClient value;
        synchronized (LOCK) {
            value = client;
        }
        if (value != null) value.emit(type, payload);
    }

    public static void screen(String screen) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("screen", safeLabel(screen));
        } catch (Exception ignored) {
        }
        event("ui.screen", payload);
    }

    public static boolean isSessionActive() {
        TelemetryClient value;
        synchronized (LOCK) {
            value = client;
        }
        return value != null && value.isSessionActive();
    }

    static String safeLabel(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > 96) clean = clean.substring(0, 96);
        return clean.replaceAll("[^A-Za-z0-9._ -]", "_");
    }

    private static void sampleProcess() {
        TelemetryClient value;
        synchronized (LOCK) {
            value = client;
        }
        if (value == null || !value.refreshSession()) return;
        try {
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memory);
            Runtime runtime = Runtime.getRuntime();
            JSONObject payload = new JSONObject();
            payload.put("pssKb", memory.getTotalPss());
            payload.put("javaPrivateKb", memory.dalvikPrivateDirty);
            payload.put("nativePrivateKb", memory.nativePrivateDirty);
            payload.put("otherPrivateKb", memory.otherPrivateDirty);
            payload.put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize());
            payload.put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory());
            payload.put("cpuElapsedMs", Process.getElapsedCpuTime());
            payload.put("threads", Thread.getAllStackTraces().size());
            value.emit("process.sample", payload);
        } catch (Exception ignored) {
        }
    }

    private static void onSessionStateChanged(boolean active) {
        synchronized (LOCK) {
            if (active && owners > 0) {
                if (sampler != null && !sampler.isShutdown()) return;
                sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "EvoTelemetrySampler");
                    thread.setDaemon(true);
                    return thread;
                });
                sampler.scheduleWithFixedDelay(TelemetryRuntime::sampleProcess,
                        0L, 5L, TimeUnit.SECONDS);
            } else {
                stopSamplerLocked();
            }
        }
    }

    private static void stopSamplerLocked() {
        if (sampler == null) return;
        sampler.shutdownNow();
        sampler = null;
    }

    static JSONObject processIdentity(Context context) {
        JSONObject payload = new JSONObject();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            payload.put("package", context.getPackageName());
            payload.put("version", info.versionName == null ? "" : info.versionName);
            payload.put("sdk", Build.VERSION.SDK_INT);
            payload.put("pid", Process.myPid());
        } catch (Exception ignored) {
        }
        return payload;
    }

    static void emitPreviousExit(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 1);
            if (exits.isEmpty()) return;
            ApplicationExitInfo exit = exits.get(0);
            JSONObject payload = new JSONObject();
            payload.put("reason", exit.getReason());
            payload.put("importance", exit.getImportance());
            payload.put("pssKb", exit.getPss());
            payload.put("rssKb", exit.getRss());
            payload.put("timestamp", exit.getTimestamp());
            event("process.previous_exit", payload);
        } catch (Exception ignored) {
        }
    }
}
