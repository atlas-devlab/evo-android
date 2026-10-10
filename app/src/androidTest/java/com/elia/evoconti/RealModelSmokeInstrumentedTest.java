package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class RealModelSmokeInstrumentedTest {
    @Test
    public void gemma4HandlesTwoIndependentQuestionsWithoutReloading() {
        Context app = ApplicationProvider.getApplicationContext();
        LlamaCppEngine engine = new LlamaCppEngine(app);
        Assume.assumeTrue("Il modello GGUF deve essere installato", engine.isAvailable());

        AiContext context = new AiContext();
        context.saluteBudget = 42;
        context.statoSalute = "Malato lieve";
        context.completezzaDati = 90;
        context.redditoNetto = 2_300;
        context.speseFisse = 1_250;
        context.usciteEssenzialiPct = 54;
        context.desideriPct = 22;
        context.futuroPct = 8;
        context.debitiPct = 17;
        context.debitoResiduo = 8_400;
        context.saldoMensile = 120;
        context.fondoEmergenzaMesi = 1.2;
        context.stressUtente = 7;
        context.checkInOre = 2;

        AiAnswer first = engine.ask(context,
                "Posso aumentare gli investimenti questo mese? Rispondi usando solo i dati.", 192);

        Log.i("EvoAiSmoke", "firstLatencyMs=" + engine.lastLatencyMs() +
                " error=" + engine.lastError() + " raw=" + engine.lastRaw());
        assertComplete(engine, first);

        context.saluteBudget = 86;
        context.statoSalute = "Sano";
        context.debitiPct = 0;
        context.debitoResiduo = 0;
        context.saldoMensile = 500;
        context.fondoEmergenzaMesi = 4;
        context.stressUtente = 3;
        AiAnswer second = engine.ask(context,
                "Il debito e estinto: qual e il prossimo passo prudente?", 192);

        Log.i("EvoAiSmoke", "secondLatencyMs=" + engine.lastLatencyMs() +
                " error=" + engine.lastError() + " raw=" + engine.lastRaw());
        assertComplete(engine, second);
        engine.unload();
    }

    private static void assertComplete(LlamaCppEngine engine, AiAnswer answer) {
        assertNotNull("Errore modello: " + engine.lastError(), answer);
        assertFalse(answer.messaggioBreve.trim().isEmpty());
        assertFalse(answer.motivo.trim().isEmpty());
        assertTrue(answer.azioni.size() >= 1 && answer.azioni.size() <= 4);
    }
}
