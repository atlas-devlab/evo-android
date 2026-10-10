package it.evocore.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF080D10;
    private static final int PANEL = 0xFF10191D;
    private static final int FIELD = 0xFF0C1418;
    private static final int LINE = 0xFF294047;
    private static final int TEXT = 0xFFF2F7F5;
    private static final int MUTED = 0xFF91A5A7;
    private static final int GREEN = 0xFF53D6A0;
    private static final int BLUE = 0xFF55B9E8;
    private static final int GOLD = 0xFFF0C96D;
    private static final int CORAL = 0xFFFF7B68;
    private static final int EXPORT_JSON = 701;
    private static final int EXPORT_CSV = 702;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshInFlight = new AtomicBoolean();
    private final String[] scenarioNames = {
            "Uso rapido", "Dialogo AI", "Uso prolungato"
    };
    private TelemetryRepository repository;
    private LinearLayout root;
    private TextView sessionStatus;
    private TextView eventValue;
    private TextView responseValue;
    private TextView memoryValue;
    private TextView thermalValue;
    private TextView summaryText;
    private TextView recentText;
    private Button sessionButton;
    private Button launchButton;
    private Button evoContiButton;
    private Button evoCoreButton;
    private final Button[] scenarioButtons = new Button[3];
    private TelemetryChartView chart;
    private String selectedPackage = "com.elia.evoconti";
    private String selectedTarget = "EvoConti";
    private int selectedScenario;
    private volatile long lastChartEventCount = -1;
    private volatile String pendingExport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        repository = new TelemetryRepository(this);
        buildUi();
        if (!repository.activeSessionId().isEmpty()) startCollector();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHandler.removeCallbacks(refreshTask);
        refreshHandler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(36));
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(18), dp(16) + top, dp(18), dp(36) + bottom);
            return insets;
        });

        TextView eyebrow = text("EVOLAB / DEVICE LAB", 12, GREEN, true);
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow);
        root.addView(text("Misura. Confronta. Migliora.", 29, TEXT, true),
                matchWrap(0, dp(2)));
        root.addView(text("Banco prova locale per EvoCore e le sue app.", 15, MUTED, false),
                matchWrap(0, dp(12)));

        TextView privacy = text("DATI LOCALI  |  CIFRATI  |  30 GIORNI", 11, GREEN, true);
        privacy.setGravity(Gravity.CENTER_VERTICAL);
        privacy.setPadding(dp(12), 0, dp(12), 0);
        privacy.setBackground(box(FIELD, LINE, 8));
        root.addView(privacy, matchHeight(dp(42), 0, dp(24)));

        sectionTitle("App sotto prova");
        LinearLayout targets = horizontal(8);
        evoContiButton = segment("EvoConti", true, view -> chooseTarget(
                "com.elia.evoconti", "EvoConti"));
        evoCoreButton = segment("EvoCore", false, view -> chooseTarget(
                "it.evocore.host", "EvoCore"));
        targets.addView(evoContiButton, weighted());
        targets.addView(evoCoreButton, weighted());
        root.addView(targets, matchHeight(dp(50), 0, dp(14)));

        sectionTitle("Scenario");
        LinearLayout scenarios = horizontal(6);
        for (int index = 0; index < scenarioButtons.length; index++) {
            final int choice = index;
            scenarioButtons[index] = segment(scenarioNames[index], index == 0,
                    view -> chooseScenario(choice));
            scenarios.addView(scenarioButtons[index], weighted());
        }
        root.addView(scenarios, matchHeight(dp(48), 0, dp(12)));

        sessionStatus = text("Nessuna sessione attiva", 13, MUTED, false);
        root.addView(sessionStatus, matchWrap(0, dp(10)));

        LinearLayout actions = horizontal(8);
        sessionButton = command("Avvia misura", GREEN, 0xFF07130F, view -> toggleSession());
        launchButton = command("Apri EvoConti", 0xFF17262C, TEXT, view -> launchTarget());
        actions.addView(sessionButton, weighted());
        actions.addView(launchButton, weighted());
        root.addView(actions, matchHeight(dp(52), 0, dp(26)));

        sectionTitle("Segnali dal vivo");
        LinearLayout firstMetrics = horizontal(8);
        eventValue = metric(firstMetrics, "EVENTI", "0", GREEN);
        responseValue = metric(firstMetrics, "RISPOSTA AI", "n/d", BLUE);
        root.addView(firstMetrics, matchHeight(dp(94), 0, dp(8)));
        LinearLayout secondMetrics = horizontal(8);
        memoryValue = metric(secondMetrics, "MEMORIA APP", "n/d", GOLD);
        thermalValue = metric(secondMetrics, "TERMICO", "n/d", CORAL);
        root.addView(secondMetrics, matchHeight(dp(94), 0, dp(18)));

        chart = new TelemetryChartView(this);
        root.addView(chart, matchHeight(dp(220), 0, dp(22)));

        sectionTitle("Esito sessione");
        summaryText = text("Le misure compariranno qui.", 14, TEXT, false);
        summaryText.setLineSpacing(0f, 1.28f);
        summaryText.setPadding(dp(14), dp(13), dp(14), dp(13));
        summaryText.setBackground(box(PANEL, LINE, 8));
        root.addView(summaryText, matchWrap(0, dp(20)));

        sectionTitle("Sessioni recenti");
        recentText = text("Nessuna sessione registrata.", 13, MUTED, false);
        recentText.setLineSpacing(dp(3), 1.15f);
        root.addView(recentText, matchWrap(0, dp(20)));

        LinearLayout export = horizontal(8);
        export.addView(command("Esporta JSON", 0xFF17262C, TEXT,
                view -> beginExport(true)), weighted());
        export.addView(command("Esporta CSV", 0xFF17262C, TEXT,
                view -> beginExport(false)), weighted());
        root.addView(export, matchHeight(dp(48), 0, dp(10)));

        Button delete = command("Cancella archivio locale", FIELD, CORAL,
                view -> confirmDelete());
        root.addView(delete, matchHeight(dp(48), 0, 0));
    }

    private void toggleSession() {
        String active = repository.activeSessionId();
        if (active.isEmpty()) {
            String id = repository.startSession(scenarioNames[selectedScenario], selectedTarget);
            JSONObject marker = new JSONObject();
            try {
                marker.put("action", "start");
                marker.put("scenario", scenarioNames[selectedScenario]);
                marker.put("target", selectedTarget);
                marker.put("session", id);
            } catch (Exception ignored) {
            }
            repository.record(getPackageName(), "session.marker",
                    SystemClock.elapsedRealtimeNanos(), marker.toString());
            startCollector();
            toast("Misura avviata");
        } else {
            JSONObject marker = new JSONObject();
            try {
                marker.put("action", "stop");
            } catch (Exception ignored) {
            }
            repository.record(getPackageName(), "session.marker",
                    SystemClock.elapsedRealtimeNanos(), marker.toString());
            repository.stopSession();
            stopCollector();
            toast("Sessione conclusa");
        }
        lastChartEventCount = -1;
        refresh();
    }

    private void startCollector() {
        try {
            setCollectorEnabled(true);
            startService(new Intent(this, TelemetryCollectorService.class));
        } catch (Exception ignored) {
            setCollectorEnabled(false);
            toast("Raccolta misure non disponibile");
        }
    }

    private void stopCollector() {
        try {
            stopService(new Intent(this, TelemetryCollectorService.class));
        } catch (Exception ignored) {
        } finally {
            setCollectorEnabled(false);
        }
    }

    private void setCollectorEnabled(boolean enabled) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, TelemetryCollectorService.class),
                enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void chooseTarget(String packageName, String label) {
        if (!repository.activeSessionId().isEmpty()) {
            toast("Concludi prima la sessione attiva");
            return;
        }
        selectedPackage = packageName;
        selectedTarget = label;
        applySegment(evoContiButton, "EvoConti".equals(label));
        applySegment(evoCoreButton, "EvoCore".equals(label));
        launchButton.setText("Apri " + label);
        refresh();
    }

    private void chooseScenario(int index) {
        if (!repository.activeSessionId().isEmpty()) {
            toast("Concludi prima la sessione attiva");
            return;
        }
        selectedScenario = index;
        for (int value = 0; value < scenarioButtons.length; value++) {
            applySegment(scenarioButtons[value], value == index);
        }
    }

    private void launchTarget() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(selectedPackage);
        if (launch == null) {
            toast(selectedTarget + " non e installato");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return;
        String packageName = selectedPackage;
        long chartEventCount = lastChartEventCount;
        io.execute(() -> {
            try {
                String active = repository.activeSessionId();
                TelemetryRepository.SessionRow activeRow = repository.activeSession();
                String measuredPackage = activeRow == null
                        ? packageName : packageForTarget(activeRow.target);
                TelemetryRepository.Summary summary = repository.summarize(active);
                JSONObject device = repository.latestPayload("device.sample");
                boolean updateChart = chartEventCount != summary.eventCount;
                List<TelemetryRepository.ChartSample> samples = updateChart
                        ? repository.chartSamples(active, 180) : null;
                String recent = recentLabel(repository.recentSessions(4));
                boolean installed = getPackageManager()
                        .getLaunchIntentForPackage(measuredPackage) != null;
                UiSnapshot snapshot = new UiSnapshot(active, activeRow, installed, summary,
                        device, samples, recent, updateChart);
                runOnUiThread(() -> applySnapshot(snapshot));
            } catch (Exception ignored) {
                refreshInFlight.set(false);
            }
        });
    }

    private void applySnapshot(UiSnapshot snapshot) {
        if (isFinishing() || isDestroyed()) {
            refreshInFlight.set(false);
            return;
        }
        String active = snapshot.activeSession;
        boolean running = !active.isEmpty();
        if (running && snapshot.activeRow != null) {
            restoreActiveSelection(snapshot.activeRow);
        }
        sessionButton.setText(running ? "Concludi misura" : "Avvia misura");
        sessionButton.setTextColor(running ? TEXT : 0xFF07130F);
        sessionButton.setBackground(box(running ? CORAL : GREEN, 0, 8));
        sessionStatus.setText(running
                ? "SESSIONE ATTIVA  |  "
                + (snapshot.activeRow == null
                ? scenarioNames[selectedScenario] + "  |  " + selectedTarget
                : snapshot.activeRow.scenario + "  |  " + snapshot.activeRow.target)
                : "Nessuna sessione attiva");
        sessionStatus.setTextColor(running ? GREEN : MUTED);

        launchButton.setEnabled(snapshot.installed);
        launchButton.setAlpha(snapshot.installed ? 1f : 0.45f);

        TelemetryRepository.Summary summary = snapshot.summary;
        eventValue.setText(Long.toString(summary.eventCount));
        responseValue.setText(summary.requestCount == 0 ? "n/d"
                : String.format(Locale.ITALY, "%.0f ms", summary.averageRequestMs));
        memoryValue.setText(summary.peakPssMb <= 0 ? "n/d"
                : String.format(Locale.ITALY, "%.0f MB", summary.peakPssMb));

        JSONObject device = snapshot.device;
        if (device.has("thermalHeadroom")) {
            thermalValue.setText(String.format(Locale.ITALY, "%.2f",
                    device.optDouble("thermalHeadroom")));
        } else if (device.has("thermalStatus")) {
            thermalValue.setText(thermalName(device.optInt("thermalStatus")));
        } else {
            thermalValue.setText("n/d");
        }

        String jank = summary.frameCount == 0 ? "n/d"
                : String.format(Locale.ITALY, "%.1f%%",
                summary.jankyFrames * 100d / summary.frameCount);
        String fallback = summary.requestCount == 0 ? "n/d"
                : summary.fallbackCount + "/" + summary.requestCount;
        String battery = Double.isNaN(summary.batteryStart) || Double.isNaN(summary.batteryEnd)
                ? "n/d" : String.format(Locale.ITALY, "%.1f%% -> %.1f%%",
                summary.batteryStart, summary.batteryEnd);
        summaryText.setText("Risposte AI: " + summary.requestCount
                + "\nPrimo segnale medio: "
                + (summary.requestCount == 0 ? "n/d"
                : String.format(Locale.ITALY, "%.0f ms", summary.averageFirstResponseMs))
                + "\nFallback: " + fallback
                + "\nFrame lenti: " + jank
                + "\nBatteria: " + battery
                + (summary.peakCorePssMb > 0
                ? "\nEvoCore picco: " + String.format(Locale.ITALY, "%.0f MB",
                summary.peakCorePssMb) : ""));

        if (snapshot.updateChart) {
            chart.setSamples(snapshot.samples);
            lastChartEventCount = summary.eventCount;
        }
        recentText.setText(snapshot.recent);
        refreshInFlight.set(false);
    }

    private void restoreActiveSelection(TelemetryRepository.SessionRow session) {
        selectedTarget = session.target;
        selectedPackage = packageForTarget(session.target);
        selectedScenario = scenarioIndex(session.scenario);
        applySegment(evoContiButton, "EvoConti".equals(session.target));
        applySegment(evoCoreButton, "EvoCore".equals(session.target));
        for (int index = 0; index < scenarioButtons.length; index++) {
            applySegment(scenarioButtons[index], index == selectedScenario);
        }
        launchButton.setText("Apri " + session.target);
    }

    private String packageForTarget(String target) {
        return "EvoCore".equals(target) ? "it.evocore.host" : "com.elia.evoconti";
    }

    private int scenarioIndex(String scenario) {
        for (int index = 0; index < scenarioNames.length; index++) {
            if (scenarioNames[index].equals(scenario)) return index;
        }
        return 0;
    }

    private static String recentLabel(List<TelemetryRepository.SessionRow> sessions) {
        if (sessions.isEmpty()) {
            return "Nessuna sessione registrata.";
        }
        SimpleDateFormat clock = new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY);
        StringBuilder out = new StringBuilder();
        for (TelemetryRepository.SessionRow session : sessions) {
            if (out.length() > 0) out.append("\n");
            out.append(clock.format(new Date(session.startedAt)))
                    .append("  |  ").append(session.target)
                    .append("  |  ").append(session.scenario)
                    .append("  |  ")
                    .append("ACTIVE".equals(session.status) ? "in corso" : "completa");
        }
        return out.toString();
    }

    private void beginExport(boolean json) {
        if (!pendingExport.isEmpty()) {
            toast("Esportazione gia in preparazione");
            return;
        }
        pendingExport = "__preparing__";
        toast("Preparo l'archivio cifrato");
        io.execute(() -> {
            String data = json ? repository.exportJson() : repository.exportCsv();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    pendingExport = "";
                    return;
                }
                pendingExport = data;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(json ? "application/json" : "text/csv");
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ITALY)
                        .format(new Date());
                intent.putExtra(Intent.EXTRA_TITLE,
                        "EvoLab-" + stamp + (json ? ".json" : ".csv"));
                startActivityForResult(intent, json ? EXPORT_JSON : EXPORT_CSV);
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_JSON && requestCode != EXPORT_CSV) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExport = "";
            return;
        }
        Uri destination = data.getData();
        try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
            if (stream == null) throw new IllegalStateException("Destinazione non disponibile");
            stream.write(pendingExport.getBytes(StandardCharsets.UTF_8));
            toast("Esportazione completata");
        } catch (Exception error) {
            toast("Esportazione non riuscita");
        } finally {
            pendingExport = "";
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Cancellare tutte le misure?")
                .setMessage("Sessioni ed eventi cifrati saranno rimossi dal dispositivo.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Cancella", (dialog, which) -> {
                    stopCollector();
                    repository.deleteAll();
                    lastChartEventCount = -1;
                    refresh();
                    toast("Archivio locale cancellato");
                })
                .show();
    }

    private TextView metric(LinearLayout parent, String label, String value, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setBackground(box(PANEL, LINE, 8));
        TextView caption = text(label, 10, accent, true);
        TextView number = text(value, 21, TEXT, true);
        number.setMaxLines(1);
        card.addView(caption);
        card.addView(number, matchWrap(0, 0));
        parent.addView(card, weighted());
        return number;
    }

    private void sectionTitle(String value) {
        root.addView(text(value, 17, TEXT, true), matchWrap(dp(2), dp(9)));
    }

    private Button segment(String label, boolean selected, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setOnClickListener(listener);
        button.setMinHeight(0);
        button.setMinWidth(0);
        applySegment(button, selected);
        return button;
    }

    private void applySegment(Button button, boolean selected) {
        button.setTextColor(selected ? 0xFF07130F : MUTED);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setBackground(box(selected ? GREEN : FIELD, selected ? 0 : LINE, 7));
    }

    private Button command(String label, int background, int foreground,
                           View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(box(background, background == FIELD ? LINE : 0, 8));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        text.setLetterSpacing(0f);
        return text;
    }

    private LinearLayout horizontal(int spacingDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (android.os.Build.VERSION.SDK_INT >= 29) row.setDividerPadding(dp(spacingDp));
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMarginEnd(dp(4));
        params.setMarginStart(dp(4));
        return params;
    }

    private LinearLayout.LayoutParams matchHeight(int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.setMargins(0, top, 0, bottom);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, top, 0, bottom);
        return params;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private String thermalName(int status) {
        if (status >= 5) return "critico";
        if (status >= 3) return "alto";
        if (status >= 1) return "caldo";
        return "normale";
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            refreshHandler.postDelayed(this, 2_000L);
        }
    };

    private static final class UiSnapshot {
        final String activeSession;
        final TelemetryRepository.SessionRow activeRow;
        final boolean installed;
        final TelemetryRepository.Summary summary;
        final JSONObject device;
        final List<TelemetryRepository.ChartSample> samples;
        final String recent;
        final boolean updateChart;

        UiSnapshot(String activeSession, TelemetryRepository.SessionRow activeRow,
                   boolean installed,
                   TelemetryRepository.Summary summary, JSONObject device,
                   List<TelemetryRepository.ChartSample> samples, String recent,
                   boolean updateChart) {
            this.activeSession = activeSession;
            this.activeRow = activeRow;
            this.installed = installed;
            this.summary = summary;
            this.device = device;
            this.samples = samples;
            this.recent = recent;
            this.updateChart = updateChart;
        }
    }
}
