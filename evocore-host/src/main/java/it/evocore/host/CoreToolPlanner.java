package it.evocore.host;

import android.os.SystemClock;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;

import it.evocore.client.CoreRequest;
import it.evocore.client.ToolCall;
import it.evocore.client.ToolDescriptor;

final class CoreToolPlanner {
    private final LongSupplier elapsedClock;

    CoreToolPlanner() {
        this(SystemClock::elapsedRealtime);
    }

    CoreToolPlanner(LongSupplier elapsedClock) {
        this.elapsedClock = elapsedClock;
    }

    ToolCall select(CoreRequest request) {
        if (request == null || request.tools == null || request.tools.isEmpty()) return null;
        String question = normalize(request.question);
        for (ToolDescriptor descriptor : request.tools) {
            if (descriptor == null || !descriptor.isValid()) continue;
            for (String term : descriptor.matchTerms) {
                if (!term.isEmpty() && question.contains(normalize(term))) {
                    long now = elapsedClock.getAsLong();
                    long deadline = Math.min(request.deadlineElapsedMs,
                            now + descriptor.timeoutMs);
                    JSONObject arguments = new JSONObject();
                    try {
                        arguments.put("question", request.question);
                        arguments.put("screen", request.context.screen);
                        arguments.put("contextJson", request.context.payloadJson);
                    } catch (Exception ignored) {
                    }
                    String callId = UUID.randomUUID().toString();
                    return new ToolCall(request.requestId, callId, descriptor.toolId,
                            descriptor.schemaVersion, descriptor.mode, arguments.toString(),
                            request.requestId + ":" + descriptor.toolId, deadline,
                            descriptor.requiresConfirmation);
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(clean, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
}
