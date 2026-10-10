package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public final class ReviewableExpenseInferenceInstrumentedTest {
    private static final String TAG = "EvoExpenseReview";

    @Test
    public void keepsTheFullPictureWhileReviewingPotentialWaste() throws Exception {
        AiContext context = new AiContext();
        context.saluteBudget = 58;
        context.statoSalute = "Affaticato";
        context.completezzaDati = 95;
        context.redditoNetto = 2_400;
        context.speseFisse = 1_050;
        context.usciteEssenzialiPct = 49;
        context.desideriPct = 34;
        context.futuroPct = 8;
        context.debitiPct = 9;
        context.debitoResiduo = 2_100;
        context.saldoMensile = 160;
        context.fondoEmergenzaMesi = 1.4;
        context.speseImpulsive = 4;
        context.obiettiviAttivi = 2;
        context.obiettiviInRitardo = 1;
        context.categoriePrincipali.add("Svago | 210.00 EUR");
        context.categoriePrincipali.add("Casa | 760.00 EUR");
        context.programmate.add("uscita | Streaming | 18.00 EUR | Svago");
        context.prossimeScadenze.add("2026-08-01 09:00 | rata | Prestito | 90.00 EUR");
        context.obiettivi.add("Fondo emergenza | 35% | active");
        context.movimentiRecenti.add("expense | Aperitivo | Svago | 46.00 EUR");
        context.speseDaRivedere.add(
                "ricorrenza da verificare | Streaming | 18.00 EUR/mese | Svago");
        context.speseDaRivedere.add(
                "categoria flessibile da verificare | Svago | 210.00 EUR nel mese");

        String question = "Quali spese inutili o evitabili posso rivedere senza toccare quelle essenziali?";
        JSONObject payload = new JSONObject(context.toJson(question));
        assertTrue(payload.has("redditoNetto"));
        assertTrue(payload.has("debitiPct"));
        assertTrue(payload.has("prossimeScadenze"));
        assertTrue(payload.has("obiettivi"));
        assertTrue(payload.has("speseDaRivedere"));

        AiAnswer baseline = new RuleBasedEngine().ask(context, question);
        assertTrue(baseline.messaggioBreve.contains("non automaticamente inutili"));
        assertTrue(baseline.azioni.get(0).contains("Streaming"));

        Context app = ApplicationProvider.getApplicationContext();
        LlamaCppEngine engine = new LlamaCppEngine(app);
        assumeTrue("EvoCore con Gemma non disponibile", engine.isAvailable());
        long started = SystemClock.elapsedRealtime();
        AiAnswer generated = engine.ask(context, question, 128);
        AiAnswer guarded = CustodianSafetyGuard.merge(baseline, generated);
        long elapsed = SystemClock.elapsedRealtime() - started;

        assertNotNull(guarded);
        assertFalse(guarded.messaggioBreve.isEmpty());
        assertFalse(CustodianSafetyGuard.unsafe(guarded.messaggioBreve));
        for (String action : guarded.azioni) {
            assertFalse(CustodianSafetyGuard.unsafe(action));
        }
        Log.i(TAG, "elapsedMs=" + elapsed
                + " contextChars=" + context.toJson(question).length()
                + " modelAccepted=" + guarded.motore.startsWith("llama.cpp")
                + " candidates=" + context.speseDaRivedere.size());
        engine.unload();
    }
}
