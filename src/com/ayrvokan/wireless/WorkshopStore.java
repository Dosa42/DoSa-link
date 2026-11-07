package com.ayrvokan.wireless;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WorkshopStore extends SQLiteOpenHelper {
    public WorkshopStore(Context context) {
        super(context, "workshop.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, recorded_at INTEGER NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL)");
        db.execSQL("CREATE TABLE profiles (id TEXT PRIMARY KEY, label TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL)");
        db.execSQL("INSERT INTO settings VALUES ('workspace', 'My room')");
        db.execSQL("INSERT INTO profiles VALUES ('fanlamp', 'Room light & fan', 'Six power-cycle modes', 'Not connected')");
        db.execSQL("INSERT INTO profiles VALUES ('nspanel', 'SONOFF NSPanel', 'Original model', 'Not connected')");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database upgrade");
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
        ContentValues values = new ContentValues();
        values.put("recorded_at", System.currentTimeMillis());
        values.put("kind", kind);
        values.put("message", message);
        getWritableDatabase().insertOrThrow("events", null, values);
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
