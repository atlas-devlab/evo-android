package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BudgetHealthCalculatorTest {
    @Test
    public void exactHealthBandsMatchProductRules() {
        assertEquals("Fortificato", BudgetHealthCalculator.levelForScore(90));
        assertEquals("Sano", BudgetHealthCalculator.levelForScore(75));
        assertEquals("Stabile", BudgetHealthCalculator.levelForScore(60));
        assertEquals("Affaticato", BudgetHealthCalculator.levelForScore(45));
        assertEquals("Malato lieve", BudgetHealthCalculator.levelForScore(30));
        assertEquals("Malato serio", BudgetHealthCalculator.levelForScore(15));
        assertEquals("Critico", BudgetHealthCalculator.levelForScore(14));
    }

    @Test
    public void setupModeDoesNotPunishAnEmptyNotebook() {
        BudgetHealthCalculator.Input input = new BudgetHealthCalculator.Input();
        input.setupMode = true;

        BudgetHealthCalculator.Result result = new BudgetHealthCalculator().calculate(input);

        assertEquals(70, result.score);
        assertEquals("Da configurare", result.level);
        assertEquals("Inserire entrata programmata", result.mainTask);
    }

    @Test
    public void strongBudgetFortifiesWhileDebtAndNegativeMarginMakeItCritical() {
        BudgetHealthCalculator.Input strong = new BudgetHealthCalculator.Input();
        strong.income = 2_000;
        strong.margin = 500;
        strong.essentialRatio = .45;
        strong.wantsRatio = .20;
        strong.futureRatio = .25;
        strong.debtRatio = .05;
        strong.emergencyFundMonths = 3;
        strong.averageGoalProgress = .5;

        BudgetHealthCalculator.Input weak = new BudgetHealthCalculator.Input();
        weak.income = 2_000;
        weak.margin = -500;
        weak.essentialRatio = .80;
        weak.wantsRatio = .40;
        weak.futureRatio = 0;
        weak.debtRatio = .30;
        weak.debtRemaining = 30_000;
        weak.emergencyFundMonths = 0;
        weak.impulseCount = 5;
        weak.stress = 9;
        weak.energy = 2;

        BudgetHealthCalculator calculator = new BudgetHealthCalculator();
        BudgetHealthCalculator.Result healthy = calculator.calculate(strong);
        BudgetHealthCalculator.Result fragile = calculator.calculate(weak);

        assertEquals("Fortificato", healthy.level);
        assertTrue(fragile.score < 15);
        assertEquals("Critico", fragile.level);
        assertEquals("Riportare il saldo mensile positivo", fragile.mainTask);
    }
}
