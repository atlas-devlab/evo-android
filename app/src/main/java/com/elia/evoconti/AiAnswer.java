package com.elia.evoconti;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class AiAnswer {
    String stato = "prudenza";
    String messaggioBreve = "";
    List<String> azioni = new ArrayList<>();
    String rischio = "medio";
    boolean puoInvestire;
    String motivo = "";
    String effettoAvatar = "stabile";
    String motore = "regole locali";

    String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("stato", stato);
            json.put("messaggioBreve", messaggioBreve);
            json.put("azioni", new JSONArray(azioni));
            json.put("rischio", rischio);
            json.put("puoInvestire", puoInvestire);
            json.put("motivo", motivo);
            json.put("effettoAvatar", effettoAvatar);
            json.put("motore", motore);
            return json.toString(2);
        } catch (Exception ignored) {
            return "{\"stato\":\"prudenza\",\"motore\":\"regole locali\"}";
        }
    }

    static AiAnswer fromModelOutput(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject json = new JSONObject(raw.substring(start, end + 1));
            AiAnswer answer = new AiAnswer();
            answer.stato = clean(json.optString("stato", "prudenza"), 40);
            answer.messaggioBreve = clean(json.optString("messaggioBreve", ""), 420);
            answer.rischio = clean(json.optString("rischio", "medio"), 40);
            answer.puoInvestire = json.optBoolean("puoInvestire", false);
            answer.motivo = clean(json.optString("motivo", ""), 260);
            answer.effettoAvatar = clean(json.optString("effettoAvatar", "stabile"), 40);
            JSONArray actions = json.optJSONArray("azioni");
            if (actions != null) {
                for (int i = 0; i < Math.min(4, actions.length()); i++) {
                    String action = clean(actions.optString(i, ""), 180);
                    if (!action.isEmpty()) answer.azioni.add(action);
                }
            }
            if (answer.messaggioBreve.isEmpty() || "risposta".equalsIgnoreCase(answer.messaggioBreve) ||
                    "motivo".equalsIgnoreCase(answer.motivo)) return null;
            for (String action : answer.azioni) {
                if ("azione".equalsIgnoreCase(action)) return null;
            }
            answer.motore = "llama.cpp locale";
            return answer;
        } catch (Exception ignored) {
            return null;
        }
    }

    AiAnswer copy() {
        AiAnswer out = new AiAnswer();
        out.stato = stato;
        out.messaggioBreve = messaggioBreve;
        out.azioni = new ArrayList<>(azioni);
        out.rischio = rischio;
        out.puoInvestire = puoInvestire;
        out.motivo = motivo;
        out.effettoAvatar = effettoAvatar;
        out.motore = motore;
        return out;
    }

    private static String clean(String value, int max) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }
}
