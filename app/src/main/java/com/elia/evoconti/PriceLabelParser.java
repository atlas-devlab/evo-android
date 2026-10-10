package com.elia.evoconti;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PriceLabelParser {
    private static final Pattern DECIMAL_PRICE = Pattern.compile(
            "(?i)(?:EUR|€)?\\s*([0-9]{1,4})\\s*([,.])\\s*([0-9]{2})\\s*(?:EUR|€)?");
    private static final Pattern SPACED_PRICE_WITH_CURRENCY = Pattern.compile(
            "(?i)(?:EUR|€)\\s*([0-9]{1,4})\\s+([0-9]{2})|([0-9]{1,4})\\s+([0-9]{2})\\s*(?:EUR|€)");
    private static final Pattern DATE = Pattern.compile("\\b[0-9]{1,2}[/.-][0-9]{1,2}[/.-][0-9]{2,4}\\b");

    static final class Candidate {
        final double value;
        final String line;
        final int score;

        Candidate(double value, String line, int score) {
            this.value = value;
            this.line = line;
            this.score = score;
        }
    }

    List<Candidate> candidates(String rawText) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        if (rawText == null) return new ArrayList<>();
        for (String rawLine : rawText.replace('\r', '\n').split("\\n+")) {
            String line = rawLine.trim().replaceAll("\\s+", " ");
            if (line.isEmpty()) continue;
            collectDecimal(line, unique);
            collectSpaced(line, unique);
        }
        List<Candidate> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparingInt((Candidate value) -> value.score).reversed()
                .thenComparingDouble(value -> value.value));
        return out;
    }

    private void collectDecimal(String line, Map<String, Candidate> unique) {
        Matcher matcher = DECIMAL_PRICE.matcher(line);
        while (matcher.find()) {
            if (overlapsDate(line, matcher.start(), matcher.end()) || followedByPercent(line, matcher.end())) continue;
            double value = parse(matcher.group(1), matcher.group(3));
            add(value, line, score(line, true), unique);
        }
    }

    private void collectSpaced(String line, Map<String, Candidate> unique) {
        Matcher matcher = SPACED_PRICE_WITH_CURRENCY.matcher(line);
        while (matcher.find()) {
            if (followedByPercent(line, matcher.end())) continue;
            String euros = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String cents = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            double value = parse(euros, cents);
            add(value, line, score(line, false), unique);
        }
    }

    private void add(double value, String line, int score, Map<String, Candidate> unique) {
        if (value < .01 || value > 9_999) return;
        String key = String.format(Locale.US, "%.2f", value);
        Candidate previous = unique.get(key);
        Candidate candidate = new Candidate(value, line, score);
        if (previous == null || candidate.score > previous.score) unique.put(key, candidate);
    }

    private int score(String line, boolean decimalSeparator) {
        String value = line.toLowerCase(Locale.ITALY);
        int score = decimalSeparator ? 4 : 1;
        if (value.contains("€") || value.contains("eur")) score += 8;
        if (containsAny(value, "prezzo", "totale", "offerta", "promo")) score += 3;
        if (line.length() <= 18) score += 2;
        if (containsAny(value, "/kg", "al kg", "kg", "/l", "al litro", "100 g")) score -= 7;
        if (containsAny(value, "%", "sconto", "risparmio")) score -= 5;
        if (value.matches(".*\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b.*")) score -= 12;
        return score;
    }

    private double parse(String euros, String cents) {
        try {
            return Double.parseDouble(euros + "." + cents);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private boolean overlapsDate(String line, int start, int end) {
        Matcher date = DATE.matcher(line);
        while (date.find()) if (start < date.end() && end > date.start()) return true;
        return false;
    }

    private boolean followedByPercent(String line, int end) {
        int index = end;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return index < line.length() && line.charAt(index) == '%';
    }
}
