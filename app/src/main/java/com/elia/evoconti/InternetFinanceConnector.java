package com.elia.evoconti;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InternetFinanceConnector {
    interface Callback {
        void onDone(boolean ok, String message);
    }

    private static final String PREF = "internet_finance";
    private static final String KEY_CONSENT = "consent";
    private static final String KEY_DIALOGUE_RESEARCH = "dialogue_research";
    private static final String ECB_RATES = "https://data-api.ecb.europa.eu/service/data/FM/B.U2.EUR.4F.KR.DFR+MRR_FR+MLFR.LEV?lastNObservations=1&format=csvdata";
    private static final String ECB_FX = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private static final String PRIVATE_SEARCH = "https://html.duckduckgo.com/html/?q=";
    private static final Pattern FX_DATE = Pattern.compile("time=['\"]([^'\"]+)['\"]");
    private static final Pattern FX_RATE = Pattern.compile("currency=['\"](USD|GBP|CHF|JPY)['\"]\\s+rate=['\"]([^'\"]+)['\"]");
    private static final Pattern SEARCH_RESULT = Pattern.compile(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SEARCH_SNIPPET = Pattern.compile(
            "<(?:a|div)[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_DESCRIPTION = Pattern.compile(
            "<meta[^>]*(?:name|property)=['\"](?:description|og:description)['\"][^>]*content=['\"]([^'\"]+)['\"][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String[] FINANCE_TERMS = {
            "fondo emergenza", "conto deposito", "interessi passivi", "interessi attivi",
            "educazione finanziaria", "pensione", "inflazione", "deflazione", "tasso", "tassi",
            "bce", "mutuo", "prestito", "prestiti", "taeg", "tan", "debito", "debiti",
            "etf", "azione", "azioni", "obbligazione", "obbligazioni", "crypto", "criptovalute",
            "diversificazione", "rischio", "rendimento", "interesse", "interessi", "risparmio",
            "investimento", "investimenti", "budget", "sovraindebitamento", "rate", "rata"
    };
    private static final String[] TRUSTED_DOMAINS = {
            "bancaditalia.it", "ecb.europa.eu", "consob.it", "istat.it", "europa.eu", "esma.europa.eu",
            "covip.it", "agenziaentrate.gov.it", "quellocheconta.gov.it", "consumerfinance.gov", "investor.gov"
    };

    static final class ResearchResult {
        final boolean ok;
        final String query;
        final String summary;
        final String source;
        final String message;

        ResearchResult(boolean ok, String query, String summary, String source, String message) {
            this.ok = ok;
            this.query = query;
            this.summary = summary;
            this.source = source;
            this.message = message;
        }
    }

    boolean hasConsent(Context context) {
        return prefs(context).getBoolean(KEY_CONSENT, false);
    }

    void setConsent(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_CONSENT, value).apply();
    }

    boolean isDialogueResearchEnabled(Context context) {
        return hasConsent(context) && prefs(context).getBoolean(KEY_DIALOGUE_RESEARCH, false);
    }

    void setDialogueResearchEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_DIALOGUE_RESEARCH, value).apply();
    }

    ResearchResult researchAndCache(EvoDb db, String userQuestion) {
        String query = sanitizedFinanceQuery(userQuestion);
        if (query.isEmpty()) {
            return new ResearchResult(false, "", "", "",
                    "La domanda contiene dettagli personali o nessun tema economico generale verificabile.");
        }
        ResearchResult direct = directOfficialResearch(db, query);
        if (direct != null) return direct;
        try {
            String domainFilter = " (site:bancaditalia.it OR site:consob.it OR site:ecb.europa.eu" +
                    " OR site:istat.it OR site:europa.eu)";
            String html = fetch(PRIVATE_SEARCH + URLEncoder.encode(query + domainFilter,
                    StandardCharsets.UTF_8.name()));
            SearchHit hit = trustedSearchHit(html);
            if (hit == null) {
                return new ResearchResult(false, query, "", "",
                        "Nessuna fonte istituzionale pertinente trovata. Il Custode usera solo i dati locali.");
            }
            String summary = hit.snippet;
            try {
                String pageDescription = extractDescription(fetch(hit.url));
                if (!pageDescription.isEmpty()) summary = pageDescription;
            } catch (Exception ignored) {
                // The institutional search snippet remains available when the source blocks previews.
            }
            if (summary.isEmpty()) summary = hit.title;
            summary = trim(summary, 900);
            db.cacheFinanceData("verifica_web", hit.title + " | " + summary,
                    hit.url, "Fonte istituzionale trovata via ricerca privata");
            return new ResearchResult(true, query, summary, hit.url,
                    "Fonte istituzionale aggiunta al contesto locale.");
        } catch (Exception error) {
            return new ResearchResult(false, query, "", "",
                    "Verifica Internet non riuscita: " + safeMessage(error));
        }
    }

    private ResearchResult directOfficialResearch(EvoDb db, String query) {
        if (!containsAny(query, "bce", "tasso", "tassi", "interesse", "interessi")) return null;
        try {
            int saved = parseAndCacheRates(fetch(ECB_RATES), db);
            List<EvoDb.FinanceCacheEntry> rows = db.financeCacheRows(20);
            StringBuilder summary = new StringBuilder("Tassi ufficiali BCE aggiornati: ");
            int included = 0;
            for (EvoDb.FinanceCacheEntry row : rows) {
                if (!row.dataType.startsWith("tasso_bce_")) continue;
                if (included++ > 0) summary.append("; ");
                summary.append(row.value);
                if (included == 3) break;
            }
            if (included == 0) return null;
            String value = trim(summary.toString(), 900);
            db.cacheFinanceData("verifica_web", value, ECB_RATES, "BCE ufficiale");
            return new ResearchResult(true, query, value, ECB_RATES,
                    saved + " tassi ufficiali BCE aggiunti al contesto locale.");
        } catch (Exception ignored) {
            // If the direct official feed is temporarily unavailable, try trusted search.
            return null;
        }
    }

    static String sanitizedFinanceQuery(String raw) {
        if (raw == null) return "";
        String clean = raw.toLowerCase(Locale.ITALY)
                .replaceAll("https?://\\S+|www\\.\\S+", " ")
                .replaceAll("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", " ")
                .replaceAll("\\b[a-z]{2}\\d{2}[a-z0-9]{10,30}\\b", " ")
                .replaceAll("[-+]?\\d+(?:[.,]\\d+)?", " ")
                .replaceAll("[^\\p{L} ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Set<String> terms = new LinkedHashSet<>();
        for (String term : FINANCE_TERMS) {
            if (clean.contains(term)) terms.add(term);
        }
        if (terms.isEmpty()) return "";
        return String.join(" ", terms) + " educazione finanziaria";
    }

    void refreshAsync(Context context, EvoDb db, Callback callback) {
        if (!hasConsent(context)) {
            callback.onDone(false, "Serve consenso per scaricare dati informativi.");
            return;
        }
        new Thread(() -> {
            try {
                int saved = parseAndCacheRates(fetch(ECB_RATES), db);
                saved += parseAndCacheFx(fetch(ECB_FX), db);
                callback.onDone(true, saved + " dati BCE reali aggiornati e salvati con fonte e data.");
            } catch (Exception e) {
                String message = e.getMessage();
                callback.onDone(false, "Aggiornamento non riuscito" +
                        (message == null || message.trim().isEmpty() ? "." : ": " + message));
            }
        }, "InternetFinanceConnector").start();
    }

    int parseAndCacheRates(String csv, EvoDb db) {
        String[] lines = csv == null ? new String[0] : csv.split("\\r?\\n");
        if (lines.length < 2) throw new IllegalArgumentException("Risposta tassi BCE vuota");
        List<String> headers = csvRow(lines[0]);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) index.put(headers.get(i), i);
        int instrumentIndex = required(index, "PROVIDER_FM_ID");
        int dateIndex = required(index, "TIME_PERIOD");
        int valueIndex = required(index, "OBS_VALUE");
        int titleIndex = required(index, "TITLE");
        int count = 0;
        for (int i = 1; i < lines.length; i++) {
            List<String> row = csvRow(lines[i]);
            if (row.size() <= Math.max(Math.max(instrumentIndex, dateIndex), Math.max(valueIndex, titleIndex))) continue;
            String instrument = row.get(instrumentIndex);
            String type;
            if ("DFR".equals(instrument)) type = "tasso_bce_depositi";
            else if ("MRR_FR".equals(instrument)) type = "tasso_bce_rifinanziamento";
            else if ("MLFR".equals(instrument)) type = "tasso_bce_prestito_marginale";
            else continue;
            double value = Double.parseDouble(row.get(valueIndex));
            String label = italianRateLabel(instrument);
            db.cacheFinanceData(type,
                    label + ": " + String.format(Locale.ITALY, "%.2f%%", value) + " | dal " + row.get(dateIndex),
                    ECB_RATES, "BCE ufficiale");
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("Tassi BCE non riconosciuti");
        return count;
    }

    int parseAndCacheFx(String xml, EvoDb db) {
        if (xml == null || xml.isEmpty()) throw new IllegalArgumentException("Risposta cambi BCE vuota");
        Matcher dateMatcher = FX_DATE.matcher(xml);
        String date = dateMatcher.find() ? dateMatcher.group(1) : "data non disponibile";
        Matcher rateMatcher = FX_RATE.matcher(xml);
        int count = 0;
        while (rateMatcher.find()) {
            String currency = rateMatcher.group(1);
            String rate = rateMatcher.group(2);
            db.cacheFinanceData("cambio_eur_" + currency.toLowerCase(Locale.ROOT),
                    "1 EUR = " + rate + " " + currency + " | " + date,
                    ECB_FX, "BCE ufficiale");
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("Cambi BCE non riconosciuti");
        return count;
    }

    private SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "text/html,application/xml,text/csv,application/json");
            connection.setRequestProperty("User-Agent", "EvoConti/0.10 informational notebook");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < 500_000) out.append(line).append('\n');
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private List<String> csvRow(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }

    private int required(Map<String, Integer> index, String name) {
        Integer value = index.get(name);
        if (value == null) throw new IllegalArgumentException("Campo BCE mancante: " + name);
        return value;
    }

    private String italianRateLabel(String instrument) {
        if ("DFR".equals(instrument)) return "Depositi BCE";
        if ("MRR_FR".equals(instrument)) return "Rifinanziamento principale BCE";
        return "Prestito marginale BCE";
    }

    private SearchHit trustedSearchHit(String html) {
        Matcher links = SEARCH_RESULT.matcher(html == null ? "" : html);
        Matcher snippets = SEARCH_SNIPPET.matcher(html == null ? "" : html);
        List<String> snippetList = new ArrayList<>();
        while (snippets.find()) snippetList.add(decodeHtml(snippets.group(1)));
        int index = 0;
        while (links.find()) {
            String url = decodeRedirectUrl(links.group(1));
            String title = decodeHtml(links.group(2));
            String snippet = index < snippetList.size() ? snippetList.get(index) : "";
            index++;
            if (isTrusted(url)) return new SearchHit(title, url, snippet);
        }
        return null;
    }

    private String extractDescription(String html) {
        Matcher matcher = META_DESCRIPTION.matcher(html == null ? "" : html);
        return matcher.find() ? decodeHtml(matcher.group(1)) : "";
    }

    private String decodeRedirectUrl(String raw) {
        String value = decodeHtml(raw);
        try {
            int start = value.indexOf("uddg=");
            if (start >= 0) {
                String encoded = value.substring(start + 5);
                int end = encoded.indexOf('&');
                if (end >= 0) encoded = encoded.substring(0, end);
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {
            // Fall through and return the visible URL.
        }
        return value.startsWith("//") ? "https:" + value : value;
    }

    private boolean isTrusted(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) return false;
        for (String domain : TRUSTED_DOMAINS) {
            if (lower.contains("://" + domain + "/") || lower.contains("." + domain + "/")) return true;
        }
        return false;
    }

    private String decodeHtml(String value) {
        return value == null ? "" : value
                .replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max - 3).trim() + "...";
    }

    private String safeMessage(Exception error) {
        String message = error == null ? "errore sconosciuto" : error.getMessage();
        if (message == null || message.trim().isEmpty()) return "errore di rete";
        return trim(message.replaceAll("https?://\\S+", "fonte remota"), 120);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text != null && text.contains(value)) return true;
        }
        return false;
    }

    private static final class SearchHit {
        final String title;
        final String url;
        final String snippet;

        SearchHit(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }
}
