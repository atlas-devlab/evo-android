package com.elia.evoconti;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class AiContext {
    String mese = new SimpleDateFormat("yyyy-MM", Locale.ITALY).format(new Date());
    int saluteBudget;
    String statoSalute = "Da configurare";
    int completezzaDati;
    double redditoNetto;
    double speseFisse;
    double usciteEssenzialiPct;
    double desideriPct;
    double futuroPct;
    double debitiPct;
    double debitoResiduo;
    double saldoMensile;
    double fondoEmergenzaMesi;
    int speseImpulsive;
    int stressUtente = -1;
    int energiaUtente = -1;
    long checkInOre = -1;
    int obiettiviAttivi;
    int obiettiviInRitardo;
    double interessiPassivi;
    double interessiAttivi;
    List<String> categoriePrincipali = new ArrayList<>();
    List<String> conti = new ArrayList<>();
    List<String> programmate = new ArrayList<>();
    List<String> debiti = new ArrayList<>();
    List<String> prossimeScadenze = new ArrayList<>();
    List<String> obiettivi = new ArrayList<>();
    List<String> movimentiRecenti = new ArrayList<>();
    List<String> speseDaRivedere = new ArrayList<>();
    List<String> contestoMultimediale = new ArrayList<>();
    List<String> watchlist = new ArrayList<>();
    List<String> datiInternet = new ArrayList<>();

    String toJson() {
        return toJson("");
    }

    String toJson(String question) {
        try {
            String focus = questionFocus(question);
            boolean full = focus.isEmpty() || containsAny(focus,
                    "tutto", "quadro completo", "contesto completo");
            boolean budget = full || containsAny(focus,
                    "budget", "mese", "margine", "saldo", "entrat", "uscit",
                    "spes", "categoria", "conto", "casa");
            boolean planned = full || containsAny(focus,
                    "programmat", "scaden", "agenda", "impegn", "calendar");
            boolean debt = full || containsAny(focus,
                    "debit", "rata", "prestito", "mutuo", "revolving");
            boolean goals = full || containsAny(focus,
                    "obiett", "risparm", "fondo emergenza");
            boolean media = full || containsAny(focus,
                    "foto", "scontrin", "barcode", "gps", "luogo", "voce",
                    "prodot", "supermercato");
            boolean investments = full || containsAny(focus,
                    "invest", "etf", "azion", "crypto", "watchlist", "radar",
                    "interess", "bce", "internet", "fonte", "tasso");

            JSONObject json = new JSONObject();
            json.put("schema", "evoconti.context.v1");
            json.put("mese", mese);
            json.put("saluteBudget", saluteBudget);
            json.put("statoSalute", statoSalute);
            json.put("completezzaDatiPct", completezzaDati);
            json.put("redditoNetto", round(redditoNetto));
            json.put("speseFisse", round(speseFisse));
            json.put("usciteEssenzialiPct", round(usciteEssenzialiPct));
            json.put("desideriPct", round(desideriPct));
            json.put("futuroPct", round(futuroPct));
            json.put("debitiPct", round(debitiPct));
            json.put("debitoResiduo", round(debitoResiduo));
            json.put("saldoMensile", round(saldoMensile));
            json.put("fondoEmergenzaMesi", round(fondoEmergenzaMesi));
            json.put("speseImpulsive", speseImpulsive);
            if (stressUtente >= 0) json.put("stressUtente", stressUtente);
            if (energiaUtente >= 0) json.put("energiaUtente", energiaUtente);
            if (checkInOre >= 0) json.put("checkInOre", checkInOre);
            json.put("obiettiviAttivi", obiettiviAttivi);
            json.put("obiettiviInRitardo", obiettiviInRitardo);
            json.put("interessiPassivi", round(interessiPassivi));
            json.put("interessiAttivi", round(interessiAttivi));
            JSONObject available = new JSONObject();
            available.put("categorie", categoriePrincipali.size());
            available.put("conti", conti.size());
            available.put("programmate", programmate.size());
            available.put("debiti", debiti.size());
            available.put("scadenze", prossimeScadenze.size());
            available.put("obiettivi", obiettivi.size());
            available.put("movimenti", movimentiRecenti.size());
            available.put("speseDaRivedere", speseDaRivedere.size());
            available.put("media", contestoMultimediale.size());
            available.put("watchlist", watchlist.size());
            available.put("fontiInternet", datiInternet.size());
            json.put("dettagliDisponibili", available);

            putLimited(json, "categoriePrincipali", categoriePrincipali,
                    budget ? categoriePrincipali.size() : 3);
            putLimited(json, "movimentiRecenti", movimentiRecenti,
                    budget ? movimentiRecenti.size() : 3);
            putLimited(json, "speseDaRivedere", speseDaRivedere,
                    budget ? speseDaRivedere.size() : 3);
            putLimited(json, "prossimeScadenze", prossimeScadenze,
                    planned ? prossimeScadenze.size() : 3);
            putLimited(json, "obiettivi", obiettivi,
                    goals ? obiettivi.size() : 2);
            if (budget) putIfPresent(json, "conti", conti);
            if (planned) {
                putIfPresent(json, "programmate", programmate);
            }
            if (debt) putIfPresent(json, "debiti", debiti);
            if (media) putIfPresent(json, "contestoMultimediale", contestoMultimediale);
            if (investments) {
                putIfPresent(json, "watchlist", watchlist);
                putIfPresent(json, "datiInternet", datiInternet);
            }
            return json.toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static String questionFocus(String question) {
        String value = question == null ? "" : question;
        String screen = "";
        int screenAt = value.lastIndexOf("Schermata e contesto visibile:");
        if (screenAt >= 0) {
            int screenEnd = value.indexOf('\n', screenAt);
            screen = screenEnd < 0 ? value.substring(screenAt) : value.substring(screenAt, screenEnd);
        }
        int currentAt = value.lastIndexOf("DOMANDA ATTUALE:");
        if (currentAt >= 0) {
            int currentEnd = value.indexOf('\n', currentAt);
            String current = currentEnd < 0
                    ? value.substring(currentAt) : value.substring(currentAt, currentEnd);
            value = screen + " " + current;
        }
        return value.toLowerCase(Locale.ITALY);
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static void putIfPresent(JSONObject target, String name, List<String> values)
            throws org.json.JSONException {
        if (values != null && !values.isEmpty()) target.put(name, new JSONArray(values));
    }

    private static void putLimited(JSONObject target, String name, List<String> values, int limit)
            throws org.json.JSONException {
        if (values == null || values.isEmpty() || limit <= 0) return;
        JSONArray selected = new JSONArray();
        for (int index = 0; index < Math.min(limit, values.size()); index++) {
            selected.put(values.get(index));
        }
        target.put(name, selected);
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
