package com.elia.evoconti;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ProductCatalogConnector {
    private static final String PREFS = "product_catalog";
    private static final String KEY_CONSENT = "internet_consent";
    private static final String PRODUCT_API = "https://world.openfoodfacts.org/api/v3/product/";
    private static final String PRICE_API = "https://prices.openfoodfacts.org/api/v1/prices";
    private static final long PRODUCT_REQUEST_GAP_MS = 4_050;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static long lastProductRequestAt;

    interface Callback {
        void onDone(LookupResult result);
    }

    static final class LookupResult {
        EvoDb.Product product;
        double suggestedPrice;
        String priceSource = "";
        String priceStore = "";
        long priceObservedAt;
        double priceConfidence;
        String error = "";

        boolean hasProduct() {
            return product != null && product.name != null && !product.name.trim().isEmpty();
        }

        boolean hasSuggestedPrice() {
            return suggestedPrice > 0;
        }
    }

    boolean hasConsent(Context context) {
        return prefs(context).getBoolean(KEY_CONSENT, false);
    }

    void setConsent(Context context, boolean consent) {
        prefs(context).edit().putBoolean(KEY_CONSENT, consent).apply();
    }

    void resolveAsync(Context context, EvoDb db, String rawBarcode, String currentStore, Callback callback) {
        String barcode = normalizeBarcode(rawBarcode);
        if (barcode.isEmpty()) {
            LookupResult result = new LookupResult();
            result.error = "Barcode non valido";
            callback.onDone(result);
            return;
        }
        if (!hasConsent(context)) {
            LookupResult result = new LookupResult();
            result.error = "Ricerca online non autorizzata";
            callback.onDone(result);
            return;
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            LookupResult result = new LookupResult();
            try {
                throttleProductRequests();
                String fields = "code,product_name,product_name_it,brands,quantity,categories,image_front_url,product_type";
                String productJson = fetch(PRODUCT_API + URLEncoder.encode(barcode, StandardCharsets.UTF_8.name()) +
                        "?product_type=all&lc=it&cc=it&fields=" + fields);
                result.product = parseProduct(productJson, barcode);
                if (result.product != null) db.upsertProduct(result.product);
            } catch (Exception error) {
                result.error = friendlyError(error);
            }
            try {
                String priceJson = fetch(PRICE_API + "?product_code=" +
                        URLEncoder.encode(barcode, StandardCharsets.UTF_8.name()) + "&currency=EUR");
                applyBestPrice(priceJson, currentStore, result);
                if (result.product == null) result.product = productFromPricePayload(priceJson, barcode);
                if (result.product != null) db.upsertProduct(result.product);
            } catch (Exception ignored) {
                // Product metadata remains useful even when the optional price source is unavailable.
            }
            new Handler(Looper.getMainLooper()).post(() -> callback.onDone(result));
        });
    }

    EvoDb.Product parseProduct(String rawJson, String fallbackBarcode) throws Exception {
        JSONObject root = new JSONObject(rawJson);
        if (!"success".equalsIgnoreCase(root.optString("status"))) return null;
        JSONObject productJson = root.optJSONObject("product");
        if (productJson == null) return null;
        EvoDb.Product product = new EvoDb.Product();
        product.barcode = value(productJson, "code", fallbackBarcode);
        product.name = value(productJson, "product_name_it", value(productJson, "product_name", ""));
        product.brand = value(productJson, "brands", "");
        product.quantityLabel = value(productJson, "quantity", "");
        product.imageUrl = value(productJson, "image_front_url", "");
        product.category = appCategory(value(productJson, "product_type", "food"),
                value(productJson, "categories", ""));
        product.source = "Open Food Facts";
        product.sourceUpdatedAt = System.currentTimeMillis();
        product.lastSeenAt = System.currentTimeMillis();
        return product.name.isEmpty() ? null : product;
    }

    void applyBestPrice(String rawJson, String requestedStore, LookupResult result) throws Exception {
        JSONArray items = new JSONObject(rawJson).optJSONArray("items");
        if (items == null || items.length() == 0) return;
        String wantedStore = normalizeText(requestedStore);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        String bestDate = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || item.optDouble("price", 0) <= 0 ||
                    !"EUR".equalsIgnoreCase(item.optString("currency", "EUR"))) continue;
            JSONObject location = item.optJSONObject("location");
            String store = location == null ? "" : firstNotBlank(
                    location.optString("osm_brand"), location.optString("osm_name"));
            String country = location == null ? "" : location.optString("osm_address_country_code");
            String candidateStore = normalizeText(store);
            int score = 0;
            if ("IT".equalsIgnoreCase(country)) score += 50;
            if (!wantedStore.isEmpty() && !candidateStore.isEmpty() &&
                    (candidateStore.contains(wantedStore) || wantedStore.contains(candidateStore))) score += 120;
            if (item.optBoolean("price_is_discounted", false)) score -= 2;
            String date = item.optString("date", "");
            if (score > bestScore || (score == bestScore && date.compareTo(bestDate) > 0)) {
                best = item;
                bestScore = score;
                bestDate = date;
            }
        }
        if (best == null) return;
        JSONObject location = best.optJSONObject("location");
        String store = location == null ? "" : firstNotBlank(
                location.optString("osm_brand"), location.optString("osm_name"));
        String country = location == null ? "" : location.optString("osm_address_country_code");
        String normalizedStore = normalizeText(store);
        boolean storeMatch = !wantedStore.isEmpty() && !normalizedStore.isEmpty() &&
                (normalizedStore.contains(wantedStore) || wantedStore.contains(normalizedStore));
        result.suggestedPrice = best.optDouble("price", 0);
        result.priceStore = store;
        result.priceSource = "Open Prices" + (store.isEmpty() ? "" : " - " + store) +
                (bestDate.isEmpty() ? "" : " (" + bestDate + ")");
        result.priceObservedAt = parseDate(bestDate);
        result.priceConfidence = storeMatch ? .65 : ("IT".equalsIgnoreCase(country) ? .40 : .22);
    }

    private EvoDb.Product productFromPricePayload(String rawJson, String barcode) {
        try {
            JSONArray items = new JSONObject(rawJson).optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            JSONObject item = items.optJSONObject(0);
            JSONObject raw = item == null ? null : item.optJSONObject("product");
            if (raw == null) return null;
            EvoDb.Product product = new EvoDb.Product();
            product.barcode = barcode;
            product.name = firstNotBlank(raw.optString("product_name"), item.optString("product_name"));
            product.brand = raw.optString("brands");
            double quantity = raw.optDouble("product_quantity", 0);
            product.quantityLabel = quantity <= 0 ? raw.optString("quantity") :
                    trimNumber(quantity) + " " + raw.optString("product_quantity_unit");
            product.imageUrl = raw.optString("image_url");
            product.category = "Alimentare";
            product.source = "Open Prices / Open Food Facts";
            product.sourceUpdatedAt = System.currentTimeMillis();
            product.lastSeenAt = System.currentTimeMillis();
            return product.name.isEmpty() ? null : product;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String fetch(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "EvoConti/0.8.1 (Android personal budget notebook)");
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder out = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
        return out.toString();
    }

    private static synchronized void throttleProductRequests() throws InterruptedException {
        long wait = PRODUCT_REQUEST_GAP_MS - (System.currentTimeMillis() - lastProductRequestAt);
        if (wait > 0) Thread.sleep(wait);
        lastProductRequestAt = System.currentTimeMillis();
    }

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String normalizeBarcode(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    private String normalizeText(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ITALY).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String appCategory(String productType, String categories) {
        String value = (productType + " " + categories).toLowerCase(Locale.ITALY);
        if (value.contains("beauty") || value.contains("cosmetic") || value.contains("igiene")) return "Farmacia";
        if (value.contains("book") || value.contains("libro")) return "Libri e cultura";
        if (value.contains("food") || value.contains("aliment") || value.contains("beverage")) return "Alimentare";
        return "Altro";
    }

    private String value(JSONObject object, String key, String fallback) {
        String value = object.optString(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private String firstNotBlank(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first.trim() : (second == null ? "" : second.trim());
    }

    private long parseDate(String raw) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw);
            return date == null ? 0 : date.getTime();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message != null && message.contains("404")) return "Prodotto non presente nel catalogo pubblico";
        if (message != null && message.contains("429")) return "Catalogo occupato: il prodotto resta in coda locale";
        return "Catalogo online non raggiungibile";
    }
}
