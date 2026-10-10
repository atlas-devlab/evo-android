package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.ContentValues;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class EvoDbInstrumentedTest {
    private Context context;
    private EvoDb db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("evoconti.db");
        db = new EvoDb(context);
        db.getWritableDatabase();
    }

    @After
    public void tearDown() {
        db.close();
        context.deleteDatabase("evoconti.db");
    }

    @Test
    public void accountBalanceFollowsIncomeAndExpense() {
        db.addAccount("Test", "Conto", 100);
        db.addTransaction("income", "Entrata", 50, "Entrate", "Test", "", "", "", 0, false, false, "", "", "");
        db.addTransaction("expense", "Spesa", 20, "Casa", "Test", "", "", "", 0, false, false, "", "", "");

        assertEquals(130, db.accountTotals().get("Test"), .001);
    }

    @Test
    public void plannedIncomeAndRecordedIncomeAreNotDoubleCounted() {
        long now = System.currentTimeMillis();
        db.addRecurring("income", "Stipendio", 1_000, "Entrate", "Conto corrente", now - 1_000, now + 100_000);
        db.addTransaction("income", "Stipendio", 1_000, "Entrate", "Conto corrente", "", "", "", 0, false, false, "", "", "");

        assertEquals(1_000, db.monthlyIncome(), .001);
    }

    @Test
    public void budgetCategoryChartIncludesRealPlannedAndDebtPressure() {
        long now = System.currentTimeMillis();
        db.addTransaction("expense", "Spesa reale", 20, "Casa", "Conto corrente", "", "", "", 0,
                false, false, "", "", "");
        db.addRecurring("fixed", "Affitto test", 30, "Casa", "Conto corrente", now - 1_000, now + 100_000);
        db.addDebt("Rata test", 100, 100, 10, 3, now - 1_000, 0,
                "Debiti", "prestito personale", "");

        assertEquals(50, db.budgetCategoryTotals().get("Casa"), .001);
        assertEquals(10, db.budgetCategoryTotals().get("Debiti"), .001);
    }

    @Test
    public void budgetAccountChartProjectsOnlyActiveProgrammedEntries() {
        long now = System.currentTimeMillis();
        db.addAccount("Proiezione", "Conto", 100);
        db.addRecurring("income", "Entrata attiva", 50, "Entrate", "Proiezione", now - 1_000, now + 100_000);
        db.addRecurring("fixed", "Uscita attiva", 20, "Casa", "Proiezione", now - 1_000, now + 100_000);
        db.addRecurring("fixed", "Uscita futura", 90, "Casa", "Proiezione", now + 100_000, 0);

        assertEquals(130, db.budgetAccountTotals().get("Proiezione"), .001);
    }

    @Test
    public void futureAndPaidDebtsDoNotWeighOnCurrentMonth() {
        long now = System.currentTimeMillis();
        db.addDebt("Futuro", 1_000, 1_000, 100, 5, now + 86_400_000L, 0);
        assertEquals(0, db.monthlyDebtPayments(), .001);

        db.addDebt("Attivo", 500, 500, 50, 4, now - 1_000, 0);
        EvoDb.Debt active = null;
        for (EvoDb.Debt debt : db.debts()) if ("Attivo".equals(debt.name)) active = debt;
        assertNotNull(active);
        assertEquals(50, db.monthlyDebtPayments(), .001);

        db.payDebt(active.id, 500);
        assertEquals(0, db.monthlyDebtPayments(), .001);
    }

    @Test
    public void oldCollapsedGoalIsRecoveredAndHealthStaysUnderCustodianControl() {
        long id = db.addGoal("Emergenza", "Fondo emergenza", "Casa", 1_000, 12, 0);
        ContentValues broken = new ContentValues();
        broken.put("status", "collapsed");
        broken.put("hp", 0);
        db.getWritableDatabase().update("goals", broken, "id=?", new String[]{String.valueOf(id)});

        db.syncLocalAiSteward();

        EvoDb.Goal recovered = db.goal(id);
        assertEquals("active", recovered.status);
        assertEquals(db.localAiReport().avatarHp, recovered.hp);
    }

    @Test
    public void ecbConnectorCachesActualRatesAndFxValues() {
        String rates = "PROVIDER_FM_ID,TIME_PERIOD,OBS_VALUE,TITLE\n" +
                "DFR,2026-06-17,2.25,Deposit facility\n" +
                "MRR_FR,2026-06-17,2.40,Main refinancing\n" +
                "MLFR,2026-06-17,2.65,Marginal lending\n";
        String fx = "<Cube time='2026-07-15'><Cube currency='USD' rate='1.1600'/>" +
                "<Cube currency='GBP' rate='0.8700'/></Cube>";
        InternetFinanceConnector connector = new InternetFinanceConnector();

        assertEquals(3, connector.parseAndCacheRates(rates, db));
        assertEquals(2, connector.parseAndCacheFx(fx, db));
        assertEquals(5, db.financeCacheRows(10).size());
        assertEquals("BCE ufficiale", db.financeCacheRows(10).get(0).reliability);
    }

    @Test
    public void shoppingCatalogLearnsProductAndStorePrice() {
        EvoDb.Product product = new EvoDb.Product();
        product.barcode = "8000000000001";
        product.name = "Pasta test";
        product.brand = "Marca";
        product.quantityLabel = "500 g";
        product.category = "Alimentare";
        product.source = "test";
        db.upsertProduct(product);
        db.rememberPrice(product.barcode, "Coop Centro", 1.89, "cartellino OCR", 1, System.currentTimeMillis());

        assertEquals("Pasta test", db.product(product.barcode).name);
        assertEquals(1.89, db.bestStorePrice(product.barcode, "coop-centro").price, .001);
    }

    @Test
    public void shoppingRunKeepsProductsSeparateAndUpdatesAccountOnce() {
        db.addAccount("Spesa test", "Carta", 100);
        long session = db.createShoppingSession("Market", "Spesa test");
        long pasta = db.addOrIncrementShoppingItem(session, "8000000000001", "Pasta", "", 1.50,
                "cartellino OCR", true, "Alimentare");
        db.addOrIncrementShoppingItem(session, "8000000000001", "Pasta", "", 1.50,
                "cartellino OCR", true, "Alimentare");
        db.addOrIncrementShoppingItem(session, "8000000000002", "Latte", "", 2.00,
                "manuale", true, "Alimentare");

        assertEquals(2, db.shoppingItem(pasta).quantity);
        assertEquals(5.00, db.shoppingSummary(session).total, .001);
        assertEquals(5.00, db.completeShoppingSession(session, "Market", "Spesa test"), .001);
        assertEquals(95.00, db.accountTotals().get("Spesa test"), .001);
        assertEquals(2, db.recentTransactions(10).size());
        assertEquals("completed", db.shoppingSession(session).status);
    }

    @Test
    public void shoppingRunDoesNotSaveAnInventedTotalWhenPriceIsMissing() {
        long session = db.createShoppingSession("Market", "Carta principale");
        db.addOrIncrementShoppingItem(session, "8000000000003", "Prodotto senza prezzo", "", 0,
                "mancante", false, "Alimentare");

        assertEquals(1, db.shoppingSummary(session).missingPrices);
        assertEquals(-1, db.completeShoppingSession(session, "Market", "Carta principale"), .001);
        assertEquals("open", db.shoppingSession(session).status);
        assertEquals(0, db.recentTransactions(10).size());
    }

    @Test
    public void shoppingRunCanPauseAndResumeWithItsSummaryIntact() {
        long session = db.createShoppingSession("Market iniziale", "Carta principale");
        db.addOrIncrementShoppingItem(session, "8000000000005", "Riso", "", 1.20,
                "Open Prices", false, "Alimentare");
        db.addOrIncrementShoppingItem(session, "8000000000005", "Riso", "", 1.20,
                "Open Prices", false, "Alimentare");
        db.updateShoppingSession(session, "Conad Centro", "Carta principale");

        EvoDb.ShoppingSession resumed = db.latestOpenShoppingSession();
        EvoDb.ShoppingSummary summary = db.shoppingSummary(resumed.id);

        assertEquals(session, resumed.id);
        assertEquals("Conad Centro", resumed.storeName);
        assertEquals(2, summary.quantity);
        assertEquals(2.40, summary.total, .001);
        assertEquals(1, summary.estimatedPrices);
    }

    @Test
    public void completingTheSameShoppingRunTwiceDoesNotChargeTwice() {
        db.addAccount("Carta test", "Carta", 50);
        long session = db.createShoppingSession("Market", "Carta test");
        db.addOrIncrementShoppingItem(session, "8000000000006", "Caffe", "", 4.00,
                "manuale", true, "Alimentare");

        assertEquals(4.00, db.completeShoppingSession(session, "Market", "Carta test"), .001);
        assertEquals(0, db.completeShoppingSession(session, "Market", "Carta test"), .001);
        assertEquals(46.00, db.accountTotals().get("Carta test"), .001);
        assertEquals(1, db.recentTransactions(10).size());
    }

    @Test
    public void custodianAndEachCompanionKeepSeparatePersistentMemory() {
        long custodianMessage = db.addAiMessage("custodian", "user", "Spese", "Spese,Debiti",
                "Ricorda che voglio evitare nuove rate", "Agenda");
        db.rememberAi("custodian", "preferenza", "Debiti", "Voglio evitare nuove rate", custodianMessage);
        db.addAiMessage("companion_1", "user", "Compagno", "Compagno",
                "Mi piace risparmiare per il mare", "Cabina");
        long companionMessage = db.addAiMessage("companion_2", "user", "Compagno", "Compagno",
                "Il mio obiettivo e una bici", "Cabina");
        db.rememberAi("companion_2", "obiettivo", "Compagno", "Voglio una bici", companionMessage);

        assertEquals(1, db.aiMessageCount("custodian"));
        assertEquals(1, db.aiMessageCount("companion_1"));
        assertEquals(1, db.aiMessageCount("companion_2"));
        assertEquals("Voglio evitare nuove rate", db.aiMemories("custodian", 10).get(0).summary);
        assertEquals("Voglio una bici", db.aiMemories("companion_2", 10).get(0).summary);
        assertTrueEmpty(db.aiMemories("companion_1", 10));

        db.clearAiConversation("companion_2");
        assertEquals(0, db.aiMessageCount("companion_2"));
        assertEquals(1, db.aiMemories("companion_2", 10).size());
    }

    @Test
    public void recurringTimeAppearsOnTheCorrectCalendarDay() {
        Calendar start = Calendar.getInstance();
        start.set(2026, Calendar.JULY, 5, 0, 0, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = Calendar.getInstance();
        end.set(2026, Calendar.SEPTEMBER, 30, 23, 59, 0);
        Calendar monthStart = Calendar.getInstance();
        monthStart.set(2026, Calendar.JULY, 1, 0, 0, 0);
        monthStart.set(Calendar.MILLISECOND, 0);
        Calendar monthEnd = (Calendar) monthStart.clone();
        monthEnd.add(Calendar.MONTH, 1);

        db.addRecurring("fixed", "Affitto con orario", 720, "Casa", "Conto corrente",
                start.getTimeInMillis(), end.getTimeInMillis(), 14 * 60 + 45);

        EvoDb.Recurring saved = db.recurring().get(0);
        assertEquals(14 * 60 + 45, saved.timeMinutes);
        List<EvoDb.CalendarItem> items = db.calendarItems(
                monthStart.getTimeInMillis(), monthEnd.getTimeInMillis());
        EvoDb.CalendarItem due = null;
        for (EvoDb.CalendarItem item : items) if ("Affitto con orario".equals(item.title)) due = item;
        assertNotNull(due);
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(due.whenAt);
        assertEquals(5, when.get(Calendar.DAY_OF_MONTH));
        assertEquals(14, when.get(Calendar.HOUR_OF_DAY));
        assertEquals(45, when.get(Calendar.MINUTE));
        assertEquals("planned_expense", due.kind);
    }

    @Test
    public void avatarPlacesResolveLocallyWithoutLeakingCoordinates() {
        db.saveAvatarPlace("Parco vicino", AvatarContextResolver.PLACE_NATURE,
                45.46420, 9.19000, 250);

        EvoDb.AvatarPlace nearby = db.nearestAvatarPlace(45.46425, 9.19004, 2_000);
        assertNotNull(nearby);
        assertEquals("Parco vicino", nearby.label);
        assertEquals(AvatarContextResolver.PLACE_NATURE, nearby.placeType);
        assertNull(db.nearestAvatarPlace(45.50, 9.25, 500));
    }

    @Test
    public void avatarContextUsesTimeAndPlaceInsteadOfTapCycling() {
        AvatarContextResolver resolver = new AvatarContextResolver();
        AvatarContextResolver.Result lunch = resolver.resolve(13, 0,
                AvatarContextResolver.PLACE_NATURE, false);
        AvatarContextResolver.Result night = resolver.resolve(23, 15,
                AvatarContextResolver.PLACE_HOME, false);

        assertEquals(CreatureView.ENV_NATURE, lunch.environment);
        assertEquals(CreatureView.SCENE_EAT, lunch.activity);
        assertEquals(CreatureView.SCENE_SLEEP, night.activity);
        assertFalse(lunch.activityLabel.isEmpty());
    }

    private static void assertTrueEmpty(List<?> rows) {
        assertEquals(0, rows.size());
    }
}
