package com.elia.evoconti;

import android.content.Context;

/** Compatibility adapter: inference now lives exclusively in the separate EvoCore APK. */
final class LlamaCppEngine implements LocalLlmEngine {
    private final EvoCoreGateway gateway;
    private volatile String lastError = "";
    private volatile String lastRaw = "";
    private volatile long lastLatencyMs;

    LlamaCppEngine(Context context) {
        this.gateway = EvoCoreGateway.get(context);
    }

    @Override
    public boolean isAvailable() {
        return gateway.isInstalled();
    }

    @Override
    public AiAnswer ask(AiContext context, String userQuestion) {
        return ask(context, userQuestion, 160, null);
    }

    AiAnswer ask(AiContext context, String userQuestion, int maxTokens) {
        return ask(context, userQuestion, maxTokens, null);
    }

    AiAnswer ask(AiContext context, String userQuestion, int maxTokens,
                 AiProgressListener progressListener) {
        long startedAt = System.nanoTime();
        AiAnswer answer = gateway.ask(context, userQuestion,
                Math.max(64, Math.min(128, maxTokens)), progressListener);
        lastLatencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        lastRaw = answer == null ? "" : answer.toJson();
        lastError = gateway.lastError();
        return answer;
    }

    String status() {
        return gateway.status();
    }

    String lastError() {
        return lastError;
    }

    String lastRaw() {
        return lastRaw;
    }

    long lastLatencyMs() {
        return lastLatencyMs;
    }

    void unload() {
        // The model lifecycle belongs to EvoCore, never to a client app.
    }
}
