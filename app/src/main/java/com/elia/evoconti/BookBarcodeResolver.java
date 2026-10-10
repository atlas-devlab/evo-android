package com.elia.evoconti;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class BookBarcodeResolver {
    interface Callback {
        void onDone(BookInfo info, String error);
    }

    static final class BookInfo {
        String isbn;
        String title;
        String author;
        String publisher;

        String displayTitle() {
            return author == null || author.isEmpty() ? title : title + " - " + author;
        }
    }

    boolean looksLikeIsbn(String raw) {
        String isbn = normalize(raw);
        return isbn.matches("[0-9]{13}") && (isbn.startsWith("978") || isbn.startsWith("979")) ||
                isbn.matches("[0-9]{9}[0-9X]");
    }

    void resolveAsync(Context context, String raw, Callback callback) {
        String isbn = normalize(raw);
        if (!looksLikeIsbn(isbn)) {
            callback.onDone(null, "Il codice non sembra un ISBN.");
            return;
        }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String key = "ISBN:" + isbn;
                URL url = new URL("https://openlibrary.org/api/books?bibkeys=" + key + "&jscmd=data&format=json");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("User-Agent", "EvoConti/0.8 ISBN notebook");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && body.length() < 200_000) body.append(line);
                }
                JSONObject root = new JSONObject(body.toString());
                JSONObject book = root.optJSONObject(key);
                if (book == null) {
                    callback.onDone(null, "Libro non trovato nel catalogo Open Library.");
                    return;
                }
                BookInfo info = new BookInfo();
                info.isbn = isbn;
                info.title = book.optString("title", "Libro ISBN " + isbn).trim();
                info.author = firstName(book.optJSONArray("authors"));
                info.publisher = firstName(book.optJSONArray("publishers"));
                callback.onDone(info, "");
            } catch (Exception error) {
                String message = error.getMessage();
                callback.onDone(null, "Ricerca libro non riuscita" + (message == null ? "." : ": " + message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "EvoBookLookup").start();
    }

    private String firstName(JSONArray values) {
        if (values == null || values.length() == 0) return "";
        JSONObject first = values.optJSONObject(0);
        return first == null ? "" : first.optString("name", "").trim();
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^0-9X]", "");
    }
}
