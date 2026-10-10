package com.elia.evoconti;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Legacy compatibility shim. EvoConti never installs or runs models: EvoCore owns that lifecycle.
 * It remains only so upgrades can detect and remove GGUF files created by versions before 0.10.
 */
@Deprecated
final class LocalModelManager {
    static final ModelSpec FAST = new ModelSpec("Gemma rapida", "", 0L);
    static final ModelSpec POWER = new ModelSpec("Gemma potente", "", 0L);

    interface Callback {
        void onProgress(int percent, long downloaded, long total);
        void onDone(boolean ok, String message);
    }

    static final class ModelSpec {
        final String label;
        final String url;
        final long bytes;

        ModelSpec(String label, String url, long bytes) {
            this.label = label;
            this.url = url;
            this.bytes = bytes;
        }
    }

    private final Context context;

    LocalModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    File activeModel() {
        return null;
    }

    boolean hasModel() {
        return false;
    }

    boolean hasLegacyFiles() {
        return legacyModels().length > 0;
    }

    String status() {
        File[] legacy = legacyModels();
        return legacy.length == 0 ? "Gestito da EvoCore" : "Vecchi file EvoConti da rimuovere: " + legacy.length;
    }

    void download(ModelSpec ignored, boolean allowMetered, Callback callback) {
        callback.onDone(false, "Installazione spostata nell'app EvoCore");
    }

    void importFrom(Uri ignored, Callback callback) {
        callback.onDone(false, "Importazione spostata nell'app EvoCore");
    }

    boolean deleteActive() {
        boolean ok = true;
        for (File file : legacyModels()) if (!file.delete()) ok = false;
        context.getSharedPreferences("local_model", Context.MODE_PRIVATE).edit().clear().apply();
        return ok;
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private File[] legacyModels() {
        File directory = new File(context.getFilesDir(), "models");
        File[] files = directory.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".gguf") || lower.endsWith(".part");
        });
        return files == null ? new File[0] : files;
    }
}
