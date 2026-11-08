package com.ayrvokan.wireless;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WorkshopStore extends SQLiteOpenHelper {
    public WorkshopStore(Context context) {
        super(context, "workshop.db", null, 4);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createJobs(db);
        db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, recorded_at INTEGER NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL)");
        db.execSQL("CREATE TABLE profiles (id TEXT PRIMARY KEY, label TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL)");
        db.execSQL("INSERT INTO settings VALUES ('workspace', 'My room')");
        db.execSQL("INSERT INTO profiles VALUES ('fanlamp', 'Room light & fan', 'Six power-cycle modes', 'Not connected')");
        db.execSQL("INSERT INTO profiles VALUES ('nspanel', 'SONOFF NSPanel', 'Original model', 'Not connected')");
        createDiagnostics(db);
        createJobKinds(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createJobs(db);
        if (oldVersion < 3) createDiagnostics(db);
        if (oldVersion < 4) createJobKinds(db);
    }

    private void createJobKinds(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE jobs ADD COLUMN kind TEXT NOT NULL DEFAULT 'jadx'");
        db.execSQL("CREATE INDEX jobs_kind ON jobs(kind,updated_at)");
    }

    private void createDiagnostics(SQLiteDatabase db) {
        for (String field : new String[] {"level TEXT NOT NULL DEFAULT 'INFO'", "module TEXT NOT NULL DEFAULT ''", "phase TEXT NOT NULL DEFAULT 'event'", "session_id TEXT NOT NULL DEFAULT 'legacy'", "operation_id TEXT NOT NULL DEFAULT ''", "request_id TEXT NOT NULL DEFAULT ''", "device_id TEXT NOT NULL DEFAULT ''", "transport TEXT NOT NULL DEFAULT ''", "payload TEXT NOT NULL DEFAULT '{}'", "elapsed_ms INTEGER", "process_id INTEGER NOT NULL DEFAULT 0", "thread_name TEXT NOT NULL DEFAULT ''"}) db.execSQL("ALTER TABLE events ADD COLUMN " + field);
        db.execSQL("UPDATE events SET module=kind,level=CASE WHEN kind='error' THEN 'ERROR' ELSE 'INFO' END");
        db.execSQL("UPDATE events SET operation_id=substr(message,1,instr(message,':')-1) WHERE kind='jadx' AND EXISTS (SELECT 1 FROM jobs WHERE jobs.id=substr(events.message,1,instr(events.message,':')-1))");
        db.execSQL("ALTER TABLE jobs ADD COLUMN session_id TEXT NOT NULL DEFAULT 'legacy'");
        db.execSQL("ALTER TABLE jobs ADD COLUMN started_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE jobs SET started_at=updated_at");
        for (String field : new String[] {"module", "level", "session_id", "operation_id", "request_id", "device_id"}) db.execSQL("CREATE INDEX events_" + field + " ON events(" + field + ",id)");
    }

    public String sessionId() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues value = new ContentValues();
        value.put("name", "diagnostic_session");
        value.put("value", UUID.randomUUID().toString());
        db.insertWithOnConflict("settings", null, value, SQLiteDatabase.CONFLICT_IGNORE);
        try (Cursor cursor = db.rawQuery("SELECT value FROM settings WHERE name='diagnostic_session'", null)) { cursor.moveToFirst(); return cursor.getString(0); }
    }

    public String newSession() {
        String id = UUID.randomUUID().toString();
        ContentValues value = new ContentValues();
        value.put("name", "diagnostic_session"); value.put("value", id);
        getWritableDatabase().insertWithOnConflict("settings", null, value, SQLiteDatabase.CONFLICT_REPLACE);
        event("diagnostics", "Diagnostic session started");
        return id;
    }

    private void createJobs(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, label TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    }

    public void job(String id, String label, String state, String detail) {
        job("jadx", id, label, state, detail);
    }

    public void job(String kind, String id, String label, String state, String detail) {
        String session = sessionId();
        long started = System.currentTimeMillis();
        boolean unchanged = false;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT session_id,started_at,state,detail FROM jobs WHERE id=?", new String[] {id})) {
            if (cursor.moveToFirst()) { session = cursor.getString(0); started = cursor.getLong(1); unchanged = state.equals(cursor.getString(2)) && detail.equals(cursor.getString(3)); }
        }
        ContentValues value = new ContentValues();
        value.put("id", id);
        value.put("label", label);
        value.put("kind", kind);
        value.put("state", state);
        value.put("detail", detail);
        value.put("updated_at", System.currentTimeMillis());
        value.put("session_id", session);
        value.put("started_at", started);
        getWritableDatabase().insertWithOnConflict("jobs", null, value, SQLiteDatabase.CONFLICT_REPLACE);
        if (!unchanged) {
            JSONObject payload = new JSONObject();
            try { payload.put("jobId", id).put("label", label).put("state", state); } catch (Exception error) { throw new IllegalStateException(error); }
            trace("failed".equals(state) ? "ERROR" : "interrupted".equals(state) || "completed-with-errors".equals(state) ? "WARN" : "INFO", kind, state, session, id, "", "", kind.equals("jadx") ? "jni" : "local-process", detail, payload, System.currentTimeMillis() - started);
        }
    }

    public void jobTrace(String id, String level, String phase, String message, JSONObject payload) {
        String kind = "jadx";
        String session = sessionId();
        long started = System.currentTimeMillis();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT session_id,started_at,kind FROM jobs WHERE id=?", new String[] {id})) { if (cursor.moveToFirst()) { session = cursor.getString(0); started = cursor.getLong(1); kind = cursor.getString(2); } }
        trace(level, kind, phase, session, id, id + (kind.equals("jadx") ? "-jni" : "-terminal"), "", kind.equals("jadx") ? "jni" : "local-process", message, payload, System.currentTimeMillis() - started);
    }

    public JSONArray jobs() throws Exception { return jobs("jadx"); }

    public JSONArray jobs(String kind) throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,label,state,detail,updated_at FROM jobs WHERE kind=? ORDER BY updated_at DESC", new String[] {kind})) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getString(0));
                item.put("label", cursor.getString(1));
                item.put("state", cursor.getString(2));
                item.put("detail", cursor.getString(3));
                item.put("updated", cursor.getLong(4));
                result.put(item);
            }
        }
        return result;
    }

    public String workspace() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE name = 'workspace'", null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "My room";
        }
    }

    public void saveWorkspace(String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("name", "workspace");
            values.put("value", name);
            db.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            event("settings", "Workspace saved: " + name);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void event(String kind, String message) {
        trace("error".equals(kind) ? "ERROR" : "INFO", kind, "event", sessionId(), "", "", "", "", message, new JSONObject(), null);
    }

    public void trace(String level, String module, String phase, String session, String operation, String request, String device, String transport, String message, JSONObject payload, Long elapsed) {
        ContentValues values = new ContentValues();
        values.put("recorded_at", System.currentTimeMillis());
        values.put("kind", module);
        values.put("message", message);
        values.put("level", level); values.put("module", module); values.put("phase", phase);
        values.put("session_id", session); values.put("operation_id", operation); values.put("request_id", request);
        values.put("device_id", device); values.put("transport", transport); values.put("payload", payload.toString());
        if (elapsed != null) values.put("elapsed_ms", elapsed);
        values.put("process_id", android.os.Process.myPid()); values.put("thread_name", Thread.currentThread().getName());
        getWritableDatabase().insertOrThrow("events", null, values);
    }

    public static final class TraceFilter {
        public String level = "", module = "", session = "", operation = "", query = "";
        public JSONObject json() throws Exception { return new JSONObject().put("level", level).put("module", module).put("session", session).put("operation", operation).put("query", query); }
        public static TraceFilter from(JSONObject value) {
            TraceFilter f = new TraceFilter();
            f.level = value.optString("level"); f.module = value.optString("module"); f.session = value.optString("session"); f.operation = value.optString("operation"); f.query = value.optString("query");
            return f;
        }
    }

    private String traceWhere(TraceFilter f, List<String> values) {
        StringBuilder sql = new StringBuilder("1=1");
        String[] fields = {"level", "module", "session_id", "operation_id"};
        String[] filters = {f.level, f.module, f.session, f.operation};
        for (int i = 0; i < fields.length; i++) if (!filters[i].isEmpty()) { sql.append(" AND ").append(fields[i]).append("=?"); values.add(filters[i]); }
        if (!f.query.isEmpty()) { sql.append(" AND instr(lower(message || char(10) || payload || char(10) || operation_id || char(10) || request_id || char(10) || device_id || char(10) || transport),lower(?))>0"); values.add(f.query); }
        return sql.toString();
    }

    public long traceCount(TraceFilter f) {
        List<String> values = new ArrayList<>(); String where = traceWhere(f, values);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM events WHERE " + where, values.toArray(new String[0]))) { c.moveToFirst(); return c.getLong(0); }
    }

    public Cursor traceCursor(TraceFilter f, long before, long maximum, int limit, boolean ascending) {
        List<String> values = new ArrayList<>(); String where = traceWhere(f, values);
        if (before > 0) { where += " AND id<?"; values.add(String.valueOf(before)); }
        if (maximum > 0) { where += " AND id<=?"; values.add(String.valueOf(maximum)); }
        String sql = "SELECT * FROM events WHERE " + where + " ORDER BY id " + (ascending ? "ASC" : "DESC");
        if (limit > 0) { sql += " LIMIT ?"; values.add(String.valueOf(limit)); }
        return getReadableDatabase().rawQuery(sql, values.toArray(new String[0]));
    }

    public JSONObject traceRow(Cursor c) throws Exception {
        JSONObject value = new JSONObject();
        for (String name : new String[] {"id", "recorded_at", "process_id"}) value.put(name, c.getLong(c.getColumnIndexOrThrow(name)));
        value.put("time", value.getLong("recorded_at")); value.put("utc", java.time.Instant.ofEpochMilli(value.getLong("time")).toString());
        for (String name : new String[] {"kind", "message", "level", "module", "phase", "session_id", "operation_id", "request_id", "device_id", "transport", "thread_name"}) value.put(name, c.getString(c.getColumnIndexOrThrow(name)));
        int elapsed = c.getColumnIndexOrThrow("elapsed_ms"); value.put("elapsed_ms", c.isNull(elapsed) ? JSONObject.NULL : c.getLong(elapsed));
        value.put("payload", new JSONObject(c.getString(c.getColumnIndexOrThrow("payload"))));
        return value;
    }

    public JSONArray tracePage(TraceFilter f, long before, int limit) throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor c = traceCursor(f, before, 0, limit, false)) { while (c.moveToNext()) rows.put(traceRow(c)); }
        return rows;
    }

    public List<String> traceValues(String field) {
        if (!field.equals("module") && !field.equals("session_id")) throw new IllegalArgumentException(field);
        List<String> values = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT " + field + " FROM events WHERE " + field + "<>'' ORDER BY " + field, null)) { while (c.moveToNext()) values.add(c.getString(0)); }
        return values;
    }

    public JSONArray events(int limit) throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id, recorded_at, kind, message FROM events ORDER BY id DESC LIMIT ?", new String[] {String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getLong(0));
                item.put("time", cursor.getLong(1));
                item.put("kind", cursor.getString(2));
                item.put("message", cursor.getString(3));
                result.put(item);
            }
        }
        return result;
    }

    public JSONArray profiles() throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id, label, kind, status FROM profiles ORDER BY id", null)) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getString(0));
                item.put("label", cursor.getString(1));
                item.put("kind", cursor.getString(2));
                item.put("status", cursor.getString(3));
                result.put(item);
            }
        }
        return result;
    }

    public void clearEvents() {
        getWritableDatabase().delete("events", null, null);
        event("history", "Activity history cleared");
    }
}
