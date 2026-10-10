package com.elia.evoconti;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class EvoCoreGatewayTest {
    @Test
    public void extractsAnIncompleteMessageWhileJsonIsStreaming() {
        assertEquals("Proteggi il margine",
                EvoCoreGateway.messagePreview(
                        "{\"messaggioBreve\":\"Proteggi il margine"));
    }

    @Test
    public void decodesEscapesAndStopsAtTheClosingQuote() {
        assertEquals("Prima riga seconda",
                EvoCoreGateway.messagePreview(
                        "{\"messaggioBreve\":\"Prima riga\\nseconda\","
                                + "\"azioni\":[\"Controlla\"]}"));
    }

    @Test
    public void staysEmptyUntilTheMessageFieldArrives() {
        assertEquals("", EvoCoreGateway.messagePreview("{\"mess"));
    }
}
