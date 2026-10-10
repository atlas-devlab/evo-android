package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProductCatalogConnectorTest {
    @Test
    public void parsesOpenFoodFactsProduct() throws Exception {
        String json = "{\"status\":\"success\",\"product\":{" +
                "\"code\":\"3017620422003\",\"product_name_it\":\"Crema nocciole\"," +
                "\"brands\":\"Marca\",\"quantity\":\"400 g\",\"product_type\":\"food\"}}";

        EvoDb.Product product = new ProductCatalogConnector().parseProduct(json, "3017620422003");

        assertNotNull(product);
        assertEquals("Crema nocciole", product.name);
        assertEquals("Marca", product.brand);
        assertEquals("Alimentare", product.category);
    }

    @Test
    public void priceFromRequestedItalianStoreWins() throws Exception {
        String json = "{\"items\":[" +
                "{\"price\":2.10,\"currency\":\"EUR\",\"date\":\"2026-07-12\"," +
                "\"location\":{\"osm_brand\":\"Market Francia\",\"osm_address_country_code\":\"FR\"}}," +
                "{\"price\":2.49,\"currency\":\"EUR\",\"date\":\"2026-07-10\"," +
                "\"location\":{\"osm_brand\":\"Coop\",\"osm_address_country_code\":\"IT\"}}]}";
        ProductCatalogConnector.LookupResult result = new ProductCatalogConnector.LookupResult();

        new ProductCatalogConnector().applyBestPrice(json, "Coop", result);

        assertEquals(2.49, result.suggestedPrice, .001);
        assertTrue(result.priceSource.contains("Coop"));
        assertTrue(result.priceConfidence >= .60);
    }

    @Test
    public void missingOrNamelessCatalogProductIsNotInvented() throws Exception {
        ProductCatalogConnector connector = new ProductCatalogConnector();

        assertNull(connector.parseProduct("{\"status\":\"failure\"}", "8000000000000"));
        assertNull(connector.parseProduct("{\"status\":\"success\",\"product\":{\"code\":\"8000000000000\"}}",
                "8000000000000"));
    }

    @Test
    public void englishFallbackAndBeautyCategoryAreMappedForTheApp() throws Exception {
        String json = "{\"status\":\"success\",\"product\":{" +
                "\"code\":\"8000000000004\",\"product_name\":\"Gentle soap\"," +
                "\"brands\":\"Care\",\"product_type\":\"beauty\"}}";

        EvoDb.Product product = new ProductCatalogConnector().parseProduct(json, "8000000000004");

        assertNotNull(product);
        assertEquals("Gentle soap", product.name);
        assertEquals("Farmacia", product.category);
    }

    @Test
    public void newestMatchingEuroPriceWinsAndForeignCurrencyIsIgnored() throws Exception {
        String json = "{\"items\":[" +
                "{\"price\":2.20,\"currency\":\"EUR\",\"date\":\"2026-06-01\"," +
                "\"location\":{\"osm_brand\":\"Conad\",\"osm_address_country_code\":\"IT\"}}," +
                "{\"price\":2.70,\"currency\":\"EUR\",\"date\":\"2026-07-15\"," +
                "\"location\":{\"osm_brand\":\"Conad\",\"osm_address_country_code\":\"IT\"}}," +
                "{\"price\":1.00,\"currency\":\"USD\",\"date\":\"2026-07-16\"," +
                "\"location\":{\"osm_brand\":\"Conad\",\"osm_address_country_code\":\"IT\"}}]}";
        ProductCatalogConnector.LookupResult result = new ProductCatalogConnector.LookupResult();

        new ProductCatalogConnector().applyBestPrice(json, "Conad", result);

        assertEquals(2.70, result.suggestedPrice, .001);
        assertTrue(result.priceSource.contains("2026-07-15"));
    }
}
