package it.evocore.lab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class TelemetrySessionControlInstrumentedTest {
    private static final String TAG = "EvoLabControl";

    @Test
    public void startEvoCoreDialogueSession() {
        Context context = ApplicationProvider.getApplicationContext();
        TelemetryRepository repository = new TelemetryRepository(context);
        repository.stopSession();
        String session = repository.startSession("Dialogo AI", "EvoCore");
        setCollectorEnabled(context, true);
        context.startService(new Intent(context, TelemetryCollectorService.class));

        assertFalse(session.isEmpty());
        assertTrue(session.equals(repository.activeSessionId()));
        Log.i(TAG, "STARTED session=" + session);
    }

    @Test
    public void stopSessionAndReportSummary() {
        Context context = ApplicationProvider.getApplicationContext();
        TelemetryRepository repository = new TelemetryRepository(context);
        String session = repository.activeSessionId();
        assertFalse("Nessuna sessione EvoLab attiva", session.isEmpty());

        repository.stopSession();
        context.stopService(new Intent(context, TelemetryCollectorService.class));
        setCollectorEnabled(context, false);
        TelemetryRepository.Summary summary = repository.summarize(session);

        assertTrue("EvoLab non ha ricevuto eventi", summary.eventCount > 0);
        Log.i(TAG, "STOPPED events=" + summary.eventCount
                + " aiRequests=" + summary.requestCount
                + " averageRequestMs=" + summary.averageRequestMs
                + " peakCorePssMb=" + summary.peakCorePssMb
                + " maxThermalStatus=" + summary.maxThermalStatus);
    }

    private static void setCollectorEnabled(Context context, boolean enabled) {
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, TelemetryCollectorService.class),
                enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
