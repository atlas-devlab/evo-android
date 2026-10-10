package com.elia.evoconti;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LocalInvestmentCustodian {
    private static final long CHECK_IN_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    private final EvoDb db;
    private final RuleBasedEngine rules = new RuleBasedEngine();
    private final LlamaCppEngine llama;

    LocalInvestmentCustodian(Context context, EvoDb db) {
        this.db = db;
        this.llama = new LlamaCppEngine(context);
    }

    AiContext context() {
        EvoDb.LocalAiReport report = db.localAiReport();
        AiContext c = new AiContext();
        c.saluteBudget = report.score;
        c.statoSalute = report.level;
        c.redditoNetto = report.income;
        c.speseFisse = report.fixed;
        c.usciteEssenzialiPct = report.essentialRatio * 100d;
        c.desideriPct = report.wantsRatio * 100d;
        c.futuroPct = report.futureRatio * 100d;
        c.debitiPct = report.debtRatio * 100d;
        c.debitoResiduo = report.debtRemaining;
        c.saldoMensile = report.margin;
        c.fondoEmergenzaMesi = report.emergencyFundMonths;
        c.speseImpulsive = report.impulseCount;

        EvoDb.CheckIn checkIn = db.latestCheckIn();
        if (checkIn != null) {
            long age = Math.max(0, System.currentTimeMillis() - checkIn.createdAt);
            c.checkInOre = age / (60L * 60L * 1000L);
            if (age <= CHECK_IN_MAX_AGE_MS) {
                c.stressUtente = checkIn.stress;
                c.energiaUtente = checkIn.energy;
            }
        }

        List<EvoDb.Goal> goals = db.goals();
        for (EvoDb.Goal g : goals) {
            double progress = g.target <= 0 ? 1 : Math.max(0, Math.min(1, g.saved / g.target));
            if ("active".equals(g.status)) {
                c.obiettiviAttivi++;
                if (progress < 0.15 && g.months <= 4) c.obiettiviInRitardo++;
            }
            if (c.obiettivi.size() < 6) {
                c.obiettivi.add(g.name + " | " + Math.round(progress * 100) + "% | " + g.status);
            }
        }

        c.interessiPassivi = db.passiveInterest();
        c.interessiAttivi = db.activeInterest();

        Map<String, Double> categoryTotals = db.categoryTotals();
        int categories = 0;
        for (Map.Entry<String, Double> row : categoryTotals.entrySet()) {
            c.categoriePrincipali.add(row.getKey() + " | " + format(row.getValue()) + " EUR");
            if (++categories >= 5) break;
        }

        for (Map.Entry<String, Double> row : db.accountTotals().entrySet()) {
            c.conti.add(row.getKey() + " | saldo " + format(row.getValue()) + " EUR");
            if (c.conti.size() >= 10) break;
        }

        for (EvoDb.Recurring recurring : db.recurring()) {
            String time = String.format(Locale.ITALY, "%02d:%02d",
                    recurring.timeMinutes / 60, recurring.timeMinutes % 60);
            c.programmate.add(("income".equals(recurring.type) ? "entrata" : "uscita") + " | " +
                    recurring.title + " | " + format(recurring.amount) + " EUR | " + recurring.category +
                    " | giorno " + dayOfMonth(recurring.startAt) + " ore " + time +
                    (recurring.endAt > 0 ? " | termina " + day(recurring.endAt) : " | senza fine"));
            if ("fixed".equals(recurring.type) && isReviewableCategory(recurring.category)) {
                addReviewCandidate(c, "ricorrenza da verificare | " + recurring.title + " | " +
                        format(recurring.amount) + " EUR/mese | " + recurring.category);
            }
            if (c.programmate.size() >= 16) break;
        }

        for (Map.Entry<String, Double> row : categoryTotals.entrySet()) {
            if (!isReviewableCategory(row.getKey()) || row.getValue() <= 0) continue;
            addReviewCandidate(c, "categoria flessibile da verificare | " + row.getKey() +
                    " | " + format(row.getValue()) + " EUR nel mese");
            if (c.speseDaRivedere.size() >= 6) break;
        }

        for (EvoDb.Debt debt : db.debts()) {
            c.debiti.add(debt.name + " | residuo " + format(debt.remaining) + " EUR | rata " +
                    format(debt.monthlyPayment) + " EUR | TAEG " + format(debt.apr) + "%");
            if (c.debiti.size() >= 12) break;
        }

        Calendar month = Calendar.getInstance();
        month.set(Calendar.DAY_OF_MONTH, 1);
        month.set(Calendar.HOUR_OF_DAY, 0);
        month.set(Calendar.MINUTE, 0);
        month.set(Calendar.SECOND, 0);
        month.set(Calendar.MILLISECOND, 0);
        long horizon = System.currentTimeMillis() + 45L * 24L * 60L * 60L * 1000L;
        for (int offset = 0; offset < 2 && c.prossimeScadenze.size() < 20; offset++) {
            long start = month.getTimeInMillis();
            month.add(Calendar.MONTH, 1);
            long end = month.getTimeInMillis();
            for (EvoDb.CalendarItem item : db.calendarItems(start, end)) {
                if (item.whenAt < System.currentTimeMillis() || item.whenAt > horizon) continue;
                c.prossimeScadenze.add(dayTime(item.whenAt) + " | " + item.kind + " | " + item.title +
                        " | " + format(item.amount) + " EUR");
                if (c.prossimeScadenze.size() >= 20) break;
            }
        }

        for (EvoDb.Tx tx : db.recentTransactions(6)) {
            c.movimentiRecenti.add(tx.type + " | " + tx.title + " | " + tx.category +
                    " | " + format(tx.amount) + " EUR");
            if ("expense".equals(tx.type) && tx.incident) {
                addReviewCandidate(c, "episodio segnalato da rivedere | " + tx.title + " | " +
                        format(tx.amount) + " EUR | " + tx.category);
            }
        }

        EvoDb.AttachmentStats stats = db.attachmentStats();
        if (stats.receipts > 0) c.contestoMultimediale.add("scontrini collegati: " + stats.receipts);
        if (stats.photos > 0) c.contestoMultimediale.add("foto prodotto collegate: " + stats.photos);
        if (stats.barcodes > 0) c.contestoMultimediale.add("barcode letti: " + stats.barcodes);
        if (stats.locations > 0) c.contestoMultimediale.add("spese con contesto luogo: " + stats.locations);
        if (stats.voices > 0) c.contestoMultimediale.add("note vocali: " + stats.voices);

        for (EvoDb.InvestmentAsset asset : db.investmentAssets("")) {
            c.watchlist.add(asset.assetType + " | " + asset.name + " | rischio " + asset.risk +
                    " | investito " + format(asset.invested));
            if (c.watchlist.size() >= 8) break;
        }
        for (EvoDb.FinanceCacheEntry entry : db.financeCacheRows(8)) {
            c.datiInternet.add(entry.dataType + " | " + entry.value + " | " + entry.source +
                    " | " + entry.reliability);
        }

        int completeness = 0;
        if (c.redditoNetto > 0) completeness += 25;
        if (!c.movimentiRecenti.isEmpty() || c.speseFisse > 0) completeness += 20;
        if (checkIn != null && c.checkInOre <= 168) completeness += 15;
        if (!c.obiettivi.isEmpty()) completeness += 15;
        if (report.debtMonthly > 0 || report.debtRemaining == 0) completeness += 10;
        if (!c.contestoMultimediale.isEmpty()) completeness += 5;
        if (!c.watchlist.isEmpty()) completeness += 5;
        if (!c.datiInternet.isEmpty()) completeness += 5;
        c.completezzaDati = Math.min(100, completeness);
        return c;
    }

    AiAnswer analyze() {
        return rules.ask(context(), "analisi prudente del mese");
    }

    AiAnswer ask(String question) {
        return ask(question, null);
    }

    AiAnswer ask(String question, AiProgressListener progressListener) {
        AiContext snapshot = context();
        AiAnswer baseline = rules.ask(snapshot, question);
        if (progressListener != null && baseline != null
                && baseline.messaggioBreve != null
                && !baseline.messaggioBreve.trim().isEmpty()) {
            progressListener.onText("Prima lettura: " + baseline.messaggioBreve
                    + " Gemma sta approfondendo...");
        }
        if (!llama.isAvailable()) return baseline;
        AiAnswer generated = llama.ask(snapshot, question, 160, progressListener);
        return CustodianSafetyGuard.merge(baseline, generated);
    }

    String engineStatus() {
        return llama.isAvailable()
                ? llama.status()
                : "Regole EvoConti attive | installa EvoCore per il dialogo profondo";
    }

    String lastEngineError() {
        return llama.lastError();
    }

    private static String format(double value) {
        return String.format(Locale.ITALY, "%.2f", value);
    }

    private static String day(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(new Date(timestamp));
    }

    private static String dayTime(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY).format(new Date(timestamp));
    }

    private static int dayOfMonth(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.DAY_OF_MONTH);
    }

    private static boolean isReviewableCategory(String category) {
        String value = category == null ? "" : category.trim().toLowerCase(Locale.ITALY);
        return !value.isEmpty() &&
                !value.equals("alimentare") &&
                !value.equals("farmacia") &&
                !value.equals("amministrativa") &&
                !value.equals("sanitaria") &&
                !value.equals("casa") &&
                !value.equals("trasporti") &&
                !value.equals("debiti") &&
                !value.equals("incidenti") &&
                !value.equals("entrate");
    }

    private static void addReviewCandidate(AiContext context, String candidate) {
        if (context.speseDaRivedere.size() >= 6 || context.speseDaRivedere.contains(candidate)) return;
        context.speseDaRivedere.add(candidate);
    }
}
