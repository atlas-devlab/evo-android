package it.evocore.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

final class CoreModelManager {
    interface Callback {
        void onProgress(int percent);
        void onDone(boolean ok, String message);
    }

    private static final String PREFS = "evocore_model";
    private static final String ACTIVE = "active_path";
    private final Context context;

    CoreModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    File activeModel() {
        String saved = prefs().getString(ACTIVE, "");
        if (!saved.isEmpty()) {
            File file = new File(saved);
            if (isGguf(file)) return file;
        }
        File[] models = modelDir().listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gguf"));
        if (models == null || models.length == 0) return null;
        Arrays.sort(models, Comparator.comparingLong(File::lastModified).reversed());
        for (File model : models) {
            if (isGguf(model)) {
                prefs().edit().putString(ACTIVE, model.getAbsolutePath()).apply();
                return model;
            }
        }
        return null;
    }

    boolean hasModel() {
        return activeModel() != null;
    }

    String status() {
        File model = activeModel();
        return model == null ? "Nessun modello installato" : model.getName() + " | " + human(model.length());
    }

    void importFrom(Uri uri, Callback callback) {
        new Thread(() -> {
            File partial = new File(modelDir(), "importazione.gguf.part");
            try {
                long expected = -1L;
                try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) expected = cursor.getLong(0);
                }
                long free = new StatFs(modelDir().getAbsolutePath()).getAvailableBytes();
                if (expected > 0 && free < expected + 500_000_000L) {
                    throw new IllegalStateException("Spazio insufficiente per importare il modello");
                }
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("File non leggibile");
                    copy(input, partial, expected, callback);
                }
                if (!isGguf(partial)) throw new IllegalStateException("Il file non e un modello GGUF valido");
                File target = new File(modelDir(), "evocore-model-" + System.currentTimeMillis() + ".gguf");
                if (!partial.renameTo(target)) throw new IllegalStateException("Impossibile completare l'importazione");
                deleteOtherModels(target);
                prefs().edit().putString(ACTIVE, target.getAbsolutePath()).apply();
                callback.onDone(true, "Modello importato nel contenitore privato di EvoCore");
            } catch (Exception error) {
                if (partial.exists()) partial.delete();
                String message = error.getMessage();
                callback.onDone(false, message == null ? error.getClass().getSimpleName() : message);
            }
        }, "EvoCoreModelImport").start();
    }

    boolean deleteActive() {
        prefs().edit().remove(ACTIVE).commit();
        boolean success = true;
        File[] files = modelDir().listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if ((name.endsWith(".gguf") || name.endsWith(".part")) && !file.delete()) success = false;
            }
        }
        return success;
    }

    private void copy(InputStream source, File target, long expected, Callback callback) throws Exception {
        byte[] buffer = new byte[1024 * 1024];
        long done = 0L;
        int last = -1;
        try (BufferedInputStream in = new BufferedInputStream(source);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                done += read;
                int percent = expected > 0 ? (int) Math.min(99L, done * 100L / expected) : -1;
                if (percent < 0 || percent >= last + 3) {
                    last = percent;
                    callback.onProgress(percent);
                }
            }
        }
        callback.onProgress(100);
    }

    private boolean isGguf(File file) {
        if (file == null || !file.isFile() || file.length() < 1024 * 1024) return false;
        byte[] magic = new byte[4];
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read(magic) == 4 && magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deleteOtherModels(File keep) {
        File[] files = modelDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.equals(keep) && file.getName().toLowerCase(Locale.ROOT).endsWith(".gguf")) file.delete();
        }
    }

    private File modelDir() {
        File directory = new File(context.getFilesDir(), "models");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String human(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.ITALY, "%.1f GB", bytes / 1_000_000_000d);
        return String.format(Locale.ITALY, "%.0f MB", bytes / 1_000_000d);
    }
}
