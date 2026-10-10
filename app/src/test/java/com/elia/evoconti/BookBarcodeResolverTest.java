package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BookBarcodeResolverTest {
    @Test
    public void recognizesIsbn10AndIsbn13Only() {
        BookBarcodeResolver resolver = new BookBarcodeResolver();

        assertTrue(resolver.looksLikeIsbn("978-88-04-12345-6"));
        assertTrue(resolver.looksLikeIsbn("0-306-40615-2"));
        assertFalse(resolver.looksLikeIsbn("8001234567890"));
        assertFalse(resolver.looksLikeIsbn("12345"));
    }
}
