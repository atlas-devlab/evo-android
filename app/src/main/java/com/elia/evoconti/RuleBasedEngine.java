package com.elia.evoconti;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RuleBasedEngine implements LocalLlmEngine {
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AiAnswer ask(AiContext context, String userQuestion) {
        String fullPrompt = userQuestion == null ? "" : userQuestion;
        String q = currentQuestion(userQuestion).toLowerCase(Locale.ITALY);
        AiAnswer answer = base(context);
        boolean asksForbidden = asksForTradeInstruction(q);
        boolean hasUsefulData = context.redditoNetto > 0 &&
                (context.speseFisse > 0 || !context.movimentiRecenti.isEmpty());
        boolean freshStress = context.stressUtente >= 7 && context.checkInOre >= 0 && context.checkInOre <= 168;
        boolean debtCritical = context.debitiPct > 20 ||
                (context.interessiPassivi > context.interessiAttivi && context.interessiPassivi > 0);
        boolean debtWatch = context.debitiPct > 10;
        boolean lowEmergency = context.fondoEmergenzaMesi < 1;
        boolean lowFuture = context.futuroPct < 10;
        boolean negativeMargin = context.saldoMensile < 0;
        boolean canPlan = hasUsefulData && !debtWatch && !lowEmergency && !lowFuture &&
                !freshStress && context.saldoMensile > 0 && context.saluteBudget >= 75;

        if (fullPrompt.contains("[PERSONA:COMPAGNO]")) {
            return companionAnswer(context, q, fullPrompt, asksForbidden, freshStress);
        }

        if (asksForbidden) {
            answer.stato = "blocco prudente";
            answer.messaggioBreve = "Non scelgo acquisti o vendite: posso aiutarti a studiare rischio, costi e sostenibilita sul tuo budget.";
            answer.azioni.add("Annota costi, rischio, orizzonte e peso sul budget");
            answer.azioni.add("Escludi leva, rendimento promesso e debito per investire");
            answer.rischio = "alto";
            answer.puoInvestire = false;
            answer.motivo = "Il Custode resta quaderno, radar ed educatore";
            return answer;
        }

        if (containsAny(q, "piano 90", "prossimi 90", "novanta giorni")) {
            double monthlyStep = Math.max(0, Math.min(context.saldoMensile * .30d, context.redditoNetto * .10d));
            answer.messaggioBreve = String.format(Locale.ITALY,
                    "Piano morbido di 90 giorni: margine attuale %.2f EUR, quota protetta indicativa %.2f EUR al mese.",
                    context.saldoMensile, monthlyStep);
            answer.azioni.add("Giorni 1-30: conferma programmate, rate e spese essenziali");
            answer.azioni.add(context.debitiPct > 10
                    ? "Giorni 31-60: riduci la pressione del debito"
                    : "Giorni 31-60: costruisci liquidita senza irrigidire il mese");
            answer.azioni.add("Giorni 61-90: verifica il margine e aggiorna un solo obiettivo");
            answer.stato = context.saldoMensile < 0 ? "recupero" : "piano attivo";
            answer.rischio = context.saldoMensile < 0 || debtWatch ? "medio" : "controllato";
            answer.puoInvestire = canPlan;
            answer.motivo = "Sequenza basata su margine, programmate, debiti e obiettivi";
            return answer;
        }

        if (containsAny(q, "collega", "quadro completo", "tutto insieme", "relazione tra")) {
            answer.messaggioBreve = String.format(Locale.ITALY,
                    "Il quadro unisce margine %.2f EUR, rate %.0f%%, fondo %.1f mesi e stress %s. La priorita e %s.",
                    context.saldoMensile, context.debitiPct, context.fondoEmergenzaMesi,
                    context.stressUtente < 0 ? "non aggiornato" : String.valueOf(context.stressUtente),
                    negativeMargin ? "tornare in positivo" : debtWatch ? "alleggerire le rate" :
                            lowEmergency ? "rafforzare la liquidita" : "proteggere la continuita");
            answer.azioni.add("Controlla le scadenze dei prossimi 30 giorni prima delle spese libere");
            answer.azioni.add(freshStress ? "Rimanda oggi le decisioni non urgenti" :
                    "Mantieni una quota flessibile, senza trasformare le soglie in regole rigide");
            answer.motivo = "Lettura congiunta di budget, debiti, fondo, obiettivi e benessere";
            answer.puoInvestire = canPlan;
            return answer;
        }

        if (containsAny(q, "verifica", "fonte", "internet", "e vero") && !context.datiInternet.isEmpty()) {
            answer.messaggioBreve = "Ho aggiunto al ragionamento una fonte Internet con data e provenienza visibili. " +
                    "La confronto con il tuo budget locale senza inviare i tuoi dati.";
            answer.azioni.add("Apri Fonti Internet per controllare URL e ultimo aggiornamento");
            answer.azioni.add("Tratta il dato come informativo, non come ordine di acquisto");
            answer.motivo = context.datiInternet.get(0);
            answer.puoInvestire = canPlan;
            return answer;
        }

        if (containsAny(q, "stress", "ansia", "impuls", "agitato", "triste")) {
            answer.stato = freshStress ? "pausa protettiva" : answer.stato;
            answer.messaggioBreve = freshStress
                    ? "Lo stress e alto: rimandiamo le decisioni non necessarie e proteggiamo il margine per oggi."
                    : "Lo stato emotivo conta. Fai un check-in aggiornato prima di una spesa importante.";
            answer.azioni.add("Aspetta 20 minuti prima di una spesa non essenziale");
            answer.azioni.add("Scrivi bisogno, importo e alternativa senza costo");
            answer.puoInvestire = false;
            answer.motivo = freshStress ? "Check-in recente con stress alto" : "Serve un check-in recente";
            return answer;
        }

        if (containsAny(q, "debito", "prestito", "rata", "finanziamento", "revolving", "mutuo")) {
            if (context.debitoResiduo <= 0) {
                answer.messaggioBreve = "Non risultano debiti attivi. Prima di crearne uno confronta attesa, obiettivo e riduzione dell'acquisto.";
                answer.azioni.add("Simula la rata nel budget prima di confermare");
            } else {
                answer.messaggioBreve = String.format(Locale.ITALY,
                        "Le rate pesano %.0f%% del reddito e il residuo e %.0f EUR: evita nuovo debito finche il margine non migliora.",
                        context.debitiPct, context.debitoResiduo);
                answer.azioni.add("Dai priorita ai debiti piu costosi");
                answer.azioni.add("Registra i pagamenti per aggiornare il residuo");
            }
            answer.stato = debtCritical ? "prima debiti" : "prudenza debito";
            answer.rischio = debtCritical ? "alto" : "medio";
            answer.puoInvestire = false;
            answer.motivo = "Il debito riduce margine e resilienza";
            return answer;
        }

        if (containsAny(q, "inut", "superflu", "spreco", "spese evitabili",
                "da rivedere", "cosa posso tagliare", "ridurre le spese")) {
            boolean pressured = negativeMargin || context.desideriPct > 30 ||
                    context.speseImpulsive >= 3 || debtWatch;
            answer.stato = pressured ? "revisione prioritaria" : "verifica guidata";
            answer.rischio = pressured ? "medio" : "controllato";
            answer.puoInvestire = canPlan && !pressured;
            if (!hasUsefulData) {
                answer.messaggioBreve = "Non etichetto una spesa come inutile senza entrate, uscite e ricorrenze sufficienti.";
                answer.azioni.add("Completa entrate, movimenti e spese programmate");
                answer.motivo = "Il quadro economico e ancora incompleto";
            } else if (context.speseDaRivedere.isEmpty()) {
                answer.messaggioBreve = String.format(Locale.ITALY,
                        "Non vedo ancora candidati chiari. I desideri pesano %.0f%% e il margine e %.2f EUR: decidiamo insieme cosa e davvero flessibile.",
                        context.desideriPct, context.saldoMensile);
                answer.azioni.add("Classifica la categoria piu pesante come essenziale o flessibile");
                answer.azioni.add("Controlla le ricorrenze prima di tagliare spese utili");
                answer.motivo = "Nessun verdetto automatico senza evidenza sufficiente";
            } else {
                answer.messaggioBreve = String.format(Locale.ITALY,
                        "Ho trovato %d spese da verificare, non automaticamente inutili. Margine %.2f EUR, desideri %.0f%%, episodi impulsivi %d.",
                        context.speseDaRivedere.size(), context.saldoMensile,
                        context.desideriPct, context.speseImpulsive);
                answer.azioni.add("Rivedi: " + context.speseDaRivedere.get(0));
                if (context.speseDaRivedere.size() > 1) {
                    answer.azioni.add("Confronta: " + context.speseDaRivedere.get(1));
                }
                answer.motivo = "Candidati ricavati da ricorrenze, categorie e movimenti nel quadro completo";
            }
            return answer;
        }

        if (containsAny(q, "spesa", "comprare", "acquisto", "permettermi", "budget")) {
            double askedAmount = extractAmount(q);
            if (!hasUsefulData) {
                answer.messaggioBreve = "Mi servono almeno un'entrata e alcune uscite reali per valutare una spesa senza inventare numeri.";
                answer.azioni.add("Completa entrata e spese programmate");
            } else if (negativeMargin) {
                answer.messaggioBreve = String.format(Locale.ITALY,
                        "Il margine mensile e negativo di circa %.0f EUR: oggi limita le spese non essenziali.",
                        Math.abs(context.saldoMensile));
                answer.azioni.add("Controlla prima la categoria piu pesante");
            } else if (askedAmount > 0) {
                boolean monthly = containsAny(q, "al mese", "mensile", "ogni mese", "abbonamento", "rata");
                double after = context.saldoMensile - askedAmount;
                double impact90 = askedAmount * (monthly ? 3d : 1d);
                double protectedMargin = Math.max(context.redditoNetto * 0.05d, context.speseFisse * 0.08d);
                answer.messaggioBreve = String.format(Locale.ITALY,
                        "Una spesa di %.2f EUR lascerebbe %.2f EUR nel mese e pesa %.2f EUR nei prossimi 90 giorni%s.",
                        askedAmount, after, impact90, monthly ? " se ricorrente" : "");
                if (after < 0) answer.azioni.add("Rinvia o riduci di almeno " + euro(Math.abs(after) + protectedMargin));
                else if (after < protectedMargin) answer.azioni.add("Proteggi almeno " + euro(protectedMargin) + " di margine");
                else answer.azioni.add("Confronta l'acquisto con la prossima scadenza registrata");
                answer.azioni.add(monthly ? "Simula la stessa uscita per tre mesi" : "Attendi 24 ore se non e essenziale");
                answer.stato = after < protectedMargin ? "prudenza" : "sostenibile con margine";
                answer.rischio = after < 0 ? "alto" : after < protectedMargin ? "medio" : "controllato";
            } else {
                double weeklyMargin = Math.max(0, context.saldoMensile / 4.33d);
                double pauseThreshold = Math.max(10, Math.min(weeklyMargin * 0.35d, context.redditoNetto * 0.04d));
                answer.messaggioBreve = String.format(Locale.ITALY,
                        "Il margine stimato e %.2f EUR, circa %.2f EUR a settimana. Sopra %.2f EUR chiederei importo e priorita.",
                        context.saldoMensile, weeklyMargin, pauseThreshold);
                answer.azioni.add("Segna la spesa come essenziale o desiderio");
                answer.azioni.add("Controlla le programmate dei prossimi giorni");
            }
            answer.motivo = "Valutazione sul margine mensile reale";
            return answer;
        }

        if (containsAny(q, "obiettivo", "risparm", "fondo emergenza", "emergenza")) {
            double suggested = Math.max(0, Math.min(context.saldoMensile * 0.35d, context.redditoNetto * 0.10d));
            answer.messaggioBreve = context.obiettiviAttivi == 0
                    ? "Crea un primo obiettivo piccolo e misurabile; il fondo emergenza viene prima della crescita."
                    : String.format(Locale.ITALY,
                    "Hai %d obiettivi attivi e %.1f mesi di fondo. Una quota morbida e %.2f EUR/mese, %.2f EUR in 90 giorni.",
                    context.obiettiviAttivi, context.fondoEmergenzaMesi, suggested, suggested * 3d);
            if (context.fondoEmergenzaMesi < 1) answer.azioni.add("Porta gradualmente il fondo verso un mese di essenziali");
            if (context.obiettiviInRitardo > 0) answer.azioni.add("Riduci la quota invece di abbandonare l'obiettivo");
            answer.motivo = "Priorita a liquidita e continuita";
            return answer;
        }

        if (containsAny(q, "crypto", "bitcoin", "ethereum")) {
            answer.messaggioBreve = "Crypto resta ad alto rischio: studiala in watchlist e non finanziarla con debito.";
            answer.azioni.add("Annota volatilita, custodia, costi e rischio frodi");
        } else if (containsAny(q, "etf")) {
            answer.messaggioBreve = "Un ETF puo diversificare ma non elimina il rischio.";
            answer.azioni.add("Annota indice, costi, replica, area, valuta e orizzonte");
        } else if (asksAboutStock(q)) {
            answer.messaggioBreve = "Una singola azione concentra il rischio specifico dell'impresa e del settore.";
            answer.azioni.add("Studia bilancio, settore, volatilita e peso massimo");
        }

        if (debtCritical || negativeMargin) {
            answer.stato = "prima debiti";
            answer.rischio = "medio-alto";
            answer.puoInvestire = false;
            answer.motivo = negativeMargin ? "Saldo mensile negativo" : "Debiti o interessi passivi sopra soglia";
            if (answer.messaggioBreve.isEmpty()) {
                answer.messaggioBreve = "Questo mese non aumenterei gli investimenti: prima proteggi saldo, debiti e liquidita.";
            }
            answer.azioni.add("Non aumentare la quota investimento questo mese");
            answer.azioni.add("Riduci la pressione delle rate piu costose");
        } else if (!hasUsefulData) {
            answer.stato = "da configurare";
            answer.rischio = "non valutabile";
            answer.puoInvestire = false;
            answer.motivo = "Dati del budget ancora incompleti";
            if (answer.messaggioBreve.isEmpty()) {
                answer.messaggioBreve = "Completa entrate, programmate e fondo emergenza: non voglio riempire i vuoti con supposizioni.";
            }
            answer.azioni.add("Inserisci entrata e spese programmate");
            answer.azioni.add("Aggiungi debiti e interessi, se presenti");
        } else if (lowEmergency || lowFuture || freshStress || debtWatch) {
            answer.stato = "prudenza";
            answer.rischio = "medio";
            answer.puoInvestire = false;
            answer.motivo = reason(lowEmergency, lowFuture, freshStress, debtWatch);
            if (answer.messaggioBreve.isEmpty()) {
                answer.messaggioBreve = "Prima proteggi margine e liquidita; il benchmark e una guida morbida, non una punizione.";
            }
            if (lowEmergency) answer.azioni.add("Fai crescere gradualmente il fondo emergenza");
            if (debtWatch) answer.azioni.add("Evita nuove rate finche il peso debiti non scende");
            if (freshStress) answer.azioni.add("Rimanda decisioni finanziarie non urgenti");
        } else if (canPlan) {
            answer.stato = "ok studio";
            answer.rischio = "controllato";
            answer.puoInvestire = true;
            answer.motivo = "Margine positivo, debiti bassi e budget stabile";
            if (answer.messaggioBreve.isEmpty()) {
                answer.messaggioBreve = "Hai margine positivo e debiti bassi: puoi pianificare una piccola quota studio/investimento.";
            }
            answer.azioni.add("Definisci una quota piccola e sostenibile");
            answer.azioni.add("Non usare il fondo emergenza");
            answer.azioni.add("Annota motivazione e orizzonte");
        }
        if (answer.messaggioBreve.isEmpty()) {
            double weekly = Math.max(0, context.saldoMensile / 4.33d);
            answer.messaggioBreve = String.format(Locale.ITALY,
                    "Salute budget %d/100, margine %.2f EUR e disponibilita indicativa %.2f EUR/settimana. Le soglie restano flessibili.",
                    context.saluteBudget, context.saldoMensile, weekly);
            answer.azioni.add("Controlla le programmate prima di una nuova decisione");
            answer.motivo = "Lettura prudente dei dati disponibili";
        }
        return answer;
    }

    private AiAnswer base(AiContext context) {
        AiAnswer answer = new AiAnswer();
        answer.stato = context.saluteBudget >= 75 ? "stabile" : "prudenza";
        answer.rischio = context.saluteBudget >= 75 ? "controllato" : "medio";
        answer.effettoAvatar = context.statoSalute == null ? "stabile" : context.statoSalute.toLowerCase(Locale.ITALY);
        answer.motore = "regole locali verificate";
        return answer;
    }

    private boolean asksForTradeInstruction(String q) {
        return containsAny(q, "cosa compro", "cosa comprare", "quale etf", "quale azione", "compra questo",
                "vendi questo", "investi tutto", "fai leva", "rendimento garantito", "crypto sicura",
                "prestito per investire", "debito per investire", "consigliami un", "dove investire",
                "miglior etf", "migliore etf", "miglior azione", "devo comprare", "conviene comprare");
    }

    private boolean asksAboutStock(String q) {
        if (containsAny(q, "azione singola", "azioni singole", "titolo azionario",
                "titoli azionari", "azioni di borsa", "stock")) return true;
        return containsAny(q, "azione", "azioni", "titolo")
                && containsAny(q, "borsa", "mercato", "portafoglio", "ticker",
                "invest", "comprare", "vendere");
    }

    private String reason(boolean lowEmergency, boolean lowFuture, boolean stress, boolean debt) {
        if (debt) return "Rate debiti in zona attenzione";
        if (lowEmergency) return "Fondo emergenza ancora basso";
        if (lowFuture) return "Margine futuro sotto la zona guida";
        if (stress) return "Stress recente alto";
        return "Prudenza sul budget";
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private double extractAmount(String text) {
        String source = text == null ? "" : text;
        Matcher explicit = Pattern.compile(
                "([0-9]{1,7}(?:[.,][0-9]{1,2})?)\\s*(?:eur|euro|\\x{20AC})")
                .matcher(source);
        double amount = firstValidAmount(explicit, source);
        if (amount > 0) return amount;
        Matcher prefixed = Pattern.compile(
                "\\x{20AC}\\s*([0-9]{1,7}(?:[.,][0-9]{1,2})?)")
                .matcher(source);
        amount = firstValidAmount(prefixed, source);
        if (amount > 0) return amount;
        Matcher described = Pattern.compile(
                "(?:costa|costerebbe|spesa|acquisto|comprare|spendere|abbonamento|rata|pagare)" +
                        "[^0-9]{0,28}([0-9]{1,7}(?:[.,][0-9]{1,2})?)")
                .matcher(source);
        return firstValidAmount(described, source);
    }

    private double firstValidAmount(Matcher matcher, String source) {
        while (matcher.find()) {
            String suffix = source.substring(matcher.end()).trim();
            if (suffix.matches("^(?:giorn|mes|ann|ore|minut|%|percento).*")) continue;
            try {
                double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private AiAnswer companionAnswer(AiContext context, String q, String prompt,
                                     boolean asksForbidden, boolean freshStress) {
        AiAnswer answer = base(context);
        answer.motore = "compagno locale con memoria";
        answer.puoInvestire = false;
        if (asksForbidden) {
            answer.stato = "ti proteggo";
            answer.messaggioBreve = "Non scelgo cosa comprare o vendere. Posso restare con te mentre confrontiamo rischio, costi e impatto sul nostro budget.";
            answer.azioni.add("Studiamo una scelta alla volta, senza debito o leva");
            answer.motivo = "Sono un compagno prudente, non un venditore";
            return answer;
        }
        if (containsAny(q, "ricordi", "ricorda", "memoria", "detto")) {
            String memory = firstPromptMemory(prompt);
            answer.messaggioBreve = memory.isEmpty()
                    ? "Non ho ancora una memoria precisa su questo. Dimmi 'ricorda che...' e la conservero solo sul telefono."
                    : "Mi ricordo: " + memory;
            answer.azioni.add("Possiamo aggiornare insieme questo ricordo quando cambia");
            answer.motivo = memory.isEmpty() ? "Nessuna memoria pertinente" : "Memoria personale del Compagno";
            return answer;
        }
        if (freshStress || containsAny(q, "stress", "ansia", "triste", "paura")) {
            answer.messaggioBreve = "Ti ascolto. Oggi non dobbiamo risolvere tutto: proteggiamo una sola scelta e lasciamo ferme quelle non urgenti.";
            answer.azioni.add("Scegliamo insieme la prossima scadenza davvero importante");
            answer.motivo = "Il tuo benessere viene prima della velocita";
            return answer;
        }
        answer.messaggioBreve = String.format(Locale.ITALY,
                "Io mi sento %s: il nostro budget e a %d/100 e il margine stimato e %.2f EUR. Possiamo migliorare con un passo piccolo e reale.",
                context.statoSalute == null ? "stabile" : context.statoSalute.toLowerCase(Locale.ITALY),
                context.saluteBudget, context.saldoMensile);
        answer.azioni.add(context.saldoMensile < 0
                ? "Guardiamo insieme la prossima uscita programmata"
                : "Proteggiamo una parte del margine per il tuo obiettivo");
        answer.motivo = "Reazione del Compagno ai dati attuali dell'app";
        return answer;
    }

    private String firstPromptMemory(String prompt) {
        int section = prompt.indexOf("MEMORIE PERSISTENTI RILEVANTI:");
        if (section < 0) return "";
        int line = prompt.indexOf("- [", section);
        if (line < 0) return "";
        int close = prompt.indexOf("] ", line);
        if (close < 0) return "";
        int end = prompt.indexOf('\n', close + 2);
        String value = end < 0 ? prompt.substring(close + 2) : prompt.substring(close + 2, end);
        return value.trim();
    }

    private String euro(double value) {
        return String.format(Locale.ITALY, "%.2f EUR", Math.max(0, value));
    }

    private String currentQuestion(String prompt) {
        String value = prompt == null ? "" : prompt;
        int marker = value.lastIndexOf("DOMANDA ATTUALE:");
        if (marker < 0) return value;
        int start = marker + "DOMANDA ATTUALE:".length();
        int end = value.indexOf('\n', start);
        return (end < 0 ? value.substring(start) : value.substring(start, end)).trim();
    }
}
