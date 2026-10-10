package it.evocore.lab;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;

import it.evocore.telemetry.ITelemetryCollector;
import it.evocore.telemetry.TelemetryProtocol;

public final class TelemetryCollectorService extends Service {
    private TelemetryRepository repository;
    private DeviceSampler deviceSampler;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new TelemetryRepository(this);
    }

    private final ITelemetryCollector.Stub binder = new ITelemetryCollector.Stub() {
        @Override
        public String getActiveSessionId() {
            return trustedSource().isEmpty() ? "" : repository.activeSessionId();
        }

        @Override
        public void publishEvent(String type, long elapsedRealtimeNanos, String payloadJson) {
            String source = trustedSource();
            if (source.isEmpty() || !TelemetryProtocol.isValidType(type)) return;
            ensureSampler();
            repository.record(source, type, elapsedRealtimeNanos,
                    TelemetryProtocol.boundedPayload(payloadJson));
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        ensureSampler();
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureSampler();
        return START_NOT_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopSampler();
        return false;
    }

    @Override
    public void onDestroy() {
        stopSampler();
        super.onDestroy();
    }

    private synchronized void ensureSampler() {
        if (repository == null || repository.activeSessionId().isEmpty()
                || deviceSampler != null) return;
        deviceSampler = new DeviceSampler(this, repository);
        deviceSampler.start();
    }

    private synchronized void stopSampler() {
        if (deviceSampler == null) return;
        deviceSampler.stop();
        deviceSampler = null;
    }

    private String trustedSource() {
        int uid = Binder.getCallingUid();
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages == null) return "";
        for (String candidate : packages) {
            if (getPackageManager().checkSignatures(getPackageName(), candidate)
                    == PackageManager.SIGNATURE_MATCH) {
                return candidate;
            }
        }
        return "";
    }
}
