package it.evocore.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    public interface Handler {
        ToolResult handle(ToolCall call) throws Exception;
    }

    public interface Approval {
        boolean approve(ToolCall call);
    }

    private static final Approval DENY_CONFIRMATION = call -> false;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Approval approval;

    public ToolRegistry() {
        this(DENY_CONFIRMATION);
    }

    public ToolRegistry(Approval approval) {
        this.approval = approval == null ? DENY_CONFIRMATION : approval;
    }

    public synchronized ToolRegistry register(ToolDescriptor descriptor, Handler handler) {
        if (descriptor == null || !descriptor.isValid()) {
            throw new IllegalArgumentException("Descrittore strumento non valido");
        }
        if (handler == null) throw new IllegalArgumentException("Handler strumento assente");
        if (bindings.containsKey(descriptor.toolId)) {
            throw new IllegalArgumentException("Strumento duplicato: " + descriptor.toolId);
        }
        bindings.put(descriptor.toolId, new Binding(descriptor, handler));
        return this;
    }

    public synchronized List<ToolDescriptor> descriptors() {
        ArrayList<ToolDescriptor> output = new ArrayList<>();
        for (Binding binding : bindings.values()) output.add(binding.descriptor);
        return Collections.unmodifiableList(output);
    }

    ToolResult execute(ToolCall call) {
        Binding binding;
        synchronized (this) {
            binding = call == null ? null : bindings.get(call.toolId);
        }
        if (binding == null || binding.descriptor.schemaVersion != call.schemaVersion
                || binding.descriptor.mode != call.mode) {
            return ToolResult.failure(call, ToolResult.STATUS_DENIED, "Strumento non autorizzato dal client");
        }
        if (call.mode == ToolDescriptor.MODE_EXECUTE) {
            return ToolResult.failure(call, ToolResult.STATUS_DENIED,
                    "EvoCore v3 non consente esecuzioni autonome");
        }
        if (call.requiresConfirmation && !approval.approve(call)) {
            return ToolResult.failure(call, ToolResult.STATUS_DENIED, "Proposta non confermata");
        }
        try {
            ToolResult result = binding.handler.handle(call);
            if (result == null || result.sideEffectCommitted
                    || !call.requestId.equals(result.requestId)
                    || !call.callId.equals(result.callId)
                    || !call.toolId.equals(result.toolId)) {
                return ToolResult.failure(call, ToolResult.STATUS_FAILED,
                        "Risultato strumento non verificabile");
            }
            return result;
        } catch (Exception error) {
            String message = error.getMessage();
            return ToolResult.failure(call, ToolResult.STATUS_FAILED,
                    message == null || message.isEmpty() ? "Strumento locale non riuscito" : message);
        }
    }

    private static final class Binding {
        final ToolDescriptor descriptor;
        final Handler handler;

        Binding(ToolDescriptor descriptor, Handler handler) {
            this.descriptor = descriptor;
            this.handler = handler;
        }
    }
}
