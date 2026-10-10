package it.evocore.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** EMVP-1 record vault: per-scope DEKs wrapped by non-exportable Android Keystore KEKs. */
final class MemoryVault {
    static final String OPERATIONAL = "operational";
    static final String SENSITIVE = "sensitive";
    private static final String PREFS = "emvp1_scope_keys";
    private static final String ALIAS_OPERATIONAL = "evocore.emvp1.kek.operational";
    private static final String ALIAS_SENSITIVE = "evocore.emvp1.kek.sensitive";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;

    static final class EncryptedRecord {
        final byte[] iv;
        final byte[] ciphertext;

        EncryptedRecord(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    private static final class CachedDek {
        final byte[] bytes;
        final long expiresAt;

        CachedDek(byte[] bytes, long expiresAt) {
            this.bytes = bytes;
            this.expiresAt = expiresAt;
        }
    }

    private final Context context;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, CachedDek> cache = new HashMap<>();

    MemoryVault(Context context) {
        this.context = context.getApplicationContext();
    }

    EncryptedRecord encrypt(String scope, String recordId, String recordType,
                            String sensitivity, byte[] plaintext) throws Exception {
        byte[] dek = getScopeDek(scope, sensitivity);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(recordAad(scope, recordId, recordType, sensitivity));
            return new EncryptedRecord(iv, cipher.doFinal(plaintext));
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    byte[] decrypt(String scope, String recordId, String recordType, String sensitivity,
                   byte[] iv, byte[] ciphertext) throws Exception {
        if (iv == null || iv.length != IV_BYTES || ciphertext == null || ciphertext.length < 16) {
            throw new IllegalArgumentException("Record cifrato non valido");
        }
        byte[] dek = getScopeDek(scope, sensitivity);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(recordAad(scope, recordId, recordType, sensitivity));
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    synchronized void lock() {
        for (CachedDek cached : cache.values()) Arrays.fill(cached.bytes, (byte) 0);
        cache.clear();
    }

    synchronized void destroyScope(String scope, String sensitivity) {
        String id = scopeKeyId(scope, sensitivity);
        CachedDek cached = cache.remove(id);
        if (cached != null) Arrays.fill(cached.bytes, (byte) 0);
        prefs().edit().remove(id + ".iv").remove(id + ".wrapped").commit();
    }

    String securityStatus() {
        try {
            SecretKey key = getOrCreateKek(OPERATIONAL);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
            KeySpec spec = factory.getKeySpec(key, KeyInfo.class);
            if (!(spec instanceof KeyInfo)) return "Android Keystore";
            KeyInfo info = (KeyInfo) spec;
            if (Build.VERSION.SDK_INT >= 31) {
                int level = info.getSecurityLevel();
                if (level == KeyProperties.SECURITY_LEVEL_STRONGBOX) return "StrongBox hardware";
                if (level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT) return "TEE hardware";
                return "Keystore software (fallback esplicito)";
            }
            return info.isInsideSecureHardware() ? "hardware-backed" : "Keystore software (fallback esplicito)";
        } catch (Exception error) {
            return "caveau non disponibile: " + safe(error);
        }
    }

    boolean isStrongBoxRequested() {
        return Build.VERSION.SDK_INT >= 28 &&
                context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    }

    static String opaqueScopeId(String scope) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private synchronized byte[] getScopeDek(String scope, String sensitivity) throws Exception {
        String id = scopeKeyId(scope, sensitivity);
        CachedDek cached = cache.get(id);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) return cached.bytes.clone();
        if (cached != null) Arrays.fill(cached.bytes, (byte) 0);

        SharedPreferences prefs = prefs();
        String ivValue = prefs.getString(id + ".iv", "");
        String wrappedValue = prefs.getString(id + ".wrapped", "");
        byte[] dek;
        if (ivValue.isEmpty() || wrappedValue.isEmpty()) {
            dek = new byte[32];
            random.nextBytes(dek);
            Wrapped wrapped = wrapDek(id, sensitivity, dek);
            boolean saved = prefs.edit()
                    .putString(id + ".iv", Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                    .putString(id + ".wrapped", Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
                    .commit();
            if (!saved) {
                Arrays.fill(dek, (byte) 0);
                throw new IllegalStateException("Impossibile salvare la chiave di scope");
            }
        } else {
            dek = unwrapDek(id, sensitivity,
                    Base64.decode(ivValue, Base64.NO_WRAP),
                    Base64.decode(wrappedValue, Base64.NO_WRAP));
        }
        cache.put(id, new CachedDek(dek.clone(), now + CACHE_TTL_MS));
        return dek;
    }

    private Wrapped wrapDek(String scopeKeyId, String sensitivity, byte[] dek) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Android Keystore must generate the IV when randomized encryption is required.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKek(sensitivity));
        cipher.updateAAD(keyAad(scopeKeyId, sensitivity));
        byte[] ciphertext = cipher.doFinal(dek);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new IllegalStateException("IV Keystore non valido");
        return new Wrapped(iv, ciphertext);
    }

    private byte[] unwrapDek(String scopeKeyId, String sensitivity, byte[] iv, byte[] wrapped) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKek(sensitivity), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(keyAad(scopeKeyId, sensitivity));
        return cipher.doFinal(wrapped);
    }

    private synchronized SecretKey getOrCreateKek(String sensitivity) throws Exception {
        String alias = SENSITIVE.equals(sensitivity) ? ALIAS_SENSITIVE : ALIAS_OPERATIONAL;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);

        boolean requestStrongBox = isStrongBoxRequested();
        try {
            return generateKek(alias, requestStrongBox);
        } catch (StrongBoxUnavailableException error) {
            return generateKek(alias, false);
        } catch (Exception error) {
            if (!requestStrongBox) throw error;
            return generateKek(alias, false);
        }
    }

    private SecretKey generateKek(String alias, boolean strongBox) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true);
        if (Build.VERSION.SDK_INT >= 28 && strongBox) builder.setIsStrongBoxBacked(true);
        generator.init(builder.build());
        return generator.generateKey();
    }

    private byte[] recordAad(String scope, String recordId, String type, String sensitivity) {
        String aad = "EMVP1|" + recordId + "|" + opaqueScopeId(scope) + "|" + type + "|1|" + sensitivity;
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] keyAad(String id, String sensitivity) {
        return ("EMVP1|scope-key|" + id + "|" + sensitivity).getBytes(StandardCharsets.UTF_8);
    }

    private String scopeKeyId(String scope, String sensitivity) {
        return opaqueScopeId(scope) + "." + (SENSITIVE.equals(sensitivity) ? "s" : "o");
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class Wrapped {
        final byte[] iv;
        final byte[] ciphertext;

        Wrapped(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }
}
