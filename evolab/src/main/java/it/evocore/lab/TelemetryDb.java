package it.evocore.lab;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class TelemetryDb extends SQLiteOpenHelper {
    private static final String NAME = "evolab.db";
    private static final int VERSION = 1;

    TelemetryDb(Context context) {
        super(context, NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (" +
                "id TEXT PRIMARY KEY," +
                "scenario TEXT NOT NULL," +
                "target TEXT NOT NULL," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id TEXT NOT NULL," +
                "source TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "elapsed_ns INTEGER NOT NULL," +
                "wall_ms INTEGER NOT NULL," +
                "payload_enc TEXT NOT NULL," +
                "FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_events_session ON events(session_id, id)");
        db.execSQL("CREATE INDEX idx_events_type ON events(type, id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }
}
