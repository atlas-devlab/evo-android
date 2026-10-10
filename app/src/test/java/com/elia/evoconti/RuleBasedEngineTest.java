package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RuleBasedEngineTest {
    @Test
    public void refusesSpecificTradingInstructions() {
        AiContext context = healthyContext();

        AiAnswer answer = new RuleBasedEngine().ask(context, "Quale ETF devo comprare?");

        assertFalse(answer.puoInvestire);
        assertTrue(answer.stato.contains("blocco"));
        assertTrue(answer.messaggioBreve.contains("Non scelgo"));
    }

    @Test
    public void healthyBudgetAllowsOnlyPlanningLanguage() {
        AiAnswer answer = new RuleBasedEngine().ask(healthyContext(), "Come va il mio piano investimenti?");

        assertTrue(answer.puoInvestire);
        assertTrue(answer.messaggioBreve.contains("piccola quota"));
        assertFalse(answer.messaggioBreve.toLowerCase().contains("compra"));
    }

    @Test
    public void debtAndLowEmergencyAlwaysStopInvestmentIncrease() {
        AiContext context = healthyContext();
        context.debitiPct = 17;
        context.debitoResiduo = 8_000;
        context.fondoEmergenzaMesi = .4;

        AiAnswer answer = new RuleBasedEngine().ask(context, "Analizza il mese");

        assertFalse(answer.puoInvestire);
        assertTrue(answer.stato.contains("prudenza") || answer.stato.contains("debiti"));
    }

    @Test
    public void genericQuestionNeverReturnsAnEmptyAnswer() {
        AiContext context = healthyContext();
        context.saluteBudget = 68;

        AiAnswer answer = new RuleBasedEngine().ask(context, "Come sto?");

        assertFalse(answer.messaggioBreve.isEmpty());
        assertFalse(answer.motivo.isEmpty());
    }

    @Test
    public void ninetyDayPlanIsNotMistakenForNinetyEuroExpense() {
        AiAnswer answer = new RuleBasedEngine().ask(healthyContext(),
                "Costruiamo un piano per i prossimi 90 giorni collegando spese e obiettivi");

        assertTrue(answer.messaggioBreve.contains("Piano morbido"));
        assertFalse(answer.messaggioBreve.contains("spesa di 90"));
        assertTrue(answer.azioni.get(0).contains("1-30"));
    }

    @Test
    public void concretePurchaseGetsThirtyAndNinetyDayImpact() {
        AiAnswer answer = new RuleBasedEngine().ask(healthyContext(),
                "Posso comprare un telefono da 120 EUR?");

        assertTrue(answer.messaggioBreve.contains("120,00 EUR"));
        assertTrue(answer.messaggioBreve.contains("90 giorni"));
        assertTrue(answer.messaggioBreve.contains("380,00 EUR"));
    }

    @Test
    public void euroPrefixIsRecognizedAsPurchaseAmount() {
        AiAnswer answer = new RuleBasedEngine().ask(healthyContext(),
                "Posso permettermi una spesa di \u20ac 75?");

        assertTrue(answer.messaggioBreve.contains("75,00 EUR"));
        assertTrue(answer.messaggioBreve.contains("425,00 EUR"));
    }

    @Test
    public void multiDomainQuestionProducesJoinedReading() {
        AiContext context = healthyContext();
        context.debitiPct = 14;
        context.fondoEmergenzaMesi = .8;

        AiAnswer answer = new RuleBasedEngine().ask(context,
                "Collega le mie spese, le rate, lo stress e gli obiettivi");

        assertTrue(answer.messaggioBreve.contains("Il quadro unisce"));
        assertTrue(answer.motivo.contains("Lettura congiunta"));
    }

    @Test
    public void companionFallbackUsesItsOwnVoiceAndPromptMemory() {
        String prompt = "[PERSONA:COMPAGNO]\nMEMORIE PERSISTENTI RILEVANTI:\n" +
                "- [preferenza/Compagno] Preferisco risparmiare per un viaggio\n" +
                "DOMANDA ATTUALE: Cosa ricordi di me?";

        AiAnswer answer = new RuleBasedEngine().ask(healthyContext(), prompt);

        assertTrue(answer.messaggioBreve.startsWith("Mi ricordo:"));
        assertTrue(answer.messaggioBreve.contains("viaggio"));
        assertTrue(answer.motore.contains("compagno"));
    }

    @Test
    public void verifiedInternetSourceIsExposedWithoutBecomingAnOrder() {
        AiContext context = healthyContext();
        context.datiInternet.add("verifica_web | Diversificazione | CONSOB | fonte istituzionale");

        AiAnswer answer = new RuleBasedEngine().ask(context,
                "Verifica su Internet se e vero che diversificare elimina il rischio");

        assertTrue(answer.messaggioBreve.contains("fonte Internet"));
        assertFalse(answer.messaggioBreve.toLowerCase().contains("compra"));
    }

    @Test
    public void operationalActionsAreNotMistakenForStocks() {
        AiAnswer answer = new RuleBasedEngine().ask(healthyContext(),
                "Analizza il mio margine e dammi tre azioni prudenti");

        assertTrue(answer.messaggioBreve.toLowerCase().contains("margine"));
        assertFalse(answer.messaggioBreve.toLowerCase().contains("impresa"));
        assertFalse(answer.messaggioBreve.toLowerCase().contains("singola azione"));
    }

    @Test
    public void reviewableExpensesAreCandidatesNotAutomaticVerdicts() {
        AiContext context = healthyContext();
        context.desideriPct = 34;
        context.speseImpulsive = 4;
        context.speseDaRivedere.add(
                "ricorrenza da verificare | Streaming | 18.00 EUR/mese | Svago");
        context.speseDaRivedere.add(
                "categoria flessibile da verificare | Svago | 210.00 EUR nel mese");

        AiAnswer answer = new RuleBasedEngine().ask(context,
                "Quali spese inutili o evitabili posso rivedere?");

        assertTrue(answer.messaggioBreve.contains("non automaticamente inutili"));
        assertTrue(answer.messaggioBreve.contains("Margine"));
        assertTrue(answer.azioni.get(0).contains("Streaming"));
        assertTrue(answer.motivo.contains("quadro completo"));
    }

    private AiContext healthyContext() {
        AiContext context = new AiContext();
        context.saluteBudget = 85;
        context.statoSalute = "Sano";
        context.completezzaDati = 80;
        context.redditoNetto = 2_300;
        context.speseFisse = 900;
        context.usciteEssenzialiPct = 48;
        context.desideriPct = 20;
        context.futuroPct = 22;
        context.debitiPct = 5;
        context.saldoMensile = 500;
        context.fondoEmergenzaMesi = 3;
        context.movimentiRecenti.add("expense | Casa | 100 EUR");
        return context;
    }
}
