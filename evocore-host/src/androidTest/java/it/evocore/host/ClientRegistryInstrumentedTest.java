package it.evocore.host;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ClientRegistryInstrumentedTest {
    private ClientRegistry registry;

    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        registry = new ClientRegistry(context);
        registry.deleteAllForTests();
    }

    @After public void tearDown() {
        registry.deleteAllForTests();
        registry.close();
    }

    @Test public void unknownClientRequiresApprovalBeforeCapabilitiesAreGranted() {
        ClientRegistry.ClientRecord pending = registry.observe(caller(
                "com.elia.tasks", "tasks", "personal", "inference.ask,status.read"));
        assertEquals(ClientRegistry.STATE_PENDING, pending.state);
        assertTrue(pending.grantedCapabilities.isEmpty());

        ClientRegistry.ClientRecord approved = registry.approve(pending.packageName);
        assertEquals(ClientRegistry.STATE_APPROVED, approved.state);
        assertEquals(pending.requestedCapabilities, approved.grantedCapabilities);
        assertTrue(approved.grantEpoch > pending.grantEpoch);
    }

    @Test public void equalNamespaceLabelsInTwoPackagesNeverShareAScope() {
        ClientRegistry.ClientRecord first = registry.observe(caller(
                "com.elia.tasks", "tasks", "personal", "status.read"));
        ClientRegistry.ClientRecord second = registry.observe(caller(
                "com.elia.health", "health", "personal", "status.read"));
        assertNotEquals(first.scope(), second.scope());
    }

    @Test public void capabilityExpansionReturnsAnApprovedClientToPending() {
        registry.observe(caller("com.elia.tasks", "tasks", "personal", "status.read"));
        registry.approve("com.elia.tasks");
        ClientRegistry.ClientRecord expanded = registry.observe(caller(
                "com.elia.tasks", "tasks", "personal", "status.read,inference.ask"));
        assertEquals(ClientRegistry.STATE_PENDING, expanded.state);
        assertTrue(expanded.grantEpoch > 1L);
    }

    @Test public void revokedClientStaysRevokedWhenItReconnects() {
        CallerVerifier.Result caller = caller(
                "com.elia.tasks", "tasks", "personal", "status.read");
        registry.observe(caller);
        registry.approve(caller.packageName);
        ClientRegistry.ClientRecord approved = registry.approve(caller.packageName);
        ClientRegistry.ClientRecord revoked = registry.revoke(caller.packageName);
        assertTrue(revoked.grantEpoch > approved.grantEpoch);
        assertEquals(ClientRegistry.STATE_REVOKED, registry.observe(caller).state);
    }

    private static CallerVerifier.Result caller(String packageName, String clientId,
                                                 String namespace, String capabilities) {
        Set<String> requested = CapabilityPolicy.parse(capabilities);
        return new CallerVerifier.Result(true, 10_123, packageName, clientId, clientId,
                namespace, "AA11BB22", requested, "");
    }
}
