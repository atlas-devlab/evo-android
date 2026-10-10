package it.evocore.host;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.evocore.client.EvoCoreProtocol;
import it.evocore.telemetry.TelemetryJankTracker;
import it.evocore.telemetry.TelemetryRuntime;

public final class MainActivity extends ComponentActivity {
    private static final int BG = 0xFF090D12;
    private static final int PANEL = 0xFF111923;
    private static final int PANEL_2 = 0xFF17232C;
    private static final int INK = 0xFFF4F7FB;
    private static final int MUTED = 0xFF9FB0C1;
    private static final int GREEN = 0xFF42E8B4;
    private static final int CYAN = 0xFF35B8FF;
    private static final int GOLD = 0xFFF4C84A;
    private static final int CORAL = 0xFFFF6D5A;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EvoCoreDb db;
    private ClientRegistry clientRegistry;
    private CoreModelManager models;
    private LinearLayout content;
    private LinearLayout root;
    private int tab;
    private String vaultStatus = "Verifica caveau in corso";
    private List<EvoCoreDb.MemoryRecord> memories = new ArrayList<>();
    private List<ClientRegistry.ClientRecord> clientAccesses = new ArrayList<>();
    private boolean loading = true;
    private ActivityResultLauncher<String[]> modelPicker;
    private TelemetryJankTracker telemetryJank;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        db = new EvoCoreDb(this);
        clientRegistry = new ClientRegistry(this);
        models = new CoreModelManager(this);
        if ("access".equals(getIntent().getStringExtra(EvoCoreProtocol.EXTRA_OPEN_TAB))) tab = 2;
        modelPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importModel);
        buildShell();
        render();
        refreshAsync();
    }

    @Override
    protected void onStart() {
        super.onStart();
        TelemetryRuntime.start(this);
        TelemetryRuntime.refreshSession();
        telemetryJank = TelemetryJankTracker.attach(getWindow());
        String screen = tab == 0 ? "core" : tab == 1 ? "memory" : "access";
        TelemetryRuntime.screen(screen);
        if (telemetryJank != null) telemetryJank.setScreen(screen);
    }

    @Override
    protected void onStop() {
        if (telemetryJank != null) {
            telemetryJank.stop();
            telemetryJank = null;
        }
        TelemetryRuntime.stop();
        super.onStop();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(16), bars.top + dp(10), dp(16), bars.bottom + dp(10));
            return insets;
        });

        LinearLayout brand = row();
        TextView mark = text("E", 18, BG, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(box(GREEN, dp(6), GREEN));
        brand.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(10), 0, 0, 0);
        identity.addView(text("EvoCore", 24, INK, Typeface.BOLD));
        identity.addView(text("Nucleo locale personale", 12, MUTED, Typeface.NORMAL));
        brand.addView(identity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView local = text("SOLO LOCALE", 10, GREEN, Typeface.BOLD);
        local.setGravity(Gravity.CENTER);
        local.setBackground(box(0xFF102A25, dp(5), 0xFF245F50));
        brand.addView(local, new LinearLayout.LayoutParams(dp(94), dp(30)));
        root.addView(brand);

        LinearLayout tabs = row();
        tabs.setPadding(0, dp(16), 0, dp(10));
        tabs.addView(tabButton("Nucleo", 0), weighted());
        tabs.addView(tabButton("Io", 1), weighted());
        tabs.addView(tabButton("Accessi", 2), weighted());
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void render() {
        if (content == null) return;
        String screen = tab == 0 ? "core" : tab == 1 ? "memory" : "access";
        TelemetryRuntime.screen(screen);
        if (telemetryJank != null) telemetryJank.setScreen(screen);
        content.removeAllViews();
        if (tab == 0) renderCore();
        else if (tab == 1) renderMemory();
        else renderAccess();
    }

    private void renderCore() {
        FrameLayout monitor = new FrameLayout(this);
        monitor.setBackground(box(0xFF0B1418, dp(8), 0xFF27665B));
        CorePulseView pulse = new CorePulseView();
        monitor.addView(pulse, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(226)));
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER_HORIZONTAL);
        overlay.setPadding(dp(16), dp(34), dp(16), dp(20));
        TextView name = text("EV0 / NUCLEO 01", 11, GREEN, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        overlay.addView(name);
        TextView state = text(models.hasModel() ? "Gemma pronta" : "Regole pronte", 28, INK, Typeface.BOLD);
        state.setGravity(Gravity.CENTER);
        overlay.addView(state, margins(0, dp(76), 0, 0));
        TextView sub = text(models.hasModel() ? "Inferenza nel processo EvoCore" : "Fallback locale sempre disponibile",
                12, MUTED, Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER);
        overlay.addView(sub, margins(0, dp(4), 0, 0));
        monitor.addView(overlay);
        content.addView(monitor, margins(0, dp(4), 0, dp(14)));

        LinearLayout status = section();
        status.addView(sectionTitle("Stato operativo", GREEN));
        status.addView(metric("Caveau", vaultStatus, vaultStatus.startsWith("StrongBox") ? GREEN : GOLD));
        status.addView(metric("Motore", models.status(), models.hasModel() ? CYAN : MUTED));
        status.addView(metric("Memorie attive", Integer.toString(memories.size()), INK));
        status.addView(metric("Protocollo", "EvoCore IPC v" + EvoCoreProtocol.VERSION, INK));
        content.addView(status, margins(0, 0, 0, dp(12)));

        LinearLayout actions = section();
        actions.addView(sectionTitle("Motore locale", CYAN));
        Button importButton = action("Importa modello GGUF", CYAN);
        importButton.setContentDescription("Importa un modello locale in EvoCore");
        importButton.setOnClickListener(view -> modelPicker.launch(new String[]{"application/octet-stream", "*/*"}));
        actions.addView(importButton, fullButton());
        if (models.hasModel()) {
            Button remove = action("Elimina modello", CORAL);
            remove.setOnClickListener(view -> confirmDeleteModel());
            actions.addView(remove, fullButton());
        }
        Button lock = action("Blocca ora il caveau", GOLD);
        lock.setOnClickListener(view -> {
            db.lockVault();
            toast("Chiavi di sessione rimosse dalla memoria");
            refreshAsync();
        });
        actions.addView(lock, fullButton());
        content.addView(actions);
    }

    private void renderMemory() {
        content.addView(text("Io, secondo EvoCore", 27, INK, Typeface.BOLD), margins(dp(2), dp(4), 0, 0));
        content.addView(text("Ricordi sotto il tuo controllo", 13, MUTED, Typeface.NORMAL),
                margins(dp(2), dp(2), 0, dp(14)));

        LinearLayout summary = row();
        summary.addView(counter("Confermati", countConfirmed(), GREEN), weightedMargins(0, 0, dp(4), 0));
        summary.addView(counter("Da verificare", memories.size() - countConfirmed(), GOLD),
                weightedMargins(dp(4), 0, 0, 0));
        content.addView(summary, margins(0, 0, 0, dp(12)));

        Button add = action("Aggiungi un ricordo", GREEN);
        add.setContentDescription("Aggiungi un ricordo personale confermato");
        add.setOnClickListener(view -> showAddMemory());
        content.addView(add, fullButton());

        if (loading) {
            LinearLayout waiting = section();
            ProgressBar progress = new ProgressBar(this);
            waiting.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
            waiting.addView(text("Apro il caveau...", 13, MUTED, Typeface.NORMAL), margins(0, dp(8), 0, 0));
            content.addView(waiting, margins(0, dp(10), 0, 0));
            return;
        }
        if (memories.isEmpty()) {
            LinearLayout empty = section();
            empty.addView(text("Nessun ricordo attivo", 18, INK, Typeface.BOLD));
            empty.addView(text("Il profilo nasce solo da elementi che puoi vedere e correggere.",
                    13, MUTED, Typeface.NORMAL), margins(0, dp(6), 0, 0));
            content.addView(empty, margins(0, dp(10), 0, 0));
            return;
        }
        for (EvoCoreDb.MemoryRecord memory : memories) content.addView(memoryCard(memory));
    }

    private void renderAccess() {
        content.addView(text("Accessi", 27, INK, Typeface.BOLD), margins(dp(2), dp(4), 0, 0));
        content.addView(text("Ogni app ha identita, permessi e memoria separati.", 13, MUTED, Typeface.NORMAL),
                margins(dp(2), dp(3), 0, dp(14)));

        if (clientAccesses.isEmpty()) {
            LinearLayout empty = section();
            empty.addView(text("Nessuna app ha chiesto accesso", 17, INK, Typeface.BOLD));
            empty.addView(text("Apri una nuova app collegata: la richiesta apparira qui prima di usare EvoCore.",
                    13, MUTED, Typeface.NORMAL), margins(0, dp(6), 0, 0));
            content.addView(empty, margins(0, 0, 0, dp(12)));
        } else {
            for (ClientRegistry.ClientRecord access : clientAccesses) {
                content.addView(clientCard(access), margins(0, 0, 0, dp(12)));
            }
        }

        LinearLayout boundary = section();
        boundary.addView(sectionTitle("CONFINI ATTIVI", GOLD));
        boundary.addView(statusLine("Database delle app", "mai letto direttamente", true));
        boundary.addView(statusLine("Internet EvoCore", "permesso assente", true));
        boundary.addView(statusLine("Servizio Binder", "permission di firma", true));
        boundary.addView(statusLine("Memoria", "package + namespace", true));
        content.addView(boundary);
    }

    private View clientCard(ClientRegistry.ClientRecord access) {
        LinearLayout card = section();
        boolean approved = ClientRegistry.STATE_APPROVED.equals(access.state);
        boolean pending = ClientRegistry.STATE_PENDING.equals(access.state);
        int color = approved ? GREEN : pending ? GOLD : CORAL;
        String state = approved ? "AUTORIZZATA" : pending ? "DA APPROVARE" : "REVOCATA";
        card.addView(sectionTitle(access.label.toUpperCase(Locale.ITALY) + "  |  " + state, color));
        card.addView(metric("Package", access.packageName, INK));
        card.addView(metric("ID client", access.clientId, CYAN));
        card.addView(metric("Spazio memoria", access.scope(), CYAN));
        card.addView(text("Richiede: " + readableCapabilities(access.requestedCapabilities),
                12, MUTED, Typeface.NORMAL), margins(0, dp(9), 0, 0));
        String fingerprint = access.certificateSha256.length() > 12
                ? access.certificateSha256.substring(0, 12) : access.certificateSha256;
        card.addView(text("Firma: " + fingerprint + "...", 11, MUTED, Typeface.NORMAL),
                margins(0, dp(5), 0, 0));

        Button action = action(approved ? "Revoca accesso" : "Approva accesso",
                approved ? CORAL : GREEN);
        action.setContentDescription((approved ? "Revoca " : "Approva ") + access.label);
        action.setOnClickListener(view -> runClientAction(access, approved ? "revoke" : "approve"));
        card.addView(action, fullButton());
        return card;
    }

    private String readableCapabilities(java.util.Set<String> capabilities) {
        List<String> labels = new ArrayList<>();
        if (capabilities.contains("inference.ask")) labels.add("dialogo AI");
        if (capabilities.contains("memory.read")) labels.add("leggere i propri ricordi");
        if (capabilities.contains("memory.write")) labels.add("salvare ricordi");
        if (capabilities.contains("memory.manage")) labels.add("dimenticare i propri ricordi");
        if (capabilities.contains("status.read")) labels.add("stato motore");
        if (capabilities.contains("tools.propose")) labels.add("chiedere dati o proporre azioni");
        return labels.isEmpty() ? "nessuna capacita riconosciuta" : String.join(", ", labels);
    }

    private void runClientAction(ClientRegistry.ClientRecord access, String action) {
        io.execute(() -> {
            if ("approve".equals(action)) clientRegistry.approve(access.packageName);
            else clientRegistry.revoke(access.packageName);
            runOnUiThread(() -> toast("approve".equals(action)
                    ? access.label + " autorizzata" : access.label + " revocata"));
            refreshAsync();
        });
    }

    private View memoryCard(EvoCoreDb.MemoryRecord memory) {
        LinearLayout card = section();
        boolean confirmed = memory.isConfirmed();
        LinearLayout top = row();
        TextView badge = text(confirmed ? "CONFERMATO" : "DA CONFERMARE", 10,
                confirmed ? GREEN : GOLD, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(box(confirmed ? 0xFF102A25 : 0xFF2B2512, dp(4),
                confirmed ? 0xFF245F50 : 0xFF6D5C21));
        top.addView(badge, new LinearLayout.LayoutParams(confirmed ? dp(104) : dp(126), dp(28)));
        TextView scope = text(memoryOwner(memory.scope),
                11, MUTED, Typeface.BOLD);
        scope.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(scope, new LinearLayout.LayoutParams(0, dp(28), 1));
        card.addView(top);
        card.addView(text(memory.text, 16, INK, Typeface.NORMAL), margins(0, dp(12), 0, 0));
        String meta = memory.kind + "  |  " + memory.sensitivity + "  |  " +
                new SimpleDateFormat("dd MMM HH:mm", Locale.ITALY).format(new Date(memory.updatedAt));
        card.addView(text(meta, 11, MUTED, Typeface.NORMAL), margins(0, dp(8), 0, dp(8)));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = row();
        if (!confirmed) {
            Button confirm = smallAction("Conferma", GREEN);
            confirm.setOnClickListener(view -> runMemoryAction(memory, "confirm", ""));
            actions.addView(confirm);
        }
        Button correct = smallAction("Correggi", CYAN);
        correct.setOnClickListener(view -> showCorrection(memory));
        actions.addView(correct);
        Button forget = smallAction("Dimentica", CORAL);
        forget.setOnClickListener(view -> confirmForget(memory));
        actions.addView(forget);
        scroller.addView(actions);
        card.addView(scroller);
        card.setLayoutParams(margins(0, dp(6), 0, dp(6)));
        return card;
    }

    private void showAddMemory() {
        EditText input = dialogInput("Scrivi un fatto, una preferenza o un obiettivo");
        new AlertDialog.Builder(this)
                .setTitle("Nuovo ricordo confermato")
                .setView(input)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Conserva", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.length() < 3) {
                        toast("Scrivi un ricordo piu preciso");
                        return;
                    }
                    loading = true;
                    render();
                    io.execute(() -> {
                        try {
                            MemoryPolicyGate.Decision decision = new MemoryPolicyGate().evaluate(
                                    value, "manual", MemoryPolicyGate.USER_CONFIRMED, System.currentTimeMillis());
                            if (decision.accepted) db.add("it.evocore.host:personal", value, "personale",
                                    decision.kind, decision.state, "manual", decision.sensitivity,
                                    "EvoCore UI", decision.expiresAt, 0L);
                            runOnUiThread(() -> toast(decision.accepted ? "Ricordo cifrato e salvato" : decision.reason));
                        } catch (Exception error) {
                            runOnUiThread(() -> toast("Salvataggio non riuscito"));
                        }
                        refreshAsync();
                    });
                }).show();
    }

    private void showCorrection(EvoCoreDb.MemoryRecord memory) {
        EditText input = dialogInput("Versione corretta");
        input.setText(memory.text);
        input.setSelection(input.length());
        new AlertDialog.Builder(this)
                .setTitle("Correggi il ricordo")
                .setView(input)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva versione", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.length() >= 3) runMemoryAction(memory, "correct", value);
                }).show();
    }

    private void confirmForget(EvoCoreDb.MemoryRecord memory) {
        new AlertDialog.Builder(this)
                .setTitle("Dimenticare questo ricordo?")
                .setMessage("Il contenuto cifrato verra eliminato dal caveau.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Dimentica", (dialog, which) -> runMemoryAction(memory, "forget", ""))
                .show();
    }

    private void runMemoryAction(EvoCoreDb.MemoryRecord memory, String action, String replacement) {
        loading = true;
        render();
        io.execute(() -> {
            try {
                if ("confirm".equals(action)) db.confirm(memory.scope, memory.id);
                else if ("correct".equals(action)) db.correct(memory.scope, memory.id, replacement);
                else db.forget(memory.scope, memory.id);
            } catch (Exception error) {
                runOnUiThread(() -> toast("Operazione non riuscita"));
            }
            refreshAsync();
        });
    }

    private void confirmDeleteModel() {
        new AlertDialog.Builder(this)
                .setTitle("Eliminare il modello locale?")
                .setMessage("Le regole di sicurezza e la memoria restano disponibili.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Elimina", (dialog, which) -> {
                    boolean ok = models.deleteActive();
                    toast(ok ? "Modello eliminato" : "Eliminazione incompleta");
                    render();
                }).show();
    }

    private void importModel(Uri uri) {
        if (uri == null) return;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Importazione modello")
                .setMessage("Preparazione...")
                .setCancelable(false)
                .create();
        progress.show();
        models.importFrom(uri, new CoreModelManager.Callback() {
            @Override
            public void onProgress(int percent) {
                runOnUiThread(() -> progress.setMessage(percent < 0 ? "Copia in corso..." : percent + "%"));
            }

            @Override
            public void onDone(boolean ok, String message) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    toast(message);
                    render();
                });
            }
        });
    }

    private void refreshAsync() {
        loading = true;
        io.execute(() -> {
            String nextVault = db.vaultStatus();
            List<ClientRegistry.ClientRecord> nextClients = clientRegistry.list();
            List<String> scopes = new ArrayList<>();
            for (ClientRegistry.ClientRecord access : nextClients) scopes.add(access.scope());
            List<EvoCoreDb.MemoryRecord> nextMemories = db.listAllKnown(scopes);
            runOnUiThread(() -> {
                vaultStatus = nextVault;
                memories = nextMemories;
                clientAccesses = nextClients;
                loading = false;
                render();
            });
        });
    }

    private Button tabButton(String label, int value) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(tab == value ? BG : MUTED);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(box(tab == value ? GREEN : PANEL, dp(6), tab == value ? GREEN : 0xFF2B3C49));
        button.setOnClickListener(view -> {
            tab = value;
            buildShell();
            render();
        });
        return button;
    }

    private LinearLayout section() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(15), dp(14), dp(15), dp(14));
        section.setBackground(box(PANEL, dp(8), 0xFF2A3D4A));
        return section;
    }

    private View sectionTitle(String value, int color) {
        return text(value, 11, color, Typeface.BOLD);
    }

    private View metric(String label, String value, int valueColor) {
        LinearLayout row = row();
        row.setPadding(0, dp(10), 0, dp(2));
        row.addView(text(label, 13, MUTED, Typeface.NORMAL), weighted());
        TextView right = text(value, 13, valueColor, Typeface.BOLD);
        right.setGravity(Gravity.END);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f));
        return row;
    }

    private View statusLine(String label, String value, boolean active) {
        LinearLayout row = row();
        row.setPadding(0, dp(10), 0, dp(2));
        TextView dot = text("●", 11, active ? GREEN : CORAL, Typeface.BOLD);
        row.addView(dot, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(label, 13, INK, Typeface.BOLD), weighted());
        TextView right = text(value, 12, MUTED, Typeface.NORMAL);
        right.setGravity(Gravity.END);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        return row;
    }

    private View counter(String label, int count, int color) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(14), dp(12), dp(14), dp(12));
        block.setBackground(box(PANEL_2, dp(8), 0xFF2A3D4A));
        block.addView(text(Integer.toString(count), 25, color, Typeface.BOLD));
        block.addView(text(label, 11, MUTED, Typeface.BOLD));
        return block;
    }

    private Button action(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(box(0xFF0D151D, dp(6), color));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private Button smallAction(String label, int color) {
        Button button = action(label, color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(108), dp(40));
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    private EditText dialogInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setPadding(dp(18), dp(12), dp(18), dp(12));
        return input;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setLetterSpacing(0f);
        view.setLineSpacing(0f, 1.08f);
        return view;
    }

    private GradientDrawable box(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(44), 1);
    }

    private LinearLayout.LayoutParams weightedMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams fullButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, dp(10), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int countConfirmed() {
        int count = 0;
        for (EvoCoreDb.MemoryRecord memory : memories) if (memory.isConfirmed()) count++;
        return count;
    }

    private String memoryOwner(String scope) {
        if (scope != null && scope.endsWith(":personal")) return "EvoCore";
        for (ClientRegistry.ClientRecord access : clientAccesses) {
            if (access.scope().equals(scope)) return access.label;
        }
        return "App rimossa";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (telemetryJank != null) telemetryJank.stop();
        if (clientRegistry != null) clientRegistry.close();
        super.onDestroy();
    }

    private final class CorePulseView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator;
        private float phase;

        CorePulseView() {
            super(MainActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2_800L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(value -> {
                phase = (float) value.getAnimatedValue();
                invalidate();
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateAnimation();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            updateAnimation();
        }

        @Override
        protected void onDetachedFromWindow() {
            animator.cancel();
            super.onDetachedFromWindow();
        }

        private void updateAnimation() {
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
                if (!animator.isStarted()) animator.start();
            } else {
                animator.cancel();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = dp(95);
            for (int i = 0; i < 4; i++) {
                float local = (phase + i * 0.25f) % 1f;
                float radius = dp(28) + local * dp(68);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(i == 0 ? 2 : 1));
                paint.setColor((Math.round((1f - local) * 110) << 24) | (GREEN & 0x00FFFFFF));
                canvas.drawCircle(cx, cy, radius, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(GREEN);
            canvas.drawCircle(cx, cy, dp(16), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(CYAN);
            RectF ring = new RectF(cx - dp(25), cy - dp(25), cx + dp(25), cy + dp(25));
            canvas.drawArc(ring, phase * 360f, 220f, false, paint);
            paint.setColor(GOLD);
            canvas.drawCircle(cx + dp(10), cy - dp(8), dp(3), paint);
        }
    }
}
