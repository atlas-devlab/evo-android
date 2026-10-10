package it.evocore.host;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

final class CoreRuleEngine {
    static final class EnforcementResult {
        final String answerJson;
        final boolean usedFallback;
        final String reason;

        EnforcementResult(String answerJson, boolean usedFallback, String reason) {
            this.answerJson = answerJson;
            this.usedFallback = usedFallback;
            this.reason = reason;
        }
    }

    String answer(String contextJson, String question, String memoryContext) {
        try {
            JSONObject envelope = new JSONObject(
                    contextJson == null || contextJson.trim().isEmpty() ? "{}" : contextJson);
            JSONObject context = payloadObject(envelope);
            String cleanQuestion = compact(QuestionFocus.current(question), 12_000);
            String lower = cleanQuestion.toLowerCase(Locale.ITALIAN);
            if (!isFinancialContext(envelope) && !isFinancialContext(context)) {
                return generalAnswer(context, lower, memoryContext).toString();
            }
            double income = context.optDouble("redditoNetto", 0d);
            double balance = context.optDouble("saldoMensile", 0d);
            double debtsPct = context.optDouble("debitiPct", 0d);
            double debtRemaining = context.optDouble("debitoResiduo", 0d);
            double emergencyMonths = context.optDouble("fondoEmergenzaMesi", 0d);
            int stress = context.optInt("stressUtente", -1);
            int health = context.optInt("saluteBudget", 50);

            boolean fragile = balance < 0d || debtsPct > 20d || emergencyMonths < 1d || stress >= 8;
            boolean cautious = fragile || debtsPct > 10d || emergencyMonths < 2d || balance < income * 0.05d;
            boolean asksTrade = containsAny(lower, "cosa compro", "quale etf", "quale azione", "compra ",
                    "vendi ", "leva", "investi tutto", "rendimento garantito");
            boolean asksMemory = isMemoryQuestion(lower);

            JSONObject out = new JSONObject();
            out.put("domain", "finance");
            out.put("stato", fragile ? "protezione" : cautious ? "prudenza" : "equilibrio");
            out.put("rischio", fragile ? "alto" : cautious ? "medio" : "contenuto");
            out.put("puoInvestire", !cautious && income > 0d);
            out.put("effettoAvatar", health >= 75 ? "fortificato" : health < 45 ? "affaticato" : "stabile");
            JSONArray actions = new JSONArray();

            if (asksTrade) {
                out.put("messaggioBreve", "Non scelgo acquisti o vendite al posto tuo. Posso confrontare rischi, costi e peso sul tuo budget.");
                out.put("motivo", "EvoCore protegge autonomia e budget");
                actions.put("Definisci orizzonte e perdita tollerabile");
                actions.put("Controlla prima debiti e fondo emergenza");
                out.put("puoInvestire", false);
            } else if (asksMemory) {
                String memory = compact(memoryContext, 1_000);
                out.put("messaggioBreve", memory.isEmpty()
                        ? "Non ho ancora ricordi confermati su di te. Puoi dirmi cosa vuoi che conservi."
                        : "Ricordo questi elementi confermati:\n" + memory);
                out.put("motivo", "Uso solo memoria confermata e autorizzata");
                actions.put("Apri EvoCore per controllare o correggere i ricordi");
            } else if (fragile) {
                out.put("messaggioBreve", "Prima proteggiamo il margine: saldo, debiti e liquidita richiedono attenzione questo mese.");
                out.put("motivo", reason(balance, debtsPct, emergencyMonths, stress));
                actions.put(balance < 0d ? "Riduci il disavanzo del mese" : "Conserva il margine disponibile");
                if (debtRemaining > 0d) actions.put("Evita nuove rate e rivedi il debito piu costoso");
                if (emergencyMonths < 2d) actions.put("Rafforza gradualmente il fondo emergenza");
            } else if (cautious) {
                out.put("messaggioBreve", "La base sta reggendo, ma conviene consolidarla prima di aumentare impegni o investimenti.");
                out.put("motivo", reason(balance, debtsPct, emergencyMonths, stress));
                actions.put("Mantieni una quota libera nel budget");
                actions.put("Rivedi la prossima spesa programmata");
            } else {
                out.put("messaggioBreve", "Hai margine positivo e debiti sotto controllo: pianifica con gradualita senza toccare la riserva.");
                out.put("motivo", "Margine e protezioni risultano coerenti");
                actions.put("Assegna una quota piccola al prossimo obiettivo");
                actions.put("Mantieni il fondo emergenza separato");
            }
            out.put("azioni", actions);
            out.put("motore", "EvoCore regole locali");
            return out.toString();
        } catch (Exception error) {
            return fallback();
        }
    }

    String enforcePolicy(String baselineJson, String generatedJson) {
        return enforcePolicy(baselineJson, generatedJson, "");
    }

    String enforcePolicy(String baselineJson, String generatedJson, String question) {
        return enforcePolicyDetailed(baselineJson, generatedJson, question).answerJson;
    }

    EnforcementResult enforcePolicyDetailed(String baselineJson, String generatedJson,
                                             String question) {
        try {
            JSONObject baseline = new JSONObject(baselineJson);
            JSONObject generated = extractObject(generatedJson);
            if (generated == null) {
                return new EnforcementResult(baselineJson, true, "invalid_model_json");
            }
            if (compact(generated.optString("messaggioBreve", ""), 420).isEmpty()) {
                return new EnforcementResult(baselineJson, true, "missing_model_message");
            }
            String lowerQuestion = compact(QuestionFocus.current(question), 12_000)
                    .toLowerCase(Locale.ITALIAN);
            String lowerGenerated = generated.toString().toLowerCase(Locale.ITALIAN);
            boolean budgetQuestion = containsAny(lowerQuestion, "margine", "budget", "spes",
                    "entrat", "saldo", "debito", "fondo", "liquid", "programmat",
                    "risparm", "reddito");
            boolean investmentQuestion = containsAny(lowerQuestion, "etf", "crypto", "bitcoin",
                    "borsa", "portafoglio", "invest", "azione singola", "azioni singole",
                    "titolo azionario", "azioni di borsa");
            boolean generatedInvestment = containsAny(lowerGenerated, "azione singola",
                    "titolo azionario", "impresa e del settore", "etf", "crypto",
                    "portafoglio", "volatilit", "investimento");
            boolean generatedBudget = containsAny(lowerGenerated, "margine", "budget", "spes",
                    "entrat", "saldo", "debito", "fondo", "liquid", "programmat",
                    "risparm", "reddito");
            boolean memoryQuestion = isMemoryQuestion(lowerQuestion);
            if (memoryQuestion && !isGroundedMemoryAnswer(
                    baseline.optString("messaggioBreve", ""),
                    generated.optString("messaggioBreve", ""), lowerQuestion)) {
                return new EnforcementResult(baselineJson, true, "memory_ungrounded");
            }
            if (budgetQuestion && !investmentQuestion
                    && (generatedInvestment || !generatedBudget)) {
                return new EnforcementResult(baselineJson, true, "semantic_drift");
            }
            generated.put("stato", baseline.optString("stato", "prudenza"));
            generated.put("domain", baseline.optString("domain", "general"));
            if (baseline.has("rischio")) generated.put("rischio", baseline.optString("rischio", "medio"));
            else generated.remove("rischio");
            if (baseline.has("puoInvestire")) generated.put("puoInvestire", baseline.optBoolean("puoInvestire", false));
            else generated.remove("puoInvestire");
            if (baseline.has("effettoAvatar")) generated.put("effettoAvatar", baseline.optString("effettoAvatar", "stabile"));
            else generated.remove("effettoAvatar");
            generated.put("messaggioBreve", compact(generated.optString("messaggioBreve", ""), 420));
            generated.put("motivo", compact(generated.optString("motivo", baseline.optString("motivo", "")), 260));
            JSONArray input = generated.optJSONArray("azioni");
            JSONArray safe = new JSONArray();
            if (input != null) {
                for (int i = 0; i < Math.min(3, input.length()); i++) {
                    String action = compact(input.optString(i, ""), 180);
                    if (!action.isEmpty() && !containsAny(action.toLowerCase(Locale.ITALIAN),
                            "investi tutto", "usa debito", "fai leva", "rendimento garantito")) safe.put(action);
                }
            }
            generated.put("azioni", safe.length() == 0 ? baseline.optJSONArray("azioni") : safe);
            generated.put("motore", "EvoCore Gemma locale");
            return new EnforcementResult(generated.toString(), false, "model_accepted");
        } catch (Exception error) {
            return new EnforcementResult(baselineJson, true, "policy_validation_error");
        }
    }

    private static boolean isFinancialContext(JSONObject context) {
        String schema = context.optString("schema", "").toLowerCase(Locale.ROOT);
        return schema.startsWith("evoconti.") || context.has("redditoNetto")
                || context.has("saldoMensile") || context.has("debitiPct")
                || context.has("fondoEmergenzaMesi") || context.has("saluteBudget");
    }

    private static JSONObject payloadObject(JSONObject envelope) {
        JSONObject payload = envelope.optJSONObject("payload");
        return payload == null ? envelope : payload;
    }

    private static JSONObject generalAnswer(JSONObject context, String lowerQuestion,
                                            String memoryContext) throws Exception {
        JSONObject out = new JSONObject();
        out.put("domain", "general");
        out.put("stato", "pronto");
        JSONArray actions = new JSONArray();
        if (isMemoryQuestion(lowerQuestion)) {
            String memory = compact(memoryContext, 1_000);
            out.put("messaggioBreve", memory.isEmpty()
                    ? "Non ho ancora ricordi confermati in questo spazio dell'app."
                    : "In questo spazio dell'app ricordo:\n" + memory);
            out.put("motivo", "Uso solo la memoria autorizzata per questa app");
            actions.put("Apri EvoCore per controllare o correggere i ricordi");
        } else {
            String schema = compact(context.optString("schema", "contesto non versionato"), 90);
            String screen = compact(context.optString("screen", "corrente"), 80);
            int itemCount = context.optJSONArray("items") == null ? -1 : context.optJSONArray("items").length();
            String count = itemCount < 0 ? "" : ", con " + itemCount + " elementi";
            out.put("messaggioBreve", "Sto usando solo il contesto sintetico " + schema
                    + " della schermata " + screen + count + "; non leggo il database completo.");
            out.put("motivo", "Fallback generale locale: descrive il contesto senza inventare dati di dominio");
            actions.put("Formula una domanda sui dati visibili in questa schermata");
        }
        out.put("azioni", actions);
        out.put("motore", "EvoCore regole generali locali");
        return out;
    }

    private static JSONObject extractObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(raw.substring(start, end + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String reason(double balance, double debt, double emergency, int stress) {
        if (balance < 0d) return "Saldo mensile negativo";
        if (debt > 20d) return "Rate del debito sopra la soglia prudente";
        if (emergency < 1d) return "Fondo emergenza inferiore a un mese";
        if (stress >= 8) return "Stress elevato: evitiamo decisioni impulsive";
        if (debt > 10d) return "Debiti da tenere sotto osservazione";
        return "Fondo emergenza ancora da consolidare";
    }

    private static String fallback() {
        return "{\"domain\":\"general\",\"stato\":\"dati_incompleti\",\"messaggioBreve\":\"Non riesco a leggere il contesto sintetico di questa schermata.\",\"azioni\":[\"Aggiorna il contesto e riprova\"],\"motivo\":\"Contesto non valido\",\"motore\":\"EvoCore regole generali locali\"}";
    }

    private static String compact(String value, int max) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static boolean isMemoryQuestion(String lowerQuestion) {
        return containsAny(lowerQuestion, "cosa sai di me", "chi sono", "cosa ricordi",
                "mi conosci", "quale nome", "come mi chiami", "come preferisco",
                "memoria su di me", "ricordi di me");
    }

    private static boolean isGroundedMemoryAnswer(String baselineMessage, String generatedMessage,
                                                   String lowerQuestion) {
        String baseline = normalizeWords(baselineMessage);
        String generated = normalizeWords(generatedMessage);
        if (baseline.isEmpty() || generated.isEmpty()) return false;

        if (containsAny(lowerQuestion, "quale nome", "come mi chiami", "chi sono")) {
            String expectedName = wordAfter(baseline, "chiamarmi");
            if (!expectedName.isEmpty() && !containsWord(generated, expectedName)) return false;
        }

        int shared = 0;
        Set<String> seen = new java.util.HashSet<>();
        for (String word : baseline.split("\\s+")) {
            if (word.length() < 5 || isMemoryNoise(word) || !seen.add(word)) continue;
            if (containsWord(generated, word) && ++shared >= 2) return true;
        }
        return shared >= 2;
    }

    private static String normalizeWords(String value) {
        return compact(value, 2_000).toLowerCase(Locale.ITALIAN)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String wordAfter(String value, String marker) {
        int markerAt = value.indexOf(marker);
        if (markerAt < 0) return "";
        String tail = value.substring(markerAt + marker.length()).trim();
        int space = tail.indexOf(' ');
        return space < 0 ? tail : tail.substring(0, space);
    }

    private static boolean containsWord(String value, String word) {
        return (" " + value + " ").contains(" " + word + " ");
    }

    private static boolean isMemoryNoise(String word) {
        return containsAny(word, "memoria", "personale", "confermata", "confirmed",
                "utente", "ricordo", "questi", "elementi", "usare", "autorizzata",
                "profilo", "preference", "identity", "identita");
    }
}
