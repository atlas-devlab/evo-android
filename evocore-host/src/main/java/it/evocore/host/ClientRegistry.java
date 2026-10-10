package it.evocore.host;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ClientRegistry extends SQLiteOpenHelper {
    static final String STATE_PENDING = "pending";
    static final String STATE_APPROVED = "approved";
    static final String STATE_REVOKED = "revoked";

    static final class ClientRecord {
        final String packageName;
        final int uid;
        final String label;
        final String clientId;
        final String namespace;
        final String certificateSha256;
        final Set<String> requestedCapabilities;
        final Set<String> grantedCapabilities;
        final String state;
        final long grantEpoch;
        final long firstSeen;
        final long lastSeen;

        ClientRecord(String packageName, int uid, String label, String clientId, String namespace,
                     String certificateSha256, Set<String> requestedCapabilities,
                     Set<String> grantedCapabilities, String state, long grantEpoch,
                     long firstSeen, long lastSeen) {
            this.packageName = packageName;
            this.uid = uid;
            this.label = label;
            this.clientId = clientId;
            this.namespace = namespace;
            this.certificateSha256 = certificateSha256;
            this.requestedCapabilities = Collections.unmodifiableSet(new LinkedHashSet<>(requestedCapabilities));
            this.grantedCapabilities = Collections.unmodifiableSet(new LinkedHashSet<>(grantedCapabilities));
            this.state = state;
            this.grantEpoch = grantEpoch;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }

        String scope() {
            return packageName + ":" + namespace;
        }

        boolean approved() {
            return STATE_APPROVED.equals(state);
        }
    }

    private static final String DB_NAME = "evocore-clients.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "clients";

    ClientRegistry(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "package_name TEXT PRIMARY KEY," +
                "uid INTEGER NOT NULL," +
                "label TEXT NOT NULL," +
                "client_id TEXT NOT NULL," +
                "namespace TEXT NOT NULL," +
                "certificate_sha256 TEXT NOT NULL," +
                "requested_capabilities TEXT NOT NULL," +
                "granted_capabilities TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "grant_epoch INTEGER NOT NULL DEFAULT 1," +
                "first_seen INTEGER NOT NULL," +
                "last_seen INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE +
                    " ADD COLUMN grant_epoch INTEGER NOT NULL DEFAULT 1");
        }
    }

    synchronized ClientRecord observe(CallerVerifier.Result caller) {
        long now = System.currentTimeMillis();
        ClientRecord current = find(caller.packageName);
        ContentValues values = new ContentValues();
        values.put("uid", caller.uid);
        values.put("label", caller.label);
        values.put("client_id", caller.clientId);
        values.put("namespace", caller.namespace);
        values.put("certificate_sha256", caller.certificateSha256);
        values.put("requested_capabilities", CapabilityPolicy.serialize(caller.capabilities));
        values.put("last_seen", now);

        if (current == null) {
            values.put("package_name", caller.packageName);
            values.put("granted_capabilities", "");
            values.put("state", STATE_PENDING);
            values.put("grant_epoch", 1L);
            values.put("first_seen", now);
            getWritableDatabase().insertOrThrow(TABLE, null, values);
        } else {
            boolean identityChanged = !current.certificateSha256.equals(caller.certificateSha256)
                    || !current.clientId.equals(caller.clientId)
                    || !current.namespace.equals(caller.namespace);
            if (identityChanged) {
                values.put("granted_capabilities", "");
                values.put("state", STATE_PENDING);
                values.put("grant_epoch", current.grantEpoch + 1L);
            } else if (current.approved()
                    && !current.requestedCapabilities.containsAll(caller.capabilities)) {
                // New permissions always return to explicit user approval.
                values.put("state", STATE_PENDING);
                values.put("granted_capabilities", CapabilityPolicy.serialize(current.grantedCapabilities));
                values.put("grant_epoch", current.grantEpoch + 1L);
            }
            getWritableDatabase().update(TABLE, values, "package_name=?",
                    new String[]{caller.packageName});
        }
        return find(caller.packageName);
    }

    synchronized ClientRecord approve(String packageName) {
        ClientRecord record = find(packageName);
        if (record == null) return null;
        ContentValues values = new ContentValues();
        values.put("state", STATE_APPROVED);
        values.put("granted_capabilities", CapabilityPolicy.serialize(record.requestedCapabilities));
        values.put("grant_epoch", record.grantEpoch + 1L);
        getWritableDatabase().update(TABLE, values, "package_name=?", new String[]{packageName});
        return find(packageName);
    }

    synchronized ClientRecord revoke(String packageName) {
        ClientRecord record = find(packageName);
        if (record == null) return null;
        ContentValues values = new ContentValues();
        values.put("state", STATE_REVOKED);
        values.put("granted_capabilities", "");
        values.put("grant_epoch", record.grantEpoch + 1L);
        getWritableDatabase().update(TABLE, values, "package_name=?", new String[]{packageName});
        return find(packageName);
    }

    synchronized ClientRecord find(String packageName) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "package_name=?",
                new String[]{packageName}, null, null, null, "1")) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    synchronized List<ClientRecord> list() {
        List<ClientRecord> rows = new ArrayList<>();
        String order = "CASE state WHEN 'pending' THEN 0 WHEN 'approved' THEN 1 ELSE 2 END, label COLLATE NOCASE";
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, null, null,
                null, null, order)) {
            while (cursor.moveToNext()) rows.add(fromCursor(cursor));
        }
        return rows;
    }

    synchronized void deleteAllForTests() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    private static ClientRecord fromCursor(Cursor cursor) {
        return new ClientRecord(
                text(cursor, "package_name"), integer(cursor, "uid"), text(cursor, "label"),
                text(cursor, "client_id"), text(cursor, "namespace"),
                text(cursor, "certificate_sha256"),
                CapabilityPolicy.parse(text(cursor, "requested_capabilities")),
                CapabilityPolicy.parse(text(cursor, "granted_capabilities")),
                text(cursor, "state"), number(cursor, "grant_epoch"),
                number(cursor, "first_seen"), number(cursor, "last_seen"));
    }

    private static String text(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }

    private static int integer(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private static long number(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }
}
