package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CustodianSafetyGuardTest {
    @Test
    public void unsafeGeneratedAdviceFallsBackCompletelyToRules() {
        AiAnswer rules = baseline();
        AiAnswer generated = new AiAnswer();
        generated.messaggioBreve = "Compra questo ETF: e senza rischio.";
        generated.puoInvestire = true;

        AiAnswer merged = CustodianSafetyGuard.merge(rules, generated);

        assertEquals(rules.messaggioBreve, merged.messaggioBreve);
        assertFalse(merged.puoInvestire);
    }

    @Test
    public void contradictoryInvestmentPermissionFallsBackCompletelyToRules() {
        AiAnswer rules = baseline();
        AiAnswer generated = new AiAnswer();
        generated.messaggioBreve = "Studia i costi e proteggi il fondo emergenza.";
        generated.stato = "via libera";
        generated.rischio = "basso";
        generated.puoInvestire = true;

        AiAnswer merged = CustodianSafetyGuard.merge(rules, generated);

        assertEquals("prudenza", merged.stato);
        assertEquals("alto", merged.rischio);
        assertFalse(merged.puoInvestire);
        assertEquals(rules.messaggioBreve, merged.messaggioBreve);
        assertEquals("regole locali", merged.motore);
    }

    @Test
    public void differentDisplayLabelsDoNotHideSafeGeneratedText() {
        AiAnswer rules = baseline();
        AiAnswer generated = new AiAnswer();
        generated.messaggioBreve = "Completa prima entrate e uscite, poi possiamo valutare il margine reale.";
        generated.stato = "protezione";
        generated.rischio = "medio";
        generated.puoInvestire = false;
        generated.effettoAvatar = "affaticato";

        AiAnswer merged = CustodianSafetyGuard.merge(rules, generated);

        assertEquals(generated.messaggioBreve, merged.messaggioBreve);
        assertEquals("prudenza", merged.stato);
        assertEquals("alto", merged.rischio);
        assertFalse(merged.puoInvestire);
        assertEquals("llama.cpp locale + barriera prudente", merged.motore);
    }

    @Test
    public void alignedGeneratedPolicyCanSupplyNaturalLanguage() {
        AiAnswer rules = baseline();
        AiAnswer generated = rules.copy();
        generated.messaggioBreve = "Proteggi prima il margine e riduci gradualmente i debiti.";
        generated.azioni.add("Rivedi le rate piu costose");

        AiAnswer merged = CustodianSafetyGuard.merge(rules, generated);

        assertEquals(generated.messaggioBreve, merged.messaggioBreve);
        assertEquals("llama.cpp locale + barriera prudente", merged.motore);
    }

    @Test
    public void copiedPromptTemplateIsRejectedAsInvalidModelOutput() {
        String copied = "{\"stato\":\"prudenza\",\"messaggioBreve\":\"risposta\"," +
                "\"azioni\":[\"azione\"],\"rischio\":\"medio\",\"puoInvestire\":false," +
                "\"motivo\":\"motivo\",\"effettoAvatar\":\"stabile\"}";

        assertNull(AiAnswer.fromModelOutput(copied));
    }

    private AiAnswer baseline() {
        AiAnswer answer = new AiAnswer();
        answer.stato = "prudenza";
        answer.rischio = "alto";
        answer.puoInvestire = false;
        answer.messaggioBreve = "Prima riduci i debiti.";
        answer.motivo = "Debiti sopra soglia";
        return answer;
    }
}
