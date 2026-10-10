package it.evocore.telemetry;

import android.os.Handler;
import android.os.Looper;
import android.view.Window;

import androidx.metrics.performance.FrameData;
import androidx.metrics.performance.JankStats;

import org.json.JSONObject;

public final class TelemetryJankTracker {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JankStats jankStats;
    private long frames;
    private long janky;
    private long durationTotalNanos;
    private long durationMaxNanos;
    private String screen = "unknown";
    private boolean stopped;

    private TelemetryJankTracker(Window window) {
        jankStats = JankStats.createAndTrack(window, this::onFrame);
        handler.postDelayed(this::flushAndSchedule, 5_000L);
    }

    public static TelemetryJankTracker attach(Window window) {
        if (window == null || window.peekDecorView() == null) return null;
        try {
            return new TelemetryJankTracker(window);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void setScreen(String value) {
        screen = TelemetryRuntime.safeLabel(value);
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        jankStats.setTrackingEnabled(false);
        flush();
    }

    private synchronized void onFrame(FrameData frame) {
        frames++;
        long duration = frame.getFrameDurationUiNanos();
        durationTotalNanos += duration;
        durationMaxNanos = Math.max(durationMaxNanos, duration);
        if (frame.isJank()) janky++;
    }

    private void flushAndSchedule() {
        if (stopped) return;
        flush();
        handler.postDelayed(this::flushAndSchedule, 5_000L);
    }

    private synchronized void flush() {
        if (frames == 0) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("screen", screen);
            payload.put("frames", frames);
            payload.put("jankyFrames", janky);
            payload.put("avgUiMs", durationTotalNanos / 1_000_000d / frames);
            payload.put("maxUiMs", durationMaxNanos / 1_000_000d);
        } catch (Exception ignored) {
        }
        frames = 0;
        janky = 0;
        durationTotalNanos = 0;
        durationMaxNanos = 0;
        TelemetryRuntime.event("ui.frame_summary", payload);
    }
}
