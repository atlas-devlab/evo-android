package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReceiptParserTest {
    @Test
    public void extractsMerchantAndExplicitTotal() {
        String ocr = "SUPERMERCATO COOP\nDocumento commerciale\nPasta 1,20\nLatte 1,80\nIVA 0,30\nTOTALE EUR 3,00";

        ReceiptParser.Result result = new ReceiptParser().parse(ocr);

        assertEquals("Supermercato Coop", result.merchant);
        assertTrue(result.hasTotal);
        assertEquals(3.0, result.total, .001);
    }

    @Test
    public void ignoresVatWhenChoosingTotal() {
        String ocr = "LIBRERIA CENTRALE\n01/07/2026\nIVA 4,00\nDA PAGARE 24,90";

        ReceiptParser.Result result = new ReceiptParser().parse(ocr);

        assertEquals(24.90, result.total, .001);
    }
}
