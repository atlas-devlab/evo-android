package com.elia.evoconti;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReceiptParser {
    private static final Pattern MONEY = Pattern.compile("(?<![\\d])(?:EUR\\s*)?([0-9]{1,6}(?:[.,][0-9]{2}))(?![\\d])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(".*\\b(?:\\d{1,2}[/.-]){2}\\d{2,4}\\b.*");

    static final class Result {
        String merchant = "";
        double total;
        String summary = "";
        boolean hasTotal;
    }

    Result parse(String rawText) {
        Result result = new Result();
        if (rawText == null || rawText.trim().isEmpty()) return result;

        String[] rawLines = rawText.replace('\r', '\n').split("\\n+");
        List<String> lines = new ArrayList<>();
        for (String raw : rawLines) {
            String line = raw.trim().replaceAll("\\s+", " ");
            if (!line.isEmpty()) lines.add(line);
        }
        result.merchant = findMerchant(lines);

        int bestScore = Integer.MIN_VALUE;
        double bestValue = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ITALY);
            Matcher matcher = MONEY.matcher(line);
            while (matcher.find()) {
                double value = parseMoney(matcher.group(1));
                if (value <= 0) continue;
                int score = 0;
                if (containsAny(lower, "totale", "total", "da pagare", "importo", "pagato")) score += 12;
                if (containsAny(lower, "subtotale", "iva", "imponibile", "sconto", "resto", "contanti ricevuti")) score -= 10;
                if (i >= lines.size() / 2) score += 2;
                score += Math.min(5, (int) Math.log10(Math.max(1, value)));
                if (score > bestScore || (score == bestScore && value > bestValue)) {
                    bestScore = score;
                    bestValue = value;
                }
            }
        }
        if (bestScore > Integer.MIN_VALUE) {
            result.total = bestValue;
            result.hasTotal = true;
        }
        String compact = String.join(" | ", lines);
        result.summary = compact.length() <= 420 ? compact : compact.substring(0, 420);
        return result;
    }

    private String findMerchant(List<String> lines) {
        for (int i = 0; i < Math.min(7, lines.size()); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ITALY);
            if (line.length() < 3 || line.length() > 70 || DATE.matcher(line).matches()) continue;
            if (containsAny(lower, "scontrino", "documento commerciale", "partita iva", "p.iva", "telefono", "tel.", "www.", "http", "cassa", "operatore")) continue;
            if (line.chars().filter(Character::isLetter).count() < 3) continue;
            return titleCase(line);
        }
        return "";
    }

    private double parseMoney(String raw) {
        try {
            return Double.parseDouble(raw.replace('.', ',').replace(',', '.'));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private String titleCase(String raw) {
        String lower = raw.toLowerCase(Locale.ITALY);
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = Character.isWhitespace(c);
        }
        return out.toString();
    }
}
