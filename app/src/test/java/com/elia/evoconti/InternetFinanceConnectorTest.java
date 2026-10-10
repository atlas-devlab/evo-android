package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InternetFinanceConnectorTest {
    @Test
    public void researchQueryKeepsOnlyGeneralFinanceTerms() {
        String query = InternetFinanceConnector.sanitizedFinanceQuery(
                "Sono Mario Rossi, mario.rossi@example.com, IBAN IT60X0542811101000000123456: " +
                        "ho 8.450 euro e vorrei verificare rischio ETF e debiti");

        assertTrue(query.contains("etf"));
        assertTrue(query.contains("debiti"));
        assertTrue(query.contains("rischio"));
        assertFalse(query.contains("mario"));
        assertFalse(query.contains("8450"));
        assertFalse(query.contains("it60"));
    }

    @Test
    public void personalQuestionWithoutGeneralFinanceTopicStaysOffline() {
        String query = InternetFinanceConnector.sanitizedFinanceQuery(
                "Dove sono e cosa ho fotografato ieri alle 18:30?");

        assertTrue(query.isEmpty());
    }

    @Test
    public void officialResearchCanCoverDebtRatesAndInflation() {
        String query = InternetFinanceConnector.sanitizedFinanceQuery(
                "E vero che inflazione, tassi BCE e TAEG cambiano il costo di un prestito?");

        assertTrue(query.contains("inflazione"));
        assertTrue(query.contains("bce"));
        assertTrue(query.contains("taeg"));
        assertTrue(query.contains("prestito"));
    }
}
