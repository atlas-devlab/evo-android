package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class AiContextTest {
    @Test
    public void debtQuestionKeepsOnlyRelevantDetailedSections() throws Exception {
        AiContext context = populated();
        JSONObject json = new JSONObject(
                context.toJson("Collega debiti, rate e prossime scadenze."));

        assertTrue(json.has("debiti"));
        assertTrue(json.has("programmate"));
        assertTrue(json.has("prossimeScadenze"));
        assertFalse(json.has("watchlist"));
        assertTrue(json.has("categoriePrincipali"));
        assertTrue(json.has("obiettivi"));
        assertTrue(json.has("dettagliDisponibili"));
    }

    @Test
    public void radarQuestionKeepsInvestmentEvidence() throws Exception {
        AiContext context = populated();
        JSONObject json = new JSONObject(
                context.toJson("Radar Finanziario: confronta ETF, tassi BCE e interessi."));

        assertTrue(json.has("watchlist"));
        assertTrue(json.has("datiInternet"));
        assertFalse(json.has("debiti"));
        assertFalse(json.has("programmate"));
    }

    @Test
    public void dialogueHistoryDoesNotActivateUnrelatedSections() throws Exception {
        AiContext context = populated();
        String prompt = "[DIALOGO_PERSISTENTE]\n"
                + "ULTIMI TURNI:\n- ASSISTENTE: avevamo parlato di ETF e crypto\n"
                + "Schermata e contesto visibile: Agenda con programmate e debiti.\n"
                + "DOMANDA ATTUALE: Quale rata scade prima?\n";
        JSONObject json = new JSONObject(context.toJson(prompt));

        assertTrue(json.has("debiti"));
        assertTrue(json.has("programmate"));
        assertFalse(json.has("watchlist"));
    }

    @Test
    public void explicitFullContextStillIncludesEverySection() throws Exception {
        JSONObject json = new JSONObject(populated().toJson());

        assertTrue(json.has("categoriePrincipali"));
        assertTrue(json.has("programmate"));
        assertTrue(json.has("debiti"));
        assertTrue(json.has("obiettivi"));
        assertTrue(json.has("contestoMultimediale"));
        assertTrue(json.has("watchlist"));
        assertTrue(json.has("datiInternet"));
    }

    @Test
    public void reviewCandidatesRemainVisibleInCompactContext() throws Exception {
        AiContext context = populated();
        context.speseDaRivedere.add(
                "ricorrenza da verificare | Streaming | 18.00 EUR/mese | Svago");

        JSONObject json = new JSONObject(context.toJson("Come sta il mio obiettivo?"));

        assertEquals(1, json.getJSONObject("dettagliDisponibili")
                .getInt("speseDaRivedere"));
        assertEquals(1, json.getJSONArray("speseDaRivedere").length());
        assertTrue(json.getJSONArray("speseDaRivedere").getString(0)
                .contains("Streaming"));
    }

    private static AiContext populated() {
        AiContext context = new AiContext();
        context.categoriePrincipali.add("Casa | 100 EUR");
        context.conti.add("Conto | 500 EUR");
        context.programmate.add("Affitto | 500 EUR");
        context.debiti.add("Prestito | residuo 1000 EUR");
        context.prossimeScadenze.add("2026-08-01 | rata");
        context.obiettivi.add("Fondo | 20%");
        context.movimentiRecenti.add("expense | Casa | 20 EUR");
        context.contestoMultimediale.add("scontrini collegati: 1");
        context.watchlist.add("ETF | Globale");
        context.datiInternet.add("tasso_bce | BCE");
        return context;
    }
}
