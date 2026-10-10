package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainActivityNavigationInstrumentedTest {
    @Test
    public void shellHasFiveClearDestinations() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(findDescription(activity.getWindow().getDecorView(), "Casa"));
                assertNotNull(findDescription(activity.getWindow().getDecorView(), "Agenda"));
                assertNotNull(findDescription(activity.getWindow().getDecorView(), "Registra"));
                assertNotNull(findDescription(activity.getWindow().getDecorView(), "Custode"));
                assertNotNull(findDescription(activity.getWindow().getDecorView(), "Compagno"));
            });
        }
    }

    @Test
    public void recordHubExposesOnlyPrimaryActionsFirst() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Registra"));
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue(hasText(root, "Spesa"));
                assertTrue(hasText(root, "Entrata"));
                assertTrue(hasText(root, "Programmata"));
                assertTrue(hasText(root, "Debito"));
                assertFalse(hasText(root, "Nuova spesa"));
            });
        }
    }

    @Test
    public void agendaCombinesRealPlannedAndDebtSections() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Agenda"));
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertNotNull(findDescriptionContaining(root, "Calendario."));
                assertTrue(hasText(root, "Tutte le programmate"));
                assertTrue(hasText(root, "Debiti"));
                assertTrue(hasTextContaining(root, "Oro: programmate"));
            });
        }
    }

    @Test
    public void custodianDialogueExplainsAutomaticContext() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Custode"));
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue(hasTextContaining(root, "Sta leggendo:"));
                assertTrue(hasText(root, "Piano 90 giorni"));
                assertTrue(hasText(root, "Tutto"));
                assertTrue(hasText(root, "Programmate"));
                assertTrue(hasTextContaining(root, "Fonti "));
            });
        }
    }

    @Test
    public void companionHasDedicatedPersistentDialogue() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field dbField = MainActivity.class.getDeclaredField("db");
                    dbField.setAccessible(true);
                    EvoDb db = (EvoDb) dbField.get(activity);
                    long goalId = db.addGoal("Test dialogo", "Fondo viaggio", "Viaggi", 500, 12, 2);
                    java.lang.reflect.Field goalField = MainActivity.class.getDeclaredField("selectedGoalId");
                    goalField.setAccessible(true);
                    goalField.setLong(activity, goalId);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                clickDescription(activity, "Compagno");
            });
            scenario.onActivity(activity -> clickText(activity, "Parla"));
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue(hasText(root, "Cosa ricordi?"));
                assertTrue(hasText(root, "Come stiamo?"));
                assertTrue(hasTextContaining(root, "Memoria "));
                assertTrue(hasText(root, "Compagno"));
            });
        }
    }

    @Test
    public void custodianKeepsTheSourceScreenInAutomaticContext() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Agenda"));
            scenario.onActivity(activity -> clickDescription(activity, "Custode"));
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method method = MainActivity.class
                            .getDeclaredMethod("withScreenContext", String.class);
                    method.setAccessible(true);
                    String prompt = (String) method.invoke(activity, "Cosa devo controllare?");
                    assertTrue(prompt.contains("Agenda con movimenti, programmate e debiti"));
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void panoramaContainsInteractiveChartAndFutureData() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Custode"));
            scenario.onActivity(activity -> clickText(activity, "Panorama"));
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue(hasText(root, "Lettura del mese"));
                assertTrue(hasText(root, "Categorie"));
                assertNotNull(findDescriptionContaining(root, "Grafico interattivo"));
            });
        }
    }

    @Test
    public void tappingChartKeepsVisibleModeAndCanvasInSync() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Custode"));
            scenario.onActivity(activity -> clickText(activity, "Panorama"));
            scenario.onActivity(activity -> {
                View chart = findDescriptionContaining(activity.getWindow().getDecorView(), "Grafico interattivo");
                assertNotNull(chart);
                long now = android.os.SystemClock.uptimeMillis();
                float x = chart.getWidth() * .75f;
                float y = chart.getHeight() * .5f;
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0);
                chart.dispatchTouchEvent(down);
                chart.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertNotNull(findDescriptionContaining(
                    activity.getWindow().getDecorView(), "Grafico interattivo: Categorie")));
        }
    }

    @Test
    public void radarIsPresentedAsNotebookNotBroker() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Custode"));
            scenario.onActivity(activity -> clickText(activity, "Radar"));
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue(hasText(root, "RADAR FINANZIARIO"));
                assertTrue(hasText(root, "Watchlist"));
                assertTrue(hasText(root, "Interessi"));
                assertTrue(hasText(root, "Studio"));
            });
        }
    }

    @Test
    public void companionMainScreenDoesNotDumpCollection() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> clickDescription(activity, "Compagno"));
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue(hasText(root, "Compagno"));
                assertFalse(hasTextContaining(root, "Scoperti "));
            });
        }
    }

    private static void clickDescription(MainActivity activity, String value) {
        View view = findDescription(activity.getWindow().getDecorView(), value);
        assertNotNull(value, view);
        view.performClick();
    }

    private static void clickText(MainActivity activity, String value) {
        View view = findText(activity.getWindow().getDecorView(), value);
        assertNotNull(value, view);
        view.performClick();
    }

    private static boolean hasText(View root, String value) {
        return findText(root, value) != null;
    }

    private static boolean hasTextContaining(View root, String value) {
        return findTextContaining(root, value) != null;
    }

    private static View findText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findTextContaining(View root, String value) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(value)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTextContaining(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescription(View root, String value) {
        if (root.getContentDescription() != null && value.contentEquals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescriptionContaining(View root, String value) {
        if (root.getContentDescription() != null && root.getContentDescription().toString().contains(value)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescriptionContaining(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }
}
