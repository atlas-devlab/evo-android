package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class RealModelGcSafetyInstrumentedTest {
    @Test
    public void gemmaInferenceRemainsSuspendableUnderGcPressure() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        LlamaCppEngine engine = new LlamaCppEngine(app);
        Assume.assumeTrue("Il modello GGUF deve essere installato", engine.isAvailable());

        AiContext context = new AiContext();
        context.saluteBudget = 72;
        context.statoSalute = "Stabile";
        context.completezzaDati = 90;
        context.redditoNetto = 2_300;
        context.speseFisse = 1_050;
        context.debitiPct = 11;
        context.saldoMensile = 1_207;
        context.fondoEmergenzaMesi = 0;

        AtomicBoolean running = new AtomicBoolean(true);
        Thread pressure = new Thread(() -> {
            List<byte[]> buffers = new ArrayList<>();
            while (running.get()) {
                for (int i = 0; i < 8; i++) buffers.add(new byte[2 * 1024 * 1024]);
                buffers.clear();
                System.gc();
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "EvoGcPressure");

        pressure.start();
        AiAnswer answer;
        try {
            answer = engine.ask(context,
                    "Valuta questo mese usando soltanto i dati e proteggi il fondo emergenza.", 192);
        } finally {
            running.set(false);
            pressure.interrupt();
            pressure.join(5_000);
            engine.unload();
        }

        assertNotNull("Errore modello: " + engine.lastError(), answer);
        assertFalse(answer.messaggioBreve.trim().isEmpty());
    }
}
