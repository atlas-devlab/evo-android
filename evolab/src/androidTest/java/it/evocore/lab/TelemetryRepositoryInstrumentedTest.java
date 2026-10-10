package it.evocore.lab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class TelemetryRepositoryInstrumentedTest {
    private TelemetryRepository repository;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        repository = new TelemetryRepository(context);
        repository.deleteAll();
    }

    @After
    public void tearDown() {
        repository.deleteAll();
    }

    @Test
    public void cryptoRoundTripPreservesPayload() {
        TelemetryCrypto crypto = new TelemetryCrypto();
        String clear = "{\"metric\":42,\"state\":\"ok\"}";
        String encrypted = crypto.encrypt(clear);
        assertFalse(encrypted.contains("\"metric\""));
        assertEquals(clear, crypto.decrypt(encrypted));
    }

    @Test
    public void eventIsIgnoredWithoutActiveSession() {
        boolean recorded = repository.record("test", "process.sample",
                SystemClock.elapsedRealtimeNanos(), "{\"pssKb\":100}");
        assertFalse(recorded);
        assertTrue(repository.recentSessions(2).isEmpty());
    }

    @Test
    public void sessionCanStartRecordAndStop() {
        String id = repository.startSession("Dialogo AI", "EvoCore");
        assertEquals(id, repository.activeSessionId());
        TelemetryRepository.SessionRow active = repository.activeSession();
        assertNotNull(active);
        assertEquals(id, active.id);
        assertEquals("Dialogo AI", active.scenario);
        assertEquals("EvoCore", active.target);
        assertEquals("ACTIVE", active.status);
        assertTrue(repository.record("test", "process.sample",
                SystemClock.elapsedRealtimeNanos(), "{\"pssKb\":2048}"));
        repository.stopSession();
        assertTrue(repository.activeSessionId().isEmpty());
        assertEquals(null, repository.activeSession());
        assertEquals("COMPLETE", repository.recentSessions(1).get(0).status);
    }

    @Test
    public void summaryAggregatesAiMemoryFramesAndBattery() {
        String id = repository.startSession("Uso rapido", "EvoConti");
        repository.record("host", "ai.request.final", 1L,
                "{\"totalMs\":500,\"firstResponseMs\":120,\"fallback\":true}");
        repository.record("com.elia.evoconti", "process.sample", 2L, "{\"pssKb\":102400}");
        repository.record("client", "ui.frame_summary", 3L,
                "{\"frames\":100,\"jankyFrames\":4}");
        repository.record("device", "device.sample", 4L,
                "{\"batteryPct\":90,\"thermalStatus\":1,\"thermalHeadroom\":0.3}");
        repository.record("device", "device.sample", 5L,
                "{\"batteryPct\":89,\"thermalStatus\":2,\"thermalHeadroom\":0.6}");
        TelemetryRepository.Summary summary = repository.summarize(id);
        assertEquals(1L, summary.requestCount);
        assertEquals(1L, summary.fallbackCount);
        assertEquals(500d, summary.averageRequestMs, 0.01d);
        assertEquals(100d, summary.peakPssMb, 0.01d);
        assertEquals(100L, summary.frameCount);
        assertEquals(4L, summary.jankyFrames);
        assertEquals(90d, summary.batteryStart, 0.01d);
        assertEquals(89d, summary.batteryEnd, 0.01d);
        assertEquals(2, summary.maxThermalStatus);
    }

    @Test
    public void exportsAreReadableAndDeleteClearsArchive() {
        repository.startSession("Uso prolungato", "EvoCore");
        repository.record("test", "session.marker", 1L, "{\"action\":\"start\"}");
        String json = repository.exportJson();
        String csv = repository.exportCsv();
        assertTrue(json.contains("\"schema\": \"evolab.export.v1\""));
        assertTrue(json.contains("\"action\": \"start\""));
        assertTrue(csv.contains("session_id,scenario,target"));
        assertTrue(csv.contains("session.marker"));
        repository.deleteAll();
        assertTrue(repository.recentSessions(1).isEmpty());
    }

    @Test
    public void chartKeepsOnlyTheSelectedTargetProcess() {
        String id = repository.startSession("Dialogo AI", "EvoConti");
        repository.record("it.evocore.lab", "process.sample", 1L, "{\"pssKb\":40960}");
        repository.record("it.evocore.host", "process.sample", 2L, "{\"pssKb\":1024000}");
        repository.record("com.elia.evoconti", "process.sample", 3L, "{\"pssKb\":61440}");
        repository.record("com.elia.evoconti", "process.sample", 4L, "{\"pssKb\":62464}");
        List<TelemetryRepository.ChartSample> samples = repository.chartSamples(id, 20);
        boolean found60Mb = false;
        boolean found61Mb = false;
        boolean leakedCoreSample = false;
        for (TelemetryRepository.ChartSample sample : samples) {
            if (Float.isNaN(sample.memoryMb)) continue;
            found60Mb |= Math.abs(sample.memoryMb - 60f) < 0.01f;
            found61Mb |= Math.abs(sample.memoryMb - 61f) < 0.01f;
            leakedCoreSample |= Math.abs(sample.memoryMb - 1000f) < 0.01f;
        }
        assertTrue(found60Mb);
        assertTrue(found61Mb);
        assertFalse(leakedCoreSample);
        TelemetryRepository.Summary summary = repository.summarize(id);
        assertTrue(summary.peakPssMb >= 61d);
        assertTrue(summary.peakCorePssMb >= 1000d);
        assertTrue(summary.peakPssMb < summary.peakCorePssMb);
    }

    @Test
    public void largeEncryptedSessionRemainsReadable() {
        String id = repository.startSession("Uso prolungato", "EvoCore");
        for (int index = 0; index < 500; index++) {
            assertTrue(repository.record("it.evocore.host", "process.sample", index,
                    "{\"pssKb\":" + (100000 + index) + "}"));
        }
        TelemetryRepository.Summary summary = repository.summarize(id);
        assertTrue(summary.eventCount >= 500L);
        assertTrue(summary.peakPssMb > 97d);
        assertNotNull(repository.exportJson());
    }
}
