package it.evocore.host;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import it.evocore.client.EvoCoreProtocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CapabilityPolicyTest {
    @Test public void parserNormalizesAndDeduplicatesCapabilities() {
        Set<String> parsed = CapabilityPolicy.parse(" inference.ask, MEMORY.READ,inference.ask,?bad ");
        assertEquals(2, parsed.size());
        assertTrue(parsed.contains(EvoCoreProtocol.CAP_INFERENCE_ASK));
        assertTrue(parsed.contains(EvoCoreProtocol.CAP_MEMORY_READ));
    }

    @Test public void intersectionNeverGrantsAnUnrequestedCapability() {
        Set<String> result = CapabilityPolicy.intersect(
                CapabilityPolicy.parse("inference.ask,status.read"),
                CapabilityPolicy.parse("status.read,memory.read"));
        assertEquals(Collections.singleton(EvoCoreProtocol.CAP_STATUS_READ), result);
    }

    @Test public void unsupportedCapabilitiesAreNotInTheHostCatalog() {
        assertFalse(CapabilityPolicy.supported().contains("finance.transfer"));
    }

    @Test public void legacyMemoryCapabilityOnlyMapsToReadAndWrite() {
        Set<String> legacy = Collections.singleton("memory.app_namespace");
        assertTrue(CapabilityPolicy.permits(legacy, EvoCoreProtocol.CAP_MEMORY_READ));
        assertTrue(CapabilityPolicy.permits(legacy, EvoCoreProtocol.CAP_MEMORY_WRITE));
        assertFalse(CapabilityPolicy.permits(legacy, EvoCoreProtocol.CAP_MEMORY_MANAGE));
    }

    @Test public void serializationIsStableForAuditAndStorage() {
        String serialized = CapabilityPolicy.serialize(CapabilityPolicy.normalize(
                Arrays.asList("status.read", "inference.ask")));
        assertEquals("inference.ask,status.read", serialized);
    }
}
