package com.elia.evoconti;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.res.ColorStateList;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.CalendarContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import it.evocore.telemetry.TelemetryJankTracker;
import it.evocore.telemetry.TelemetryRuntime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int BG = 0xFF080A0E;
    private static final int SURFACE = 0xFF13181D;
    private static final int FIELD = 0xFF0E1317;
    private static final int LINE = 0xFF30383E;
    private static final int INK = 0xFFF3F6F4;
    private static final int MUTED = 0xFF9CA8A3;
    private static final int GREEN = 0xFF43D19A;
    private static final int CORAL = 0xFFFF7165;
    private static final int BLUE = 0xFF57B5E8;
    private static final int GOLD = 0xFFF0C96D;
    private static final int REQUEST_CAMERA = 451;
    private static final int REQUEST_SHOPPING_RUN = 453;
    private static final int REQUEST_CAMERA_PERMISSION = 12;
    private static final int REQUEST_LOCATION_PERMISSION = 13;
    private static final int REQUEST_AUDIO_PERMISSION = 14;
    private static final int REQUEST_AVATAR_LOCATION_PERMISSION = 15;
    private static final int CAPTURE_BARCODE = 1;
    private static final int CAPTURE_PHOTO = 2;
    private static final int CAPTURE_RECEIPT = 3;

    private EvoDb db;
    private ScrollView scrollView;
    private LinearLayout content;
    private LinearLayout nav;
    private String active = "home";
    private int addMode = -1;
    private int chartMode = FinanceChartView.MODE_DAILY;
    private int moreMode = 0;
    private int investmentTab = 0;
    private int avatarTab = 0;
    private int custodianMode = 0;
    private int settingsMode = 0;
    private final Calendar agendaMonth = Calendar.getInstance();
    private int agendaSelectedDay;
    private String custodianTopic = "Tutto";
    private String companionTopic = "Compagno";
    private String thinkingPersona = "";
    private TextView thinkingBubbleText;
    private boolean scrollConversationToBottom;
    private Location avatarLocation;
    private long selectedGoalId = -1;
    private String lastAiQuestion = "";
    private String lastAiResponse = "";
    private String custodianSourceContext = "Casa con margine, impegni e salute budget";
    private int captureMode = 0;
    private EditText barcodeInput;
    private EditText expenseTitleInput;
    private EditText expenseAmountInput;
    private EditText expenseNoteInput;
    private Spinner expenseCategorySpinner;
    private String pendingReceiptOcr = "";
    private String pendingPhoto = "";
    private String pendingReceipt = "";
    private String pendingVoice = "";
    private String pendingLocation = "";
    private Uri pendingCaptureUri;
    private MediaRecorder recorder;
    private boolean recording;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastShakeAt;
    private long avatarReactionUntil;
    private int avatarReaction = CreatureView.REACTION_CURIOUS;
    private TelemetryJankTracker telemetryJank;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        db = new EvoDb(this);
        agendaMonth.set(Calendar.DAY_OF_MONTH, 1);
        agendaMonth.set(Calendar.HOUR_OF_DAY, 0);
        agendaMonth.set(Calendar.MINUTE, 0);
        agendaMonth.set(Calendar.SECOND, 0);
        agendaMonth.set(Calendar.MILLISECOND, 0);
        agendaSelectedDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
        db.syncLocalAiSteward();
        setupSensors();
        buildShell();
        show("home");
    }

    @Override
    protected void onStart() {
        super.onStart();
        TelemetryRuntime.start(this);
        TelemetryRuntime.refreshSession();
        telemetryJank = TelemetryJankTracker.attach(getWindow());
        TelemetryRuntime.screen(active);
        if (telemetryJank != null) telemetryJank.setScreen(active);
    }

    @Override
    protected void onStop() {
        EvoCoreGateway.disconnectIfCreated();
        if (telemetryJank != null) {
            telemetryJank.stop();
            telemetryJank = null;
        }
        TelemetryRuntime.stop();
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording) stopRecording();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onDestroy() {
        if (telemetryJank != null) telemetryJank.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if ("add".equals(active) && addMode >= 0) {
            addMode = -1;
            show("add");
            return;
        }
        if ("companion".equals(active) && avatarTab != 0) {
            avatarTab = 0;
            show("companion");
            return;
        }
        if ("custodian".equals(active) && custodianMode == 2 && investmentTab != 0) {
            investmentTab = 0;
            show("custodian");
            return;
        }
        if ("settings".equals(active)) {
            if (settingsMode != 0) {
                settingsMode = 0;
                show("settings");
            } else {
                show("home");
            }
            return;
        }
        if (!"home".equals(active)) {
            show("home");
            return;
        }
        super.onBackPressed();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setFocusableInTouchMode(true);
        scrollView.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(8), dp(5), dp(8), dp(5));
        nav.setBackground(box(0xFF0A0D11, 0, 0xFF242B30));
        nav.setClipChildren(false);
        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                bottomInset = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            int topInset;
            if (Build.VERSION.SDK_INT >= 30) {
                topInset = insets.getInsets(WindowInsets.Type.systemBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            root.setPadding(0, topInset, 0, 0);
            content.setPadding(dp(16), dp(12), dp(16), dp(32));
            nav.setPadding(dp(8), dp(5), dp(8), dp(5) + bottomInset);
            ViewGroup.LayoutParams params = nav.getLayoutParams();
            params.height = dp(68) + bottomInset;
            nav.setLayoutParams(params);
            return insets;
        });
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(0xFF0A0D11);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void setupSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        double force = Math.sqrt(x * x + y * y + z * z);
        long now = System.currentTimeMillis();
        if (force > 24 && now - lastShakeAt > 2500) {
            lastShakeAt = now;
            handleShake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void handleShake() {
        boolean sleeping = autoAvatarScene() == CreatureView.SCENE_SLEEP;
        EvoDb.LocalAiReport report = db.localAiReport();
        boolean budgetRisk = report.score < 55 || report.margin < 0 || report.debtRatio > 0.20;
        boolean budgetWatch = !budgetRisk && (report.score < 75 || report.debtRatio > 0.15 || report.futureRatio < 0.10);
        db.syncLocalAiSteward();
        if (sleeping) {
            avatarReaction = CreatureView.REACTION_CURIOUS;
            toast("Lo hai svegliato: reagisce male, ma la salute resta al Custode AI");
        } else if (budgetRisk) {
            avatarReaction = CreatureView.REACTION_CURIOUS;
            toast("Budget fragile: lo scuotimento non cura, serve ridurre pressione economica");
        } else if (budgetWatch) {
            avatarReaction = CreatureView.REACTION_GREETING;
            toast("Reagisce, ma senza bonus: prima proteggiamo il margine");
        } else {
            avatarReaction = CreatureView.REACTION_HAPPY;
            toast("Si \u00e8 caricato: piccola energia positiva");
        }
        avatarReactionUntil = System.currentTimeMillis() + 1_100;
        if ("companion".equals(active) || "home".equals(active)) show(active);
    }

    private void show(String section) {
        String previousSection = active;
        boolean requestedChart = "charts".equals(section);
        if ("oggi".equals(section)) section = "home";
        if ("charts".equals(section)) {
            custodianMode = 1;
            section = "custodian";
        }
        if ("monsters".equals(section)) section = "companion";
        if ("more".equals(section)) section = "custodian";
        if ("custodian".equals(section) && !"custodian".equals(previousSection)) {
            custodianSourceContext = contextForSection(previousSection);
            if (!requestedChart) custodianMode = 0;
        }
        active = section;
        TelemetryRuntime.screen(section);
        if (telemetryJank != null) telemetryJank.setScreen(section);
        thinkingBubbleText = null;
        content.removeAllViews();
        buildNav();
        if ("add".equals(section)) showAdd();
        else if ("agenda".equals(section)) showAgenda();
        else if ("custodian".equals(section)) showCustodian();
        else if ("companion".equals(section)) showMonsters();
        else if ("settings".equals(section)) showSettings();
        else showDashboard();
        content.setAlpha(0.86f);
        content.setTranslationY(dp(5));
        content.animate().alpha(1f).translationY(0).setDuration(170).start();
        if (scrollView != null) {
            scrollView.requestFocus();
            boolean scrollBottom = scrollConversationToBottom;
            scrollConversationToBottom = false;
            if (scrollBottom) {
                scrollView.postDelayed(() -> scrollView.fullScroll(View.FOCUS_DOWN), 200);
            } else {
                scrollView.post(() -> scrollView.scrollTo(0, 0));
                scrollView.postDelayed(() -> scrollView.scrollTo(0, 0), 200);
            }
        }
    }

    private void buildNav() {
        nav.removeAllViews();
        nav.addView(navItem("Casa", "home", R.drawable.ic_home));
        nav.addView(navItem("Agenda", "agenda", R.drawable.ic_agenda));
        nav.addView(addNavItem());
        nav.addView(navItem("Custode", "custodian", R.drawable.ic_custodian));
        nav.addView(navItem("Compagno", "companion", R.drawable.ic_companion));
    }

    private View navItem(String label, String key, int iconRes) {
        boolean selected = key.equals(active);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(3), dp(5), dp(3), dp(3));
        item.setBackground(box(selected ? 0xFF17231F : Color.TRANSPARENT, dp(6), 0));
        item.setContentDescription(label);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tintedIcon(iconRes, selected ? GREEN : MUTED));
        item.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView text = text(label, 10, selected ? INK : MUTED, selected ? Typeface.BOLD : Typeface.NORMAL);
        text.setGravity(Gravity.CENTER);
        item.addView(text, matchMargins(0, dp(2), 0, 0));
        item.setOnClickListener(v -> show(key));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        item.setLayoutParams(lp);
        return item;
    }

    private View addNavItem() {
        FrameLayout holder = new FrameLayout(this);
        holder.setContentDescription("Registra");
        ImageView icon = new ImageView(this);
        icon.setPadding(dp(13), dp(13), dp(13), dp(13));
        icon.setImageDrawable(tintedIcon(R.drawable.ic_add, Color.WHITE));
        icon.setBackground(box("add".equals(active) ? GOLD : GREEN, dp(27), 0));
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER);
        holder.addView(icon, iconParams);
        holder.setOnClickListener(v -> {
            addMode = -1;
            show("add");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        holder.setLayoutParams(lp);
        return holder;
    }

    private void showDashboard() {
        db.syncLocalAiSteward();
        double income = db.monthlyIncome();
        double fixed = db.fixedMonthly();
        double spent = db.monthExpense();
        double free = income - fixed - spent;
        EvoDb.LocalAiReport report = db.localAiReport();
        showHomeTopBar();
        showBudgetHero(income, fixed, spent, free, report);
        showHomeQuickActions();
        showHomeTimeline();
        showHomeCustodian(report);
        showHomeCompanion(report);
    }

    private void showHomeTopBar() {
        LinearLayout top = row();
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text("EvoConti", 25, INK, Typeface.BOLD));
        copy.addView(text(new SimpleDateFormat("EEEE d MMMM", Locale.ITALY)
                .format(System.currentTimeMillis()), 12, MUTED, Typeface.NORMAL));
        top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView settings = iconButton(R.drawable.ic_settings, "Impostazioni", MUTED);
        settings.setOnClickListener(v -> {
            settingsMode = 0;
            show("settings");
        });
        top.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(44)));
        content.addView(top, matchMargins(0, 0, 0, dp(14)));
    }

    private void showBudgetHero(double income, double fixed, double spent, double free,
                                EvoDb.LocalAiReport report) {
        int healthColor = report.score >= 75 ? GREEN : report.score >= 55 ? GOLD : CORAL;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(16));
        panel.setBackground(box(0xFF10171A, dp(8), 0xFF344139));
        panel.addView(text("MARGINE DEL MESE", 11, GREEN, Typeface.BOLD));
        panel.addView(text(money(free), 31, free >= 0 ? INK : CORAL, Typeface.BOLD),
                matchMargins(0, dp(2), 0, dp(12)));

        LinearLayout numbers = row();
        numbers.addView(metric("Entrate", money(income), GREEN));
        numbers.addView(metricDivider());
        numbers.addView(metric("Impegni", money(fixed), GOLD));
        numbers.addView(metricDivider());
        numbers.addView(metric("Speso", money(spent), CORAL));
        panel.addView(numbers);

        LinearLayout health = row();
        health.setPadding(0, dp(12), 0, dp(4));
        health.addView(text("Salute budget", 12, MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        health.addView(text(report.score + "/100  " + report.level, 12, healthColor, Typeface.BOLD));
        panel.addView(health);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(report.score);
        progress.setProgressTintList(ColorStateList.valueOf(healthColor));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF26302D));
        panel.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7)));
        content.addView(panel, matchMargins(0, 0, 0, dp(12)));
    }

    private void showHomeQuickActions() {
        LinearLayout actions = row();
        Button expense = actionButton("Spesa", R.drawable.ic_expense, CORAL);
        expense.setContentDescription("Registra spesa");
        expense.setOnClickListener(v -> {
            addMode = 0;
            show("add");
        });
        Button scan = actionButton("Scansiona", R.drawable.ic_scan, BLUE);
        scan.setContentDescription("Avvia corsa spesa");
        scan.setOnClickListener(v -> openShoppingRun());
        actions.addView(expense, weightedMargins(dp(3), 0, dp(5), 0, dp(56)));
        actions.addView(scan, weightedMargins(dp(5), 0, dp(3), 0, dp(56)));
        content.addView(actions, matchMargins(0, 0, 0, dp(18)));
    }

    private void showHomeTimeline() {
        content.addView(sectionHeader("Oggi e prossimi", "Apri agenda", () -> show("agenda")));
        LinearLayout timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);

        int todayCount = 0;
        for (EvoDb.Tx tx : db.recentTransactions(16)) {
            if (!"expense".equals(tx.type) || !sameDay(tx.createdAt, System.currentTimeMillis())) continue;
            timeline.addView(txLine(tx));
            if (++todayCount >= 3) break;
        }
        if (todayCount == 0) {
            timeline.addView(emptyLine("Nessuna spesa registrata oggi", "Il mese resta aggiornato con le programmate."));
        }

        List<EvoDb.MoneyRow> programmed = db.programmedExpenseRows();
        int shown = 0;
        for (EvoDb.MoneyRow item : programmed) {
            timeline.addView(timelineLine("PROGRAMMATA", item.left, item.right, GOLD));
            if (++shown >= 2) break;
        }
        if (!db.debts().isEmpty()) {
            timeline.addView(timelineLine("DEBITI", "Rate attive e residui",
                    money(db.monthlyDebtPayments()) + "/mese", CORAL));
        }
        content.addView(timeline, matchMargins(0, dp(4), 0, dp(18)));
    }

    private void showHomeCustodian(EvoDb.LocalAiReport report) {
        int color = report.score >= 75 ? GREEN : report.score >= 55 ? GOLD : CORAL;
        LinearLayout panel = card();
        LinearLayout heading = row();
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tintedIcon(R.drawable.ic_custodian, color));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text("Custode", 17, INK, Typeface.BOLD);
        heading.addView(name, matchMargins(dp(10), 0, 0, 0));
        panel.addView(heading);
        panel.addView(text(report.mainTask, 14, INK, Typeface.NORMAL),
                matchMargins(0, dp(10), 0, dp(4)));
        panel.addView(text("Legge gi\u00e0 budget, programmate, debiti, obiettivi e stato emotivo.",
                12, MUTED, Typeface.NORMAL));
        Button ask = quietButton("Parla con il Custode");
        ask.setTextColor(color);
        ask.setOnClickListener(v -> {
            custodianMode = 0;
            show("custodian");
        });
        panel.addView(ask, matchMargins(0, dp(9), 0, 0));
        content.addView(panel);
    }

    private void showHomeCompanion(EvoDb.LocalAiReport report) {
        List<EvoDb.Goal> goals = db.goals();
        content.addView(sectionHeader("Il tuo compagno", goals.isEmpty() ? "Crea obiettivo" : "Apri scena", () -> {
            avatarTab = goals.isEmpty() ? 1 : 0;
            show("companion");
        }), matchMargins(0, dp(8), 0, 0));
        if (goals.isEmpty()) {
            content.addView(emptyLine("La cabina aspetta il primo obiettivo",
                    "Una creatura verr\u00e0 assegnata senza mostrare quelle ancora bloccate."));
            return;
        }
        EvoDb.Goal goal = goals.get(0);
        LinearLayout scene = new LinearLayout(this);
        scene.setOrientation(LinearLayout.VERTICAL);
        scene.setBackground(box(0xFF0A0E12, dp(8), 0xFF3A3423));
        CreatureView creature = new CreatureView(this);
        creature.setCreature(goal.creature, goal.stage, goal.hp);
        creature.setHealthScore(report.avatarHp);
        AvatarContextResolver.Result avatarContext = currentAvatarContext();
        creature.setAutoSceneMode(avatarContext.activity);
        creature.setEnvironmentMode(avatarContext.environment);
        applyPendingAvatarReaction(creature);
        scene.addView(creature, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(235)));
        LinearLayout footer = row();
        footer.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(CreatureCatalog.name(goal.creature), 16, GOLD, Typeface.BOLD));
        copy.addView(text(goal.name + "  |  " + CreatureCatalog.stageLabel(goal.stage),
                12, MUTED, Typeface.NORMAL));
        footer.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        footer.addView(text(report.avatarState, 12, report.score >= 60 ? GREEN : CORAL, Typeface.BOLD));
        scene.addView(footer);
        scene.setOnClickListener(v -> {
            selectedGoalId = goal.id;
            avatarTab = 0;
            show("companion");
        });
        content.addView(scene, matchMargins(0, dp(6), 0, dp(8)));
    }

    private void showRecordHub() {
        LinearLayout first = row();
        first.addView(recordTile("Spesa", "Acquisto o bolletta", R.drawable.ic_expense, CORAL, 0));
        first.addView(recordTile("Entrata", "Stipendio o rimborso", R.drawable.ic_income, GREEN, 1));
        content.addView(first);
        LinearLayout second = row();
        second.addView(recordTile("Programmata", "Con inizio e fine", R.drawable.ic_schedule, GOLD, 2));
        second.addView(recordTile("Debito", "Rata e residuo", R.drawable.ic_debt, CORAL, 4));
        content.addView(second);

        EvoDb.ShoppingSession open = db.latestOpenShoppingSession();
        EvoDb.ShoppingSummary summary = open == null ? null : db.shoppingSummary(open.id);
        Button shopping = actionButton(open == null ? "Corsa spesa" :
                "Riprendi: " + summary.quantity + " prodotti, " + money(summary.total), R.drawable.ic_scan, BLUE);
        shopping.setOnClickListener(v -> openShoppingRun());
        content.addView(shopping, matchMargins(dp(3), dp(14), dp(3), dp(10)));

        Button account = quietButton("Aggiungi conto o carta");
        account.setOnClickListener(v -> {
            addMode = 3;
            show("add");
        });
        content.addView(account);
    }

    private View recordTile(String name, String subtitle, int iconRes, int color, int mode) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.START);
        tile.setPadding(dp(15), dp(15), dp(15), dp(14));
        tile.setBackground(box(SURFACE, dp(8), LINE));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tintedIcon(iconRes, color));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        tile.addView(text(name, 16, INK, Typeface.BOLD), matchMargins(0, dp(12), 0, 0));
        tile.addView(text(subtitle, 11, MUTED, Typeface.NORMAL));
        tile.setContentDescription(name + ". " + subtitle);
        tile.setOnClickListener(v -> {
            addMode = mode;
            show("add");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(126), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        tile.setLayoutParams(lp);
        return tile;
    }

    private void showAgenda() {
        header("Agenda", "Il mese reale: movimenti, programmate, orari e rate.");
        LinearLayout summary = row();
        summary.addView(metric("Speso", money(db.monthExpense()), CORAL));
        summary.addView(metricDivider());
        summary.addView(metric("Programmato", money(Math.max(0, db.fixedMonthly() - db.monthlyDebtPayments())), GOLD));
        summary.addView(metricDivider());
        summary.addView(metric("Rate", money(db.monthlyDebtPayments()), CORAL));
        content.addView(summary, matchMargins(0, 0, 0, dp(14)));

        Calendar nextMonth = (Calendar) agendaMonth.clone();
        nextMonth.add(Calendar.MONTH, 1);
        List<EvoDb.CalendarItem> monthItems = db.calendarItems(agendaMonth.getTimeInMillis(), nextMonth.getTimeInMillis());
        int monthDays = agendaMonth.getActualMaximum(Calendar.DAY_OF_MONTH);
        agendaSelectedDay = Math.max(1, Math.min(monthDays, agendaSelectedDay));

        LinearLayout monthHeader = row();
        ImageView previous = iconButton(R.drawable.ic_back, "Mese precedente", MUTED);
        previous.setOnClickListener(v -> {
            agendaMonth.add(Calendar.MONTH, -1);
            agendaSelectedDay = 1;
            show("agenda");
        });
        monthHeader.addView(previous, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView monthTitle = text(new SimpleDateFormat("MMMM yyyy", Locale.ITALY).format(agendaMonth.getTime()),
                18, INK, Typeface.BOLD);
        monthTitle.setGravity(Gravity.CENTER);
        monthHeader.addView(monthTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        ImageView following = iconButton(R.drawable.ic_chevron, "Mese successivo", MUTED);
        following.setOnClickListener(v -> {
            agendaMonth.add(Calendar.MONTH, 1);
            agendaSelectedDay = 1;
            show("agenda");
        });
        monthHeader.addView(following, new LinearLayout.LayoutParams(dp(44), dp(44)));
        content.addView(monthHeader);

        BudgetCalendarView calendar = new BudgetCalendarView(this);
        calendar.setMonth(agendaMonth.get(Calendar.YEAR), agendaMonth.get(Calendar.MONTH), monthItems);
        calendar.setSelectedDay(agendaSelectedDay);
        calendar.setOnDaySelectedListener((year, month, day) -> {
            agendaSelectedDay = day;
            show("agenda");
        });
        calendar.setBackground(box(FIELD, dp(8), LINE));
        content.addView(calendar, matchMargins(0, dp(4), 0, dp(7)));
        content.addView(text("Blu: movimenti  |  Oro: programmate  |  Verde: entrate  |  Corallo: rate",
                10, MUTED, Typeface.NORMAL));

        Calendar selected = (Calendar) agendaMonth.clone();
        selected.set(Calendar.DAY_OF_MONTH, agendaSelectedDay);
        String selectedTitle = new SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(selected.getTime());
        content.addView(sectionHeader(selectedTitle, "Nuova spesa", () -> {
            addMode = 0;
            show("add");
        }), matchMargins(0, dp(15), 0, 0));
        List<EvoDb.CalendarItem> selectedItems = calendarItemsForDay(monthItems, selected);
        if (selectedItems.isEmpty()) content.addView(emptyLine("Giornata libera", "Nessun movimento o impegno previsto."));
        for (EvoDb.CalendarItem item : selectedItems) content.addView(calendarItemLine(item));

        List<EvoDb.Recurring> recurring = db.recurring();
        content.addView(sectionHeader("Tutte le programmate", "Aggiungi", () -> {
            addMode = 2;
            show("add");
        }), matchMargins(0, dp(16), 0, 0));
        if (recurring.isEmpty()) content.addView(emptyLine("Nessuna voce programmata",
                "Aggiungi entrate o uscite con un limite temporale."));
        for (EvoDb.Recurring item : recurring) content.addView(recurringLine(item));

        List<EvoDb.Debt> debts = db.debts();
        content.addView(sectionHeader("Debiti", "Registra", () -> {
            addMode = 4;
            show("add");
        }), matchMargins(0, dp(16), 0, 0));
        if (debts.isEmpty()) content.addView(emptyLine("Nessun debito attivo",
                "Il Custode continuera a proteggere il margine disponibile."));
        for (EvoDb.Debt debt : debts) content.addView(debtLine(debt));
    }

    private List<EvoDb.CalendarItem> calendarItemsForDay(List<EvoDb.CalendarItem> items, Calendar selected) {
        List<EvoDb.CalendarItem> out = new ArrayList<>();
        for (EvoDb.CalendarItem item : items) {
            Calendar when = Calendar.getInstance();
            when.setTimeInMillis(item.whenAt);
            if (when.get(Calendar.YEAR) == selected.get(Calendar.YEAR) &&
                    when.get(Calendar.DAY_OF_YEAR) == selected.get(Calendar.DAY_OF_YEAR)) out.add(item);
        }
        return out;
    }

    private View calendarItemLine(EvoDb.CalendarItem item) {
        String label;
        int color;
        boolean positive = "income".equals(item.kind) || "planned_income".equals(item.kind);
        if ("planned_expense".equals(item.kind)) {
            label = "PROGRAMMATA";
            color = GOLD;
        } else if ("planned_income".equals(item.kind)) {
            label = "ENTRATA PROGRAMMATA";
            color = GREEN;
        } else if ("debt".equals(item.kind)) {
            label = "RATA";
            color = CORAL;
        } else if ("income".equals(item.kind)) {
            label = "ENTRATA";
            color = GREEN;
        } else {
            label = "SPESA REALE";
            color = BLUE;
        }
        String time = new SimpleDateFormat("HH:mm", Locale.ITALY).format(item.whenAt);
        return timelineLine(label, time + "  " + item.title + "  |  " + item.category,
                (positive ? "+" : "-") + money(item.amount), color);
    }

    private View recurringLine(EvoDb.Recurring item) {
        LinearLayout line = row();
        line.setPadding(dp(13), dp(10), dp(8), dp(10));
        line.setBackground(box(FIELD, dp(6), LINE));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        boolean income = "income".equals(item.type);
        copy.addView(text(item.title, 14, INK, Typeface.BOLD));
        String period = dayText(item.startAt) + " - " + (item.endAt <= 0 ? "senza fine" : dayText(item.endAt));
        String time = String.format(Locale.ITALY, "%02d:%02d", item.timeMinutes / 60, item.timeMinutes % 60);
        copy.addView(text((income ? "Entrata" : "Uscita") + "  |  " + time + "  |  " + item.category + "  |  " + period,
                11, MUTED, Typeface.NORMAL));
        line.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(text((income ? "+" : "-") + money(item.amount), 12,
                income ? GREEN : GOLD, Typeface.BOLD));
        ImageView export = iconButton(R.drawable.ic_export, "Aggiungi " + item.title + " al calendario", BLUE);
        export.setOnClickListener(v -> exportRecurringToCalendar(item));
        line.addView(export, new LinearLayout.LayoutParams(dp(42), dp(42)));
        ImageView remove = iconButton(R.drawable.ic_close, "Elimina " + item.title, MUTED);
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Eliminare la programmata?")
                .setMessage(item.title)
                .setPositiveButton("Elimina", (dialog, which) -> {
                    db.deleteRecurring(item.id);
                    show("agenda");
                })
                .setNegativeButton("Annulla", null)
                .show());
        line.addView(remove, new LinearLayout.LayoutParams(dp(42), dp(42)));
        line.setLayoutParams(matchMargins(0, dp(4), 0, dp(4)));
        return line;
    }

    private void exportRecurringToCalendar(EvoDb.Recurring item) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(item.startAt);
        start.set(Calendar.HOUR_OF_DAY, item.timeMinutes / 60);
        start.set(Calendar.MINUTE, item.timeMinutes % 60);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, "EvoConti: " + item.title)
                .putExtra(CalendarContract.Events.DESCRIPTION,
                        ("income".equals(item.type) ? "Entrata programmata" : "Spesa programmata") +
                                " | " + money(item.amount) + " | " + item.category +
                                "\nDato informativo del tuo quaderno EvoConti.")
                .putExtra(CalendarContract.Events.DTSTART, start.getTimeInMillis())
                .putExtra(CalendarContract.Events.DTEND, start.getTimeInMillis() + 60L * 60L * 1000L)
                .putExtra(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        String rule = "FREQ=MONTHLY";
        if (item.endAt > 0) {
            SimpleDateFormat until = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
            until.setTimeZone(TimeZone.getTimeZone("UTC"));
            rule += ";UNTIL=" + until.format(item.endAt);
        }
        intent.putExtra(CalendarContract.Events.RRULE, rule);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            toast("Nessuna app calendario disponibile");
        }
    }

    private void showAdd() {
        if (addMode < 0) {
            header("Registra", "Scegli una sola azione. Il Custode aggiorna il resto.");
            showRecordHub();
            return;
        }
        Button back = quietButton("Torna alle azioni");
        back.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_back, MUTED), null, null, null);
        back.setCompoundDrawablePadding(dp(8));
        back.setOnClickListener(v -> {
            addMode = -1;
            show("add");
        });
        content.addView(back, matchMargins(0, 0, 0, dp(8)));
        header(addMode == 0 ? "Nuova spesa" : addMode == 1 ? "Nuova entrata" :
                addMode == 2 ? "Programma" : addMode == 4 ? "Nuovo debito" : "Nuovo conto",
                addMode == 0 ? "Veloce quando serve, dettagliata quando vuoi." :
                        addMode == 2 ? "Entrate e uscite con una vera durata." : "Dati chiari, senza passaggi inutili.");
        if (addMode == 1) showIncomeForm();
        else if (addMode == 2) showRecurringForm();
        else if (addMode == 3) showAccountForm();
        else if (addMode == 4) showDebtForm();
        else showExpenseForm();
    }

    private Button modeButton(String text, int mode) {
        Button b = secondaryButton(text);
        if (addMode == mode) {
            b.setTextColor(Color.WHITE);
            b.setBackground(box(GREEN, dp(10), 0));
        }
        b.setOnClickListener(v -> {
            addMode = mode;
            show("add");
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        return b;
    }

    private void showExpenseForm() {
        pendingPhoto = "";
        pendingReceipt = "";
        pendingReceiptOcr = "";
        pendingVoice = "";
        pendingLocation = "";
        LinearLayout card = card();
        EditText title = expenseTitleInput = input("Descrizione, prodotto o negozio");
        EditText amount = expenseAmountInput = input("Importo EUR");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner category = expenseCategorySpinner = spinner(db.listCategories());
        Spinner account = spinner(db.listAccounts());
        EditText newCategory = input("Nuova categoria, se serve");
        EditText usage = input("Ore/giorni di utilizzo, opzionale");
        usage.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        barcodeInput = input("Barcode, manuale o da foto");
        EditText note = expenseNoteInput = input("Note");
        CheckBox offer = check("Prodotto in offerta");
        CheckBox incident = check("Spesa impulsiva o fuori piano");

        title.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                setSpinner(category, db.smartCategory(s.toString()));
            }
        });

        card.addView(label("Descrizione"));
        card.addView(title);
        card.addView(label("Importo"));
        card.addView(amount);
        card.addView(label("Categoria"));
        card.addView(category);
        card.addView(label("Conto o carta"));
        card.addView(account);
        card.addView(offer);
        card.addView(incident);

        card.addView(label("Acquisizione rapida"), matchMargins(0, dp(8), 0, dp(4)));
        LinearLayout mediaA = row();
        Button scan = actionButton("Barcode", R.drawable.ic_scan, BLUE);
        scan.setOnClickListener(v -> openCamera(CAPTURE_BARCODE));
        Button receipt = actionButton("Scontrino", R.drawable.ic_receipt, GOLD);
        receipt.setOnClickListener(v -> openCamera(CAPTURE_RECEIPT));
        mediaA.addView(scan, weightedMargins(dp(2), 0, dp(3), 0, dp(50)));
        mediaA.addView(receipt, weightedMargins(dp(3), 0, dp(2), 0, dp(50)));
        card.addView(mediaA);
        LinearLayout mediaB = row();
        Button photo = actionButton("Prodotto", R.drawable.ic_camera, BLUE);
        photo.setOnClickListener(v -> openCamera(CAPTURE_PHOTO));
        Button place = actionButton("Posizione", R.drawable.ic_location, GREEN);
        place.setOnClickListener(v -> grabLocation());
        mediaB.addView(photo, weightedMargins(dp(2), 0, dp(3), 0, dp(50)));
        mediaB.addView(place, weightedMargins(dp(3), 0, dp(2), 0, dp(50)));
        card.addView(mediaB);
        LinearLayout mediaC = row();
        Button voice = actionButton("Voce", R.drawable.ic_mic, CORAL);
        voice.setOnClickListener(v -> toggleRecording());
        Button shopping = actionButton("Corsa spesa", R.drawable.ic_cart, GREEN);
        shopping.setOnClickListener(v -> openShoppingRun());
        mediaC.addView(voice, weightedMargins(dp(2), 0, dp(3), 0, dp(50)));
        mediaC.addView(shopping, weightedMargins(dp(3), 0, dp(2), 0, dp(50)));
        card.addView(mediaC);
        card.addView(note);

        Button evaluate = quietButton("Valuta questa spesa con il Custode");
        evaluate.setOnClickListener(v -> showAdvisorAnswer("Sto valutando una spesa chiamata " +
                textOf(title) + " da " + money(parse(amount)) + " nella categoria " + selected(category) +
                ". Come pesa sul mese?"));
        card.addView(evaluate, matchMargins(0, dp(8), 0, 0));

        Button save = primaryButton("Salva spesa");
        save.setOnClickListener(v -> {
            double value = parse(amount);
            if (value <= 0) {
                toast("Inserisci un importo valido");
                return;
            }
            String cat = clean(newCategory.getText().toString()).isEmpty()
                    ? selected(category) : clean(newCategory.getText().toString());
            db.addCategory(cat, "#5C6F7B");
            String storedNote = textOf(note);
            if (!pendingReceiptOcr.isEmpty()) {
                storedNote += (storedNote.isEmpty() ? "" : "\n") + "OCR scontrino: " + pendingReceiptOcr;
            }
            db.addTransaction("expense", textOf(title), value, cat, selected(account), storedNote,
                    textOf(barcodeInput), pendingLocation, parse(usage), offer.isChecked(), incident.isChecked(),
                    pendingPhoto, pendingReceipt, pendingVoice);
            db.applyExpenseImpact(value, cat, offer.isChecked(), incident.isChecked());
            hideKeyboard(title);
            toast("Spesa salvata");
            addMode = -1;
            show("home");
        });
        card.addView(save, matchMargins(0, dp(14), 0, 0));
        content.addView(card);
    }

    private void showExpenseOverview() {
        LinearLayout card = card();
        card.addView(title("Uscite", 18));
        List<EvoDb.MoneyRow> programmed = db.programmedExpenseRows();
        if (!programmed.isEmpty()) {
            card.addView(label("Programmate"));
            for (EvoDb.MoneyRow row : programmed) {
                card.addView(simpleLine(row.left, row.right));
            }
        }
        List<EvoDb.MoneyRow> debts = db.debtRows();
        if (!debts.isEmpty()) {
            card.addView(label("Debiti"));
            for (EvoDb.MoneyRow row : debts) {
                card.addView(simpleLine(row.left, row.right));
            }
        }
        List<EvoDb.MoneyRow> recent = db.expenseRows(8);
        if (!recent.isEmpty()) {
            card.addView(label("Recenti"));
            for (EvoDb.MoneyRow row : recent) {
                card.addView(simpleLine(row.left, row.right));
            }
        }
        if (programmed.isEmpty() && debts.isEmpty() && recent.isEmpty()) {
            card.addView(text("Nessuna uscita salvata o programmata.", 13, MUTED, Typeface.NORMAL));
        }
        content.addView(card);
    }

    private void showIncomeForm() {
        LinearLayout card = card();
        card.addView(title("Nuova entrata", 18));
        EditText title = input("Stipendio, bonus, rimborso");
        EditText amount = input("Importo EUR");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner account = spinner(db.listAccounts());
        card.addView(title);
        card.addView(amount);
        card.addView(label("Conto o carta"));
        card.addView(account);
        Button save = primaryButton("Salva entrata");
        save.setOnClickListener(v -> {
            double value = parse(amount);
            if (value <= 0) {
                toast("Importo non valido");
                return;
            }
            db.addTransaction("income", textOf(title), value, "Entrate", selected(account), "", "", "", 0, false, false, "", "", "");
            db.syncLocalAiSteward();
            toast("Entrata salvata");
            addMode = -1;
            show("home");
        });
        card.addView(save, matchMargins(0, dp(12), 0, 0));
        content.addView(card);
    }

    private void showRecurringForm() {
        LinearLayout card = card();
        card.addView(title("Entrate e spese programmate", 18));
        card.addView(text("Usale per stipendio, bonus a tempo, affitto, abbonamenti, rate e tasse ricorrenti.", 13, MUTED, Typeface.NORMAL));
        Spinner type = spinner(list("Entrata programmata", "Spesa programmata"));
        EditText title = input("Nome, es. stipendio luglio-dicembre o affitto");
        EditText amount = input("Importo EUR");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText startDate = input("Inizio YYYY-MM-DD");
        startDate.setText(todayText());
        EditText endDate = input("Fine YYYY-MM-DD, opzionale");
        EditText expectedTime = input("Ora prevista HH:mm");
        expectedTime.setText("09:00");
        expectedTime.setFocusable(false);
        expectedTime.setClickable(true);
        expectedTime.setOnClickListener(v -> {
            int current = parseTimeMinutes(textOf(expectedTime));
            new TimePickerDialog(this, (picker, hour, minute) -> expectedTime.setText(
                    String.format(Locale.ITALY, "%02d:%02d", hour, minute)),
                    current / 60, current % 60, true).show();
        });
        Spinner category = spinner(db.listCategories());
        Spinner account = spinner(db.listAccounts());
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setSpinner(category, selected(type).startsWith("Entrata") ? "Entrate" : "Casa");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        card.addView(label("Tipo"));
        card.addView(type);
        card.addView(title);
        card.addView(amount);
        card.addView(label("Periodo valido"));
        card.addView(startDate);
        card.addView(endDate);
        card.addView(expectedTime);
        card.addView(label("Categoria e conto"));
        card.addView(category);
        card.addView(account);
        Button evaluate = quietButton("Controlla sostenibilita con il Custode");
        evaluate.setOnClickListener(v -> showAdvisorAnswer("Valuta questa voce programmata: " + textOf(title) +
                ", " + money(parse(amount)) + ", dal " + textOf(startDate) + " al " + textOf(endDate) +
                ", alle " + textOf(expectedTime) + "."));
        card.addView(evaluate, matchMargins(0, dp(7), 0, 0));
        Button save = primaryButton("Salva programmata");
        save.setOnClickListener(v -> {
            double value = parse(amount);
            if (value <= 0) {
                toast("Importo non valido");
                return;
            }
            long start = parseDay(textOf(startDate), System.currentTimeMillis());
            long end = parseDay(textOf(endDate), 0);
            if (start == Long.MIN_VALUE || end == Long.MIN_VALUE) {
                toast("Usa una data valida nel formato YYYY-MM-DD");
                return;
            }
            if (end > 0 && end < start) {
                toast("La fine deve essere dopo l'inizio");
                return;
            }
            String code = selected(type).startsWith("Entrata") ? "income" : "fixed";
            db.addRecurring(code, textOf(title), value, selected(category), selected(account), start, end,
                    parseTimeMinutes(textOf(expectedTime)));
            db.syncLocalAiSteward();
            toast("Voce programmata salvata");
            addMode = -1;
            show("agenda");
        });
        card.addView(save, matchMargins(0, dp(12), 0, 0));
        content.addView(card);
    }

    private void showAccountForm() {
        LinearLayout card = card();
        card.addView(title("Nuovo conto o carta", 18));
        EditText name = input("Nome conto/carta");
        Spinner type = spinner(list("Conto", "Carta", "Cash", "Prepagata"));
        EditText balance = input("Saldo iniziale EUR");
        balance.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        card.addView(name);
        card.addView(type);
        card.addView(balance);
        Button save = primaryButton("Salva conto");
        save.setOnClickListener(v -> {
            if (clean(textOf(name)).isEmpty()) {
                toast("Nome mancante");
                return;
            }
            db.addAccount(textOf(name), selected(type), parse(balance));
            toast("Conto salvato");
            addMode = -1;
            if ("settings".equals(active)) {
                settingsMode = 4;
                show("settings");
            } else {
                show("home");
            }
        });
        card.addView(save, matchMargins(0, dp(12), 0, 0));
        content.addView(card);
    }

    private void showDebtForm() {
        LinearLayout card = card();
        card.addView(text("Da usare con cautela: l'app considera il debito come peso sul budget futuro e sulla salute degli avatar. Prima di crearne uno valuta alternative e necessit\u00e0 reale.", 13, CORAL, Typeface.BOLD));
        EditText name = input("Nome debito, es. rata auto o carta");
        EditText total = input("Importo iniziale EUR");
        EditText remaining = input("Residuo EUR");
        EditText monthly = input("Rata mensile EUR");
        EditText apr = input("TAEG %, opzionale");
        EditText startDate = input("Data inizio YYYY-MM-DD");
        startDate.setText(todayText());
        EditText endDate = input("Data fine stimata YYYY-MM-DD");
        Spinner category = spinner(db.listCategories());
        setSpinner(category, "Debiti");
        Spinner debtType = spinner(list("mutuo", "prestito personale", "carta/revolving", "finanziamento acquisto", "debito familiare", "altro"));
        EditText note = input("Nota");
        total.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        remaining.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        monthly.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        apr.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        card.addView(name);
        card.addView(total);
        card.addView(remaining);
        card.addView(monthly);
        card.addView(apr);
        card.addView(label("Periodo"));
        card.addView(startDate);
        card.addView(endDate);
        card.addView(label("Categoria"));
        card.addView(category);
        card.addView(label("Tipo debito"));
        card.addView(debtType);
        card.addView(note);
        Button evaluate = quietButton("Chiedi prudenza al Custode");
        evaluate.setOnClickListener(v -> showAdvisorAnswer("Sto pensando a un nuovo debito " + textOf(name) +
                " con residuo " + money(parse(remaining)) + ", rata " + money(parse(monthly)) +
                " e TAEG " + textOf(apr) + "%. Valuta il rischio senza bloccare la scelta."));
        card.addView(evaluate, matchMargins(0, dp(7), 0, 0));
        Button save = primaryButton("Registra debito con avviso");
        save.setBackground(box(CORAL, dp(8), 0x99FFFFFF));
        save.setOnClickListener(v -> {
            double m = parse(monthly);
            double r = parse(remaining);
            if (clean(textOf(name)).isEmpty() || m <= 0 || r <= 0) {
                toast("Nome, residuo e rata sono obbligatori");
                return;
            }
            Runnable action = () -> {
                long start = parseDay(textOf(startDate), System.currentTimeMillis());
                long end = parseDay(textOf(endDate), 0);
                if (start == Long.MIN_VALUE || end == Long.MIN_VALUE) {
                    toast("Usa una data valida nel formato YYYY-MM-DD");
                    return;
                }
                if (end > 0 && end < start) {
                    toast("La fine stimata deve essere dopo l'inizio");
                    return;
                }
                db.addDebt(textOf(name), parse(total) <= 0 ? r : parse(total), r, m, parse(apr), start, end,
                        selected(category), selected(debtType), textOf(note));
                toast("Debito registrato: il Custode aggiorna budget e avatar");
                addMode = -1;
                show("agenda");
            };
            if (shouldWarnNewDebt()) {
                new AlertDialog.Builder(this)
                        .setTitle("Prudenza debito")
                        .setMessage("Il Custode ti consiglia prudenza: questo nuovo debito pu\u00f2 indebolire il budget e rallentare l'evoluzione del tuo avatar.")
                        .setPositiveButton("Registra comunque", (d, w) -> action.run())
                        .setNegativeButton("Annulla", null)
                        .show();
            } else {
                action.run();
            }
        });
        card.addView(save, matchMargins(0, dp(12), 0, 0));
        content.addView(card);
    }

    private boolean shouldWarnNewDebt() {
        EvoDb.LocalAiReport report = db.localAiReport();
        EvoDb.CheckIn checkIn = freshCheckIn();
        boolean debtPctHigh = report.debtRatio > 0.10;
        boolean lowMargin = report.income > 0 && report.margin < report.income * 0.08;
        boolean lowEmergency = report.emergencyFundMonths < 1;
        boolean stressed = checkIn != null && checkIn.stress >= 7;
        return debtPctHigh || lowMargin || lowEmergency || stressed;
    }

    private View debtLine(EvoDb.Debt debt) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(dp(12), dp(10), dp(12), dp(10));
        line.setBackground(box(FIELD, dp(6), LINE));
        line.addView(text(debt.name + " | " + debt.debtType, 14, INK, Typeface.BOLD));
        line.addView(text("Residuo " + money(debt.remaining) + " | rata " + money(debt.monthlyPayment) +
                " | TAEG " + String.format(Locale.ITALY, "%.2f%%", debt.apr), 12, MUTED, Typeface.NORMAL));
        Button payment = secondaryButton("Registra pagamento");
        payment.setOnClickListener(v -> askDebtPayment(debt));
        line.addView(payment, matchMargins(0, dp(6), 0, 0));
        line.setLayoutParams(matchMargins(0, dp(5), 0, dp(5)));
        return line;
    }

    private void askDebtPayment(EvoDb.Debt debt) {
        EditText amount = input("Pagamento EUR");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText(String.format(Locale.US, "%.2f", Math.min(debt.monthlyPayment, debt.remaining)));
        new AlertDialog.Builder(this)
                .setTitle("Pagamento " + debt.name)
                .setView(amount)
                .setPositiveButton("Registra", (dialog, which) -> {
                    double value = parse(amount);
                    if (value <= 0) {
                        toast("Importo non valido");
                        return;
                    }
                    db.payDebt(debt.id, value);
                    toast(value >= debt.remaining ? "Debito estinto" : "Residuo debito aggiornato");
                    show(active);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showCustodian() {
        header("Custode", "Conosce la schermata, legge i tuoi dati e risponde sul dispositivo.");
        LinearLayout tabs = row();
        tabs.addView(custodianTab("Dialogo", 0));
        tabs.addView(custodianTab("Panorama", 1));
        tabs.addView(custodianTab("Radar", 2));
        content.addView(tabs, matchMargins(0, 0, 0, dp(12)));
        if (custodianMode == 1) showCustodianAnalysis();
        else if (custodianMode == 2) showFinancialRadar();
        else showCustodianChat();
    }

    private Button custodianTab(String label, int mode) {
        Button button = quietButton(label);
        button.setTextSize(12);
        button.setTextColor(custodianMode == mode ? INK : MUTED);
        button.setBackground(box(custodianMode == mode ? 0xFF23312D : FIELD, dp(6),
                custodianMode == mode ? GREEN : LINE));
        button.setOnClickListener(v -> {
            custodianMode = mode;
            if (mode != 2) investmentTab = 0;
            show("custodian");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void showCustodianChat() {
        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(this, db);
        EvoDb.LocalAiReport report = db.localAiReport();
        ConversationMemory memory = new ConversationMemory(db);
        InternetFinanceConnector internet = new InternetFinanceConnector();
        int statusColor = report.score >= 75 ? GREEN : report.score >= 55 ? GOLD : CORAL;

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        status.setBackground(box(0xFF0D1516, dp(8), 0xFF31453E));
        LinearLayout top = row();
        top.addView(text(report.level, 16, statusColor, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(text(report.score + "/100", 16, INK, Typeface.BOLD));
        status.addView(top);
        status.addView(text(report.mainTask, 13, INK, Typeface.NORMAL), matchMargins(0, dp(6), 0, 0));
        status.addView(text("Sta leggendo: " + custodianSourceContext, 11, BLUE, Typeface.NORMAL),
                matchMargins(0, dp(5), 0, 0));
        LinearLayout memoryLine = row();
        memoryLine.addView(text("Motore: " + custodian.engineStatus(), 10, MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button memories = quietButton("Memoria " + db.aiMemories(ConversationMemory.CUSTODIAN, 100).size());
        memories.setTextSize(10);
        memories.setOnClickListener(v -> showAiMemory(ConversationMemory.CUSTODIAN));
        memoryLine.addView(memories, new LinearLayout.LayoutParams(dp(105), dp(38)));
        status.addView(memoryLine, matchMargins(0, dp(4), 0, 0));
        LinearLayout internetLine = row();
        internetLine.addView(text(internet.isDialogueResearchEnabled(this)
                        ? "Verifica fonti attiva: escono solo termini economici generali"
                        : "Internet disattivato nel dialogo: tutti i dati restano locali",
                10, internet.isDialogueResearchEnabled(this) ? GREEN : MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button internetToggle = quietButton(internet.isDialogueResearchEnabled(this)
                ? "Fonti ON" : "Fonti OFF");
        internetToggle.setTextSize(10);
        internetToggle.setOnClickListener(v -> toggleDialogueInternet(internet));
        internetLine.addView(internetToggle, new LinearLayout.LayoutParams(dp(105), dp(38)));
        status.addView(internetLine, matchMargins(0, dp(3), 0, 0));
        content.addView(status, matchMargins(0, 0, 0, dp(12)));

        content.addView(dialogueTopicStrip(ConversationMemory.CUSTODIAN));
        List<EvoDb.AiMessage> messages = db.aiMessages(ConversationMemory.CUSTODIAN, custodianTopic, 30);
        if (messages.isEmpty()) {
            content.addView(emptyLine("Inizia un dialogo",
                    "Collega una spesa a debiti, obiettivi, programmate e stato emotivo nella stessa domanda."));
        } else {
            for (EvoDb.AiMessage message : messages) content.addView(chatBubble(message, "Custode"));
        }
        if (ConversationMemory.CUSTODIAN.equals(thinkingPersona)) {
            content.addView(thinkingBubble("Custode", "Collego dati, memoria e possibili scenari..."));
        }

        LinearLayout quick = row();
        Button plan = quietButton("Piano 90 giorni");
        plan.setOnClickListener(v -> sendConversation(ConversationMemory.CUSTODIAN, custodianTopic,
                "Costruiamo un piano concreto per i prossimi 90 giorni collegando spese, programmate, debiti e obiettivi.", ""));
        Button links = quietButton("Collega i problemi");
        links.setOnClickListener(v -> sendConversation(ConversationMemory.CUSTODIAN, custodianTopic,
                "Quali collegamenti vedi tra le mie spese, lo stress, le rate e gli obiettivi?", ""));
        quick.addView(plan, weightedMargins(dp(3), 0, dp(4), 0, dp(44)));
        quick.addView(links, weightedMargins(dp(4), 0, dp(3), 0, dp(44)));
        content.addView(quick, matchMargins(0, dp(9), 0, dp(8)));

        LinearLayout composer = card();
        EditText question = input("Parla di una spesa, un dubbio o una decisione...");
        question.setSingleLine(false);
        question.setMinLines(2);
        composer.addView(question);
        Button send = actionButton("Invia", R.drawable.ic_chat, GREEN);
        send.setOnClickListener(v -> sendConversation(ConversationMemory.CUSTODIAN, custodianTopic,
                textOf(question), ""));
        composer.addView(send, matchMargins(0, dp(7), 0, 0));
        content.addView(composer);

        Button checkIn = quietButton("Aggiorna stato emotivo");
        checkIn.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_mood, BLUE), null, null, null);
        checkIn.setCompoundDrawablePadding(dp(8));
        checkIn.setOnClickListener(v -> showCheckInDialog());
        content.addView(checkIn, matchMargins(0, dp(7), 0, 0));

        List<String> tips = localAdvisorTips();
        content.addView(sectionHeader("Tre segnali utili", "Panorama", () -> {
            custodianMode = 1;
            show("custodian");
        }), matchMargins(0, dp(12), 0, 0));
        for (int i = 0; i < Math.min(3, tips.size()); i++) {
            content.addView(timelineLine("CUSTODE", tips.get(i).replaceFirst("^-\\s*", ""), "", GREEN));
        }
    }

    private View dialogueTopicStrip(String persona) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout topics = row();
        topics.setPadding(0, 0, dp(8), dp(8));
        String selected = ConversationMemory.isCompanion(persona) ? companionTopic : custodianTopic;
        for (String topic : ConversationMemory.TOPICS) {
            if (ConversationMemory.isCompanion(persona) && "Investimenti".equals(topic)) continue;
            Button chip = quietButton(topic);
            chip.setTextSize(10);
            boolean activeTopic = topic.equals(selected);
            chip.setTextColor(activeTopic ? BG : MUTED);
            chip.setBackground(box(activeTopic ? GREEN : FIELD, dp(18), activeTopic ? GREEN : LINE));
            chip.setOnClickListener(v -> {
                if (ConversationMemory.isCompanion(persona)) companionTopic = topic;
                else custodianTopic = topic;
                show(ConversationMemory.isCompanion(persona) ? "companion" : "custodian");
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            params.setMargins(0, 0, dp(6), 0);
            topics.addView(chip, params);
        }
        horizontal.addView(topics);
        return horizontal;
    }

    private View chatBubble(EvoDb.AiMessage message, String assistantName) {
        boolean user = "user".equals(message.speaker);
        LinearLayout shell = new LinearLayout(this);
        shell.setGravity(user ? Gravity.END : Gravity.START);
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(box(user ? 0xFF183B31 : 0xFF141C21, dp(8), user ? 0xFF367965 : LINE));
        String time = new SimpleDateFormat("HH:mm", Locale.ITALY).format(message.createdAt);
        bubble.addView(text((user ? "Tu" : assistantName) + "  |  " + message.topic + "  |  " + time,
                9, user ? GREEN : BLUE, Typeface.BOLD));
        bubble.addView(text(message.body, 13, INK, Typeface.NORMAL), matchMargins(0, dp(4), 0, 0));
        bubble.setContentDescription((user ? "Messaggio tuo" : "Risposta di " + assistantName) + ". " + message.body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(user ? dp(42) : 0, dp(4), user ? 0 : dp(42), dp(4));
        shell.addView(bubble, params);
        return shell;
    }

    private View thinkingBubble(String name, String message) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setBackground(box(0xFF111A1D, dp(8), 0xFF355B4E));
        thinkingBubbleText = text(name + "  " + message, 12, GREEN, Typeface.NORMAL);
        bubble.addView(thinkingBubbleText);
        return bubble;
    }

    private void sendConversation(String persona, String selectedTopic, String question, String creatureName) {
        String prompt = clean(question);
        if (prompt.isEmpty()) {
            toast("Scrivi un messaggio");
            return;
        }
        if (!thinkingPersona.isEmpty()) {
            toast("Sto gia preparando una risposta");
            return;
        }
        String context = screenContextText();
        ConversationMemory memory = new ConversationMemory(db);
        long userMessageId = memory.persistUser(persona, selectedTopic, prompt, context);
        thinkingPersona = persona;
        scrollConversationToBottom = true;
        show(ConversationMemory.isCompanion(persona) ? "companion" : "custodian");
        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(this, db);
        new Thread(() -> {
            InternetFinanceConnector.ResearchResult research = null;
            InternetFinanceConnector internet = new InternetFinanceConnector();
            if (ConversationMemory.CUSTODIAN.equals(persona) &&
                    internet.isDialogueResearchEnabled(this)) {
                research = internet.researchAndCache(db, prompt);
            }
            String enriched = memory.buildPrompt(persona, selectedTopic, prompt, context, creatureName);
            if (research != null) {
                enriched += research.ok
                        ? "\n[FONTE INTERNET AUTORIZZATA]\nQuery ripulita: " + research.query +
                        "\nSintesi: " + research.summary + "\nFonte: " + research.source +
                        "\nUsala solo come informazione verificabile, citando la fonte; non come ordine finanziario."
                        : "\n[ESITO VERIFICA INTERNET]\n" + research.message;
            }
            String assistantName = ConversationMemory.isCompanion(persona)
                    ? clean(creatureName) : "Custode";
            AiAnswer answer = custodian.ask(enriched, preview -> runOnUiThread(() -> {
                if (!persona.equals(thinkingPersona) || thinkingBubbleText == null) return;
                thinkingBubbleText.setText(assistantName + "  " + preview);
            }));
            if (answer == null) answer = new RuleBasedEngine().ask(custodian.context(), enriched);
            String savedContext = context + (research == null ? "" : " | " + research.message);
            memory.persistAnswer(persona, selectedTopic, prompt, answer, savedContext, userMessageId);
            runOnUiThread(() -> {
                thinkingPersona = "";
                scrollConversationToBottom = true;
                show(ConversationMemory.isCompanion(persona) ? "companion" : "custodian");
            });
        }, ConversationMemory.isCompanion(persona) ? "EvoCompanionDialogue" : "EvoCustodianDialogue").start();
    }

    private void toggleDialogueInternet(InternetFinanceConnector connector) {
        if (connector.isDialogueResearchEnabled(this)) {
            connector.setDialogueResearchEnabled(this, false);
            toast("Verifica Internet disattivata");
            show("custodian");
            return;
        }
        if (connector.hasConsent(this)) {
            connector.setDialogueResearchEnabled(this, true);
            toast("Verifica fonti attiva");
            show("custodian");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Verifica fonti su Internet")
                .setMessage("EvoConti non invia budget, importi, posizione, immagini, memoria o la frase completa. " +
                        "Estrae solo termini economici generali, cerca fonti istituzionali e salva sul telefono URL e data. " +
                        "Puoi spegnere la funzione in qualsiasi momento.")
                .setPositiveButton("Autorizzo", (dialog, which) -> {
                    connector.setConsent(this, true);
                    connector.setDialogueResearchEnabled(this, true);
                    toast("Verifica fonti attiva");
                    show("custodian");
                })
                .setNegativeButton("Resta locale", null)
                .show();
    }

    private void showAiMemory(String persona) {
        boolean companion = ConversationMemory.isCompanion(persona);
        List<EvoDb.AiMemory> memories = db.aiMemories(persona, 100);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(4), dp(4), dp(4), dp(4));
        body.addView(text("Fatti, preferenze, decisioni e soluzioni restano solo sul dispositivo.",
                12, MUTED, Typeface.NORMAL), matchMargins(0, 0, 0, dp(8)));
        if (memories.isEmpty()) {
            body.addView(text("Nessuna memoria consolidata. La cronologia del dialogo resta comunque disponibile.",
                    13, INK, Typeface.NORMAL));
        }
        for (EvoDb.AiMemory memory : memories) {
            LinearLayout line = row();
            line.setPadding(dp(10), dp(8), dp(4), dp(8));
            line.setBackground(box(FIELD, dp(6), LINE));
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.addView(text(memory.memoryType.toUpperCase(Locale.ITALY) + "  |  " + memory.topic,
                    9, companion ? GOLD : GREEN, Typeface.BOLD));
            copy.addView(text(memory.summary, 12, INK, Typeface.NORMAL));
            line.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageView forget = iconButton(R.drawable.ic_close, "Dimentica questa memoria", MUTED);
            forget.setOnClickListener(v -> {
                db.forgetAiMemory(memory.id);
                toast("Memoria rimossa");
                show(companion ? "companion" : "custodian");
            });
            line.addView(forget, new LinearLayout.LayoutParams(dp(42), dp(42)));
            body.addView(line, matchMargins(0, dp(3), 0, dp(3)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(companion ? "Memoria del Compagno" : "Memoria del Custode")
                .setView(scroll)
                .setPositiveButton("Chiudi", null)
                .setNegativeButton("Cancella cronologia", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("Cancellare la conversazione?")
                        .setMessage("Le memorie consolidate restano. Puoi eliminarle singolarmente dalla memoria.")
                        .setPositiveButton("Cancella", (confirm, action) -> {
                            db.clearAiConversation(persona);
                            toast("Cronologia cancellata");
                            show(companion ? "companion" : "custodian");
                        })
                        .setNegativeButton("Annulla", null)
                        .show())
                .show();
    }

    private String withScreenContext(String prompt) {
        return "Contesto visibile nell'app: " + screenContextText() + ". Domanda dell'utente: " + prompt;
    }

    private String screenContextText() {
        String visible;
        if (custodianMode == 1) visible = "Panorama mensile e grafico " + chartModeLabel();
        else if (custodianMode == 2) visible = "Radar Finanziario, watchlist e interessi";
        else if ("custodian".equals(active)) visible = custodianSourceContext;
        else visible = contextForSection(active);
        return visible;
    }

    private String contextForSection(String section) {
        if ("agenda".equals(section)) return "Agenda con movimenti, programmate e debiti";
        if ("companion".equals(section)) return "Compagno, salute avatar e obiettivo selezionato";
        if ("add".equals(section)) return "Inserimento finanziario in corso";
        if ("settings".equals(section)) return "Impostazioni, conti, categorie e motore locale";
        return "Casa con margine, impegni e salute budget";
    }

    private void showCheckInDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(6), 0, dp(6), 0);
        Spinner mood = spinner(list("Neutro", "Sereno", "Stressato", "Stanco", "Ansioso", "Impulsivo", "Motivato"));
        Spinner stress = spinner(list("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        Spinner energy = spinner(list("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        stress.setSelection(5);
        energy.setSelection(5);
        EditText note = input("Cosa sta succedendo?");
        body.addView(label("Umore"));
        body.addView(mood);
        body.addView(label("Stress"));
        body.addView(stress);
        body.addView(label("Energia"));
        body.addView(energy);
        body.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Check-in del Custode")
                .setView(body)
                .setPositiveButton("Salva", (dialog, which) -> {
                    db.addCheckIn(selected(mood), intFrom(selected(stress)), intFrom(selected(energy)), textOf(note));
                    db.syncLocalAiSteward();
                    toast("Check-in salvato");
                    show("custodian");
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showCustodianAnalysis() {
        EvoDb.LocalAiReport report = db.localAiReport();
        LinearLayout pulse = card();
        pulse.addView(text("Lettura del mese", 17, INK, Typeface.BOLD));
        pulse.addView(simpleLine("Essenziali", percent(report.essentialRatio) + "  guida morbida 50%"));
        pulse.addView(simpleLine("Desideri", percent(report.wantsRatio) + "  guida morbida 30%"));
        pulse.addView(simpleLine("Futuro", percent(report.futureRatio) + "  guida morbida 20%"));
        pulse.addView(simpleLine("Rate debiti", percent(report.debtRatio)));
        pulse.addView(simpleLine("Fondo emergenza", String.format(Locale.ITALY, "%.1f mesi", report.emergencyFundMonths)));
        content.addView(pulse);

        LinearLayout modeRowA = row();
        modeRowA.addView(chartButton("Giorni", FinanceChartView.MODE_DAILY));
        modeRowA.addView(chartButton("Categorie", FinanceChartView.MODE_CATEGORY));
        modeRowA.addView(chartButton("Conti", FinanceChartView.MODE_ACCOUNTS));
        content.addView(modeRowA, matchMargins(0, dp(8), 0, 0));
        LinearLayout modeRowB = row();
        modeRowB.addView(chartButton("Obiettivi", FinanceChartView.MODE_GOALS));
        modeRowB.addView(chartButton("Debiti", FinanceChartView.MODE_DEBTS));
        content.addView(modeRowB);

        FinanceChartView chart = new FinanceChartView(this);
        chart.setMode(chartMode);
        chart.setData(db.dailyExpenses(), db.budgetCategoryTotals(), db.budgetAccountTotals(), db.goals(), db.debts());
        chart.setPlannedTotal(db.fixedMonthly());
        chart.setModeChangeListener(mode -> {
            chartMode = mode;
            chart.post(() -> show("custodian"));
        });
        chart.setContentDescription("Grafico interattivo: " + chartModeLabel());
        content.addView(chart, matchMargins(0, dp(8), 0, dp(10)));
        showChartDetails();
    }

    private String chartModeLabel() {
        if (chartMode == FinanceChartView.MODE_CATEGORY) return "Categorie";
        if (chartMode == FinanceChartView.MODE_ACCOUNTS) return "Conti";
        if (chartMode == FinanceChartView.MODE_GOALS) return "Obiettivi";
        if (chartMode == FinanceChartView.MODE_DEBTS) return "Debiti";
        return "Giorni";
    }

    private void showFinancialRadar() {
        if (investmentTab != 0) {
            Button back = quietButton("Torna al Radar");
            back.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_back, MUTED), null, null, null);
            back.setCompoundDrawablePadding(dp(8));
            back.setOnClickListener(v -> {
                investmentTab = 0;
                show("custodian");
            });
            content.addView(back, matchMargins(0, 0, 0, dp(8)));
            if (investmentTab == 1) showInvestmentAssetTab("", true);
            else if (investmentTab == 2) showInvestmentAssetTab("ETF", false);
            else if (investmentTab == 3) showInvestmentAssetTab("azione", false);
            else if (investmentTab == 4) showInvestmentAssetTab("crypto", false);
            else if (investmentTab == 5) showInterestTab();
            else showStudyAndModel();
            return;
        }

        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(this, db);
        AiAnswer answer = custodian.analyze();
        EvoDb.InvestmentSummary summary = db.investmentSummary();
        LinearLayout radar = new LinearLayout(this);
        radar.setOrientation(LinearLayout.VERTICAL);
        radar.setPadding(dp(16), dp(16), dp(16), dp(14));
        radar.setBackground(box(0xFF0A1214, dp(8), 0xFF376A5B));
        radar.addView(text("RADAR FINANZIARIO", 12, GREEN, Typeface.BOLD));
        radar.addView(text(answer.stato, 24, INK, Typeface.BOLD), matchMargins(0, dp(4), 0, 0));
        radar.addView(text(answer.messaggioBreve, 13, MUTED, Typeface.NORMAL),
                matchMargins(0, dp(4), 0, dp(12)));
        LinearLayout values = row();
        values.addView(metric("Investito", money(summary.totalInvested), GOLD));
        values.addView(metricDivider());
        values.addView(metric("Valore", money(summary.currentValue), BLUE));
        values.addView(metricDivider());
        values.addView(metric("Differenza", money(summary.gainLoss), summary.gainLoss >= 0 ? GREEN : CORAL));
        radar.addView(values);
        radar.addView(riskBar(answer.rischio), matchMargins(0, dp(10), 0, 0));
        content.addView(radar);

        InvestmentChartView chart = new InvestmentChartView(this);
        chart.setData(db.investmentAssets(""));
        content.addView(chart, matchMargins(0, dp(8), 0, dp(8)));

        LinearLayout first = row();
        first.addView(radarTile("Watchlist", "Asset da studiare", R.drawable.ic_watchlist, BLUE, 1));
        first.addView(radarTile("Interessi", "Attivi e passivi", R.drawable.ic_interest, GOLD, 5));
        content.addView(first);
        LinearLayout second = row();
        second.addView(radarTile("Studio", "Note e motore locale", R.drawable.ic_study, GREEN, 6));
        second.addView(radarTile("Aggiorna", "Fonti autorizzate", R.drawable.ic_refresh, BLUE, -1));
        content.addView(second);

        Button ask = actionButton("Chiedi al Custode del Radar", R.drawable.ic_chat, GREEN);
        ask.setOnClickListener(v -> showAdvisorAnswer("Valuta con prudenza il mio Radar Finanziario rispetto al budget di questo mese."));
        content.addView(ask, matchMargins(dp(3), dp(10), dp(3), 0));
    }

    private View radarTile(String label, String sub, int iconRes, int color, int tab) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(13), dp(14), dp(12));
        tile.setBackground(box(SURFACE, dp(8), LINE));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tintedIcon(iconRes, color));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        tile.addView(text(label, 15, INK, Typeface.BOLD), matchMargins(0, dp(8), 0, 0));
        tile.addView(text(sub, 11, MUTED, Typeface.NORMAL));
        tile.setOnClickListener(v -> {
            if (tab < 0) refreshFinanceData();
            else {
                investmentTab = tab;
                show("custodian");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        tile.setLayoutParams(lp);
        return tile;
    }

    private void showStudyAndModel() {
        LinearLayout study = card();
        study.addView(text("Promemoria di studio", 17, INK, Typeface.BOLD));
        study.addView(text("ETF: costo, indice, replica, area e valuta.", 13, MUTED, Typeface.NORMAL), matchMargins(0, dp(8), 0, 0));
        study.addView(text("Azioni: settore, bilancio, volatilit\u00e0 e peso massimo.", 13, MUTED, Typeface.NORMAL), matchMargins(0, dp(6), 0, 0));
        study.addView(text("Crypto: rischio alto, mai finanziata con debito.", 13, MUTED, Typeface.NORMAL), matchMargins(0, dp(6), 0, 0));
        content.addView(study);
        showModelPanel(false);
        showFinanceCachePreview();
    }

    private void showSettings() {
        Button back = quietButton("Torna a Casa");
        back.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_back, MUTED), null, null, null);
        back.setCompoundDrawablePadding(dp(8));
        back.setOnClickListener(v -> {
            settingsMode = 0;
            show("home");
        });
        content.addView(back, matchMargins(0, 0, 0, dp(8)));
        if (settingsMode == 1) {
            header("Categorie", "Poche categorie chiare rendono migliore anche il Custode.");
            showCategoryManager();
            return;
        }
        if (settingsMode == 2) {
            header("Motore AI locale", "EvoCore separato, fallback prudente sempre disponibile.");
            showModelPanel(true);
            return;
        }
        if (settingsMode == 3) {
            header("Dati ed export", "I tuoi dati restano sul dispositivo finche non scegli di esportarli.");
            showExport();
            showFinanceCachePreview();
            return;
        }
        if (settingsMode == 4) {
            header("Conti e carte", "I contenitori usati per organizzare movimenti e grafici.");
            for (String account : db.listAccounts()) content.addView(simpleLine(account, "attivo"));
            showAccountForm();
            return;
        }

        header("Impostazioni", "Solo le cose che servono davvero.");
        content.addView(settingsRow("Conti e carte", "Gestisci i conti usati nei movimenti", R.drawable.ic_account, 4));
        content.addView(settingsRow("Categorie", "Mantieni semplice la classificazione", R.drawable.ic_categories, 1));
        content.addView(settingsRow("Motore AI locale", new LocalInvestmentCustodian(this, db).engineStatus(), R.drawable.ic_custodian, 2));
        content.addView(settingsRow("Dati ed export", "CSV, fonti Internet e cache locale", R.drawable.ic_export, 3));
        content.addView(text("EvoConti " + appVersion() + "  |  dati privati sul dispositivo",
                11, MUTED, Typeface.NORMAL), matchMargins(dp(4), dp(18), 0, 0));
    }

    private View settingsRow(String label, String sub, int iconRes, int mode) {
        LinearLayout line = row();
        line.setPadding(dp(14), dp(12), dp(12), dp(12));
        line.setBackground(box(SURFACE, dp(7), LINE));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tintedIcon(iconRes, GREEN));
        line.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(label, 15, INK, Typeface.BOLD));
        copy.addView(text(sub, 11, MUTED, Typeface.NORMAL));
        line.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageDrawable(tintedIcon(R.drawable.ic_chevron, MUTED));
        line.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        line.setOnClickListener(v -> {
            settingsMode = mode;
            show("settings");
        });
        line.setLayoutParams(matchMargins(0, dp(4), 0, dp(4)));
        return line;
    }

    private void showModelPanel(boolean includeActions) {
        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(this, db);
        EvoCoreGateway gateway = EvoCoreGateway.get(this);
        LinearLayout model = card();
        model.addView(text("EvoCore separato", 17, INK, Typeface.BOLD));
        model.addView(simpleLine("Collegamento", custodian.engineStatus()));
        model.addView(simpleLine("Installazione", gateway.isInstalled() ? "APK EvoCore presente" : "APK EvoCore richiesto"));
        model.addView(text("EvoConti invia solo il contesto sintetico della schermata. Modello e memoria personale restano nel caveau EvoCore.",
                12, MUTED, Typeface.NORMAL), matchMargins(0, dp(8), 0, dp(6)));
        Button open = actionButton(gateway.isInstalled() ? "Apri EvoCore" : "EvoCore non installato",
                R.drawable.ic_custodian, GREEN);
        open.setOnClickListener(v -> {
            if (!gateway.openHost()) toast("Installa prima l'APK EvoCore fornito insieme a EvoConti");
        });
        model.addView(open);
        LocalModelManager legacy = new LocalModelManager(this);
        if (includeActions && legacy.hasLegacyFiles()) {
            Button cleanup = quietButton("Rimuovi vecchio modello da EvoConti");
            cleanup.setTextColor(CORAL);
            cleanup.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Liberare lo spazio del vecchio modello?")
                    .setMessage("Elimina solo i file GGUF delle versioni precedenti. Movimenti, budget e impostazioni non cambiano.")
                    .setPositiveButton("Rimuovi", (dialog, which) -> {
                        toast(legacy.deleteActive() ? "Vecchio modello rimosso" : "Alcuni file non sono stati rimossi");
                        show("settings");
                    })
                    .setNegativeButton("Annulla", null)
                    .show());
            model.addView(cleanup);
        }
        content.addView(model);
    }

    private void showCharts() {
        header("Grafici", "Andamento, categorie, conti, obiettivi e debiti");
        LinearLayout modes = row();
        modes.addView(chartButton("Giorni", FinanceChartView.MODE_DAILY));
        modes.addView(chartButton("Categorie", FinanceChartView.MODE_CATEGORY));
        modes.addView(chartButton("Conti", FinanceChartView.MODE_ACCOUNTS));
        modes.addView(chartButton("Obiettivi", FinanceChartView.MODE_GOALS));
        modes.addView(chartButton("Debiti", FinanceChartView.MODE_DEBTS));
        content.addView(modes);

        FinanceChartView chart = new FinanceChartView(this);
        chart.setMode(chartMode);
        chart.setData(db.dailyExpenses(), db.categoryTotals(), db.accountTotals(), db.goals(), db.debts());
        chart.setModeChangeListener(mode -> {
            chartMode = mode;
            show("charts");
        });
        content.addView(chart, matchMargins(0, dp(12), 0, dp(12)));

        showChartDetails();
    }

    private Button chartButton(String text, int mode) {
        Button b = secondaryButton(text);
        if (chartMode == mode) {
            b.setTextColor(Color.WHITE);
            b.setBackground(box(BLUE, dp(10), 0));
        }
        b.setOnClickListener(v -> {
            chartMode = mode;
            show("charts");
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        return b;
    }

    private void showChartDetails() {
        if (chartMode == FinanceChartView.MODE_DEBTS) {
            content.addView(title("Dettaglio debiti", 18));
            List<EvoDb.Debt> debts = db.debts();
            if (debts.isEmpty()) content.addView(infoCard("Nessun debito", "I debiti compariranno qui senza essere trattati come spese normali.", BLUE));
            for (EvoDb.Debt d : debts) {
                content.addView(debtLine(d));
            }
            return;
        }
        if (chartMode == FinanceChartView.MODE_ACCOUNTS) {
            content.addView(title("Dettaglio conti", 18));
            Map<String, Double> totals = db.accountTotals();
            if (totals.isEmpty()) content.addView(infoCard("Nessun movimento", "Aggiungi una spesa o entrata collegata a un conto.", BLUE));
            for (Map.Entry<String, Double> e : totals.entrySet()) content.addView(simpleLine(e.getKey(), money(e.getValue())));
            return;
        }
        if (chartMode == FinanceChartView.MODE_GOALS) {
            content.addView(title("Dettaglio obiettivi", 18));
            List<EvoDb.Goal> goals = db.goals();
            if (goals.isEmpty()) content.addView(infoCard("Nessun obiettivo", "Crea un obiettivo e il grafico diventa reattivo.", BLUE));
            for (EvoDb.Goal g : goals) content.addView(simpleLine(g.name, money(g.saved) + " / " + money(g.target)));
            return;
        }
        content.addView(title("Riepilogo categorie", 18));
        Map<String, Double> totals = db.categoryTotals();
        if (totals.isEmpty()) {
            content.addView(infoCard("Nessun dato", "Aggiungi qualche spesa per vedere i grafici.", BLUE));
        } else {
            for (Map.Entry<String, Double> e : totals.entrySet()) {
                content.addView(simpleLine(e.getKey(), money(e.getValue())));
            }
        }
    }

    private void showMonsters() {
        List<EvoDb.Goal> goals = db.goals();
        if (avatarTab != 0) {
            Button back = quietButton("Torna al Compagno");
            back.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_back, MUTED), null, null, null);
            back.setCompoundDrawablePadding(dp(8));
            back.setOnClickListener(v -> {
                avatarTab = 0;
                show("companion");
            });
            content.addView(back, matchMargins(0, 0, 0, dp(8)));
            if (avatarTab == 3) {
                EvoDb.Goal talkingGoal = selectedGoalId > 0 ? db.goal(selectedGoalId) : null;
                if (talkingGoal == null && !goals.isEmpty()) talkingGoal = goals.get(0);
                if (talkingGoal == null) {
                    header("Compagno", "Crea prima un obiettivo e incontra la tua creatura.");
                    return;
                }
                header("Parla con " + CreatureCatalog.name(talkingGoal.creature),
                        "Ricorda i vostri dialoghi e conosce il viaggio economico collegato.");
                showCompanionConversation(talkingGoal);
            } else if (avatarTab == 2) {
                header("Collezione", "Solo le creature incontrate. Le altre restano davvero segrete.");
                showCollection();
            } else {
                header("Obiettivi", "Ogni obiettivo ha un solo compagno e una propria cabina.");
                if (goals.isEmpty()) content.addView(emptyLine("Nessun obiettivo attivo",
                        "Il primo obiettivo assegnera una creatura casuale."));
                for (EvoDb.Goal goal : goals) content.addView(goalSelectorLine(goal));
                showGoalForm();
            }
            return;
        }

        header("Compagno", "Una creatura, una scena viva, un obiettivo alla volta.");
        EvoDb.Goal selectedGoal = selectedGoalId > 0 ? db.goal(selectedGoalId) : null;
        if (selectedGoal == null && !goals.isEmpty()) selectedGoal = goals.get(0);
        if (selectedGoal == null) {
            content.addView(emptyLine("La cabina \u00e8 ancora vuota",
                    "Crea un obiettivo: l'app sorteggia una creatura e apre la sua scena personale."));
            showGoalForm();
            return;
        }
        selectedGoalId = selectedGoal.id;
        content.addView(goalCard(selectedGoal));

        LinearLayout destinations = row();
        Button goalsButton = quietButton("Obiettivi");
        goalsButton.setOnClickListener(v -> {
            avatarTab = 1;
            show("companion");
        });
        Button collection = quietButton("Collezione");
        collection.setOnClickListener(v -> {
            avatarTab = 2;
            show("companion");
        });
        destinations.addView(goalsButton, weightedMargins(dp(3), 0, dp(4), 0, dp(46)));
        destinations.addView(collection, weightedMargins(dp(4), 0, dp(3), 0, dp(46)));
        content.addView(destinations, matchMargins(0, dp(9), 0, 0));
    }

    private Button avatarTabButton(String text, int mode) {
        Button button = secondaryButton(text);
        if (avatarTab == mode) {
            button.setTextColor(Color.WHITE);
            button.setBackground(box(GOLD, dp(8), 0));
        }
        button.setOnClickListener(v -> {
            avatarTab = mode;
            show("monsters");
        });
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        return button;
    }

    private View goalSelectorLine(EvoDb.Goal goal) {
        LinearLayout line = card();
        double progress = goal.target <= 0 ? 0 : goal.saved / goal.target;
        line.addView(text(goal.name + " | " + CreatureCatalog.name(goal.creature), 15, INK, Typeface.BOLD));
        line.addView(text(Math.round(progress * 100) + "% | " + CreatureCatalog.stageLabel(goal.stage) +
                " | salute " + goal.hp + "/100", 12, MUTED, Typeface.NORMAL));
        Button open = secondaryButton("Apri la cabina");
        open.setOnClickListener(v -> {
            selectedGoalId = goal.id;
            avatarTab = 0;
            show("monsters");
        });
        line.addView(open, matchMargins(0, dp(6), 0, 0));
        return line;
    }

    private void showGoalForm() {
        LinearLayout card = card();
        card.addView(title("Crea obiettivo", 18));
        EditText name = input("Nome obiettivo");
        EditText finality = input("Finalizzazione, es. fondo emergenza");
        EditText target = input("Quanto vuoi risparmiare EUR");
        target.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner months = spinner(list("1 mese", "4 mesi", "12 mesi", "36 mesi", "120 mesi"));
        Spinner category = spinner(db.listCategories());
        TextView feasibility = text("", 13, MUTED, Typeface.NORMAL);
        AdapterView.OnItemSelectedListener recalc = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                feasibility.setText(goalFeasibility(parse(target), monthsFrom(selected(months))));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        months.setOnItemSelectedListener(recalc);
        target.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                feasibility.setText(goalFeasibility(parse(target), monthsFrom(selected(months))));
            }
        });
        card.addView(name);
        card.addView(finality);
        card.addView(target);
        card.addView(label("Durata"));
        card.addView(months);
        card.addView(label("Categoria collegata"));
        card.addView(category);
        card.addView(feasibility);
        Button save = primaryButton("Crea obiettivo e assegna creatura");
        save.setOnClickListener(v -> {
            if (db.activeGoalCount() >= 7) {
                toast("Per ora il limite \u00e8 7 obiettivi attivi");
                return;
            }
            double value = parse(target);
            if (value <= 0 || clean(textOf(name)).isEmpty()) {
                toast("Nome e importo sono obbligatori");
                return;
            }
            int m = monthsFrom(selected(months));
            int creature = CreatureCatalog.chooseSpecies(System.currentTimeMillis() + (long) value, m);
            selectedGoalId = db.addGoal(textOf(name), textOf(finality), selected(category), value, m, creature);
            avatarTab = 0;
            toast("Creatura assegnata: " + CreatureCatalog.name(creature));
            show("monsters");
        });
        card.addView(save, matchMargins(0, dp(12), 0, 0));
        content.addView(card);
    }

    private View goalCard(EvoDb.Goal goal) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        CreatureView cv = new CreatureView(this);
        EvoDb.LocalAiReport report = db.localAiReport();
        AvatarContextResolver.Result avatarContext = currentAvatarContext();
        cv.setCreature(goal.creature, goal.stage, goal.hp);
        cv.setHealthScore(report.avatarHp);
        cv.setAutoSceneMode(avatarContext.activity);
        cv.setEnvironmentMode(avatarContext.environment);
        applyPendingAvatarReaction(cv);
        FrameLayout scene = new FrameLayout(this);
        scene.setBackground(box(0xFF080C10, dp(8), GOLD));
        scene.addView(cv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView state = text(sceneStatus(report, avatarContext), 11,
                report.score >= 60 ? GREEN : CORAL, Typeface.BOLD);
        state.setPadding(dp(10), dp(6), dp(10), dp(6));
        state.setBackground(box(0xDD0A0E12, dp(5), report.score >= 60 ? GREEN : CORAL));
        FrameLayout.LayoutParams stateParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        stateParams.setMargins(0, dp(12), dp(12), 0);
        scene.addView(state, stateParams);
        card.addView(scene, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(365)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(15), dp(14), dp(15), dp(14));
        details.setBackground(box(SURFACE, dp(8), LINE));
        LinearLayout heading = row();
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(CreatureCatalog.name(goal.creature), 20, GOLD, Typeface.BOLD));
        names.addView(text(goal.name + (clean(goal.finality).isEmpty() ? "" : "  |  " + goal.finality),
                12, MUTED, Typeface.NORMAL));
        heading.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heading.addView(text(CreatureCatalog.stageLabel(goal.stage), 12, GREEN, Typeface.BOLD));
        details.addView(heading);

        double progress = goal.target <= 0 ? 0 : Math.min(1, goal.saved / goal.target);
        LinearLayout goalLine = row();
        goalLine.setPadding(0, dp(12), 0, dp(4));
        goalLine.addView(text("Obiettivo", 11, MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        goalLine.addView(text(money(goal.saved) + " / " + money(goal.target), 11, INK, Typeface.BOLD));
        details.addView(goalLine);
        details.addView(progressBar((int) Math.round(progress * 100), GREEN));

        LinearLayout healthLine = row();
        healthLine.setPadding(0, dp(10), 0, dp(4));
        healthLine.addView(text("Salute economica", 11, MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        healthLine.addView(text(report.avatarHp + "/100  " + report.avatarState, 11,
                report.avatarHp >= 60 ? GREEN : CORAL, Typeface.BOLD));
        details.addView(healthLine);
        details.addView(progressBar(report.avatarHp, report.avatarHp >= 60 ? GREEN : CORAL));
        double daily = goal.target / Math.max(1, goal.months * 30d);
        details.addView(text(companionThought(report, daily), 12, MUTED, Typeface.NORMAL),
                matchMargins(0, dp(10), 0, dp(8)));

        Button place = quietButton(avatarContext.placeLabel + "  |  Imposta luogo");
        place.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_location, BLUE), null, null, null);
        place.setCompoundDrawablePadding(dp(6));
        place.setOnClickListener(v -> configureAvatarPlace());
        details.addView(place, matchMargins(0, 0, 0, dp(7)));

        LinearLayout buttons = row();
        Button interact = quietButton("Saluta");
        interact.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_mood, GOLD), null, null, null);
        interact.setCompoundDrawablePadding(dp(5));
        interact.setOnClickListener(v -> {
            cv.react(CreatureView.REACTION_GREETING);
            toast(report.score >= 60 ? "Ti saluta con energia" : "Ti riconosce, ma oggi ha bisogno di calma");
        });
        Button talk = quietButton("Parla");
        talk.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_chat, BLUE), null, null, null);
        talk.setCompoundDrawablePadding(dp(5));
        talk.setOnClickListener(v -> {
            avatarTab = 3;
            show("companion");
        });
        Button save = quietButton("Risparmio");
        save.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_income, GREEN), null, null, null);
        save.setCompoundDrawablePadding(dp(5));
        save.setOnClickListener(v -> {
            cv.react(CreatureView.REACTION_HAPPY);
            askSavings(goal);
        });
        buttons.addView(interact, weightedMargins(dp(2), 0, dp(2), 0, dp(44)));
        buttons.addView(talk, weightedMargins(dp(2), 0, dp(2), 0, dp(44)));
        buttons.addView(save, weightedMargins(dp(2), 0, dp(2), 0, dp(44)));
        details.addView(buttons);
        card.addView(details, matchMargins(0, dp(8), 0, 0));
        return card;
    }

    private void showCompanionConversation(EvoDb.Goal goal) {
        String name = CreatureCatalog.name(goal.creature);
        String persona = companionPersona(goal);
        AvatarContextResolver.Result avatarContext = currentAvatarContext();
        CreatureView creature = new CreatureView(this);
        EvoDb.LocalAiReport report = db.localAiReport();
        creature.setCreature(goal.creature, goal.stage, goal.hp);
        creature.setHealthScore(report.avatarHp);
        creature.setAutoSceneMode(avatarContext.activity);
        creature.setEnvironmentMode(avatarContext.environment);
        applyPendingAvatarReaction(creature);
        content.addView(creature, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(245)));

        LinearLayout memoryLine = row();
        memoryLine.addView(text(avatarContext.status(), 11, GOLD, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button memory = quietButton("Memoria " + db.aiMemories(persona, 100).size());
        memory.setTextSize(10);
        memory.setOnClickListener(v -> showAiMemory(persona));
        memoryLine.addView(memory, new LinearLayout.LayoutParams(dp(105), dp(38)));
        content.addView(memoryLine, matchMargins(0, dp(5), 0, dp(7)));
        content.addView(dialogueTopicStrip(persona));

        List<EvoDb.AiMessage> messages = db.aiMessages(persona, companionTopic, 30);
        if (messages.isEmpty()) {
            content.addView(emptyLine(name + " ti ascolta",
                    "Puoi raccontargli un obiettivo, una preoccupazione o una piccola vittoria."));
        } else {
            for (EvoDb.AiMessage message : messages) content.addView(chatBubble(message, name));
        }
        if (persona.equals(thinkingPersona)) {
            content.addView(thinkingBubble(name, "Ricordo quello che mi hai detto e ci penso..."));
        }

        LinearLayout quick = row();
        Button remember = quietButton("Cosa ricordi?");
        remember.setOnClickListener(v -> sendConversation(persona, companionTopic,
                "Cosa ricordi di me e del nostro obiettivo?", name));
        Button together = quietButton("Come stiamo?");
        together.setOnClickListener(v -> sendConversation(persona, companionTopic,
                "Come stiamo andando e qual e un piccolo passo che possiamo fare insieme oggi?", name));
        quick.addView(remember, weightedMargins(dp(3), 0, dp(4), 0, dp(44)));
        quick.addView(together, weightedMargins(dp(4), 0, dp(3), 0, dp(44)));
        content.addView(quick, matchMargins(0, dp(8), 0, dp(8)));

        LinearLayout composer = card();
        EditText input = input("Scrivi a " + name + "...");
        input.setSingleLine(false);
        input.setMinLines(2);
        composer.addView(input);
        Button send = actionButton("Invia a " + name, R.drawable.ic_chat, BLUE);
        send.setOnClickListener(v -> sendConversation(persona, companionTopic, textOf(input), name));
        composer.addView(send, matchMargins(0, dp(7), 0, 0));
        content.addView(composer);
    }

    private String companionPersona(EvoDb.Goal goal) {
        return ConversationMemory.COMPANION + "_" + goal.id;
    }

    private ProgressBar progressBar(int value, int color) {
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(Math.max(0, Math.min(100, value)));
        progress.setProgressTintList(ColorStateList.valueOf(color));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF28302E));
        progress.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7)));
        return progress;
    }

    private String sceneStatus(EvoDb.LocalAiReport report, AvatarContextResolver.Result context) {
        if (report.avatarHp < 45) return "Ha bisogno di te";
        return context.status();
    }

    private String companionThought(EvoDb.LocalAiReport report, double daily) {
        if (report.score < 45) return "Oggi niente corse: proteggiamo il margine e rimettiamoci in forze.";
        if (report.debtRatio > 0.10) return "Le rate pesano sul viaggio. Una riduzione vale pi\u00f9 di una spinta casuale.";
        if (report.score >= 90) return "Il budget \u00e8 forte. La quota guida resta " + money(daily) + " al giorno.";
        return "Procediamo con ritmo: quota guida " + money(daily) + " al giorno, senza rigidit\u00e0.";
    }

    private void showCollection() {
        List<EvoDb.PokeRow> rows = db.pokedex();
        int discovered = 0;
        for (EvoDb.PokeRow row : rows) if (row.discovered) discovered++;
        content.addView(title("Collezione", 18));
        LinearLayout card = card();
        card.addView(text("Scoperti " + discovered + " / " + CreatureCatalog.SPECIES_COUNT, 16, BLUE, Typeface.BOLD));
        if (discovered == 0) {
            card.addView(text("Gli altri avatar restano nascosti finche non crei nuovi obiettivi.", 13, MUTED, Typeface.NORMAL));
        } else {
            for (EvoDb.PokeRow row : rows) {
                if (!row.discovered) continue;
                card.addView(simpleLine(CreatureCatalog.name(row.species), CreatureCatalog.stageLabel(row.maxStage)));
            }
            int locked = CreatureCatalog.SPECIES_COUNT - discovered;
            card.addView(text("Nascosti da sbloccare: " + locked, 13, MUTED, Typeface.NORMAL));
        }
        content.addView(card);
    }

    private void askSavings(EvoDb.Goal goal) {
        EditText input = input("Importo risparmiato EUR");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new AlertDialog.Builder(this)
                .setTitle("Aggiungi risparmio")
                .setView(input)
                .setPositiveButton("Salva", (d, w) -> {
                    db.addGoalSavings(goal.id, parse(input));
                    show("monsters");
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private int autoAvatarScene() {
        return currentAvatarContext().activity;
    }

    private void applyPendingAvatarReaction(CreatureView creature) {
        if (creature != null && System.currentTimeMillis() < avatarReactionUntil) {
            creature.react(avatarReaction);
        }
    }

    private AvatarContextResolver.Result currentAvatarContext() {
        if (avatarLocation == null || Math.abs(System.currentTimeMillis() - avatarLocation.getTime()) > 10L * 60L * 1000L) {
            avatarLocation = bestLastLocation();
        }
        return new AvatarContextResolver().resolve(db, Calendar.getInstance(), avatarLocation);
    }

    private Location bestLastLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return null;
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location gps = null;
        Location network = null;
        try {
            gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException ignored) {
        }
        if (gps == null) return network;
        if (network == null) return gps;
        long gpsAge = Math.abs(System.currentTimeMillis() - gps.getTime());
        long networkAge = Math.abs(System.currentTimeMillis() - network.getTime());
        double gpsScore = gpsAge / 1000d + (gps.hasAccuracy() ? gps.getAccuracy() * 2d : 500d);
        double networkScore = networkAge / 1000d + (network.hasAccuracy() ? network.getAccuracy() * 2d : 500d);
        return gpsScore <= networkScore ? gps : network;
    }

    private void configureAvatarPlace() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_AVATAR_LOCATION_PERMISSION);
            return;
        }
        Location cached = bestLastLocation();
        if (cached != null && Math.abs(System.currentTimeMillis() - cached.getTime()) <= 10L * 60L * 1000L) {
            chooseAvatarPlace(cached);
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            String provider;
            try {
                provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
                manager.getCurrentLocation(provider, null, getMainExecutor(), location -> {
                    if (location == null) toast("Posizione non disponibile ora");
                    else chooseAvatarPlace(location);
                });
            } catch (SecurityException | IllegalArgumentException error) {
                toast("Posizione non disponibile ora");
            }
        } else if (cached != null) {
            chooseAvatarPlace(cached);
        } else {
            toast("Apri la posizione del telefono e riprova");
        }
    }

    private void chooseAvatarPlace(Location location) {
        String[] labels = {"Casa", "Natura o parco", "Lavoro o studio", "Negozio o supermercato", "Citta o passeggiata"};
        String[] types = {AvatarContextResolver.PLACE_HOME, AvatarContextResolver.PLACE_NATURE,
                AvatarContextResolver.PLACE_WORK, AvatarContextResolver.PLACE_SHOP, AvatarContextResolver.PLACE_CITY};
        new AlertDialog.Builder(this)
                .setTitle("Dove siete adesso?")
                .setItems(labels, (dialog, which) -> {
                    avatarLocation = location;
                    db.saveAvatarPlace(labels[which], types[which], location.getLatitude(), location.getLongitude(), 180);
                    toast("Luogo ricordato solo sul telefono");
                    show("companion");
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private EvoDb.CheckIn freshCheckIn() {
        EvoDb.CheckIn checkIn = db.latestCheckIn();
        if (checkIn == null) return null;
        long age = System.currentTimeMillis() - checkIn.createdAt;
        return age <= 7L * 24 * 60 * 60 * 1000 ? checkIn : null;
    }

    private void showMore() {
        header("Extra", "Suggerimenti, investimenti e gestione");
        LinearLayout modes = row();
        modes.addView(moreButton("Suggerimenti", 0));
        modes.addView(moreButton("Investimenti", 1));
        modes.addView(moreButton("Gestione", 2));
        content.addView(modes);
        if (moreMode == 1) showInvestments();
        else if (moreMode == 2) showManagement();
        else showSuggestions();
    }

    private Button moreButton(String text, int mode) {
        Button b = secondaryButton(text);
        if (moreMode == mode) {
            b.setTextColor(Color.WHITE);
            b.setBackground(box(BLUE, dp(10), 0));
        }
        b.setOnClickListener(v -> {
            moreMode = mode;
            show("more");
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        return b;
    }

    private void showSuggestions() {
        content.addView(infoCard("AI locale economica", "Lavora sui dati dell'app: entrate, spese, obiettivi, GPS, foto, scontrini, barcode, voce e check-in emotivi. Sul tuo S25 Ultra possiamo usarla come base per un modello locale quantizzato.", GREEN));
        EvoDb.LocalAiReport report = db.localAiReport();
        LinearLayout brain = card();
        brain.addView(text("Motore decisionale", 18, report.score >= 75 ? GREEN : (report.score >= 55 ? GOLD : CORAL), Typeface.BOLD));
        brain.addView(simpleLine("Salute economica", report.score + "/100"));
        brain.addView(simpleLine("Stato avatar", report.level + " - " + report.avatarState));
        brain.addView(simpleLine("Task affidato", report.mainTask));
        brain.addView(simpleLine("Essenziali", percent(report.essentialRatio) + " / guida 50%"));
        brain.addView(simpleLine("Desideri", percent(report.wantsRatio) + " / guida 30%"));
        brain.addView(simpleLine("Futuro", percent(report.futureRatio) + " / guida 20%"));
        brain.addView(simpleLine("Rate debiti", percent(report.debtRatio) + " / attenzione 10-20%"));
        brain.addView(simpleLine("Debito mensile", money(report.debtMonthly)));
        brain.addView(simpleLine("Margine reale", money(report.margin)));
        content.addView(brain);
        showEmotionalCheckIn();
        showAdvisorDialogCard();
        LinearLayout card = card();
        card.addView(title("Suggerimenti operativi", 18));
        for (String tip : localAdvisorTips()) {
            card.addView(text(tip, 13, MUTED, Typeface.NORMAL), matchMargins(0, dp(6), 0, 0));
        }
        content.addView(card);
        showContextMemory();

        LinearLayout budget = card();
        budget.addView(title("Budget vivo", 18));
        double income = db.monthlyIncome();
        double fixed = db.fixedMonthly();
        double spent = db.monthExpense();
        double margin = income - fixed - spent;
        budget.addView(simpleLine("Entrate considerate", money(income)));
        budget.addView(simpleLine("Programmate e fisse", money(fixed)));
        budget.addView(simpleLine("Spese mese", money(spent)));
        budget.addView(simpleLine("Margine stimato", money(margin)));
        content.addView(budget);
    }

    private void showEmotionalCheckIn() {
        LinearLayout card = card();
        card.addView(title("Stato emotivo", 18));
        EvoDb.CheckIn latest = db.latestCheckIn();
        if (latest == null) {
            card.addView(text("Nessun check-in salvato. Bastano dieci secondi: aiuta l'assistente a capire se stai spendendo per bisogno, stress o impulso.", 13, MUTED, Typeface.NORMAL));
        } else {
            card.addView(text("Ultimo: " + latest.mood + " | stress " + latest.stress + "/10 | energia " + latest.energy + "/10", 13, MUTED, Typeface.BOLD));
            if (!clean(latest.note).isEmpty()) card.addView(text(latest.note, 13, MUTED, Typeface.NORMAL));
            if (System.currentTimeMillis() - latest.createdAt > 7L * 24 * 60 * 60 * 1000) {
                card.addView(text("Check-in scaduto: resta nello storico ma non modifica pi\u00f9 salute e suggerimenti.", 12, GOLD, Typeface.NORMAL));
            }
        }
        Spinner mood = spinner(list("Neutro", "Sereno", "Stressato", "Stanco", "Ansioso", "Impulsivo", "Motivato"));
        Spinner stress = spinner(list("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        Spinner energy = spinner(list("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        stress.setSelection(5);
        energy.setSelection(5);
        EditText note = input("Nota breve: cosa sta succedendo?");
        card.addView(label("Umore"));
        card.addView(mood);
        card.addView(label("Stress"));
        card.addView(stress);
        card.addView(label("Energia"));
        card.addView(energy);
        card.addView(note);
        Button save = primaryButton("Salva check-in");
        save.setOnClickListener(v -> {
            db.addCheckIn(selected(mood), intFrom(selected(stress)), intFrom(selected(energy)), textOf(note));
            toast("Check-in salvato");
            show("more");
        });
        card.addView(save);
        content.addView(card);
    }

    private void showAdvisorDialogCard() {
        LinearLayout card = card();
        card.addView(title("Dialogo locale", 18));
        card.addView(text("Fagli una domanda economica: usa budget, obiettivi, categorie e stato emotivo. Non manda dati fuori dal telefono.", 13, MUTED, Typeface.NORMAL));
        Button ask = primaryButton("Parla con assistente");
        ask.setOnClickListener(v -> askLocalAdvisor());
        card.addView(ask);
        LinearLayout quick = rowWrap();
        Button q1 = secondaryButton("Posso fare questa spesa?");
        q1.setOnClickListener(v -> showAdvisorAnswer("Posso fare questa spesa oggi?"));
        Button q2 = secondaryButton("Sono stressato");
        q2.setOnClickListener(v -> showAdvisorAnswer("Sono stressato e sto per spendere per impulso"));
        Button q3 = secondaryButton("Come proteggo il budget?");
        q3.setOnClickListener(v -> showAdvisorAnswer("Come proteggo il budget questa settimana?"));
        quick.addView(q1);
        quick.addView(q2);
        quick.addView(q3);
        card.addView(quick);
        content.addView(card);
    }

    private void askLocalAdvisor() {
        EditText input = input("Scrivi la domanda");
        input.setSingleLine(false);
        input.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Assistente locale")
                .setView(input)
                .setPositiveButton("Rispondi", (d, w) -> showAdvisorAnswer(textOf(input)))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showAdvisorAnswer(String question) {
        String prompt = clean(question);
        if (prompt.isEmpty()) {
            toast("Scrivi una domanda");
            return;
        }
        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(this, db);
        LoadingDialog waiting = loadingDialog("Custode locale", custodian.engineStatus());
        new Thread(() -> {
            AiAnswer answer = custodian.ask(withScreenContext(prompt),
                    preview -> runOnUiThread(() -> waiting.status.setText(preview)));
            String engineError = custodian.lastEngineError();
            runOnUiThread(() -> {
                if (!isFinishing()) waiting.dialog.dismiss();
                if (answer == null) {
                    toast("Il Custode non ha prodotto una risposta valida");
                    return;
                }
                String message = formatAiAnswer(answer);
                if (!engineError.isEmpty() && answer.motore.startsWith("regole locali")) {
                    message += "\n\nIl modello generativo non ha risposto; fallback sicuro attivo: " + engineError;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Custode: " + answer.stato)
                        .setMessage(message)
                        .setPositiveButton("Ok", null)
                        .show();
            });
        }, "EvoCustodianQuestion").start();
    }

    private LoadingDialog loadingDialog(String title, String message) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(24), dp(18), dp(24), dp(18));
        ProgressBar progress = new ProgressBar(this);
        body.addView(progress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView status = text(message, 13, MUTED, Typeface.NORMAL);
        body.addView(status, matchMargins(dp(14), 0, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(body)
                .setCancelable(false)
                .create();
        dialog.show();
        return new LoadingDialog(dialog, status);
    }

    private static final class LoadingDialog {
        final AlertDialog dialog;
        final TextView status;

        LoadingDialog(AlertDialog dialog, TextView status) {
            this.dialog = dialog;
            this.status = status;
        }
    }

    private String formatAiAnswer(AiAnswer answer) {
        StringBuilder out = new StringBuilder(answer.messaggioBreve);
        if (!answer.motivo.isEmpty()) out.append("\n\nPerche: ").append(answer.motivo);
        if (!answer.azioni.isEmpty()) {
            out.append("\n\nAzioni prudenti:");
            for (String action : answer.azioni) out.append("\n- ").append(action);
        }
        out.append("\n\nRischio: ").append(answer.rischio)
                .append(" | motore: ").append(answer.motore);
        return out.toString();
    }

    private void showContextMemory() {
        EvoDb.AttachmentStats stats = db.attachmentStats();
        LinearLayout card = card();
        card.addView(title("Contesto raccolto", 18));
        card.addView(simpleLine("GPS collegati", String.valueOf(stats.locations)));
        card.addView(simpleLine("Foto prodotto", String.valueOf(stats.photos)));
        card.addView(simpleLine("Scontrini", String.valueOf(stats.receipts)));
        card.addView(simpleLine("Barcode", String.valueOf(stats.barcodes)));
        card.addView(simpleLine("Note vocali", String.valueOf(stats.voices)));
        if (!clean(stats.latestLocation).isEmpty()) {
            card.addView(text("Ultima posizione salvata: " + stats.latestLocation, 12, MUTED, Typeface.NORMAL));
        }
        List<EvoDb.MoneyRow> rows = db.contextRows(3);
        if (!rows.isEmpty()) {
            card.addView(text("Ultime spese con contesto", 14, INK, Typeface.BOLD), matchMargins(0, dp(8), 0, 0));
            for (EvoDb.MoneyRow row : rows) card.addView(simpleLine(row.left, row.right));
        }
        card.addView(text("Gli scontrini vengono letti sul telefono con OCR locale. Foto, luogo, ISBN e voce restano collegati alla spesa e sono riassunti nel contesto del Custode.", 12, MUTED, Typeface.NORMAL), matchMargins(0, dp(8), 0, 0));
        content.addView(card);
    }

    private List<String> localAdvisorTips() {
        List<String> tips = new ArrayList<>();
        double income = db.monthlyIncome();
        double fixed = db.fixedMonthly();
        double spent = db.monthExpense();
        double margin = income - fixed - spent;
        Map<String, Double> cats = db.categoryTotals();
        EvoDb.LocalAiReport report = db.localAiReport();
        EvoDb.CheckIn checkIn = freshCheckIn();
        if (income <= 0) {
            tips.add("- Inserisci almeno una entrata programmata con inizio e fine: i suggerimenti diventano molto pi\u00f9 precisi.");
        } else if (margin < 0) {
            tips.add("- Margine negativo: prima rivedi spese programmate e categorie ricorrenti, poi imposta obiettivi pi\u00f9 piccoli.");
        } else {
            tips.add("- Margine positivo: puoi assegnare una quota automatica agli obiettivi senza stringere troppo il mese.");
        }
        if (income > 0) {
            if (report.essentialRatio > 0.60) {
                tips.add("- Essenziali sopra zona sana: prova a trattare una voce fissa o spostare una scadenza, senza tagli brutali.");
            } else if (report.essentialRatio > 0.50) {
                tips.add("- Essenziali un po' sopra la guida 50%: resta solo una spia gialla, non un blocco.");
            }
            if (report.futureRatio < 0.10) {
                tips.add("- Futuro sotto il 10%: prima ricostruisci margine e fondo emergenza, poi spingi sugli investimenti.");
            } else if (report.futureRatio < 0.20) {
                tips.add("- Futuro sotto il 20%: va bene come fase temporanea, ma l'avatar non accelera ancora le evoluzioni.");
            }
            if (report.wantsRatio > 0.35) {
                tips.add("- Desideri oltre la zona morbida: scegli una sola categoria da calmare questa settimana.");
            }
            if (report.debtRatio > 0.20) {
                tips.add("- Rate debiti oltre il 20% del netto: l'app diventa prudente e scoraggia nuovo debito.");
            } else if (report.debtRatio > 0.10) {
                tips.add("- Rate debiti in zona attenzione: niente panico, ma evita nuove rate finche non scendono.");
            }
        }
        if (checkIn != null && checkIn.stress >= 7) {
            tips.add("- Stress alto rilevato: attiva una pausa prima degli acquisti non essenziali e usa il check-in come freno gentile.");
        }
        if (checkIn != null && checkIn.energy <= 3) {
            tips.add("- Energia bassa: evita decisioni su abbonamenti, rate o investimenti finch\u00e9 non sei pi\u00f9 lucido.");
        }
        if (fixed > income * 0.55 && income > 0) {
            tips.add("- Le spese fisse superano il 55% delle entrate: controlla abbonamenti, rate e affitti temporanei.");
        }
        if (report.debtMonthly > 0) {
            tips.add("- Debiti attivi: " + money(report.debtMonthly) + " al mese. Prima di crearne altri, libera margine o chiudi quelli pi\u00f9 costosi.");
        }
        String top = "";
        double topValue = 0;
        for (Map.Entry<String, Double> e : cats.entrySet()) {
            if (e.getValue() > topValue) {
                top = e.getKey();
                topValue = e.getValue();
            }
        }
        if (!top.isEmpty()) {
            tips.add("- Categoria pi\u00f9 pesante: " + top + " con " + money(topValue) + ". Parti da qui per recuperare margine.");
        } else {
            tips.add("- Mancano spese categorizzate: registra anche poche uscite reali per far lavorare meglio l'assistente.");
        }
        List<EvoDb.Goal> goals = db.goals();
        if (goals.isEmpty()) {
            tips.add("- Crea un obiettivo piccolo: l'app assegna un avatar e ti d\u00e0 un ritmo giornaliero.");
        } else {
            EvoDb.Goal g = goals.get(0);
            double needed = (g.target - g.saved) / Math.max(1, g.months * 30d);
            tips.add("- Obiettivo attivo: per " + g.name + " servono circa " + money(Math.max(0, needed)) + " al giorno.");
        }
        EvoDb.AttachmentStats stats = db.attachmentStats();
        if (stats.photos + stats.receipts + stats.locations > 0) {
            tips.add("- Hai gia contesto extra su alcune spese: foto/GPS/scontrini aiutano a capire dove nascono gli impulsi.");
        }
        tips.add("- Internet pu\u00f2 servire solo per dati esterni e cultura finanziaria; le decisioni restano basate sui tuoi numeri e sul tuo stato.");
        return tips;
    }

    private void showInvestments() {
        content.addView(infoCard("Radar Finanziario", "Quaderno di studio, diario investimenti e radar rischio. Non compra, non vende e non promette guadagni: protegge budget e avatar.", GOLD));
        LinearLayout tabs = rowWrap();
        tabs.addView(investmentTabRow("Radar", 0, "Watchlist", 1));
        tabs.addView(investmentTabRow("ETF", 2, "Azioni", 3));
        tabs.addView(investmentTabRow("Crypto", 4, "Interessi", 5));
        tabs.addView(investmentTabRow("Note", 6, "", -1));
        content.addView(tabs);
        if (investmentTab == 1) showInvestmentAssetTab("", true);
        else if (investmentTab == 2) showInvestmentAssetTab("ETF", false);
        else if (investmentTab == 3) showInvestmentAssetTab("azione", false);
        else if (investmentTab == 4) showInvestmentAssetTab("crypto", false);
        else if (investmentTab == 5) showInterestTab();
        else if (investmentTab == 6) showInvestmentNotes();
        else showInvestmentRadar();
    }

    private LinearLayout investmentTabRow(String left, int leftTab, String right, int rightTab) {
        LinearLayout row = row();
        Button a = investmentTabButton(left, leftTab);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(a, lp);
        if (rightTab >= 0) {
            Button b = investmentTabButton(right, rightTab);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(42), 1);
            rp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(b, rp);
        } else {
            TextView spacer = new TextView(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        return row;
    }

    private Button investmentTabButton(String text, int tab) {
        Button b = secondaryButton(text);
        b.setTextSize(13);
        b.setMinHeight(dp(40));
        if (investmentTab == tab) {
            b.setTextColor(Color.WHITE);
            b.setBackground(box(tab == 5 ? GOLD : GREEN, dp(8), 0xAAFFFFFF));
        }
        b.setOnClickListener(v -> {
            investmentTab = tab;
            show("more");
        });
        return b;
    }

    private void showInvestmentRadar() {
        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(this, db);
        AiAnswer answer = custodian.analyze();
        EvoDb.InvestmentSummary summary = db.investmentSummary();
        EvoDb.LocalAiReport budget = db.localAiReport();

        LinearLayout monitor = card();
        monitor.setBackground(box(0xFF080C14, dp(8), BLUE));
        monitor.addView(text("RADAR FINANZIARIO", 18, GREEN, Typeface.BOLD));
        monitor.addView(simpleLine("Stato Custode", answer.stato));
        monitor.addView(simpleLine("Messaggio", answer.messaggioBreve));
        monitor.addView(simpleLine("Totale investito", money(summary.totalInvested)));
        monitor.addView(simpleLine("Valore attuale", money(summary.currentValue)));
        monitor.addView(simpleLine("Guadagno/perdita", money(summary.gainLoss)));
        monitor.addView(simpleLine("Rischio percepito", answer.rischio));
        monitor.addView(simpleLine("Fondo emergenza", String.format(Locale.ITALY, "%.1f mesi", budget.emergencyFundMonths)));
        monitor.addView(simpleLine("Interessi passivi/attivi", money(db.passiveInterest()) + " / " + money(db.activeInterest())));
        monitor.addView(riskBar(answer.rischio));
        content.addView(monitor);

        InvestmentChartView chart = new InvestmentChartView(this);
        chart.setData(db.investmentAssets(""));
        content.addView(chart, matchMargins(0, dp(10), 0, dp(10)));

        LinearLayout buttons = rowWrap();
        Button ask = primaryButton("Chiedi al Custode");
        ask.setOnClickListener(v -> askInvestmentCustodian());
        Button update = secondaryButton("Aggiorna dati");
        update.setOnClickListener(v -> refreshFinanceData());
        Button add = secondaryButton("Aggiungi alla watchlist");
        add.setOnClickListener(v -> {
            investmentTab = 1;
            show("more");
        });
        buttons.addView(ask);
        buttons.addView(update);
        buttons.addView(add);
        content.addView(buttons);

        LinearLayout actions = card();
        actions.addView(title("Azioni Custode", 18));
        for (String action : answer.azioni) actions.addView(text("- " + action, 13, MUTED, Typeface.NORMAL));
        actions.addView(text("JSON vincolato", 14, BLUE, Typeface.BOLD), matchMargins(0, dp(10), 0, 0));
        actions.addView(text(answer.toJson(), 12, MUTED, Typeface.NORMAL));
        content.addView(actions);

        showFinanceCachePreview();
    }

    private void showInvestmentAssetTab(String type, boolean withForm) {
        String title = type.isEmpty() ? "Watchlist" : type;
        content.addView(infoCard(title, "Schede asset di studio: manuali o aggiornabili da fonte esterna autorizzata. Nessun consiglio di acquisto o vendita.", BLUE));
        List<EvoDb.InvestmentAsset> rows = db.investmentAssets(type);
        if (rows.isEmpty()) content.addView(infoCard("Vuoto", "Aggiungi un asset per studiarlo con rischio, orizzonte, fonte e alert personale.", GREEN));
        for (EvoDb.InvestmentAsset asset : rows) content.addView(assetCard(asset));
        if (withForm) showAddAssetForm();
    }

    private View assetCard(EvoDb.InvestmentAsset asset) {
        LinearLayout card = card();
        int color = "crypto".equalsIgnoreCase(asset.assetType) ? CORAL : ("ETF".equalsIgnoreCase(asset.assetType) ? GREEN : BLUE);
        card.addView(text(iconForAsset(asset.assetType) + " " + asset.name, 17, color, Typeface.BOLD));
        card.addView(simpleLine("Ticker", clean(asset.ticker).isEmpty() ? "-" : asset.ticker));
        card.addView(simpleLine("Tipo", asset.assetType + " | " + clean(asset.category)));
        card.addView(simpleLine("Prezzo manuale", money(asset.manualPrice)));
        card.addView(simpleLine("Valore attuale", money(asset.currentValue)));
        card.addView(simpleLine("Investito", money(asset.invested)));
        card.addView(simpleLine("Fonte dati", clean(asset.source).isEmpty() ? "manuale" : asset.source));
        card.addView(simpleLine("Rischio", clean(asset.risk).isEmpty() ? "da valutare" : asset.risk));
        card.addView(simpleLine("Orizzonte", clean(asset.horizon).isEmpty() ? "non definito" : asset.horizon));
        if (!clean(asset.studyReason).isEmpty()) card.addView(text("Studio: " + asset.studyReason, 13, MUTED, Typeface.NORMAL));
        if (!clean(asset.alert).isEmpty()) card.addView(text("Alert: " + asset.alert, 13, GOLD, Typeface.BOLD));
        card.addView(riskBar(asset.risk));
        card.addView(text("badge: " + (asset.source == null || asset.source.equals("manuale") ? "manuale" : "internet") + " | dato informativo, non consiglio", 12, MUTED, Typeface.NORMAL));
        Button remove = secondaryButton("Rimuovi dalla watchlist");
        remove.setTextColor(CORAL);
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Rimuovere " + asset.name + "?")
                .setPositiveButton("Rimuovi", (dialog, which) -> {
                    db.deleteInvestmentAsset(asset.id);
                    show("more");
                })
                .setNegativeButton("Annulla", null)
                .show());
        card.addView(remove, matchMargins(0, dp(8), 0, 0));
        return card;
    }

    private void showAddAssetForm() {
        LinearLayout card = card();
        card.addView(title("Aggiungi alla watchlist", 18));
        EditText name = input("Nome asset");
        EditText ticker = input("Ticker/simbolo");
        Spinner type = spinner(list("ETF", "azione", "crypto", "conto", "altro"));
        EditText category = input("Categoria/tema");
        EditText price = input("Prezzo manuale");
        EditText invested = input("Importo investito o pianificato");
        EditText current = input("Valore attuale manuale");
        Spinner risk = spinner(list("basso", "medio", "medio-alto", "alto", "molto alto"));
        EditText horizon = input("Orizzonte temporale");
        EditText reason = input("Motivo per cui lo sto studiando");
        EditText alert = input("Alert personale");
        EditText note = input("Note personali");
        price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        invested.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        current.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        card.addView(name);
        card.addView(ticker);
        card.addView(label("Tipo"));
        card.addView(type);
        card.addView(category);
        card.addView(price);
        card.addView(invested);
        card.addView(current);
        card.addView(label("Rischio percepito"));
        card.addView(risk);
        card.addView(horizon);
        card.addView(reason);
        card.addView(alert);
        card.addView(note);
        Button save = primaryButton("Salva scheda asset");
        save.setOnClickListener(v -> {
            if (clean(textOf(name)).isEmpty()) {
                toast("Nome asset obbligatorio");
                return;
            }
            db.addInvestmentAsset(textOf(name), textOf(ticker), textOf(category), selected(type), parse(price),
                    selected(risk), textOf(note), textOf(reason), textOf(horizon), parse(invested),
                    parse(current), textOf(alert));
            toast("Asset salvato nel Radar");
            show("more");
        });
        card.addView(save);
        content.addView(card);
    }

    private void showInterestTab() {
        content.addView(infoCard("Interessi", "Confronta tassi passivi e attivi. Se gli interessi passivi superano quelli attivi, il Custode d\u00e0 priorit\u00e0 ai debiti.", GOLD));
        LinearLayout summary = card();
        summary.addView(title("Confronto", 18));
        summary.addView(simpleLine("Interessi passivi", money(db.passiveInterest())));
        summary.addView(simpleLine("Interessi attivi", money(db.activeInterest())));
        if (db.passiveInterest() > db.activeInterest() && db.passiveInterest() > 0) {
            summary.addView(text("Avviso forte: debiti/interessi passivi pesano pi\u00f9 dei rendimenti attivi. Prima riduzione debiti.", 13, CORAL, Typeface.BOLD));
        }
        content.addView(summary);
        for (EvoDb.InterestItem item : db.interestItems()) {
            LinearLayout row = card();
            row.addView(text(item.name, 16, "debito".equalsIgnoreCase(item.type) || "prestito".equalsIgnoreCase(item.type) ? CORAL : GREEN, Typeface.BOLD));
            row.addView(simpleLine("Tipo", item.type + " | fonte " + item.source));
            row.addView(simpleLine("Tasso annuo", String.format(Locale.ITALY, "%.2f%%", item.annualRate)));
            row.addView(simpleLine("Importo", money(item.amount)));
            row.addView(simpleLine("Durata", item.durationMonths + " mesi"));
            row.addView(simpleLine("Interesse stimato", money(item.estimatedInterest)));
            if (!clean(item.note).isEmpty()) row.addView(text(item.note, 13, MUTED, Typeface.NORMAL));
            Button remove = secondaryButton("Rimuovi voce");
            remove.setTextColor(CORAL);
            remove.setOnClickListener(v -> {
                db.deleteInterestItem(item.id);
                show("more");
            });
            row.addView(remove, matchMargins(0, dp(6), 0, 0));
            content.addView(row);
        }
        showAddInterestForm();
    }

    private void showAddInterestForm() {
        LinearLayout card = card();
        card.addView(title("Aggiungi interesse", 18));
        EditText name = input("Nome");
        Spinner type = spinner(list("debito", "deposito", "conto", "prestito", "altro"));
        EditText rate = input("Tasso annuo %");
        EditText amount = input("Importo");
        EditText months = input("Durata mesi");
        EditText note = input("Nota");
        Spinner source = spinner(list("manuale", "internet"));
        rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        months.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        card.addView(name);
        card.addView(label("Tipo"));
        card.addView(type);
        card.addView(rate);
        card.addView(amount);
        card.addView(months);
        card.addView(note);
        card.addView(label("Fonte"));
        card.addView(source);
        Button save = primaryButton("Salva interesse");
        save.setOnClickListener(v -> {
            double estimated = parse(amount) * parse(rate) / 100d * Math.max(1, intFrom(textOf(months))) / 12d;
            db.addInterestItem(textOf(name), selected(type), parse(rate), parse(amount), Math.max(1, intFrom(textOf(months))),
                    estimated, textOf(note), selected(source));
            toast("Interesse salvato");
            show("more");
        });
        card.addView(save);
        content.addView(card);
    }

    private void showInvestmentNotes() {
        LocalInvestmentCustodian custodian = new LocalInvestmentCustodian(this, db);
        AiContext context = custodian.context();
        LinearLayout note = card();
        note.addView(title("Promemoria studio", 18));
        note.addView(text("- studiare ETF: costo, indice, replica, area, valuta", 13, MUTED, Typeface.NORMAL));
        note.addView(text("- aggiornare valore manuale o da Internet autorizzato", 13, MUTED, Typeface.NORMAL));
        note.addView(text("- controllare tassi e interessi passivi", 13, MUTED, Typeface.NORMAL));
        note.addView(text("- rivedere rischio e peso sul budget", 13, MUTED, Typeface.NORMAL));
        note.addView(text("- controllare debiti prima di investire", 13, MUTED, Typeface.NORMAL));
        content.addView(note);

        LinearLayout model = card();
        EvoCoreGateway gateway = EvoCoreGateway.get(this);
        model.addView(title("EvoCore", 18));
        model.addView(simpleLine("Stato", custodian.engineStatus()));
        model.addView(simpleLine("Runtime", "APK autonomo condiviso con le app Evo"));
        model.addView(simpleLine("Privacy", "Memoria cifrata e separata per namespace"));
        Button open = secondaryButton(gateway.isInstalled() ? "Apri EvoCore" : "Installa EvoCore");
        open.setOnClickListener(v -> {
            if (!gateway.openHost()) toast("Installa l'APK EvoCore prima di usare il dialogo profondo");
        });
        model.addView(open);
        model.addView(text("Contesto sintetico JSON", 14, BLUE, Typeface.BOLD), matchMargins(0, dp(10), 0, 0));
        model.addView(text(context.toJson(), 12, MUTED, Typeface.NORMAL));
        content.addView(model);

        showFinanceCachePreview();
    }

    private void showFinanceCachePreview() {
        LinearLayout cache = card();
        cache.addView(title("Fonti Internet salvate", 18));
        List<EvoDb.FinanceCacheEntry> rows = db.financeCacheRows(4);
        if (rows.isEmpty()) {
            cache.addView(text("Nessun dato scaricato. Usa Aggiorna dati solo se autorizzi Internet.", 13, MUTED, Typeface.NORMAL));
        } else {
            for (EvoDb.FinanceCacheEntry row : rows) {
                cache.addView(text(row.dataType + " | " + row.reliability, 13, BLUE, Typeface.BOLD));
                cache.addView(text(row.value, 13, INK, Typeface.NORMAL));
                cache.addView(text("Fonte: " + row.source + " | aggiornato " +
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY).format(row.updatedAt), 11, MUTED, Typeface.NORMAL));
                cache.addView(text(row.disclaimer, 11, MUTED, Typeface.NORMAL), matchMargins(0, 0, 0, dp(8)));
            }
        }
        content.addView(cache);
    }

    private void chooseLocalModel() {
        String[] choices = {
                "Rapido: Gemma 3 1B Q4 (circa 806 MB)",
                "Potente: Gemma 4 E4B Q4 (circa 5,3 GB)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Modello locale per S25 Ultra")
                .setSingleChoiceItems(choices, 0, null)
                .setPositiveButton("Continua", (dialog, which) -> {
                    int selected = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    confirmModelDownload(selected == 1 ? LocalModelManager.POWER : LocalModelManager.FAST);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void confirmModelDownload(LocalModelManager.ModelSpec spec) {
        new AlertDialog.Builder(this)
                .setTitle("Installare " + spec.label + "?")
                .setMessage("Download solo su Wi-Fi o rete non a consumo. Occupa circa " + humanBytes(spec.bytes) +
                        " pi\u00f9 spazio temporaneo durante l'installazione. Il modello resta nella memoria privata di EvoConti.")
                .setPositiveButton("Installa", (dialog, which) -> downloadLocalModel(spec))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void downloadLocalModel(LocalModelManager.ModelSpec spec) {
        ProgressUi ui = modelProgress("Download " + spec.label);
        new LocalModelManager(this).download(spec, false, new LocalModelManager.Callback() {
            @Override
            public void onProgress(int percent, long downloaded, long total) {
                runOnUiThread(() -> ui.update(percent, humanBytes(downloaded) +
                        (total > 0 ? " / " + humanBytes(total) : "")));
            }

            @Override
            public void onDone(boolean ok, String message) {
                runOnUiThread(() -> {
                    ui.dialog.dismiss();
                    toast(message);
                    if (ok) show(active);
                });
            }
        });
    }

    private void importLocalModel(Uri uri) {
        ProgressUi ui = modelProgress("Importazione GGUF");
        new LocalModelManager(this).importFrom(uri, new LocalModelManager.Callback() {
            @Override
            public void onProgress(int percent, long downloaded, long total) {
                runOnUiThread(() -> ui.update(percent, humanBytes(downloaded)));
            }

            @Override
            public void onDone(boolean ok, String message) {
                runOnUiThread(() -> {
                    ui.dialog.dismiss();
                    toast(message);
                    if (ok) show(active);
                });
            }
        });
    }

    private ProgressUi modelProgress(String title) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(18), dp(24), dp(18));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        TextView status = text("Preparazione...", 13, MUTED, Typeface.NORMAL);
        body.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
        body.addView(status, matchMargins(0, dp(10), 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(body)
                .setCancelable(false)
                .create();
        dialog.show();
        return new ProgressUi(dialog, bar, status);
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.ITALY, "%.1f GB", bytes / 1_000_000_000d);
        return String.format(Locale.ITALY, "%.0f MB", bytes / 1_000_000d);
    }

    private static final class ProgressUi {
        final AlertDialog dialog;
        final ProgressBar bar;
        final TextView status;

        ProgressUi(AlertDialog dialog, ProgressBar bar, TextView status) {
            this.dialog = dialog;
            this.bar = bar;
            this.status = status;
        }

        void update(int percent, String message) {
            bar.setIndeterminate(percent < 0);
            if (percent >= 0) bar.setProgress(percent);
            status.setText(percent >= 0
                    ? String.format(Locale.ITALY, "%d%% | %s", percent, message)
                    : message);
        }
    }

    private View riskBar(String rawRisk) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int steps = riskStep(rawRisk);
        String riskLabel = clean(rawRisk);
        if (riskLabel.isEmpty()) riskLabel = "non valutabile";
        LinearLayout bar = row();
        for (int i = 0; i < 5; i++) {
            TextView block = new TextView(this);
            block.setText("");
            block.setBackground(box(i < steps ? (steps >= 4 ? CORAL : (steps >= 3 ? GOLD : GREEN)) : FIELD, dp(4), LINE));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(12), 1);
            lp.setMargins(dp(2), dp(8), dp(2), dp(6));
            bar.addView(block, lp);
        }
        wrap.addView(bar);
        wrap.addView(text("Rischio: " + riskLabel, 12, MUTED, Typeface.NORMAL));
        return wrap;
    }

    private int riskStep(String rawRisk) {
        String r = clean(rawRisk).toLowerCase(Locale.ITALY);
        if (r.contains("molto")) return 5;
        if (r.contains("alto")) return 4;
        if (r.contains("medio")) return 3;
        if (r.contains("basso")) return 1;
        return 0;
    }

    private String iconForAsset(String type) {
        String t = clean(type).toLowerCase(Locale.ITALY);
        if (t.contains("etf")) return "[ETF]";
        if (t.contains("crypto")) return "[CR]";
        if (t.contains("azione")) return "[AZ]";
        if (t.contains("conto")) return "[%]";
        return "[*]";
    }

    private void askInvestmentCustodian() {
        EditText input = input("Domanda al Custode");
        input.setSingleLine(false);
        input.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Custode investimenti")
                .setView(input)
                .setPositiveButton("Rispondi", (d, w) ->
                        showAdvisorAnswer("Domanda sul Radar Finanziario: " + textOf(input)))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void refreshFinanceData() {
        InternetFinanceConnector connector = new InternetFinanceConnector();
        if (!connector.hasConsent(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Autorizzare Internet finanziario?")
                    .setMessage("Scarica solo dati informativi con fonte e timestamp. Non decide investimenti e non consiglia acquisti.")
                    .setPositiveButton("Autorizzo", (d, w) -> {
                        connector.setConsent(this, true);
                        refreshFinanceData();
                    })
                    .setNegativeButton("Annulla", null)
                    .show();
            return;
        }
        toast("Aggiornamento dati informativi...");
        connector.refreshAsync(this, db, (ok, message) -> runOnUiThread(() -> {
            toast(message);
            show("more");
        }));
    }

    private void showManagement() {
        showCategoryManager();
        showRecurringList();
        showExport();
    }

    private void showCategoryManager() {
        LinearLayout card = card();
        card.addView(title("Categorie", 18));
        EditText name = input("Nuova categoria");
        card.addView(name);
        Button add = secondaryButton("Aggiungi categoria");
        add.setOnClickListener(v -> {
            if (!clean(textOf(name)).isEmpty()) {
                db.addCategory(textOf(name), "#5C6F7B");
                show("settings");
            }
        });
        card.addView(add);
        for (String cat : db.listCategories()) {
            LinearLayout line = row();
            TextView label = text(cat, 14, INK, Typeface.NORMAL);
            line.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button del = secondaryButton("Elimina");
            del.setOnClickListener(v -> {
                db.deleteCategory(cat);
                show("settings");
            });
            line.addView(del, new LinearLayout.LayoutParams(dp(100), dp(44)));
            card.addView(line);
        }
        Button clear = secondaryButton("Azzera categorie");
        clear.setTextColor(CORAL);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Azzerare categorie?")
                .setMessage("Le spese gia salvate restano intatte, ma la lista categorie viene svuotata.")
                .setPositiveButton("Azzera", (d, w) -> {
                    db.clearCategories();
                    show("settings");
                })
                .setNegativeButton("Annulla", null)
                .show());
        card.addView(clear, matchMargins(0, dp(8), 0, 0));
        content.addView(card);
    }

    private void showRecurringList() {
        LinearLayout card = card();
        card.addView(title("Entrate e spese programmate", 18));
        List<EvoDb.Recurring> rows = db.recurring();
        if (rows.isEmpty()) {
            card.addView(text("Nessuna ricorrenza programmata.", 13, MUTED, Typeface.NORMAL));
        } else {
            for (EvoDb.Recurring recurring : rows) {
                LinearLayout line = row();
                String period = dayText(recurring.startAt) + " - " +
                        (recurring.endAt <= 0 ? "senza fine" : dayText(recurring.endAt));
                TextView description = text(("income".equals(recurring.type) ? "Entrata" : "Spesa") +
                        " | " + recurring.title + " | " + money(recurring.amount) + "\n" +
                        recurring.category + " | " + period, 12, MUTED, Typeface.NORMAL);
                line.addView(description, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button remove = secondaryButton("×");
                remove.setContentDescription("Elimina " + recurring.title);
                remove.setTextSize(22);
                remove.setTextColor(CORAL);
                remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Eliminare la programmata?")
                        .setMessage(recurring.title)
                        .setPositiveButton("Elimina", (dialog, which) -> {
                            db.deleteRecurring(recurring.id);
                    show("settings");
                        })
                        .setNegativeButton("Annulla", null)
                        .show());
                line.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(44)));
                card.addView(line, matchMargins(0, dp(5), 0, dp(5)));
            }
        }
        Button add = secondaryButton("Aggiungi programmata");
        add.setOnClickListener(v -> {
            addMode = 2;
            show("add");
        });
        card.addView(add, matchMargins(0, dp(10), 0, 0));
        content.addView(card);
    }

    private void showExport() {
        LinearLayout card = card();
        card.addView(title("Export", 18));
        card.addView(text("Esporta una tabella CSV apribile in Excel, oppure condividi una nota testuale verso Samsung Notes o altre app.", 13, MUTED, Typeface.NORMAL));
        LinearLayout buttons = rowWrap();
        Button csv = primaryButton("Esporta CSV");
        csv.setOnClickListener(v -> exportCsv());
        Button note = secondaryButton("Condividi nota");
        note.setOnClickListener(v -> shareNote());
        buttons.addView(csv);
        buttons.addView(note);
        card.addView(buttons);
        content.addView(card);
    }

    private void openShoppingRun() {
        EvoDb.ShoppingSession open = db.latestOpenShoppingSession();
        if (open == null) {
            showShoppingRunSetup();
            return;
        }
        EvoDb.ShoppingSummary summary = db.shoppingSummary(open.id);
        new AlertDialog.Builder(this)
                .setTitle("Corsa spesa in pausa")
                .setMessage(open.storeName + " | " + summary.quantity + " prodotti | " + money(summary.total))
                .setPositiveButton("Riprendi", (dialog, which) -> launchShoppingRun(open.id, open.storeName, open.account))
                .setNeutralButton("Elimina e ricomincia", (dialog, which) -> {
                    db.discardShoppingSession(open.id);
                    showShoppingRunSetup();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showShoppingRunSetup() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText store = input("Supermercato");
        String lastStore = getSharedPreferences("shopping_run", MODE_PRIVATE)
                .getString("last_store", "Supermercato");
        store.setText(lastStore);
        Spinner account = spinner(db.listAccounts());
        CheckBox online = check("Catalogo online: invia solo il barcode");
        ProductCatalogConnector connector = new ProductCatalogConnector();
        online.setChecked(connector.hasConsent(this));
        form.addView(label("Negozio"));
        form.addView(store);
        form.addView(label("Conto o carta"));
        form.addView(account);
        form.addView(online);
        new AlertDialog.Builder(this)
                .setTitle("Nuova corsa spesa")
                .setView(form)
                .setPositiveButton("Avvia", (dialog, which) -> {
                    String storeName = clean(textOf(store)).isEmpty() ? "Supermercato" : clean(textOf(store));
                    String accountName = selected(account);
                    connector.setConsent(this, online.isChecked());
                    getSharedPreferences("shopping_run", MODE_PRIVATE).edit()
                            .putString("last_store", storeName).apply();
                    long sessionId = db.createShoppingSession(storeName, accountName);
                    launchShoppingRun(sessionId, storeName, accountName);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void launchShoppingRun(long sessionId, String storeName, String accountName) {
        Intent intent = new Intent(this, ShoppingRunActivity.class);
        intent.putExtra(ShoppingRunActivity.EXTRA_SESSION_ID, sessionId);
        intent.putExtra(ShoppingRunActivity.EXTRA_STORE, storeName);
        intent.putExtra(ShoppingRunActivity.EXTRA_ACCOUNT, accountName);
        intent.putExtra(ShoppingRunActivity.EXTRA_ONLINE, new ProductCatalogConnector().hasConsent(this));
        startActivityForResult(intent, REQUEST_SHOPPING_RUN);
    }

    private void openCamera(int mode) {
        captureMode = mode;
        if (!hasPermission(Manifest.permission.CAMERA)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        pendingCaptureUri = createCaptureUri(mode);
        if (pendingCaptureUri != null) {
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCaptureUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivityForResult(intent, REQUEST_CAMERA);
        } catch (ActivityNotFoundException error) {
            toast("Nessuna app camera disponibile");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SHOPPING_RUN) {
            if (resultCode == RESULT_OK && data != null) {
                double total = data.getDoubleExtra(ShoppingRunActivity.RESULT_TOTAL, 0);
                int items = data.getIntExtra(ShoppingRunActivity.RESULT_ITEMS, 0);
                toast("Corsa salvata: " + items + " prodotti, " + money(total));
            }
            show("oggi");
            return;
        }
        if (requestCode != REQUEST_CAMERA || resultCode != RESULT_OK) return;
        Bitmap bitmap = null;
        if (data != null && data.getExtras() != null) {
            bitmap = (Bitmap) data.getExtras().get("data");
        }
        if (captureMode == CAPTURE_BARCODE) {
            if (pendingCaptureUri != null) scanBarcode(pendingCaptureUri);
            else if (bitmap != null) scanBarcode(bitmap);
            else toast("Scatto non disponibile. Riprova con buona luce.");
        } else if (captureMode == CAPTURE_RECEIPT) {
            pendingReceipt = pendingCaptureUri != null ? pendingCaptureUri.toString() : saveBitmap(bitmap, "receipt");
            if (pendingCaptureUri != null) recognizeReceipt(pendingCaptureUri);
            else if (bitmap != null) recognizeReceipt(bitmap);
            else toast("Scontrino salvato, ma immagine non leggibile");
        } else {
            pendingPhoto = pendingCaptureUri != null ? pendingCaptureUri.toString() : saveBitmap(bitmap, "photo");
            toast("Foto prodotto collegata");
        }
    }

    private Uri createCaptureUri(int mode) {
        try {
            String prefix = mode == CAPTURE_BARCODE ? "barcode" : (mode == CAPTURE_RECEIPT ? "receipt" : "photo");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, prefix + "_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EvoConti");
            }
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Exception e) {
            return null;
        }
    }

    private void scanBarcode(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        processBarcodeImage(image);
    }

    private void scanBarcode(Uri uri) {
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            processBarcodeImage(image);
        } catch (Exception e) {
            toast("Immagine barcode non leggibile. Riprova pi\u00f9 vicino.");
        }
    }

    private void processBarcodeImage(InputImage image) {
        BarcodeScanner scanner = BarcodeScanning.getClient();
        scanner.process(image)
                .addOnSuccessListener(codes -> {
                    if (codes.isEmpty()) {
                        toast("Barcode non trovato. Prova pi\u00f9 vicino, dritto e con pi\u00f9 luce.");
                        return;
                    }
                    Barcode code = codes.get(0);
                    String value = code.getRawValue() == null ? "" : code.getRawValue();
                    if (barcodeInput != null) barcodeInput.setText(value);
                    BookBarcodeResolver resolver = new BookBarcodeResolver();
                    if (resolver.looksLikeIsbn(value)) resolveBookBarcode(value);
                    else resolveProductBarcode(value);
                })
                .addOnFailureListener(e -> toast("Lettura barcode non riuscita"))
                .addOnCompleteListener(task -> scanner.close());
    }

    private void resolveProductBarcode(String barcode) {
        EvoDb.Product local = db.product(barcode);
        if (local != null) applyProductToExpense(local);
        ProductCatalogConnector connector = new ProductCatalogConnector();
        if (!connector.hasConsent(this)) {
            if (local != null) {
                toast("Prodotto riconosciuto dall'archivio locale");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Cercare il prodotto?")
                    .setMessage("Invia solo il barcode a Open Food Facts e Open Prices. Foto, importi e dati personali restano sul telefono.")
                    .setPositiveButton("Cerca", (dialog, which) -> {
                        connector.setConsent(this, true);
                        resolveProductBarcode(barcode);
                    })
                    .setNegativeButton("Solo codice", (dialog, which) -> toast("Barcode salvato senza ricerca"))
                    .show();
            return;
        }
        toast("Ricerca prodotto nel catalogo...");
        connector.resolveAsync(this, db, barcode, "", result -> {
            if (result.hasProduct()) applyProductToExpense(result.product);
            else if (local == null) toast(result.error.isEmpty() ? "Prodotto non trovato" : result.error);
            if (result.hasSuggestedPrice() && expenseAmountInput != null && clean(textOf(expenseAmountInput)).isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Prezzo pubblico disponibile")
                        .setMessage(money(result.suggestedPrice) + " | " + result.priceSource +
                                "\nE una stima: il prezzo del tuo negozio puo essere diverso.")
                        .setPositiveButton("Usa come stima", (dialog, which) ->
                                expenseAmountInput.setText(String.format(Locale.US, "%.2f", result.suggestedPrice)))
                        .setNegativeButton("Ignora", null)
                        .show();
            }
        });
    }

    private void applyProductToExpense(EvoDb.Product product) {
        if (product == null) return;
        if (expenseTitleInput != null) expenseTitleInput.setText(product.displayName());
        if (expenseCategorySpinner != null) setSpinner(expenseCategorySpinner,
                clean(product.category).isEmpty() ? "Alimentare" : product.category);
        if (expenseNoteInput != null && clean(textOf(expenseNoteInput)).isEmpty()) {
            String detail = "Catalogo: " + product.source;
            if (!clean(product.quantityLabel).isEmpty()) detail += " | " + product.quantityLabel;
            expenseNoteInput.setText(detail);
        }
        toast("Prodotto riconosciuto: " + product.displayName());
    }

    private void recognizeReceipt(Bitmap bitmap) {
        processReceiptImage(InputImage.fromBitmap(bitmap, 0));
    }

    private void recognizeReceipt(Uri uri) {
        try {
            processReceiptImage(InputImage.fromFilePath(this, uri));
        } catch (Exception error) {
            toast("Scontrino salvato, ma immagine non leggibile");
        }
    }

    private void processReceiptImage(InputImage image) {
        toast("Lettura locale dello scontrino...");
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
                .addOnSuccessListener(result -> applyReceiptOcr(new ReceiptParser().parse(result.getText())))
                .addOnFailureListener(error -> toast("Scontrino collegato; OCR non riuscito"))
                .addOnCompleteListener(task -> recognizer.close());
    }

    private void applyReceiptOcr(ReceiptParser.Result result) {
        pendingReceiptOcr = result.summary;
        if (expenseTitleInput != null && clean(textOf(expenseTitleInput)).isEmpty() && !result.merchant.isEmpty()) {
            expenseTitleInput.setText(result.merchant);
        }
        if (expenseAmountInput != null && result.hasTotal) {
            expenseAmountInput.setText(String.format(Locale.US, "%.2f", result.total));
        }
        if (expenseCategorySpinner != null && !result.merchant.isEmpty()) {
            setSpinner(expenseCategorySpinner, db.smartCategory(result.merchant));
        }
        if (result.hasTotal) {
            toast("Scontrino letto: totale " + money(result.total));
        } else if (!result.merchant.isEmpty()) {
            toast("Negozio riconosciuto; controlla l'importo");
        } else {
            toast("Testo letto, ma totale non certo: controlla i campi");
        }
    }

    private void resolveBookBarcode(String isbn) {
        android.content.SharedPreferences preferences = getSharedPreferences("book_lookup", MODE_PRIVATE);
        if (!preferences.getBoolean("consent", false)) {
            new AlertDialog.Builder(this)
                    .setTitle("Cercare il libro online?")
                    .setMessage("Invia solo l'ISBN a Open Library per ottenere titolo e autore. La spesa resta sul telefono.")
                    .setPositiveButton("Cerca", (dialog, which) -> {
                        preferences.edit().putBoolean("consent", true).apply();
                        resolveBookBarcode(isbn);
                    })
                    .setNegativeButton("Solo codice", (dialog, which) -> toast("ISBN salvato senza ricerca"))
                    .show();
            return;
        }
        toast("Ricerca del libro...");
        new BookBarcodeResolver().resolveAsync(this, isbn, (book, error) -> runOnUiThread(() -> {
            if (book == null) {
                toast(error);
                return;
            }
            if (expenseTitleInput != null) expenseTitleInput.setText(book.displayTitle());
            if (expenseCategorySpinner != null) setSpinner(expenseCategorySpinner, "Libri e cultura");
            if (expenseNoteInput != null && clean(textOf(expenseNoteInput)).isEmpty()) {
                String metadata = book.publisher.isEmpty() ? "Metadati ISBN da Open Library" :
                        String.format(Locale.ITALY, "Metadati ISBN da Open Library | %s", book.publisher);
                expenseNoteInput.setText(metadata);
            }
            toast("Libro riconosciuto: " + book.title);
        }));
    }

    private void grabLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location loc = null;
        try {
            loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException ignored) {
        }
        if (loc == null) {
            toast("Posizione non disponibile ora");
            return;
        }
        pendingLocation = String.format(Locale.US, "%.5f,%.5f", loc.getLatitude(), loc.getLongitude());
        toast("Posizione collegata");
    }

    private void toggleRecording() {
        if (recording) {
            stopRecording();
            toast("Nota vocale salvata");
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }
        try {
            File dir = new File(getFilesDir(), "voice");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "voice_" + System.currentTimeMillis() + ".3gp");
            pendingVoice = file.getAbsolutePath();
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setOutputFile(pendingVoice);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.prepare();
            recorder.start();
            recording = true;
            toast("Registrazione avviata: premi Voce per fermare");
        } catch (Exception e) {
            recording = false;
            pendingVoice = "";
            toast("Registrazione non avviata");
        }
    }

    private void stopRecording() {
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
        recording = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = false;
        for (int value : grantResults) {
            if (value == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        if (!granted) {
            toast("Permesso non concesso: la funzione resta disattivata");
            return;
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION) openCamera(captureMode);
        else if (requestCode == REQUEST_LOCATION_PERMISSION) grabLocation();
        else if (requestCode == REQUEST_AVATAR_LOCATION_PERMISSION) configureAvatarPlace();
        else if (requestCode == REQUEST_AUDIO_PERMISSION) toggleRecording();
    }

    private String saveBitmap(Bitmap bitmap, String prefix) {
        try {
            File dir = new File(getFilesDir(), "images");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, prefix + "_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    private void exportCsv() {
        String csv = db.csvExport();
        String name = "evoconti_export_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(System.currentTimeMillis()) + ".csv";
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EvoConti");
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                OutputStream out = getContentResolver().openOutputStream(uri);
                out.write(csv.getBytes(StandardCharsets.UTF_8));
                out.close();
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "EvoConti");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                FileOutputStream out = new FileOutputStream(file);
                out.write(csv.getBytes(StandardCharsets.UTF_8));
                out.close();
                uri = Uri.fromFile(file);
            }
            toast("CSV salvato in Download/EvoConti");
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Apri o condividi CSV"));
        } catch (Exception e) {
            toast("Export non riuscito");
        }
    }

    private void shareNote() {
        StringBuilder sb = new StringBuilder();
        sb.append("EvoConti - riepilogo mese\n");
        sb.append("Entrate: ").append(money(db.monthlyIncome())).append('\n');
        sb.append("Programmate: ").append(money(db.fixedMonthly())).append('\n');
        sb.append("Spese variabili: ").append(money(db.monthExpense())).append('\n');
        sb.append("Suggerimento: ").append(db.insight()).append('\n');
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(share, "Condividi nota"));
    }

    private String goalFeasibility(double target, int months) {
        if (target <= 0) return "Inserisci un importo per stimare se \u00e8 realistico.";
        double monthlyNeed = target / Math.max(1, months);
        double available = db.monthlyIncome() - db.fixedMonthly() - db.monthExpense();
        double dailyNeed = target / Math.max(1, months * 30d);
        if (available >= monthlyNeed) {
            return "Possibile: servono circa " + money(dailyNeed) + " al giorno e " + money(monthlyNeed) + " al mese.";
        }
        return "Difficile ora: servono " + money(monthlyNeed) + " al mese, ma il margine stimato \u00e8 " + money(available) + ". Riduci categorie ricorrenti o abbassa l'obiettivo.";
    }

    private int monthsFrom(String label) {
        if (label.startsWith("4")) return 4;
        if (label.startsWith("12")) return 12;
        if (label.startsWith("36")) return 36;
        if (label.startsWith("120")) return 120;
        return 1;
    }

    private View txLine(EvoDb.Tx tx) {
        String right = ("expense".equals(tx.type) ? "-" : "+") + money(tx.amount);
        return timelineLine("expense".equals(tx.type) ? "SPESA" : "ENTRATA",
                tx.title + "  |  " + tx.category, right,
                "expense".equals(tx.type) ? CORAL : GREEN);
    }

    private View simpleLine(String left, String right) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(6));
        TextView a = text(left, 13, MUTED, Typeface.NORMAL);
        TextView b = text(right, 13, INK, Typeface.BOLD);
        line.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(b);
        return line;
    }

    private View timelineLine(String eyebrow, String left, String right, int color) {
        LinearLayout line = row();
        line.setPadding(dp(13), dp(10), dp(13), dp(10));
        line.setBackground(box(FIELD, dp(6), LINE));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        if (!clean(eyebrow).isEmpty()) copy.addView(text(eyebrow, 9, color, Typeface.BOLD));
        copy.addView(text(left, 13, INK, Typeface.NORMAL), matchMargins(0, dp(2), 0, 0));
        line.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!clean(right).isEmpty()) line.addView(text(right, 12, color, Typeface.BOLD));
        line.setLayoutParams(matchMargins(0, dp(3), 0, dp(3)));
        return line;
    }

    private View emptyLine(String title, String subtitle) {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setPadding(dp(14), dp(13), dp(14), dp(13));
        empty.setBackground(box(0xFF0E1216, dp(6), 0xFF252D32));
        empty.addView(text(title, 13, INK, Typeface.BOLD));
        empty.addView(text(subtitle, 11, MUTED, Typeface.NORMAL), matchMargins(0, dp(3), 0, 0));
        empty.setLayoutParams(matchMargins(0, dp(4), 0, dp(4)));
        return empty;
    }

    private View sectionHeader(String label, String action, Runnable onAction) {
        LinearLayout header = row();
        header.setPadding(dp(2), dp(4), 0, dp(4));
        header.addView(text(label, 18, INK, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!clean(action).isEmpty()) {
            Button button = quietButton(action);
            button.setTextSize(11);
            button.setTextColor(GREEN);
            button.setMinHeight(0);
            button.setPadding(dp(10), 0, dp(6), 0);
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setOnClickListener(v -> onAction.run());
            header.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        return header;
    }

    private View metric(String label, String value, int color) {
        LinearLayout segment = new LinearLayout(this);
        segment.setOrientation(LinearLayout.VERTICAL);
        segment.setPadding(dp(6), dp(7), dp(6), dp(7));
        segment.addView(text(label, 10, MUTED, Typeface.NORMAL));
        TextView amount = text(value, 15, color, Typeface.BOLD);
        amount.setMaxLines(1);
        amount.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        segment.addView(amount);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        segment.setLayoutParams(lp);
        return segment;
    }

    private View metricDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(40));
        lp.gravity = Gravity.CENTER_VERTICAL;
        divider.setLayoutParams(lp);
        return divider;
    }

    private View infoCard(String label, String body, int color) {
        LinearLayout card = card();
        card.addView(text(label, 16, color, Typeface.BOLD));
        card.addView(text(body, 13, MUTED, Typeface.NORMAL));
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(box(SURFACE, dp(6), LINE));
        contentSpacer(card, 0, dp(8), 0, dp(8));
        return card;
    }

    private void header(String title, String subtitle) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(2), dp(2), dp(2), dp(10));
        h.addView(text(title, 26, INK, Typeface.BOLD));
        h.addView(text(subtitle, 12, MUTED, Typeface.NORMAL), matchMargins(0, dp(3), 0, 0));
        content.addView(h, matchMargins(0, 0, 0, dp(12)));
    }

    private View led(int color) {
        TextView dot = new TextView(this);
        dot.setText("");
        dot.setBackground(box(color, dp(2), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(4));
        lp.setMargins(0, 0, dp(6), 0);
        dot.setLayoutParams(lp);
        return dot;
    }

    private TextView title(String value, int size) {
        return text(value, size, INK, Typeface.BOLD);
    }

    private TextView label(String value) {
        return text(value, 12, MUTED, Typeface.BOLD);
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(value == null ? "" : value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, style);
        tv.setLetterSpacing(0f);
        tv.setLineSpacing(0, 1.08f);
        tv.setIncludeFontPadding(true);
        return tv;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(INK);
        e.setHintTextColor(0xFF6F8190);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setMinHeight(dp(48));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(box(FIELD, dp(6), LINE));
        e.setLayoutParams(matchMargins(0, dp(5), 0, dp(8)));
        e.setFocusableInTouchMode(false);
        e.setOnTouchListener((view, event) -> {
            e.setFocusableInTouchMode(true);
            return false;
        });
        return e;
    }

    private CheckBox check(String value) {
        CheckBox c = new CheckBox(this);
        c.setText(value);
        c.setTextColor(INK);
        c.setTextSize(14);
        return c;
    }

    private Spinner spinner(List<String> values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(INK);
                    ((TextView) view).setTextSize(15);
                }
                view.setBackgroundColor(FIELD);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(INK);
                    ((TextView) view).setTextSize(15);
                    view.setBackgroundColor(SURFACE);
                    view.setPadding(dp(14), dp(12), dp(14), dp(12));
                }
                return view;
            }
        };
        spinner.setAdapter(adapter);
        spinner.setBackground(box(FIELD, dp(6), LINE));
        spinner.setPadding(dp(8), 0, dp(8), 0);
        spinner.setLayoutParams(matchMargins(0, dp(5), 0, dp(8)));
        return spinner;
    }

    private Button primaryButton(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(box(GREEN, dp(8), 0x99FFFFFF));
        b.setMinHeight(dp(48));
        b.setLayoutParams(matchMargins(0, dp(4), 0, dp(4)));
        return b;
    }

    private Button actionButton(String value, int iconRes, int color) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextColor(INK);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setCompoundDrawablesWithIntrinsicBounds(tintedIcon(iconRes, color), null, null, null);
        button.setCompoundDrawablePadding(dp(9));
        button.setBackground(box(0xFF161D20, dp(8), color));
        button.setMinHeight(dp(52));
        return button;
    }

    private Button quietButton(String value) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextColor(MUTED);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setBackground(box(FIELD, dp(7), LINE));
        button.setMinHeight(dp(44));
        return button;
    }

    private Button secondaryButton(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextColor(INK);
        b.setTextSize(13);
        b.setBackground(box(FIELD, dp(8), LINE));
        b.setMinHeight(dp(44));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout rowWrap() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private GradientDrawable box(int color, int radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private Drawable tintedIcon(int resource, int color) {
        Drawable drawable = getDrawable(resource);
        if (drawable == null) return null;
        drawable = drawable.mutate();
        drawable.setTint(color);
        return drawable;
    }

    private ImageView iconButton(int resource, String description, int color) {
        ImageView button = new ImageView(this);
        button.setImageDrawable(tintedIcon(resource, color));
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(box(FIELD, dp(8), LINE));
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private LinearLayout.LayoutParams matchMargins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private LinearLayout.LayoutParams weightedMargins(int l, int t, int r, int b, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private void contentSpacer(View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, r, b);
        v.setLayoutParams(lp);
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void setSpinner(Spinner spinner, String value) {
        if (spinner == null || value == null) return;
        for (int i = 0; i < spinner.getCount(); i++) {
            if (value.equalsIgnoreCase(String.valueOf(spinner.getItemAtPosition(i)))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private String selected(Spinner spinner) {
        Object item = spinner.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private String textOf(EditText e) {
        return e == null ? "" : e.getText().toString();
    }

    private String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private double parse(EditText e) {
        try {
            return Double.parseDouble(textOf(e).replace(",", ".").trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private int intFrom(String value) {
        try {
            return Integer.parseInt(clean(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    private List<String> list(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) out.add(v);
        return out;
    }

    private String money(double value) {
        return String.format(Locale.ITALY, "%.2f EUR", value);
    }

    private String percent(double ratio) {
        return String.format(Locale.ITALY, "%.0f%%", Math.max(0, ratio) * 100d);
    }

    private String todayText() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(System.currentTimeMillis());
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean sameDay(long first, long second) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTimeInMillis(first);
        b.setTimeInMillis(second);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA)
                && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private String dayText(long timestamp) {
        if (timestamp <= 0) return "da ora";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(timestamp);
    }

    private long parseDay(String raw, long fallback) {
        String value = clean(raw);
        if (value.isEmpty()) return fallback;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
            f.setLenient(false);
            return f.parse(value).getTime();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private int parseTimeMinutes(String raw) {
        String value = clean(raw);
        String[] parts = value.split(":");
        if (parts.length == 2) {
            try {
                int hour = Math.max(0, Math.min(23, Integer.parseInt(parts[0])));
                int minute = Math.max(0, Math.min(59, Integer.parseInt(parts[1])));
                return hour * 60 + minute;
            } catch (NumberFormatException ignored) {
            }
        }
        return 9 * 60;
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }
}
