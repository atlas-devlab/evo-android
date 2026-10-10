package com.elia.evoconti;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProductTextParserTest {
    @Test
    public void frontLabelWinsOverIngredientsAndWeight() {
        String text = "FRESCOBUONO\nYOGURT GRECO\n500 g\nIngredienti: latte e fermenti\nValori nutrizionali";

        String name = new ProductTextParser().bestName(text);

        assertEquals("Frescobuono Yogurt Greco", name);
    }

    @Test
    public void blankPackagingTextReturnsNoInventedName() {
        ProductTextParser parser = new ProductTextParser();

        assertEquals("", parser.bestName(null));
        assertEquals("", parser.bestName("  \n  "));
    }

    @Test
    public void barcodeAndWeightAreIgnoredAroundBrandAndProduct() {
        String text = "8001234567890\n500 g\nMULINO BIANCO\nPAVESINI\nIngredienti: farina e zucchero";

        String name = new ProductTextParser().bestName(text);

        assertEquals("Mulino Bianco Pavesini", name);
    }

    @Test
    public void noiseOnlyPackagingReturnsNoName() {
        String text = "8001234567890\n500 g\nIngredienti: acqua\nValori nutrizionali\nwww.example.it";

        assertEquals("", new ProductTextParser().bestName(text));
    }
}
