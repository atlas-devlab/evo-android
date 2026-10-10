package it.evocore.lab;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TelemetryCrypto {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "evolab.telemetry.payload.v1";
    private SecretKey cachedKey;

    synchronized String encrypt(String clearText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(
                    (clearText == null ? "{}" : clearText).getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            ByteBuffer buffer = ByteBuffer.allocate(2 + iv.length + encrypted.length);
            buffer.put((byte) 1);
            buffer.put((byte) iv.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
        } catch (Exception error) {
            throw new IllegalStateException("Cifratura telemetria non disponibile", error);
        }
    }

    synchronized String decrypt(String encoded) {
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int version = buffer.get() & 0xff;
            int ivLength = buffer.get() & 0xff;
            if (version != 1 || ivLength < 12 || ivLength > 32 || buffer.remaining() <= ivLength) {
                throw new IllegalArgumentException("Payload cifrato non valido");
            }
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Decifratura telemetria non riuscita", error);
        }
    }

    private SecretKey key() throws Exception {
        if (cachedKey != null) return cachedKey;
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            cachedKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            return cachedKey;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        cachedKey = generator.generateKey();
        return cachedKey;
    }
}
