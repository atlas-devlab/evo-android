package it.evocore.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ContextEnvelopeTest {
    @Test
    public void acceptsFreshBoundedContext() {
        long now = System.currentTimeMillis();
        ContextEnvelope envelope = new ContextEnvelope("sample.screen.v1", "home", now,
                now + 60_000L, "personal", "{\"count\":2}");
        assertTrue(envelope.isValidAt(now));
    }

    @Test
    public void rejectsExpiredOrLongLivedContext() {
        long now = System.currentTimeMillis();
        assertFalse(new ContextEnvelope("sample.screen.v1", "home", now - 2_000L,
                now - 1_000L, "personal", "{}").isValidAt(now));
        assertFalse(new ContextEnvelope("sample.screen.v1", "home", now,
                now + ContextEnvelope.MAX_TTL_MS + 1L, "personal", "{}").isValidAt(now));
    }

    @Test
    public void rejectsOversizedPayload() {
        long now = System.currentTimeMillis();
        String payload = "x".repeat(ContextEnvelope.MAX_PAYLOAD_BYTES + 1);
        assertFalse(new ContextEnvelope("sample.screen.v1", "home", now,
                now + 60_000L, "personal", payload).isValidAt(now));
    }
}
