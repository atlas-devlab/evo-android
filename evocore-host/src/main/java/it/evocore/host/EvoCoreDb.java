package it.evocore.host;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class EvoCoreDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "evocore-vault.db";
    private static final int DB_VERSION = 1;
    private static final String MAX_SEARCHABLE_RECORDS = "500";
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "anche", "avere", "cosa", "come", "della", "delle", "degli", "dell",
            "sono", "questo", "questa", "quello", "quella", "posso", "puoi", "usare",
            "vorrei", "devo", "mio", "mia", "miei", "mie", "solo", "tutto", "tutti",
            "nella", "nelle", "alla", "alle", "con", "per", "che", "una", "uno", "gli"));

    static final class MemoryRecord {
        long id;
        String uuid;
        String scope;
        String text;
        String topic;
        String kind;
        String state;
        String source;
        String sensitivity;
        String sourceRef;
        long createdAt;
        long updatedAt;
        long expiresAt;
        long supersedes;

        boolean isConfirmed() {
            return MemoryPolicyGate.USER_CONFIRMED.equals(state) || MemoryPolicyGate.APP_VERIFIED.equals(state);
        }
    }

    private final MemoryVault vault;

    EvoCoreDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        vault = new MemoryVault(context);
        getWritableDatabase();
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        try (Cursor cursor = db.rawQuery("PRAGMA secure_delete=ON", null)) {
            cursor.moveToFirst();
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "record_uuid TEXT NOT NULL UNIQUE," +
                "opaque_scope TEXT NOT NULL," +
                "record_type TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "sensitivity TEXT NOT NULL," +
                "iv BLOB NOT NULL," +
                "ciphertext BLOB NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 1," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "expires_at INTEGER NOT NULL DEFAULT 0," +
                "supersedes INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_memories_scope ON memories(opaque_scope, active, updated_at DESC)");
        db.execSQL("CREATE TABLE audit_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "opaque_scope TEXT NOT NULL," +
                "event_type TEXT NOT NULL," +
                "record_id INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    synchronized MemoryRecord add(String scope, String text, String topic, String kind,
                                  String state, String source, String sensitivity,
                                  String sourceRef, long expiresAt, long supersedes) throws Exception {
        String uuid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        JSONObject payload = new JSONObject();
        payload.put("text", clean(text, 2_000));
        payload.put("topic", clean(topic, 120));
        payload.put("kind", clean(kind, 80));
        payload.put("source", clean(source, 80));
        payload.put("sourceRef", clean(sourceRef, 160));
        MemoryVault.EncryptedRecord encrypted = vault.encrypt(scope, uuid, kind, sensitivity,
                payload.toString().getBytes(StandardCharsets.UTF_8));

        ContentValues values = new ContentValues();
        values.put("record_uuid", uuid);
        values.put("opaque_scope", MemoryVault.opaqueScopeId(scope));
        values.put("record_type", kind);
        values.put("state", state);
        values.put("sensitivity", sensitivity);
        values.put("iv", encrypted.iv);
        values.put("ciphertext", encrypted.ciphertext);
        values.put("active", 1);
        values.put("created_at", now);
        values.put("updated_at", now);
        values.put("expires_at", expiresAt);
        values.put("supersedes", supersedes);
        SQLiteDatabase db = getWritableDatabase();
        long id = db.insertOrThrow("memories", null, values);
        audit(db, scope, "CREATE", id);
        return find(scope, id);
    }

    synchronized List<MemoryRecord> list(String scope, boolean includeCandidates) {
        expire(scope);
        List<MemoryRecord> out = new ArrayList<>();
        String selection = "opaque_scope=? AND active=1" +
                (includeCandidates ? "" : " AND state IN ('USER_CONFIRMED','APP_VERIFIED')");
        try (Cursor cursor = getReadableDatabase().query("memories", null, selection,
                new String[]{MemoryVault.opaqueScopeId(scope)}, null, null, "updated_at DESC",
                MAX_SEARCHABLE_RECORDS)) {
            while (cursor.moveToNext()) {
                MemoryRecord record = decode(scope, cursor);
                if (record != null) out.add(record);
            }
        }
        return out;
    }

    synchronized List<MemoryRecord> listAllKnown(Iterable<String> clientScopes) {
        List<MemoryRecord> out = new ArrayList<>();
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add("it.evocore.host:personal");
        if (clientScopes != null) {
            for (String scope : clientScopes) if (scope != null && !scope.trim().isEmpty()) scopes.add(scope);
        }
        for (String scope : scopes) out.addAll(list(scope, true));
        out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    synchronized MemoryRecord confirm(String scope, long id) throws Exception {
        MemoryRecord current = find(scope, id);
        if (current == null) return null;
        return rewriteState(scope, current, MemoryPolicyGate.USER_CONFIRMED);
    }

    synchronized MemoryRecord correct(String scope, long id, String replacement) throws Exception {
        MemoryRecord current = find(scope, id);
        if (current == null) return null;
        MemoryRecord newer = add(scope, replacement, current.topic, current.kind,
                MemoryPolicyGate.USER_CONFIRMED, "user", current.sensitivity,
                "correction:" + current.uuid, current.expiresAt, current.id);
        ContentValues values = new ContentValues();
        values.put("active", 0);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("memories", values, "id=?", new String[]{Long.toString(id)});
        audit(getWritableDatabase(), scope, "CORRECT", id);
        return newer;
    }

    synchronized boolean forget(String scope, long id) {
        SQLiteDatabase db = getWritableDatabase();
        int count = 0;
        long current = id;
        while (current > 0L) {
            long previous = 0L;
            try (Cursor cursor = db.query("memories", new String[]{"supersedes"},
                    "id=? AND opaque_scope=?", new String[]{Long.toString(current), MemoryVault.opaqueScopeId(scope)},
                    null, null, null, "1")) {
                if (cursor.moveToFirst()) previous = cursor.getLong(0);
            }
            count += db.delete("memories", "id=? AND opaque_scope=?",
                    new String[]{Long.toString(current), MemoryVault.opaqueScopeId(scope)});
            current = previous;
        }
        if (count > 0) audit(db, scope, "FORGET", id);
        if (count > 0 && activeCount(scope) == 0) {
            vault.destroyScope(scope, MemoryVault.OPERATIONAL);
            vault.destroyScope(scope, MemoryVault.SENSITIVE);
        }
        return count > 0;
    }

    synchronized String contextFor(String scope, int limit) {
        return contextFor(scope, "", limit);
    }

    synchronized String contextFor(String scope, String query, int limit) {
        StringBuilder out = new StringBuilder();
        for (MemoryRecord record : selectRelevant(list(scope, false), query, limit)) {
            out.append("- [").append(record.state)
                    .append(" | ").append(record.kind)
                    .append(" | ").append(record.topic).append("] ")
                    .append(record.text.replace('\n', ' ')).append('\n');
        }
        return out.toString();
    }

    synchronized String contextForAppAndPersonal(String appScope, int appLimit, int personalLimit) {
        return contextForAppAndPersonal(appScope, "", appLimit, personalLimit);
    }

    synchronized String contextForAppAndPersonal(String appScope, String query,
                                                 int appLimit, int personalLimit) {
        StringBuilder output = new StringBuilder();
        String personal = contextFor("it.evocore.host:personal", query, personalLimit);
        if (!personal.isEmpty()) {
            output.append("MEMORIA PERSONALE CONFERMATA:\n").append(personal);
        }
        String app = contextFor(appScope, query, appLimit);
        if (!app.isEmpty()) {
            output.append("MEMORIA CONFERMATA DI QUESTA APP:\n").append(app);
        }
        return output.toString();
    }

    static List<MemoryRecord> selectRelevant(List<MemoryRecord> records, String query, int limit) {
        int safeLimit = Math.max(1, limit);
        if (records == null || records.isEmpty()) return new ArrayList<>();
        String focused = normalize(QuestionFocus.current(query));
        if (focused.isEmpty()) {
            return new ArrayList<>(records.subList(0, Math.min(safeLimit, records.size())));
        }

        Set<String> queryTerms = terms(focused);
        boolean profileIntent = containsAny(focused, "chi sono", "cosa ricordi", "cosa sai di me",
                "mi conosci", "quale nome", "come preferisco", "ricordi di me", "memoria su di me");
        boolean identityIntent = containsAny(focused, "chi sono", "quale nome", "nome puoi",
                "come mi chiami", "chiamarmi", "chiamare");
        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryRecord record : records) {
            int score = relevanceScore(record, focused, queryTerms, profileIntent, identityIntent);
            if (score > 0) scored.add(new ScoredMemory(record, score));
        }
        scored.sort(Comparator.comparingInt((ScoredMemory row) -> row.score).reversed()
                .thenComparingLong(row -> -row.record.updatedAt));

        List<MemoryRecord> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(safeLimit, scored.size()); i++) {
            selected.add(scored.get(i).record);
        }
        return selected;
    }

    int countAll() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories WHERE active=1", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    String vaultStatus() {
        return vault.securityStatus();
    }

    void lockVault() {
        vault.lock();
    }

    private MemoryRecord rewriteState(String scope, MemoryRecord current, String nextState) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("text", current.text);
        payload.put("topic", current.topic);
        payload.put("kind", current.kind);
        payload.put("source", current.source);
        payload.put("sourceRef", current.sourceRef);
        MemoryVault.EncryptedRecord encrypted = vault.encrypt(scope, current.uuid, current.kind,
                current.sensitivity, payload.toString().getBytes(StandardCharsets.UTF_8));
        ContentValues values = new ContentValues();
        values.put("state", nextState);
        values.put("iv", encrypted.iv);
        values.put("ciphertext", encrypted.ciphertext);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("memories", values, "id=? AND opaque_scope=?",
                new String[]{Long.toString(current.id), MemoryVault.opaqueScopeId(scope)});
        audit(getWritableDatabase(), scope, "CONFIRM", current.id);
        return find(scope, current.id);
    }

    private MemoryRecord find(String scope, long id) {
        try (Cursor cursor = getReadableDatabase().query("memories", null,
                "id=? AND opaque_scope=? AND active=1",
                new String[]{Long.toString(id), MemoryVault.opaqueScopeId(scope)},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? decode(scope, cursor) : null;
        }
    }

    private MemoryRecord decode(String scope, Cursor cursor) {
        try {
            MemoryRecord out = new MemoryRecord();
            out.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            out.uuid = cursor.getString(cursor.getColumnIndexOrThrow("record_uuid"));
            out.scope = scope;
            out.kind = cursor.getString(cursor.getColumnIndexOrThrow("record_type"));
            out.state = cursor.getString(cursor.getColumnIndexOrThrow("state"));
            out.sensitivity = cursor.getString(cursor.getColumnIndexOrThrow("sensitivity"));
            out.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
            out.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
            out.expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow("expires_at"));
            out.supersedes = cursor.getLong(cursor.getColumnIndexOrThrow("supersedes"));
            byte[] plaintext = vault.decrypt(scope, out.uuid, out.kind, out.sensitivity,
                    cursor.getBlob(cursor.getColumnIndexOrThrow("iv")),
                    cursor.getBlob(cursor.getColumnIndexOrThrow("ciphertext")));
            JSONObject payload = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            out.text = payload.optString("text", "");
            out.topic = payload.optString("topic", "");
            out.source = payload.optString("source", "unknown");
            out.sourceRef = payload.optString("sourceRef", "");
            return out;
        } catch (Exception error) {
            return null;
        }
    }

    private void expire(String scope) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        String selection = "opaque_scope=? AND active=1 AND expires_at>0 AND expires_at<=?";
        String[] args = new String[]{MemoryVault.opaqueScopeId(scope), Long.toString(now)};
        List<Long> expired = new ArrayList<>();
        try (Cursor cursor = db.query("memories", new String[]{"id"}, selection, args,
                null, null, null)) {
            while (cursor.moveToNext()) expired.add(cursor.getLong(0));
        }
        if (expired.isEmpty()) return;
        for (long id : expired) audit(db, scope, "EXPIRE", id);
        db.delete("memories", selection, args);
        if (activeCount(scope) == 0) {
            vault.destroyScope(scope, MemoryVault.OPERATIONAL);
            vault.destroyScope(scope, MemoryVault.SENSITIVE);
        }
    }

    private int activeCount(String scope) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM memories WHERE opaque_scope=? AND active=1",
                new String[]{MemoryVault.opaqueScopeId(scope)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private void audit(SQLiteDatabase db, String scope, String event, long recordId) {
        ContentValues values = new ContentValues();
        values.put("opaque_scope", MemoryVault.opaqueScopeId(scope));
        values.put("event_type", event);
        values.put("record_id", recordId);
        values.put("created_at", System.currentTimeMillis());
        db.insert("audit_events", null, values);
    }

    private static String clean(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static int relevanceScore(MemoryRecord record, String focused,
                                      Set<String> queryTerms, boolean profileIntent,
                                      boolean identityIntent) {
        String text = normalize(record.text);
        String metadata = normalize(record.topic + " " + record.kind);
        Set<String> textTerms = terms(text);
        Set<String> metadataTerms = terms(metadata);
        int score = 0;
        for (String term : queryTerms) {
            if (metadataTerms.contains(term)) score += 8;
            if (textTerms.contains(term)) score += 5;
            else if (text.contains(term)) score += 2;
        }
        if (focused.length() >= 8 && text.contains(focused)) score += 20;
        if (profileIntent && isProfileRecord(metadata, text)) score += 12;
        if (identityIntent && containsAny(metadata, "identity", "identita")) score += 36;
        if (identityIntent && containsAny(text, "chiamarmi", "chiamare", "chiamarti")) score += 28;
        if (focused.contains("atlas") && text.contains("atlas")) score += 20;
        return score;
    }

    private static boolean isProfileRecord(String metadata, String text) {
        return containsAny(metadata,
                "identity", "identita", "profile", "profilo", "preference", "preferenza",
                "value", "valori", "continuity", "continuita", "privacy", "profession",
                "learning", "apprendimento", "workflow", "metodo", "project", "progetto",
                "boundary", "confine")
                || containsAny(text, "puoi chiamarmi", "preferisco", "per me conta",
                "il mio obiettivo", "lavoro meglio", "non usare automaticamente");
    }

    private static Set<String> terms(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (String word : normalize(value).split("\\s+")) {
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) out.add(word);
        }
        return out;
    }

    private static String normalize(String value) {
        String lowered = value == null ? "" : value.toLowerCase(Locale.ITALIAN);
        String decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static final class ScoredMemory {
        final MemoryRecord record;
        final int score;

        ScoredMemory(MemoryRecord record, int score) {
            this.record = record;
            this.score = score;
        }
    }
}
