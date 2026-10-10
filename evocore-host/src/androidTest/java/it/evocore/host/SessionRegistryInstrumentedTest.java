package it.evocore.host;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

import it.evocore.client.EvoCoreProtocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SessionRegistryInstrumentedTest {
    @Test public void nonceCannotBeReplayed() {
        SessionRegistry registry = new SessionRegistry();
        CallerVerifier.Result caller = caller();
        String nonce = "0123456789abcdefghijklmnopqrstuv";
        assertNotNull(registry.open(caller, nonce));
        assertNull(registry.open(caller, nonce));
    }

    @Test public void tokenIsBoundToUidPackageAndNamespace() {
        SessionRegistry registry = new SessionRegistry();
        CallerVerifier.Result caller = caller();
        SessionRegistry.Session session = registry.open(caller, "abcdefghijklmnopqrstuvwxyz012345");
        assertNotNull(session);
        assertNotNull(registry.verify(session.token, caller.uid, caller.packageName, caller.namespace));
        assertNull(registry.verify(session.token, caller.uid + 1, caller.packageName, caller.namespace));
    }

    @Test public void sessionCarriesOnlyGrantedCapabilities() {
        SessionRegistry registry = new SessionRegistry();
        SessionRegistry.Session session = registry.open(caller(), "zyxwvutsrqponmlkjihgfedcba987654");
        assertNotNull(session);
        assertTrue(session.permits(EvoCoreProtocol.CAP_STATUS_READ));
        assertFalse(session.permits(EvoCoreProtocol.CAP_MEMORY_WRITE));
    }

    @Test public void protocolAndGrantEpochAreBoundToSession() {
        SessionRegistry registry = new SessionRegistry();
        CallerVerifier.Result caller = caller();
        SessionRegistry.Session session = registry.open(caller,
                "protocol3grantbinding0123456789", 3, 7L);
        assertNotNull(session);
        assertTrue(session.protocolVersion == 3);
        assertTrue(session.grantEpoch == 7L);
        registry.close(session.token, caller.uid, caller.packageName);
        assertNull(registry.verify(session.token, caller.uid, caller.packageName, caller.namespace));
    }

    private static CallerVerifier.Result caller() {
        Set<String> capabilities = CapabilityPolicy.parse("inference.ask,status.read");
        return new CallerVerifier.Result(true, 10_321, "com.elia.test", "test", "Test",
                "principale", "AA11", capabilities, "");
    }
}
