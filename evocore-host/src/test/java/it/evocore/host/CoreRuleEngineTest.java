package it.evocore.host;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CoreRuleEngineTest {
    private final CoreRuleEngine engine = new CoreRuleEngine();

    @Test public void negativeBalanceTriggersProtection() throws Exception {
        JSONObject answer = ask("{\"redditoNetto\":2000,\"saldoMensile\":-80,\"fondoEmergenzaMesi\":2}", "Come va?");
        assertEquals("protezione", answer.getString("stato"));
        assertFalse(answer.getBoolean("puoInvestire"));
    }

    @Test public void highDebtTriggersProtection() throws Exception {
        JSONObject answer = ask("{\"redditoNetto\":2200,\"saldoMensile\":300,\"debitiPct\":24,\"fondoEmergenzaMesi\":3}", "Analizza");
        assertEquals("alto", answer.getString("rischio"));
    }

    @Test public void healthyBudgetAllowsPrudentQuota() throws Exception {
        JSONObject answer = ask("{\"redditoNetto\":2500,\"saldoMensile\":500,\"debitiPct\":4,\"fondoEmergenzaMesi\":4,\"saluteBudget\":82}", "Come va?");
        assertEquals("equilibrio", answer.getString("stato"));
        assertTrue(answer.getBoolean("puoInvestire"));
    }

    @Test public void typedEnvelopeReadsFinancialValuesFromPayload() throws Exception {
        JSONObject answer = ask("{\"trust\":\"CLIENT_ASSERTED\","
                        + "\"schema\":\"evoconti.dialogue.v1\",\"screen\":\"custodian\","
                        + "\"payload\":{\"schema\":\"evoconti.context.v1\","
                        + "\"redditoNetto\":2500,\"saldoMensile\":500,\"debitiPct\":4,"
                        + "\"fondoEmergenzaMesi\":4,\"saluteBudget\":82}}",
                "Come va?");

        assertEquals("equilibrio", answer.getString("stato"));
        assertEquals("contenuto", answer.getString("rischio"));
        assertTrue(answer.getBoolean("puoInvestire"));
    }

    @Test public void typedEnvelopeKeepsNegativePayloadInProtection() throws Exception {
        JSONObject answer = ask("{\"schema\":\"evoconti.dialogue.v1\","
                        + "\"payload\":{\"redditoNetto\":2500,\"saldoMensile\":-20,"
                        + "\"debitiPct\":4,\"fondoEmergenzaMesi\":4}}",
                "Come va?");

        assertEquals("protezione", answer.getString("stato"));
        assertFalse(answer.getBoolean("puoInvestire"));
    }

    @Test public void refusesSpecificTradeChoice() throws Exception {
        JSONObject answer = ask(healthy(), "Quale ETF devo comprare?");
        assertFalse(answer.getBoolean("puoInvestire"));
        assertTrue(answer.getString("messaggioBreve").contains("Non scelgo"));
    }

    @Test public void emptyMemoryIsHonest() throws Exception {
        JSONObject answer = new JSONObject(engine.answer(healthy(), "Cosa sai di me?", ""));
        assertTrue(answer.getString("messaggioBreve").contains("Non ho ancora"));
    }

    @Test public void confirmedMemoryCanBeSummarized() throws Exception {
        JSONObject answer = new JSONObject(engine.answer(healthy(), "Cosa ricordi?",
                "- [USER_CONFIRMED] Preferisco esempi concreti"));
        assertTrue(answer.getString("messaggioBreve").contains("Preferisco esempi concreti"));
    }

    @Test public void currentQuestionWinsOverInvestmentWordsInHistory() throws Exception {
        String prompt = "[DIALOGO_PERSISTENTE]\n"
                + "ULTIMI TURNI:\n"
                + "- ASSISTENTE: Per una azione singola studia il rischio prima di comprare.\n"
                + "DOMANDA ATTUALE: Cosa ricordi di me e quale nome puoi usare?\n"
                + "Rispondi alla domanda attuale.";
        JSONObject answer = new JSONObject(engine.answer(healthy(), prompt,
                "- [USER_CONFIRMED | identity | identita] Puoi chiamarmi Aurora"));

        assertTrue(answer.getString("messaggioBreve").contains("Aurora"));
        assertFalse(answer.getString("messaggioBreve").contains("Non scelgo"));
    }

    @Test public void genericModelAnswerCannotReplaceConfirmedMemory() throws Exception {
        String baseline = engine.answer(healthy(),
                "Quale nome puoi usare per me e come preferisco lavorare?",
                "- [USER_CONFIRMED | identity | identita] Puoi chiamarmi Aurora\n"
                        + "- [USER_CONFIRMED | preference | metodo] Preferisco compiti chiari");
        String generated = "{\"messaggioBreve\":\"Proteggi il budget e controlla il fondo emergenza\","
                + "\"azioni\":[\"Rivedi le spese\"],\"motivo\":\"Prudenza\"}";

        CoreRuleEngine.EnforcementResult result = engine.enforcePolicyDetailed(
                baseline, generated, "Quale nome puoi usare per me e come preferisco lavorare?");

        assertTrue(result.usedFallback);
        assertEquals("memory_ungrounded", result.reason);
        assertTrue(new JSONObject(result.answerJson).getString("messaggioBreve").contains("Aurora"));
    }

    @Test public void highStressTriggersProtection() throws Exception {
        JSONObject answer = ask("{\"redditoNetto\":2500,\"saldoMensile\":500,\"debitiPct\":2,\"fondoEmergenzaMesi\":4,\"stressUtente\":9}", "Posso decidere ora?");
        assertEquals("protezione", answer.getString("stato"));
    }

    @Test public void invalidContextFallsBackSafely() throws Exception {
        JSONObject answer = ask("not-json", "Analizza");
        assertEquals("dati_incompleti", answer.getString("stato"));
        assertFalse(answer.has("puoInvestire"));
    }

    @Test public void genericAppContextDoesNotReceiveFinancialFallback() throws Exception {
        JSONObject answer = ask("{\"schema\":\"evoagenda.screen.v1\",\"screen\":\"settimana\",\"items\":[1,2]}",
                "Quale contesto stai usando?");
        assertEquals("general", answer.getString("domain"));
        assertTrue(answer.getString("messaggioBreve").contains("evoagenda.screen.v1"));
        assertFalse(answer.has("puoInvestire"));
        assertFalse(answer.getString("messaggioBreve").contains("fondo emergenza"));
    }

    @Test public void typedGenericEnvelopeRemainsOutsideFinance() throws Exception {
        JSONObject answer = ask("{\"schema\":\"evoagenda.dialogue.v1\",\"screen\":\"assistant\","
                        + "\"payload\":{\"schema\":\"evoagenda.screen.v1\","
                        + "\"screen\":\"settimana\",\"items\":[1,2]}}",
                "Quale contesto stai usando?");

        assertEquals("general", answer.getString("domain"));
        assertTrue(answer.getString("messaggioBreve").contains("evoagenda.screen.v1"));
        assertFalse(answer.has("puoInvestire"));
    }

    @Test public void modelCannotOverridePolicyFields() throws Exception {
        String baseline = engine.answer("{\"saldoMensile\":-100}", "Analizza", "");
        String generated = "{\"stato\":\"ottimo\",\"messaggioBreve\":\"Va tutto bene\",\"azioni\":[\"Respira\"],\"rischio\":\"basso\",\"puoInvestire\":true,\"effettoAvatar\":\"forte\"}";
        JSONObject merged = new JSONObject(engine.enforcePolicy(baseline, generated));
        assertEquals("protezione", merged.getString("stato"));
        assertFalse(merged.getBoolean("puoInvestire"));
    }

    @Test public void unsafeGeneratedActionIsRemoved() throws Exception {
        String baseline = engine.answer(healthy(), "Analizza", "");
        String generated = "{\"messaggioBreve\":\"Piano\",\"azioni\":[\"Investi tutto oggi\",\"Rivedi il budget\"],\"motivo\":\"test\"}";
        JSONObject merged = new JSONObject(engine.enforcePolicy(baseline, generated));
        assertEquals(1, merged.getJSONArray("azioni").length());
        assertEquals("Rivedi il budget", merged.getJSONArray("azioni").getString(0));
    }

    @Test public void investmentDriftFallsBackForBudgetQuestion() throws Exception {
        String baseline = engine.answer(healthy(),
                "Analizza il mio margine e dammi tre azioni prudenti", "");
        String generated = "{\"messaggioBreve\":\"Una singola azione concentra il rischio specifico dell'impresa e del settore\",\"azioni\":[\"Studia la volatilita\"],\"motivo\":\"Rischio azionario\"}";

        String merged = engine.enforcePolicy(baseline, generated,
                "Analizza il mio margine e dammi tre azioni prudenti");

        assertEquals(new JSONObject(baseline).getString("messaggioBreve"),
                new JSONObject(merged).getString("messaggioBreve"));
    }

    @Test public void minimalModelJsonIsAcceptedAndReceivesPolicyFields() throws Exception {
        String baseline = engine.answer(healthy(), "Come miglioro il budget?", "");
        String generated = "{\"messaggioBreve\":\"Consolida il margine disponibile.\","
                + "\"azioni\":[\"Rivedi le programmate\"],\"motivo\":\"Mantieni flessibilita\"}";

        CoreRuleEngine.EnforcementResult result =
                engine.enforcePolicyDetailed(baseline, generated, "Come miglioro il budget?");
        JSONObject answer = new JSONObject(result.answerJson);

        assertFalse(result.usedFallback);
        assertEquals("model_accepted", result.reason);
        assertEquals("finance", answer.getString("domain"));
        assertTrue(answer.has("puoInvestire"));
        assertEquals("EvoCore Gemma locale", answer.getString("motore"));
    }

    private JSONObject ask(String context, String question) throws Exception {
        return new JSONObject(engine.answer(context, question, ""));
    }

    private static String healthy() {
        return "{\"redditoNetto\":2500,\"saldoMensile\":500,\"debitiPct\":4,\"fondoEmergenzaMesi\":4}";
    }
}
