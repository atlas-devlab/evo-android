package it.evocore.host;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MemoryVaultInstrumentedTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test public void encryptDecryptRoundTrip() throws Exception {
        MemoryVault vault = new MemoryVault(context);
        String scope = uniqueScope();
        byte[] clear = "memoria personale".getBytes(StandardCharsets.UTF_8);
        MemoryVault.EncryptedRecord record = vault.encrypt(scope, "r1", "note", MemoryVault.OPERATIONAL, clear);
        assertArrayEquals(clear, vault.decrypt(scope, "r1", "note", MemoryVault.OPERATIONAL, record.iv, record.ciphertext));
    }

    @Test public void ciphertextDoesNotContainPlaintext() throws Exception {
        MemoryVault vault = new MemoryVault(context);
        byte[] clear = "segreto-riconoscibile".getBytes(StandardCharsets.UTF_8);
        MemoryVault.EncryptedRecord record = vault.encrypt(uniqueScope(), "r2", "note", MemoryVault.SENSITIVE, clear);
        assertFalse(new String(record.ciphertext, StandardCharsets.ISO_8859_1).contains("segreto-riconoscibile"));
    }

    @Test public void modifiedCiphertextFailsAuthentication() throws Exception {
        MemoryVault vault = new MemoryVault(context);
        String scope = uniqueScope();
        MemoryVault.EncryptedRecord record = vault.encrypt(scope, "r3", "note", MemoryVault.OPERATIONAL,
                "dato".getBytes(StandardCharsets.UTF_8));
        record.ciphertext[record.ciphertext.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class,
                () -> vault.decrypt(scope, "r3", "note", MemoryVault.OPERATIONAL, record.iv, record.ciphertext));
    }

    @Test public void wrongAssociatedDataFailsAuthentication() throws Exception {
        MemoryVault vault = new MemoryVault(context);
        String scope = uniqueScope();
        MemoryVault.EncryptedRecord record = vault.encrypt(scope, "r4", "note", MemoryVault.OPERATIONAL,
                "dato".getBytes(StandardCharsets.UTF_8));
        assertThrows(GeneralSecurityException.class,
                () -> vault.decrypt(scope, "wrong-id", "note", MemoryVault.OPERATIONAL, record.iv, record.ciphertext));
    }

    @Test public void scopesUseDifferentCiphertextAndKeys() throws Exception {
        MemoryVault vault = new MemoryVault(context);
        byte[] clear = "stesso dato".getBytes(StandardCharsets.UTF_8);
        MemoryVault.EncryptedRecord one = vault.encrypt(uniqueScope(), "r5", "note", MemoryVault.OPERATIONAL, clear);
        MemoryVault.EncryptedRecord two = vault.encrypt(uniqueScope(), "r5", "note", MemoryVault.OPERATIONAL, clear);
        assertNotEquals(android.util.Base64.encodeToString(one.ciphertext, 2),
                android.util.Base64.encodeToString(two.ciphertext, 2));
        assertTrue(vault.securityStatus().length() > 3);
    }

    private static String uniqueScope() {
        return "test:" + UUID.randomUUID();
    }
}
