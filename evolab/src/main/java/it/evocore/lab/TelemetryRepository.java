package it.evocore.lab;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class TelemetryRepository {
    static final class SessionRow {
        final String id;
        final String scenario;
        final String target;
        final long startedAt;
        final long endedAt;
        final String status;

        SessionRow(String id, String scenario, String target, long startedAt,
                   long endedAt, String status) {
            this.id = id;
            this.scenario = scenario;
            this.target = target;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.status = status;
        }
    }

    static final class ChartSample {
        final long elapsedNanos;
        final float batteryPct;
        final float thermalHeadroom;
        final float memoryMb;

        ChartSample(long elapsedNanos, float batteryPct, float thermalHeadroom,
                    float memoryMb) {
            this.elapsedNanos = elapsedNanos;
            this.batteryPct = batteryPct;
            this.thermalHeadroom = thermalHeadroom;
            this.memoryMb = memoryMb;
        }
    }

    static final class Summary {
        long eventCount;
        long requestCount;
        long fallbackCount;
        long frameCount;
        long jankyFrames;
        double averageRequestMs;
        double averageFirstResponseMs;
        double peakPssMb;
        double peakCorePssMb;
        double batteryStart = Double.NaN;
        double batteryEnd = Double.NaN;
        int maxThermalStatus;
        double maxThermalHeadroom = Double.NaN;

        String compact() {
            String response = requestCount == 0 ? "n/d"
                    : String.format(Locale.ITALY, "%.0f ms", averageRequestMs);
            String memory = peakPssMb <= 0 ? "n/d"
                    : String.format(Locale.ITALY, "%.0f MB", peakPssMb);
            return eventCount + " eventi | risposta " + response + " | picco " + memory;
        }
    }

    private static final String PREFS = "evolab_session";
    private static final String ACTIVE = "active_id";
    private static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    private final TelemetryDb db;
    private final TelemetryCrypto crypto = new TelemetryCrypto();
    private final SharedPreferences prefs;

    TelemetryRepository(Context context) {
        Context app = context.getApplicationContext();
        db = new TelemetryDb(app);
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prune();
    }

    synchronized String startSession(String scenario, String target) {
        stopSession();
        String id = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("scenario", safeLabel(scenario));
        values.put("target", safeLabel(target));
        values.put("started_at", System.currentTimeMillis());
        values.put("ended_at", 0L);
        values.put("status", "ACTIVE");
        db.getWritableDatabase().insertOrThrow("sessions", null, values);
        prefs.edit().putString(ACTIVE, id).commit();
        return id;
    }

    synchronized void stopSession() {
        String active = activeSessionId();
        if (active.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("ended_at", System.currentTimeMillis());
        values.put("status", "COMPLETE");
        db.getWritableDatabase().update("sessions", values, "id=?", new String[]{active});
        prefs.edit().remove(ACTIVE).commit();
    }

    String activeSessionId() {
        return prefs.getString(ACTIVE, "");
    }

    SessionRow activeSession() {
        String active = activeSessionId();
        if (active.isEmpty()) return null;
        try (Cursor cursor = db.getReadableDatabase().query("sessions",
                new String[]{"id", "scenario", "target", "started_at", "ended_at", "status"},
                "id=? AND status=?", new String[]{active, "ACTIVE"},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            return new SessionRow(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3), cursor.getLong(4),
                    cursor.getString(5));
        }
    }

    synchronized boolean record(String source, String type, long elapsedNanos, String payloadJson) {
        String session = activeSessionId();
        if (session.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("session_id", session);
        values.put("source", safeSource(source));
        values.put("type", safeType(type));
        values.put("elapsed_ns", elapsedNanos);
        values.put("wall_ms", System.currentTimeMillis());
        values.put("payload_enc", crypto.encrypt(validJson(payloadJson)));
        return db.getWritableDatabase().insert("events", null, values) >= 0;
    }

    List<SessionRow> recentSessions(int limit) {
        List<SessionRow> rows = new ArrayList<>();
        try (Cursor cursor = db.getReadableDatabase().query("sessions",
                new String[]{"id", "scenario", "target", "started_at", "ended_at", "status"},
                null, null, null, null, "started_at DESC", Integer.toString(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                rows.add(new SessionRow(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getLong(3), cursor.getLong(4),
                        cursor.getString(5)));
            }
        }
        return rows;
    }

    JSONObject latestPayload(String type) {
        String session = activeSessionId();
        String where = "type=?";
        List<String> args = new ArrayList<>();
        args.add(type);
        if (!session.isEmpty()) {
            where += " AND session_id=?";
            args.add(session);
        }
        try (Cursor cursor = db.getReadableDatabase().query("events",
                new String[]{"payload_enc"}, where, args.toArray(new String[0]),
                null, null, "id DESC", "1")) {
            if (cursor.moveToFirst()) return new JSONObject(crypto.decrypt(cursor.getString(0)));
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    List<ChartSample> chartSamples(String requestedSession, int limit) {
        String session = requestedSession == null || requestedSession.isEmpty()
                ? activeSessionId() : requestedSession;
        if (session.isEmpty()) {
            List<SessionRow> recent = recentSessions(1);
            if (recent.isEmpty()) return Collections.emptyList();
            session = recent.get(0).id;
        }
        List<ChartSample> reversed = new ArrayList<>();
        String targetSource = targetSource(session);
        try (Cursor cursor = db.getReadableDatabase().query("events",
                new String[]{"elapsed_ns", "source", "type", "payload_enc"},
                "session_id=? AND type IN (?,?)",
                new String[]{session, "device.sample", "process.sample"}, null, null,
                "id DESC", Integer.toString(Math.max(10, limit)))) {
            while (cursor.moveToNext()) {
                String source = cursor.getString(1);
                String type = cursor.getString(2);
                if ("process.sample".equals(type)
                        && (!targetSource.isEmpty() && !targetSource.equals(source))) continue;
                JSONObject payload = new JSONObject(crypto.decrypt(cursor.getString(3)));
                float battery = Float.NaN;
                float thermal = Float.NaN;
                float memory = Float.NaN;
                if ("device.sample".equals(type)) {
                    if (payload.has("batteryPct")) battery = (float) payload.optDouble("batteryPct");
                    if (payload.has("thermalHeadroom")) {
                        thermal = (float) payload.optDouble("thermalHeadroom");
                    }
                } else if (payload.has("pssKb")) {
                    memory = (float) (payload.optDouble("pssKb") / 1024d);
                }
                reversed.add(new ChartSample(cursor.getLong(0), battery, thermal, memory));
            }
        } catch (Exception ignored) {
        }
        Collections.reverse(reversed);
        return reversed;
    }

    Summary summarize(String requestedSession) {
        Summary summary = new Summary();
        String session = requestedSession;
        if (session == null || session.isEmpty()) {
            session = activeSessionId();
            if (session.isEmpty()) {
                List<SessionRow> recent = recentSessions(1);
                if (recent.isEmpty()) return summary;
                session = recent.get(0).id;
            }
        }
        double requestTotal = 0;
        double firstTotal = 0;
        double firstBattery = Double.NaN;
        String targetSource = targetSource(session);
        try (Cursor cursor = db.getReadableDatabase().query("events",
                new String[]{"source", "type", "payload_enc"}, "session_id=?",
                new String[]{session}, null, null, "id ASC")) {
            while (cursor.moveToNext()) {
                summary.eventCount++;
                String source = cursor.getString(0);
                String type = cursor.getString(1);
                JSONObject payload = new JSONObject(crypto.decrypt(cursor.getString(2)));
                if ("ai.request.final".equals(type)) {
                    summary.requestCount++;
                    requestTotal += payload.optDouble("totalMs", 0);
                    firstTotal += payload.optDouble("firstResponseMs", 0);
                    if (payload.optBoolean("fallback", false)) summary.fallbackCount++;
                } else if ("process.sample".equals(type)
                        && !"it.evocore.lab".equals(source)) {
                    double pssMb = payload.optDouble("pssKb", 0) / 1024d;
                    if (targetSource.isEmpty() || targetSource.equals(source)) {
                        summary.peakPssMb = Math.max(summary.peakPssMb, pssMb);
                    }
                    if ("it.evocore.host".equals(source)) {
                        summary.peakCorePssMb = Math.max(summary.peakCorePssMb, pssMb);
                    }
                } else if ("ui.frame_summary".equals(type)) {
                    summary.frameCount += payload.optLong("frames", 0);
                    summary.jankyFrames += payload.optLong("jankyFrames", 0);
                } else if ("device.sample".equals(type)) {
                    double battery = payload.optDouble("batteryPct", Double.NaN);
                    if (!Double.isNaN(battery)) {
                        if (Double.isNaN(firstBattery)) firstBattery = battery;
                        summary.batteryEnd = battery;
                    }
                    summary.maxThermalStatus = Math.max(summary.maxThermalStatus,
                            payload.optInt("thermalStatus", 0));
                    double headroom = payload.optDouble("thermalHeadroom", Double.NaN);
                    if (!Double.isNaN(headroom)) {
                        summary.maxThermalHeadroom = Double.isNaN(summary.maxThermalHeadroom)
                                ? headroom : Math.max(summary.maxThermalHeadroom, headroom);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        summary.batteryStart = firstBattery;
        if (summary.requestCount > 0) {
            summary.averageRequestMs = requestTotal / summary.requestCount;
            summary.averageFirstResponseMs = firstTotal / summary.requestCount;
        }
        return summary;
    }

    String exportJson() {
        JSONObject root = new JSONObject();
        JSONArray sessions = new JSONArray();
        try {
            root.put("schema", "evolab.export.v1");
            root.put("exportedAt", System.currentTimeMillis());
            try (Cursor sessionCursor = db.getReadableDatabase().query("sessions",
                    new String[]{"id", "scenario", "target", "started_at", "ended_at", "status"},
                    null, null, null, null, "started_at ASC")) {
                while (sessionCursor.moveToNext()) {
                    JSONObject session = new JSONObject();
                    String id = sessionCursor.getString(0);
                    session.put("id", id);
                    session.put("scenario", sessionCursor.getString(1));
                    session.put("target", sessionCursor.getString(2));
                    session.put("startedAt", sessionCursor.getLong(3));
                    session.put("endedAt", sessionCursor.getLong(4));
                    session.put("status", sessionCursor.getString(5));
                    JSONArray events = new JSONArray();
                    try (Cursor eventCursor = db.getReadableDatabase().query("events",
                            new String[]{"source", "type", "elapsed_ns", "wall_ms", "payload_enc"},
                            "session_id=?", new String[]{id}, null, null, "id ASC")) {
                        while (eventCursor.moveToNext()) {
                            JSONObject event = new JSONObject();
                            event.put("source", eventCursor.getString(0));
                            event.put("type", eventCursor.getString(1));
                            event.put("elapsedRealtimeNanos", eventCursor.getLong(2));
                            event.put("wallTimeMillis", eventCursor.getLong(3));
                            event.put("payload", new JSONObject(
                                    crypto.decrypt(eventCursor.getString(4))));
                            events.put(event);
                        }
                    }
                    session.put("events", events);
                    sessions.put(session);
                }
            }
            root.put("sessions", sessions);
        } catch (Exception error) {
            try {
                root.put("error", error.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        try {
            return root.toString(2);
        } catch (Exception ignored) {
            return root.toString();
        }
    }

    String exportCsv() {
        StringBuilder out = new StringBuilder(
                "session_id,scenario,target,source,type,elapsed_ns,wall_ms,payload_json\r\n");
        try (Cursor cursor = db.getReadableDatabase().rawQuery(
                "SELECT s.id,s.scenario,s.target,e.source,e.type,e.elapsed_ns,e.wall_ms,e.payload_enc " +
                        "FROM events e JOIN sessions s ON s.id=e.session_id ORDER BY e.id ASC", null)) {
            while (cursor.moveToNext()) {
                for (int index = 0; index < 7; index++) {
                    if (index > 0) out.append(',');
                    out.append(csv(cursor.getString(index)));
                }
                out.append(',').append(csv(crypto.decrypt(cursor.getString(7)))).append("\r\n");
            }
        }
        return out.toString();
    }

    synchronized void deleteAll() {
        prefs.edit().remove(ACTIVE).commit();
        SQLiteDatabase writable = db.getWritableDatabase();
        writable.delete("events", null, null);
        writable.delete("sessions", null, null);
    }

    private String targetSource(String sessionId) {
        try (Cursor cursor = db.getReadableDatabase().query("sessions",
                new String[]{"target"}, "id=?", new String[]{sessionId},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) return "";
            String target = cursor.getString(0);
            if ("EvoConti".equalsIgnoreCase(target)) return "com.elia.evoconti";
            if ("EvoCore".equalsIgnoreCase(target)) return "it.evocore.host";
        }
        return "";
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        db.getWritableDatabase().delete("sessions", "started_at<? AND status<>?",
                new String[]{Long.toString(cutoff), "ACTIVE"});
    }

    private static String validJson(String value) {
        try {
            return new JSONObject(value == null ? "{}" : value).toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static String safeType(String value) {
        String clean = value == null ? "unknown" : value.toLowerCase(Locale.ROOT);
        clean = clean.replaceAll("[^a-z0-9._-]", "_");
        if (clean.isEmpty()) clean = "unknown";
        return clean.substring(0, Math.min(64, clean.length()));
    }

    private static String safeSource(String value) {
        String clean = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.isEmpty()) clean = "unknown";
        return clean.substring(0, Math.min(128, clean.length()));
    }

    private static String safeLabel(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("[\\r\\n\\t]", " ");
        return clean.substring(0, Math.min(120, clean.length()));
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
