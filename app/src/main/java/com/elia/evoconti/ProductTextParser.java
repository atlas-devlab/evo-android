package com.elia.evoconti;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ProductTextParser {
    String bestName(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return "";
        String[] rawLines = rawText.replace('\r', '\n').split("\\n+");
        List<ScoredLine> candidates = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].trim().replaceAll("\\s+", " ");
            if (!usable(line)) continue;
            int score = Math.max(0, 10 - i);
            long letters = line.chars().filter(Character::isLetter).count();
            long upper = line.chars().filter(Character::isUpperCase).count();
            if (letters > 0 && upper >= letters * .7) score += 6;
            if (line.length() >= 4 && line.length() <= 24) score += 4;
            if (line.split(" ").length <= 4) score += 3;
            candidates.add(new ScoredLine(line, score, i));
        }
        if (candidates.isEmpty()) return "";
        candidates.sort(Comparator.comparingInt((ScoredLine value) -> value.score).reversed()
                .thenComparingInt(value -> value.position));
        String first = title(candidates.get(0).text);
        if (candidates.size() == 1) return first;
        ScoredLine second = candidates.get(1);
        if (Math.abs(second.position - candidates.get(0).position) > 3 ||
                first.equalsIgnoreCase(second.text) || first.length() + second.text.length() > 55) return first;
        return first + " " + title(second.text);
    }

    private boolean usable(String line) {
        if (line.length() < 3 || line.length() > 55) return false;
        String value = line.toLowerCase(Locale.ITALY);
        if (containsAny(value, "ingredient", "nutriz", "valori", "energia", "kcal", "scadenza",
                "lotto", "www.", "http", "prodotto da", "distribuito", "conservare", "servizio clienti")) return false;
        if (value.matches(".*\\b[0-9]{8,}\\b.*")) return false;
        if (value.matches(".*\\b[0-9]+\\s*(g|kg|ml|cl|l)\\b.*")) return false;
        long letters = line.chars().filter(Character::isLetter).count();
        return letters >= 3 && letters >= line.length() * .45;
    }

    private String title(String raw) {
        if (raw.chars().filter(Character::isUpperCase).count() < raw.length() * .7) return raw;
        String lower = raw.toLowerCase(Locale.ITALY);
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < lower.length(); i++) {
            char value = lower.charAt(i);
            out.append(upper ? Character.toUpperCase(value) : value);
            upper = Character.isWhitespace(value) || value == '-';
        }
        return out.toString();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static final class ScoredLine {
        final String text;
        final int score;
        final int position;

        ScoredLine(String text, int score, int position) {
            this.text = text;
            this.score = score;
            this.position = position;
        }
    }
}
