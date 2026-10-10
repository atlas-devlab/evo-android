package it.evocore.sample;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.UUID;

import it.evocore.client.ContextEnvelope;
import it.evocore.client.CoreEvent;
import it.evocore.client.EvoCoreClient;
import it.evocore.client.ToolDescriptor;
import it.evocore.client.ToolRegistry;
import it.evocore.client.ToolResult;

public final class MainActivity extends ComponentActivity {
    private static final int BG = 0xFF090D12;
    private static final int SURFACE = 0xFF111820;
    private static final int SURFACE_HIGH = 0xFF17222C;
    private static final int INK = 0xFFF5F8FA;
    private static final int MUTED = 0xFFA6B4C1;
    private static final int GREEN = 0xFF42E8B4;
    private static final int CYAN = 0xFF35B8FF;
    private static final int AMBER = 0xFFFFC44D;
    private static final int RED = 0xFFFF6B6B;

    private EvoCoreClient client;
    private SampleTaskStore store;
    private ToolRegistry tools;
    private EvoCoreClient.RequestHandle activeRequest;
    private SampleTaskProposal pendingProposal;
    private TextView state;
    private TextView detail;
    private TextView taskHeading;
    private LinearLayout taskList;
    private EditText question;
    private Button ask;
    private Button cancel;
    private TextView output;
    private LinearLayout proposalPanel;
    private TextView proposalText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        store = new SampleTaskStore(this);
        tools = buildTools();
        client = EvoCoreClient.fromManifest(this,
                (ready, message) -> runOnUiThread(this::renderConnection));
        setContentView(buildUi());
        renderTasks();
        renderConnection();
        client.connect();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(18), bars.top + dp(16), dp(18), bars.bottom + dp(28));
            return insets;
        });

        root.addView(text("Attivita", 30, INK, Typeface.BOLD));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        state = text("DISCONNESSO", 12, AMBER, Typeface.BOLD);
        detail = text("", 12, MUTED, Typeface.NORMAL);
        statusRow.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        detailParams.setMargins(dp(10), 0, 0, 0);
        statusRow.addView(detail, detailParams);
        root.addView(statusRow, margins(0, dp(7), 0, dp(22)));

        taskHeading = text("", 19, INK, Typeface.BOLD);
        root.addView(taskHeading);
        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        root.addView(taskList, margins(0, dp(10), 0, dp(22)));

        root.addView(text("Cosa vuoi organizzare?", 18, INK, Typeface.BOLD));
        question = new EditText(this);
        question.setHint("Es. Ricordami di comprare il latte oggi alle 18");
        question.setHintTextColor(0xFF70808E);
        question.setTextColor(INK);
        question.setTextSize(15);
        question.setSingleLine(false);
        question.setMaxLines(4);
        question.setMinHeight(dp(92));
        question.setGravity(Gravity.TOP);
        question.setPadding(dp(14), dp(13), dp(14), dp(13));
        question.setBackground(box(SURFACE, 0xFF31414E));
        question.setContentDescription("sample-question");
        root.addView(question, margins(0, dp(10), 0, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        ask = button("Chiedi", GREEN);
        ask.setContentDescription("sample-ask");
        ask.setOnClickListener(view -> runQuestion());
        cancel = button("Annulla", RED);
        cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(view -> {
            EvoCoreClient.RequestHandle request = activeRequest;
            if (request != null) request.cancel();
        });
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(0, dp(52), 1f);
        action.setMargins(0, dp(10), dp(5), 0);
        actions.addView(ask, action);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        cancelParams.setMargins(dp(5), dp(10), 0, 0);
        actions.addView(cancel, cancelParams);
        root.addView(actions);

        output = text("Pronto.", 14, INK, Typeface.NORMAL);
        output.setTextIsSelectable(true);
        LinearLayout response = panel(SURFACE);
        response.addView(text("Custode locale", 12, CYAN, Typeface.BOLD));
        response.addView(output, margins(0, dp(8), 0, 0));
        root.addView(response, margins(0, dp(14), 0, 0));

        proposalPanel = panel(SURFACE_HIGH);
        proposalPanel.setVisibility(View.GONE);
        proposalPanel.addView(text("Azione proposta", 12, AMBER, Typeface.BOLD));
        proposalText = text("", 16, INK, Typeface.BOLD);
        proposalPanel.addView(proposalText, margins(0, dp(8), 0, dp(10)));
        Button confirm = button("Conferma", GREEN);
        confirm.setContentDescription("sample-confirm");
        confirm.setOnClickListener(view -> confirmProposal());
        Button reject = button("Non ora", MUTED);
        reject.setOnClickListener(view -> clearProposal("Proposta annullata. Nessun dato e stato modificato."));
        proposalPanel.addView(confirm, fixedHeight(dp(48)));
        proposalPanel.addView(reject, margins(0, dp(8), 0, 0));
        root.addView(proposalPanel, margins(0, dp(12), 0, 0));

        Button access = button("Gestisci accesso EvoCore", CYAN);
        access.setContentDescription("sample-access");
        access.setOnClickListener(view -> EvoCoreClient.openAccessManager(this));
        root.addView(access, marginsHeight(0, dp(18), 0, 0, dp(50)));

        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private ToolRegistry buildTools() {
        ToolDescriptor count = new ToolDescriptor(
                "sample.tasks.count", 1, ToolDescriptor.MODE_READ,
                "Conta le attivita visibili nella schermata",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Arrays.asList("quante attivita", "conta le attivita", "numero di attivita"),
                false, 5_000L, 4_096);
        ToolDescriptor propose = new ToolDescriptor(
                "sample.task.propose", 1, ToolDescriptor.MODE_PROPOSE,
                "Prepara una proposta di attivita senza salvarla",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Arrays.asList("ricordami", "aggiungi", "crea"),
                false, 5_000L, 8_192);
        ToolDescriptor slowDiagnostic = new ToolDescriptor(
                "sample.diagnostic.wait", 1, ToolDescriptor.MODE_READ,
                "Lettura lenta usata per verificare annullamento e revoca",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Arrays.asList("diagnostica annullamento"),
                false, 10_000L, 4_096);
        return new ToolRegistry()
                .register(count, call -> {
                    int total = store.list().size();
                    JSONObject result = new JSONObject();
                    result.put("count", total);
                    result.put("message", total == 1
                            ? "C'e 1 attivita visibile." : "Ci sono " + total + " attivita visibili.");
                    return ToolResult.success(call, result.toString());
                })
                .register(propose, call -> {
                    String input = new JSONObject(call.argumentsJson).optString("question", "");
                    SampleTaskProposal proposal = SampleTaskProposal.parse(input, call.callId);
                    if (proposal == null) {
                        return ToolResult.failure(call, ToolResult.STATUS_FAILED,
                                "Indica anche l'ora, per esempio: Ricordami di comprare il latte oggi alle 18");
                    }
                    return ToolResult.success(call, proposal.toResultJson());
                })
                .register(slowDiagnostic, call -> {
                    Thread.sleep(5_000L);
                    JSONObject result = new JSONObject();
                    result.put("message", "Diagnostica completata.");
                    return ToolResult.success(call, result.toString());
                });
    }

    private void runQuestion() {
        String input = question.getText().toString().trim();
        if (input.isEmpty()) {
            output.setText("Scrivi una domanda o una nuova attivita.");
            return;
        }
        hideKeyboard();
        pendingProposal = null;
        proposalPanel.setVisibility(View.GONE);
        if (!client.isReady()) {
            runFallback(input);
            return;
        }
        setRunning(true);
        output.setText("Richiesta inviata.");
        ContextEnvelope context = ContextEnvelope.screen(
                "evocore.sample.tasks.screen.v1", "tasks_today", store.contextJson());
        activeRequest = client.submit(context, input, "assistant", "sample.tasks",
                30_000L, tools, command -> runOnUiThread(command), this::onCoreEvent);
    }

    private void onCoreEvent(CoreEvent event) {
        if (event == null) return;
        if (event.type == CoreEvent.ACCEPTED) {
            output.setText("Richiesta accettata.");
        } else if (event.type == CoreEvent.STARTED) {
            output.setText("Sto leggendo il contesto di questa schermata.");
        } else if (event.type == CoreEvent.TOOL_CALL && event.toolCall != null) {
            output.setText("L'app sta verificando il dato richiesto.");
        } else if (event.type == CoreEvent.FINAL) {
            setRunning(false);
            activeRequest = null;
            renderFinal(event.finalJson);
        } else if (event.type == CoreEvent.CANCELLED) {
            setRunning(false);
            activeRequest = null;
            output.setText("Richiesta annullata.");
        } else if (event.type == CoreEvent.ERROR) {
            setRunning(false);
            activeRequest = null;
            output.setText(event.errorMessage.isEmpty()
                    ? "EvoCore non ha completato la richiesta." : event.errorMessage);
            renderConnection();
        }
    }

    private void renderFinal(String answerJson) {
        try {
            JSONObject answer = new JSONObject(answerJson);
            output.setText(answer.optString("messaggioBreve", answerJson));
            if ("sample.task.propose".equals(answer.optString("toolId"))) {
                JSONObject result = answer.optJSONObject("result");
                if (result != null) {
                    pendingProposal = new SampleTaskProposal(
                            result.optString("proposalId", ""),
                            result.optString("title", ""),
                            result.optString("due", ""));
                    showProposal();
                }
            }
        } catch (Exception ignored) {
            output.setText(answerJson == null || answerJson.isEmpty() ? "Risposta vuota." : answerJson);
        }
    }

    private void runFallback(String input) {
        String normalized = input.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("quante attivita") || normalized.contains("conta le attivita")
                || normalized.contains("numero di attivita")) {
            int count = store.list().size();
            output.setText(count == 1 ? "C'e 1 attivita visibile."
                    : "Ci sono " + count + " attivita visibili.");
            return;
        }
        SampleTaskProposal proposal = SampleTaskProposal.parse(input, UUID.randomUUID().toString());
        if (proposal != null) {
            pendingProposal = proposal;
            output.setText("Modalita locale essenziale: proposta pronta.");
            showProposal();
        } else {
            output.setText("Completa i dettagli, per esempio: Ricordami di comprare il latte oggi alle 18.");
        }
    }

    private void showProposal() {
        if (pendingProposal == null || pendingProposal.title.isEmpty()) return;
        proposalText.setText(pendingProposal.title + "\n" + pendingProposal.due);
        proposalPanel.setVisibility(View.VISIBLE);
    }

    private void confirmProposal() {
        SampleTaskProposal proposal = pendingProposal;
        if (proposal == null) return;
        boolean saved = store.addIfAbsent(proposal.proposalId, proposal.title, proposal.due);
        if (saved) {
            question.setText("");
            clearProposal("Attivita aggiunta.");
            renderTasks();
        } else {
            output.setText("Non sono riuscito a salvarla. Nessuna modifica e stata applicata.");
        }
    }

    private void clearProposal(String message) {
        pendingProposal = null;
        proposalPanel.setVisibility(View.GONE);
        output.setText(message);
    }

    private void renderTasks() {
        if (taskList == null) return;
        taskList.removeAllViews();
        java.util.List<SampleTaskStore.Task> rows = store.list();
        taskHeading.setText("Oggi  " + rows.size());
        if (rows.isEmpty()) {
            TextView empty = text("Nessuna attivita in elenco.", 14, MUTED, Typeface.NORMAL);
            empty.setPadding(dp(2), dp(8), dp(2), dp(8));
            taskList.addView(empty);
            return;
        }
        for (SampleTaskStore.Task task : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(box(SURFACE, 0xFF263744));
            TextView marker = text("OK", 11, GREEN, Typeface.BOLD);
            row.addView(marker, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.addView(text(task.title, 15, INK, Typeface.BOLD));
            copy.addView(text(task.due, 12, MUTED, Typeface.NORMAL), margins(0, dp(3), 0, 0));
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            taskList.addView(row, margins(0, 0, 0, dp(8)));
        }
    }

    private void renderConnection() {
        if (state == null) return;
        boolean ready = client.isReady();
        state.setText(ready ? "EVOCORE COLLEGATO" : "MODALITA ESSENZIALE");
        state.setTextColor(ready ? GREEN : AMBER);
        detail.setText(ready ? "v" + client.negotiatedProtocolVersion() : client.detail());
    }

    private void setRunning(boolean running) {
        ask.setEnabled(!running);
        ask.setAlpha(running ? 0.45f : 1f);
        cancel.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        Object service = getSystemService(INPUT_METHOD_SERVICE);
        if (service instanceof InputMethodManager) {
            ((InputMethodManager) service).hideSoftInputFromWindow(question.getWindowToken(), 0);
        }
        question.clearFocus();
    }

    private LinearLayout panel(int fill) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15), dp(14), dp(15), dp(14));
        panel.setBackground(box(fill, 0xFF2A3B48));
        return panel;
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(box(0xFF0D141B, color));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setLetterSpacing(0f);
        text.setLineSpacing(0f, 1.08f);
        return text;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable box = new GradientDrawable();
        box.setColor(fill);
        box.setCornerRadius(dp(8));
        box.setStroke(dp(1), stroke);
        return box;
    }

    private LinearLayout.LayoutParams fixedHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams marginsHeight(int left, int top, int right, int bottom, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (activeRequest != null) activeRequest.cancel();
        client.close();
        super.onDestroy();
    }
}
