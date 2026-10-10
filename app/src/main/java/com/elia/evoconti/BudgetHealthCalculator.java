package com.elia.evoconti;

final class BudgetHealthCalculator {
    static final class Input {
        boolean setupMode;
        double income;
        double margin;
        double essentialRatio;
        double wantsRatio;
        double futureRatio;
        double debtRatio;
        double debtRemaining;
        double emergencyFundMonths;
        double averageGoalProgress;
        int completedGoals;
        int impulseCount;
        int stress = -1;
        int energy = -1;
        boolean positiveMood;
    }

    static final class Result {
        int score;
        String level;
        String avatarState;
        String mainTask;
    }

    Result calculate(Input input) {
        Result result = new Result();
        if (input.setupMode) {
            result.score = 70;
            result.level = "Da configurare";
            result.avatarState = "in attesa";
            result.mainTask = "Inserire entrata programmata";
            return result;
        }

        double score = 55;
        double marginRatio = input.income <= 0 ? (input.margin < 0 ? -1 : 0) : input.margin / input.income;
        score += clamp(marginRatio * 60, -24, 12);

        if (input.essentialRatio <= 0.50) score += 8;
        else score -= clamp((input.essentialRatio - 0.50) * 45, 0, 20);

        if (input.wantsRatio <= 0.30) score += 5;
        else score -= clamp((input.wantsRatio - 0.30) * 35, 0, 12);

        if (input.futureRatio >= 0.20) score += 8;
        else if (input.futureRatio >= 0.10) score += 3;
        else score -= clamp((0.10 - input.futureRatio) * 70, 0, 9);

        if (input.debtRatio <= 0.10) score += 5;
        else if (input.debtRatio <= 0.20) score -= (input.debtRatio - 0.10) * 80;
        else score -= 8 + clamp((input.debtRatio - 0.20) * 70, 0, 16);

        if (input.emergencyFundMonths >= 3) score += 8;
        else if (input.emergencyFundMonths >= 1) score += 3 + (input.emergencyFundMonths - 1) * 2.5;
        else score -= (1 - Math.max(0, input.emergencyFundMonths)) * 8;

        if (input.income > 0 && input.debtRemaining > input.income * 12) score -= 15;
        else if (input.income > 0 && input.debtRemaining > input.income * 6) score -= 10;
        else if (input.income > 0 && input.debtRemaining > input.income * 3) score -= 5;

        score -= Math.min(10, input.impulseCount * 2);
        score += clamp(input.averageGoalProgress * 8, 0, 8);
        score += Math.min(6, input.completedGoals * 2);

        if (input.stress >= 8) score -= 7;
        else if (input.stress >= 6) score -= 3;
        if (input.energy >= 0 && input.energy <= 2) score -= 5;
        else if (input.energy >= 0 && input.energy <= 4) score -= 2;
        if (input.positiveMood) score += 3;

        result.score = (int) Math.round(clamp(score, 0, 100));
        mapState(result);
        result.mainTask = chooseTask(input, result.score);
        return result;
    }

    private void mapState(Result result) {
        result.level = levelForScore(result.score);
        if (result.score >= 90) {
            result.avatarState = "aura forte";
        } else if (result.score >= 75) {
            result.avatarState = "in forma";
        } else if (result.score >= 60) {
            result.avatarState = "stabile";
        } else if (result.score >= 45) {
            result.avatarState = "stanco";
        } else if (result.score >= 30) {
            result.avatarState = "malattia lieve";
        } else if (result.score >= 15) {
            result.avatarState = "malattia seria";
        } else {
            result.avatarState = "malattia grave";
        }
    }

    static String levelForScore(int score) {
        if (score >= 90) return "Fortificato";
        if (score >= 75) return "Sano";
        if (score >= 60) return "Stabile";
        if (score >= 45) return "Affaticato";
        if (score >= 30) return "Malato lieve";
        if (score >= 15) return "Malato serio";
        return "Critico";
    }

    private String chooseTask(Input input, int score) {
        if (input.income <= 0) return "Inserire entrata programmata";
        if (input.margin < 0) return "Riportare il saldo mensile positivo";
        if (input.debtRatio > 0.20) return "Ridurre rischio debito";
        if (input.essentialRatio > 0.60) return "Rivedere una spesa essenziale o fissa";
        if (input.emergencyFundMonths < 1) return "Costruire il primo mese di emergenza";
        if (input.futureRatio < 0.10) return "Ricostruire margine futuro";
        if (input.stress >= 7) return "Pausa anti-acquisto impulsivo";
        if (score >= 90) return "Fortificare obiettivi";
        return "Monitorare budget senza irrigidirlo";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
