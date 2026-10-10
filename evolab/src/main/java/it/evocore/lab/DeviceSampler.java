package it.evocore.lab;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class DeviceSampler {
    private final Context context;
    private final TelemetryRepository repository;
    private final ScheduledExecutorService scheduler;

    DeviceSampler(Context context, TelemetryRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "EvoLabDeviceSampler");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::sample, 0L, 5L, TimeUnit.SECONDS);
    }

    void stop() {
        scheduler.shutdownNow();
    }

    void sampleNow() {
        scheduler.execute(this::sample);
    }

    private void sample() {
        if (repository.activeSessionId().isEmpty()) return;
        try {
            JSONObject payload = new JSONObject();
            readBattery(payload);
            readPower(payload);
            readMemory(payload);
            payload.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            payload.put("sdk", Build.VERSION.SDK_INT);
            repository.record("device", "device.sample",
                    SystemClock.elapsedRealtimeNanos(), payload.toString());
        } catch (Exception ignored) {
        }
    }

    private void readBattery(JSONObject payload) throws Exception {
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) payload.put("batteryPct", level * 100d / scale);
        payload.put("batteryStatus",
                battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN));
        payload.put("batteryTemperatureC",
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10d);
        payload.put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
    }

    private void readPower(JSONObject payload) throws Exception {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power == null) return;
        payload.put("powerSave", power.isPowerSaveMode());
        if (Build.VERSION.SDK_INT >= 29) {
            payload.put("thermalStatus", power.getCurrentThermalStatus());
        }
        if (Build.VERSION.SDK_INT >= 30) {
            float headroom = power.getThermalHeadroom(0);
            if (!Float.isNaN(headroom)) payload.put("thermalHeadroom", headroom);
        }
    }

    private void readMemory(JSONObject payload) throws Exception {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        payload.put("deviceAvailMemoryBytes", memory.availMem);
        payload.put("deviceTotalMemoryBytes", memory.totalMem);
        payload.put("deviceLowMemory", memory.lowMemory);
        payload.put("deviceMemoryThresholdBytes", memory.threshold);
    }
}
