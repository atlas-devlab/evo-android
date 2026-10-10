package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import it.evocore.client.ContextEnvelope;
import it.evocore.client.CoreEvent;
import it.evocore.client.EvoCoreClient;
import it.evocore.client.ToolRegistry;

@RunWith(AndroidJUnit4.class)
@LargeTest
public final class EvoCoreLatencyInstrumentedTest {
    private static final String TAG = "EvoCoreLatency";

    @Test
    public void measuresColdWarmAndPostIdleStreamingLatency() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assumeTrue("EvoCore host must be installed", EvoCoreClient.isInstalled(context));

        try (EvoCoreClient client = new EvoCoreClient(context, "evoconti", null)) {
            long connectStart = SystemClock.elapsedRealtime();
            assertTrue("EvoCore bind was rejected", client.connect());
            while (!client.isReady()
                    && SystemClock.elapsedRealtime() - connectStart < 5_000L) {
                Thread.sleep(20L);
            }
            assertTrue("EvoCore handshake did not complete", client.isReady());
            long connectMs = SystemClock.elapsedRealtime() - connectStart;

            EvoCoreClient.Result status = client.status();
            assertTrue(status.error, status.ok);
            assumeTrue("A local model is required",
                    status.data.getBoolean("modelAvailable", false));
            Log.i(TAG, "connectMs=" + connectMs);

            String payload = "{"
                    + "\"schema\":\"evoconti.context.v1\","
                    + "\"saluteBudget\":62,"
                    + "\"redditoNetto\":2300,"
                    + "\"speseFisse\":1250,"
                    + "\"debitiPct\":9,"
                    + "\"saldoMensile\":280,"
                    + "\"fondoEmergenzaMesi\":2.1,"
                    + "\"obiettiviAttivi\":2"
                    + "}";

            measure(client, "cold", payload);
            measure(client, "warm", payload);

            status = client.status();
            long idleMs = status.data.getLong("modelIdleUnloadMs", 15_000L);
            Thread.sleep(idleMs + 2_000L);
            status = client.status();
            assertTrue(status.error, status.ok);
            assertTrue("The model should be unloaded before the post-idle sample",
                    !status.data.getBoolean("modelLoaded", true));

            measure(client, "post_idle", payload);
        }
    }

    private static void measure(EvoCoreClient client, String label, String payload)
            throws Exception {
        long started = SystemClock.elapsedRealtime();
        AtomicLong accepted = new AtomicLong(-1L);
        AtomicLong reasoning = new AtomicLong(-1L);
        AtomicLong firstText = new AtomicLong(-1L);
        AtomicInteger deltas = new AtomicInteger();
        AtomicInteger chars = new AtomicInteger();

        ContextEnvelope envelope = ContextEnvelope.screen(
                "evoconti.dialogue.v1", "custodian", payload);
        EvoCoreClient.RequestHandle handle = client.submit(
                envelope,
                "Collega margine, debiti e fondo e proponi due passi prudenti.",
                "custodian",
                "latency-" + label,
                120_000L,
                new ToolRegistry(),
                Runnable::run,
                event -> {
                    long now = SystemClock.elapsedRealtime();
                    if (event.type == CoreEvent.ACCEPTED) accepted.compareAndSet(-1L, now);
                    if (event.type == CoreEvent.STARTED) reasoning.compareAndSet(-1L, now);
                    if (event.type == CoreEvent.TEXT_DELTA && !event.textDelta.isEmpty()) {
                        firstText.compareAndSet(-1L, now);
                        deltas.incrementAndGet();
                        chars.addAndGet(event.textDelta.length());
                    }
                });

        CoreEvent terminal = handle.terminal().get(125_000L, TimeUnit.MILLISECONDS);
        long finished = SystemClock.elapsedRealtime();
        assertEquals(terminal.errorMessage, CoreEvent.FINAL, terminal.type);
        assertTrue("No streamed text was emitted", firstText.get() >= started);

        Log.i(TAG, label
                + " acceptedMs=" + elapsed(started, accepted.get())
                + " startedMs=" + elapsed(started, reasoning.get())
                + " firstTextMs=" + elapsed(started, firstText.get())
                + " totalMs=" + (finished - started)
                + " deltas=" + deltas.get()
                + " streamedChars=" + chars.get()
                + " finalChars=" + terminal.finalJson.length()
                + " engine=" + terminal.engine);
    }

    private static long elapsed(long started, long timestamp) {
        return timestamp < started ? -1L : timestamp - started;
    }
}
