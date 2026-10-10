package com.elia.evoconti;

import android.content.Context;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import it.evocore.client.ContextEnvelope;
import it.evocore.client.CoreEvent;
import it.evocore.client.EvoCoreClient;
import it.evocore.client.EvoCoreProtocol;
import it.evocore.client.ToolRegistry;
import it.evocore.telemetry.TelemetryRuntime;

final class EvoCoreGateway {
    private static final long REQUEST_TIMEOUT_MS = 120_000L;
    private static final long TERMINAL_WAIT_MS = 122_000L;
    private static volatile EvoCoreGateway instance;
    private final Context context;
    private final EvoCoreClient client;
    private volatile String status = "Collegamento a EvoCore";
    private volatile String lastError = "";

    static EvoCoreGateway get(Context context) {
        EvoCoreGateway value = instance;
        if (value != null) return value;
        synchronized (EvoCoreGateway.class) {
            value = instance;
            if (value == null) instance = value = new EvoCoreGateway(context);
            return value;
        }
    }

    private EvoCoreGateway(Context context) {
        this.context = context.getApplicationContext();
        client = new EvoCoreClient(this.context, "evoconti", (ready, detail) -> {
            status = detail;
            if (ready) refreshStatus();
        });
        status = EvoCoreClient.isInstalled(this.context)
                ? "EvoCore pronto quando serve" : "EvoCore non installato";
    }

    static void disconnectIfCreated() {
        EvoCoreGateway value = instance;
        if (value == null) return;
        value.client.disconnect();
        value.status = "EvoCore in attesa";
    }

    boolean isInstalled() {
        return EvoCoreClient.isInstalled(context);
    }

    boolean isReady() {
        if (!isInstalled()) return false;
        if (!client.isReady()) client.connect();
        return client.isReady();
    }

    AiAnswer ask(AiContext context, String question) {
        return ask(context, question, 160, null);
    }

    AiAnswer ask(AiContext context, String question, int maxOutputTokens,
                 AiProgressListener progressListener) {
        long started = System.nanoTime();
        if (!client.isReady()) {
            EvoCoreClient.Result connection = client.status();
            if (!connection.ok) {
                String detail = connection.error.isEmpty() ? client.detail() : connection.error;
                return failed(detail.isEmpty() ? "EvoCore non collegato" : detail, started);
            }
        }
        String persona = question != null && question.contains("[PERSONA:COMPAGNO]") ? "companion" : "custodian";
        String safeQuestion = question == null ? "" : question;
        String contextJson = context == null ? "{}" : context.toJson(safeQuestion);
        JSONObject requestMetric = new JSONObject();
        try {
            requestMetric.put("persona", persona);
            requestMetric.put("contextChars", contextJson.length());
            requestMetric.put("questionChars", safeQuestion.length());
        } catch (Exception ignored) {
        }
        TelemetryRuntime.event("ai.client.request_started", requestMetric);
        ContextEnvelope envelope = ContextEnvelope.screen(
                "evoconti.dialogue.v1", persona, contextJson);
        StringBuilder streamed = new StringBuilder();
        AtomicLong lastProgressAt = new AtomicLong();
        AtomicReference<String> lastPreview = new AtomicReference<>("");
        EvoCoreClient.RequestHandle handle = client.submit(envelope, safeQuestion, persona,
                "evoconti-" + persona, REQUEST_TIMEOUT_MS, maxOutputTokens,
                new ToolRegistry(), Runnable::run,
                event -> {
                    if (event.type == CoreEvent.STARTED) status = "EvoCore sta ragionando";
                    if (event.type == CoreEvent.TEXT_DELTA && !event.textDelta.isEmpty()) {
                        streamed.append(event.textDelta);
                        String preview = messagePreview(streamed.toString());
                        long now = android.os.SystemClock.elapsedRealtime();
                        String previous = lastPreview.get();
                        if (progressListener != null && !preview.isEmpty()
                                && !preview.equals(previous)
                                && (now - lastProgressAt.get() >= 80L
                                || preview.length() - previous.length() >= 18)) {
                            lastProgressAt.set(now);
                            lastPreview.set(preview);
                            progressListener.onText(preview);
                        }
                    }
                });
        CoreEvent terminal;
        try {
            terminal = handle.terminal().get(TERMINAL_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            handle.cancel();
            return failed("Tempo EvoCore terminato", started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            handle.cancel();
            return failed("Richiesta EvoCore interrotta", started);
        } catch (Exception error) {
            handle.cancel();
            return failed("Connessione EvoCore interrotta", started);
        }
        long latency = (System.nanoTime() - started) / 1_000_000L;
        if (terminal.type != CoreEvent.FINAL) {
            lastError = terminal.errorMessage.isEmpty()
                    ? "EvoCore non ha completato la risposta" : terminal.errorMessage;
            status = "EvoCore non disponibile | " + lastError;
            emitFinalMetric("failure", latency, true, lastError, terminal.engine);
            return null;
        }
        String json = terminal.finalJson;
        AiAnswer answer = AiAnswer.fromModelOutput(json);
        if (answer == null) {
            lastError = "Risposta EvoCore non conforme";
            emitFinalMetric("invalid_response", latency, true, lastError, terminal.engine);
            return null;
        }
        boolean fallback = terminal.engine.contains("fallback-regole");
        answer.motore = fallback
                ? "EvoCore separato | regole locali verificate"
                : "EvoCore separato | Gemma locale";
        status = terminal.engine + " | " + latency + " ms";
        lastError = "";
        emitFinalMetric("success", latency, fallback, "", terminal.engine);
        return answer;
    }

    static String messagePreview(String streamedJson) {
        if (streamedJson == null || streamedJson.isEmpty()) return "";
        String field = "\"messaggioBreve\"";
        int key = streamedJson.indexOf(field);
        if (key < 0) return "";
        int colon = streamedJson.indexOf(':', key + field.length());
        if (colon < 0) return "";
        int quote = streamedJson.indexOf('"', colon + 1);
        if (quote < 0) return "";
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = quote + 1; index < streamedJson.length(); index++) {
            char value = streamedJson.charAt(index);
            if (escaped) {
                if (value == 'n' || value == 'r' || value == 't') result.append(' ');
                else result.append(value);
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '"') {
                break;
            } else {
                result.append(value);
            }
            if (result.length() >= 280) break;
        }
        return result.toString().trim();
    }

    String status() {
        if (!isInstalled()) return "EvoCore non installato | regole EvoConti attive";
        if (!client.isReady()) client.connect();
        return status;
    }

    String lastError() {
        return lastError;
    }

    boolean openHost() {
        return EvoCoreClient.openHost(context);
    }

    private void refreshStatus() {
        new Thread(() -> {
            EvoCoreClient.Result result = client.status();
            if (result.ok) {
                String engine = result.data.getString(EvoCoreProtocol.KEY_STATUS, "EvoCore collegato");
                String vault = result.data.getString("vault", "caveau attivo");
                status = engine + " | " + vault;
            }
        }, "EvoCoreStatus").start();
    }

    private AiAnswer failed(String message, long startedNanos) {
        long latency = (System.nanoTime() - startedNanos) / 1_000_000L;
        lastError = message;
        status = isInstalled() ? "EvoCore non disponibile | " + message
                : "EvoCore non installato";
        emitFinalMetric("failure", latency, true, message, "");
        return null;
    }

    private static void emitFinalMetric(String state, long latencyMs, boolean fallback,
                                        String error, String engine) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("state", state);
            payload.put("totalMs", latencyMs);
            payload.put("fallback", fallback);
            payload.put("error", error == null ? "" : error);
            payload.put("engine", engine == null ? "" : engine);
        } catch (Exception ignored) {
        }
        TelemetryRuntime.event("ai.client.final", payload);
    }
}
