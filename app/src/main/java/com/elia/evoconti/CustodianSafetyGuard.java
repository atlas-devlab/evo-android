package com.elia.evoconti;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CustodianSafetyGuard {
    private CustodianSafetyGuard() {
    }

    static AiAnswer merge(AiAnswer rules, AiAnswer generated) {
        if (generated == null || unsafe(generated.messaggioBreve) || unsafe(generated.motivo)) {
            return rules;
        }
        for (String action : generated.azioni) {
            if (unsafe(action)) return rules;
        }
        if (!sameHardPolicy(rules, generated)) return rules;

        AiAnswer out = rules.copy();
        out.motore = "llama.cpp locale + barriera prudente";
        out.messaggioBreve = generated.messaggioBreve;
        if (!generated.motivo.isEmpty()) out.motivo = generated.motivo;
        List<String> safeActions = new ArrayList<>();
        for (String action : generated.azioni) {
            if (!action.trim().isEmpty() && !unsafe(action)) safeActions.add(action.trim());
            if (safeActions.size() == 3) break;
        }
        if (!safeActions.isEmpty()) out.azioni = safeActions;

        // Numeric policy always wins over generative text.
        out.stato = rules.stato;
        out.rischio = rules.rischio;
        out.puoInvestire = rules.puoInvestire;
        out.effettoAvatar = rules.effettoAvatar;
        return out;
    }

    private static boolean sameHardPolicy(AiAnswer rules, AiAnswer generated) {
        // EvoCore and EvoConti can use different human-readable labels for the same
        // budget condition. The client's numeric policy remains authoritative below;
        // only an incompatible investment permission must reject the generated text.
        return rules.puoInvestire == generated.puoInvestire;
    }

    static boolean unsafe(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ITALY);
        return text.contains("compra ") || text.contains("acquista ") ||
                text.contains("vendi ") || text.contains("investi tutto") ||
                text.contains("all-in") || text.contains("tutto in ") ||
                text.contains("leva finanziaria") || text.contains("usa la leva") || text.contains("fai leva") ||
                text.contains("rendimento garantit") || text.contains("guadagno garantit") ||
                text.contains("senza rischio") || text.contains("crypto sicura") ||
                text.contains("debito per investire") || text.contains("prestito per investire") ||
                text.contains("indebitati per investire");
    }
}
