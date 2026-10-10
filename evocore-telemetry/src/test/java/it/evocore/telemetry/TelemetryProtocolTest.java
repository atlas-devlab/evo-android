package it.evocore.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TelemetryProtocolTest {
    @Test
    public void typeValidationRejectsContentLikeValues() {
        assertTrue(TelemetryProtocol.isValidType("ai.request.final"));
        assertFalse(TelemetryProtocol.isValidType("AI request"));
        assertFalse(TelemetryProtocol.isValidType("../prompt"));
        assertFalse(TelemetryProtocol.isValidType(""));
    }

    @Test
    public void payloadIsBounded() {
        String oversized = "x".repeat(TelemetryProtocol.MAX_PAYLOAD_CHARS + 10);
        assertEquals(TelemetryProtocol.MAX_PAYLOAD_CHARS,
                TelemetryProtocol.boundedPayload(oversized).length());
    }
}
