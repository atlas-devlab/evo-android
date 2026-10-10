package it.evocore.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Collections;

public final class ToolRegistryTest {
    @Test
    public void executesOnlyMatchingDeclaredReadTool() {
        ToolDescriptor descriptor = descriptor(ToolDescriptor.MODE_READ);
        ToolRegistry registry = new ToolRegistry().register(descriptor,
                call -> ToolResult.success(call, "{\"count\":2}"));
        ToolCall call = call(ToolDescriptor.MODE_READ);

        ToolResult result = registry.execute(call);

        assertEquals(ToolResult.STATUS_OK, result.status);
        assertFalse(result.sideEffectCommitted);
    }

    @Test
    public void rejectsForgedToolIdentity() {
        ToolRegistry registry = new ToolRegistry().register(descriptor(ToolDescriptor.MODE_READ),
                call -> ToolResult.success(call, "{}"));
        ToolCall forged = new ToolCall("request", "call", "sample.other", 1,
                ToolDescriptor.MODE_READ, "{}", "key", 10_000L, false);

        assertEquals(ToolResult.STATUS_DENIED, registry.execute(forged).status);
    }

    @Test(expected = IllegalArgumentException.class)
    public void executeModeCannotBeRegisteredInV3() {
        new ToolRegistry().register(descriptor(ToolDescriptor.MODE_EXECUTE),
                call -> ToolResult.success(call, "{}"));
    }

    @Test
    public void sideEffectClaimIsRejected() {
        ToolRegistry registry = new ToolRegistry().register(descriptor(ToolDescriptor.MODE_READ),
                call -> new ToolResult(call.requestId, call.callId, call.toolId,
                        ToolResult.STATUS_OK, "{}", 0, "", true));

        assertEquals(ToolResult.STATUS_FAILED,
                registry.execute(call(ToolDescriptor.MODE_READ)).status);
    }

    private static ToolDescriptor descriptor(int mode) {
        return new ToolDescriptor("sample.count", 1, mode, "Conta", "{}", "{}",
                Collections.singletonList("quante"), false, 5_000L, 4_096);
    }

    private static ToolCall call(int mode) {
        return new ToolCall("request", "call", "sample.count", 1, mode,
                "{}", "key", 10_000L, false);
    }
}
