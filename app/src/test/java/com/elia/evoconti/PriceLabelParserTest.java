package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PriceLabelParserTest {
    @Test
    public void shelfPriceWinsOverUnitPrice() {
        String ocr = "OFFERTA\n2,49 EUR\nPrezzo al kg 7,80 EUR\nValida 12.07.2026";

        List<PriceLabelParser.Candidate> values = new PriceLabelParser().candidates(ocr);

        assertFalse(values.isEmpty());
        assertEquals(2.49, values.get(0).value, .001);
    }

    @Test
    public void acceptsCentsSeparatedBySpaceOnlyWithCurrency() {
        List<PriceLabelParser.Candidate> values = new PriceLabelParser().candidates("EUR 3 59");

        assertEquals(3.59, values.get(0).value, .001);
    }

    @Test
    public void deduplicatesTheSamePrice() {
        List<PriceLabelParser.Candidate> values = new PriceLabelParser().candidates("1,99 EUR\nPREZZO 1.99 EUR");

        assertEquals(1, values.size());
        assertEquals(1.99, values.get(0).value, .001);
    }

    @Test
    public void dateAloneDoesNotBecomeAProductPrice() {
        List<PriceLabelParser.Candidate> values = new PriceLabelParser().candidates("SCADENZA 12.07.2026");

        assertTrue(values.isEmpty());
    }

    @Test
    public void discountPercentageAloneDoesNotBecomeAProductPrice() {
        List<PriceLabelParser.Candidate> values = new PriceLabelParser().candidates("SCONTO 20,00%");

        assertTrue(values.isEmpty());
    }

    @Test
    public void acceptsExplicitPlainShelfPriceWithoutCurrency() {
        List<PriceLabelParser.Candidate> values = new PriceLabelParser().candidates("PREZZO 4.25");

        assertEquals(4.25, values.get(0).value, .001);
    }
}
