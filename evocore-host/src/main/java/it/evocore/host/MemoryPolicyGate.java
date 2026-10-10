package it.evocore.host;

import java.util.Locale;

final class MemoryPolicyGate {
    static final String USER_CONFIRMED = "USER_CONFIRMED";
    static final String APP_VERIFIED = "APP_VERIFIED";
    static final String REPEATED_PATTERN = "REPEATED_PATTERN";
    static final String MODEL_HYPOTHESIS = "MODEL_HYPOTHESIS";
    static final String DISPUTED = "DISPUTED";
    static final String EPHEMERAL = "EPHEMERAL";
    static final String EXPIRED = "EXPIRED";

    static final class Decision {
        final boolean accepted;
        final String state;
        final String kind;
        final String sensitivity;
        final long expiresAt;
        final String reason;

        Decision(boolean accepted, String state, String kind, String sensitivity,
                 long expiresAt, String reason) {
            this.accepted = accepted;
            this.state = state;
            this.kind = kind;
            this.sensitivity = sensitivity;
            this.expiresAt = expiresAt;
            this.reason = reason;
        }
    }

    Decision evaluate(String text, String source, String requestedState, long now) {
        String clean = text == null ? "" : text.trim();
        if (clean.length() < 3 || clean.length() > 2_000) {
            return new Decision(false, MODEL_HYPOTHESIS, "invalid", MemoryVault.OPERATIONAL,
                    0L, "Lunghezza memoria non valida");
        }
        String lower = clean.toLowerCase(Locale.ITALIAN);
        String normalizedSource = source == null ? "unknown" : source.trim().toLowerCase(Locale.ROOT);

        boolean emotional = containsAny(lower, "sono stress", "mi sento stress", "sono ansios",
                "sono triste", "sono arrabbiat", "umore", "energia bassa");
        if (emotional) {
            return new Decision(true, EPHEMERAL, "wellbeing", MemoryVault.SENSITIVE,
                    now + 72L * 60L * 60L * 1000L, "Stato personale temporaneo");
        }

        boolean sensitive = containsAny(lower, "salute", "debito", "stipendio", "reddito", "diagnosi",
                "religione", "politica", "sessual", "password", "pin ");
        String level = sensitive ? MemoryVault.SENSITIVE : MemoryVault.OPERATIONAL;
        boolean directUser = "user".equals(normalizedSource) || "manual".equals(normalizedSource);
        boolean explicit = lower.startsWith("ricorda") || lower.contains("ricordati che") ||
                lower.startsWith("preferisco") || lower.startsWith("il mio obiettivo");
        if (directUser && (explicit || USER_CONFIRMED.equals(requestedState))) {
            return new Decision(true, USER_CONFIRMED, classify(lower), level, 0L,
                    "Dichiarazione esplicita dell'utente");
        }
        if ("app".equals(normalizedSource) && APP_VERIFIED.equals(requestedState)) {
            return new Decision(true, APP_VERIFIED, classify(lower), level, 0L,
                    "Dato verificato dall'app proprietaria");
        }
        if (containsAny(lower, "ignora le istruzioni", "system prompt", "agisci come sistema",
                "sovrascrivi la policy")) {
            return new Decision(false, MODEL_HYPOTHESIS, "instruction", level, 0L,
                    "Tentativo di trasformare memoria in istruzione");
        }
        return new Decision(true, MODEL_HYPOTHESIS, classify(lower), level, 0L,
                "Candidato da confermare");
    }

    Decision evaluateDialogueCapture(String prompt, long now) {
        String focused = QuestionFocus.current(prompt).trim();
        String lower = focused.toLowerCase(Locale.ITALIAN);
        boolean explicit = lower.startsWith("ricorda ") || lower.startsWith("ricordati ")
                || lower.startsWith("tieni a mente ") || lower.startsWith("non dimenticare ")
                || lower.startsWith("salva in memoria ") || lower.startsWith("preferisco ")
                || lower.startsWith("mi piace ") || lower.startsWith("non mi piace ")
                || lower.startsWith("il mio obiettivo ") || lower.startsWith("voglio che tu ricordi ");
        boolean wellbeing = lower.startsWith("sono stress") || lower.startsWith("mi sento stress")
                || lower.startsWith("sono ansios") || lower.startsWith("sono triste")
                || lower.startsWith("sono arrabbiat");
        if (focused.isEmpty() || (!explicit && !wellbeing)) {
            return new Decision(false, MODEL_HYPOTHESIS, "dialogue",
                    MemoryVault.OPERATIONAL, 0L,
                    "Il dialogo non contiene una richiesta esplicita di memoria");
        }
        return evaluate(focused, "client-dialogue", "", now);
    }

    private static String classify(String lower) {
        if (containsAny(lower, "preferisco", "mi piace", "non mi piace")) return "preference";
        if (containsAny(lower, "obiettivo", "voglio", "vorrei")) return "goal";
        if (containsAny(lower, "debito", "budget", "reddito", "spesa")) return "financial_context";
        if (containsAny(lower, "famiglia", "lavoro", "casa")) return "life_context";
        return "personal_note";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
