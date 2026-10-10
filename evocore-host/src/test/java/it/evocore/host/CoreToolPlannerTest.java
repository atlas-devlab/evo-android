package it.evocore.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.Collections;

import it.evocore.client.ContextEnvelope;
import it.evocore.client.CoreRequest;
import it.evocore.client.ToolCall;
import it.evocore.client.ToolDescriptor;

public final class CoreToolPlannerTest {
    @Test
    public void selectsDeclaredReadToolByTerm() {
        ToolDescriptor descriptor = new ToolDescriptor("sample.count_items", 1,
                ToolDescriptor.MODE_READ, "Conta gli elementi", "{}", "{}",
                Collections.singletonList("quanti elementi"), false, 5_000L, 4_096);
        CoreRequest request = new CoreRequest(3, "request-12345678", "sample",
                11_000L, 128,
                ContextEnvelope.screen("sample.screen.v1", "home", "{\"items\":[1,2]}"),
                "Quanti elementi sono visibili?", "assistant",
                Collections.singletonList(descriptor));

        ToolCall call = new CoreToolPlanner(() -> 1_000L).select(request);

        assertNotNull(call);
        assertEquals("sample.count_items", call.toolId);
        assertEquals(ToolDescriptor.MODE_READ, call.mode);
    }

    @Test
    public void doesNotInventUndeclaredTool() {
        CoreRequest request = new CoreRequest(3, "request-12345678", "sample",
                11_000L, 128,
                ContextEnvelope.screen("sample.screen.v1", "home", "{}"),
                "Cancella tutto", "assistant", Collections.emptyList());

        assertNull(new CoreToolPlanner(() -> 1_000L).select(request));
    }
}
