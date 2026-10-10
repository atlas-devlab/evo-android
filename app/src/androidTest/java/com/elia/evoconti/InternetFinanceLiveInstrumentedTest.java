package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class InternetFinanceLiveInstrumentedTest {
    @Test
    public void refreshRequiresExplicitConsent() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        InternetFinanceConnector connector = new InternetFinanceConnector();
        connector.setConsent(app, false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(true);
        AtomicReference<String> message = new AtomicReference<>("");

        connector.refreshAsync(app, new EvoDb(app), (success, detail) -> {
            ok.set(success);
            message.set(detail);
            done.countDown();
        });

        assertTrue("Il callback del consenso non e arrivato", done.await(2, TimeUnit.SECONDS));
        assertFalse("La rete non deve partire senza consenso", ok.get());
        assertTrue(message.get().toLowerCase(Locale.ITALY).contains("consenso"));
    }

    @Test
    public void liveEcbRefreshCachesRatesFxSourcesAndTimestamps() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        InternetFinanceConnector connector = new InternetFinanceConnector();
        EvoDb db = new EvoDb(app);
        connector.setConsent(app, true);
        db.getWritableDatabase().delete("finance_cache", null, null);
        long startedAt = System.currentTimeMillis();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean();
        AtomicReference<String> message = new AtomicReference<>("");

        connector.refreshAsync(app, db, (success, detail) -> {
            ok.set(success);
            message.set(detail);
            done.countDown();
        });

        assertTrue("Timeout durante il download BCE", done.await(40, TimeUnit.SECONDS));
        assertTrue(message.get(), ok.get());
        List<EvoDb.FinanceCacheEntry> rows = db.financeCacheRows(20);
        assertTrue("Attesi tre tassi e quattro cambi BCE, trovati " + rows.size(), rows.size() >= 7);
        assertTrue(hasType(rows, "tasso_bce_depositi"));
        assertTrue(hasType(rows, "tasso_bce_rifinanziamento"));
        assertTrue(hasType(rows, "tasso_bce_prestito_marginale"));
        assertTrue(hasType(rows, "cambio_eur_usd"));
        assertTrue(hasType(rows, "cambio_eur_gbp"));
        assertTrue(hasType(rows, "cambio_eur_chf"));
        assertTrue(hasType(rows, "cambio_eur_jpy"));
        for (EvoDb.FinanceCacheEntry row : rows) {
            assertTrue(row.source.startsWith("https://"));
            assertTrue("Timestamp non aggiornato", row.updatedAt >= startedAt - 1_000L);
            assertTrue(row.reliability.contains("BCE"));
            assertTrue(row.disclaimer.contains("non consiglio"));
        }
    }

    @Test
    public void liveResearchSendsOnlySanitizedTermsAndCachesTrustedSource() {
        Context app = ApplicationProvider.getApplicationContext();
        InternetFinanceConnector connector = new InternetFinanceConnector();
        EvoDb db = new EvoDb(app);
        String question = "Sono Mario Rossi, mario.rossi@example.com, IBAN " +
                "IT60X0542811101000000123456 e ho 8450 euro. E vero che inflazione, " +
                "tassi BCE e TAEG cambiano il costo di un prestito?";
        String sanitized = InternetFinanceConnector.sanitizedFinanceQuery(question);

        assertFalse(sanitized.contains("mario"));
        assertFalse(sanitized.contains("example"));
        assertFalse(sanitized.contains("it60"));
        assertFalse(sanitized.contains("8450"));
        InternetFinanceConnector.ResearchResult result =
                connector.researchAndCache(db, question);

        assertTrue(result.message, result.ok);
        assertTrue("La query inviata non coincide con quella ripulita",
                result.query.equals(sanitized));
        assertTrue("Fonte non istituzionale: " + result.source, trusted(result.source));
        List<EvoDb.FinanceCacheEntry> rows = db.financeCacheRows(20);
        assertTrue("La fonte trovata non e stata salvata", containsSource(rows, result.source));
    }

    @Test
    public void liveInternetCacheFeedsGemmaThroughSeparateEvoCore() {
        Context app = ApplicationProvider.getApplicationContext();
        InternetFinanceConnector connector = new InternetFinanceConnector();
        EvoDb db = new EvoDb(app);
        String question = "Verifica con una fonte istituzionale se inflazione, tassi BCE e " +
                "TAEG influenzano il costo di un prestito, senza decidere al posto mio.";
        InternetFinanceConnector.ResearchResult research =
                connector.researchAndCache(db, question);
        assertTrue(research.message, research.ok);

        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(app, db);
        AiContext context = custodian.context();
        assertFalse("La cache Internet non e entrata nel contesto sintetico",
                context.datiInternet.isEmpty());
        String prompt = question + "\n[FONTE INTERNET AUTORIZZATA]\nSintesi: " +
                research.summary + "\nFonte: " + research.source +
                "\nDato informativo, non consiglio.";
        AiAnswer answer = custodian.ask(prompt);

        assertNotNull("Il Custode non ha risposto", answer);
        assertTrue("Gemma non e stata usata: " + answer.motore,
                answer.motore.startsWith("llama.cpp"));
        assertFalse(CustodianSafetyGuard.unsafe(answer.messaggioBreve));
        assertFalse(CustodianSafetyGuard.unsafe(answer.motivo));
        for (String action : answer.azioni) assertFalse(CustodianSafetyGuard.unsafe(action));
    }

    private static boolean hasType(List<EvoDb.FinanceCacheEntry> rows, String type) {
        for (EvoDb.FinanceCacheEntry row : rows) {
            if (type.equals(row.dataType)) return true;
        }
        return false;
    }

    private static boolean containsSource(List<EvoDb.FinanceCacheEntry> rows, String source) {
        for (EvoDb.FinanceCacheEntry row : rows) {
            if (source.equals(row.source) && row.disclaimer.contains("non consiglio")) return true;
        }
        return false;
    }

    private static boolean trusted(String source) {
        String value = source == null ? "" : source.toLowerCase(Locale.ROOT);
        return value.startsWith("https://") &&
                (value.contains("ecb.europa.eu") || value.contains("bancaditalia.it") ||
                        value.contains("consob.it") || value.contains("istat.it") ||
                        value.contains("europa.eu"));
    }
}
