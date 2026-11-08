package com.ayrvokan.wireless;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class JadxJobs {
    public static File root(Context context) {
        File root = new File(context.getFilesDir(), "jadx-jobs");
        root.mkdirs();
        return root;
    }

    public static File directory(Context context, String id) {
        return new File(root(context), id);
    }

    public static String read(File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void write(File file, JSONObject value) throws Exception {
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream output = new FileOutputStream(part)) {
            output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static void copy(InputStream input, java.io.OutputStream output) throws Exception {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    public static void remove(File file) throws Exception {
        File[] files = file.listFiles();
        if (files != null) for (File child : files) remove(child);
        if (file.exists() && !file.delete()) throw new java.io.IOException("Unable to remove unfinished file: " + file.getName());
    }

    public static void retainLogs(File dir) throws Exception {
        File working = new File(dir, "working");
        File[] files = working.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && (name.equals("console.log") || name.equals("jadx.log") || name.equals("native-jvm.log") || name.equals("runtime-start.json") || name.startsWith("hs_err_pid"))) {
                java.nio.file.Files.copy(file.toPath(), new File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static boolean alive(Context context, int pid) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == pid && process.uid == android.os.Process.myUid() && process.processName.equals(context.getPackageName() + ":jadx")) return true;
        }
        return false;
    }

    public static void recover(Context context) throws Exception {
        try (WorkshopStore store = new WorkshopStore(context)) {
            JSONArray jobs = store.jobs();
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                String state = job.getString("state");
                if (!state.equals("running") && !state.equals("queued") && !state.equals("stopping")) continue;
                File dir = directory(context, job.getString("id"));
                File runtime = new File(dir, "worker.json");
                int pid = runtime.exists() ? new JSONObject(read(runtime)).optInt("pid", -1) : -1;
                if (alive(context, pid)) continue;
                retainLogs(dir);
                remove(new File(dir, "working"));
                remove(new File(dir, "input"));
                remove(new File(context.getCodeCacheDir(), "jadx/engine.part"));
                NativeJadx.cleanUnfinished(context);
                for (String name : new String[] {"progress.json.part", "request.json.part", "worker.json.part"}) remove(new File(dir, name));
                boolean finalized = new File(dir, "result/engine-report.json").isFile();
                String resolved = finalized ? new JSONObject(read(new File(dir, "result/engine-report.json"))).getString("status") : state.equals("stopping") ? "stopped" : "interrupted";
                store.job(job.getString("id"), job.getString("label"), resolved, finalized ? "Finished output recovered" : "Unfinished working files cleaned");
                store.event("jadx", job.getString("id") + ": " + resolved);
            }
        }
    }

    public static String start(Context context, String source, String label, String selectedClass, JSONArray options, JSONArray vmOptions) throws Exception {
        try (WorkshopStore store = new WorkshopStore(context)) {
            String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
            File dir = directory(context, id);
            android.database.sqlite.SQLiteDatabase database = store.getWritableDatabase();
            database.beginTransaction();
            try {
                JSONArray jobs = store.jobs();
                for (int i = 0; i < jobs.length(); i++) {
                    JSONObject job = jobs.getJSONObject(i);
                    String state = job.getString("state");
                    if (state.equals("queued") || state.equals("running") || state.equals("stopping")) throw new IllegalStateException("A JADX job is already active");
                    File runtime = new File(directory(context, job.getString("id")), "worker.json");
                    if (runtime.isFile() && alive(context, new JSONObject(read(runtime)).optInt("pid", -1))) throw new IllegalStateException("The previous worker is finishing; retry Run shortly");
                }
                dir.mkdirs();
                JSONObject request = new JSONObject().put("id", id).put("label", label).put("source", source).put("selectedClass", selectedClass).put("options", options).put("vmOptions", vmOptions);
                write(new File(dir, "request.json"), request);
                store.job(id, label, "queued", "Starting bundled JADX worker");
                store.event("jadx", id + ": queued " + label);
                database.setTransactionSuccessful();
            } catch (Exception error) {
                remove(dir);
                throw error;
            } finally {
                database.endTransaction();
            }
            try {
                Intent intent = new Intent(context, JadxService.class).putExtra("id", id);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
                else context.startService(intent);
            } catch (Exception error) {
                store.job(id, label, "failed", error.toString());
                throw error;
            }
            return id;
        }
    }

    public static void stop(Context context, String id) throws Exception {
        File dir = directory(context, id);
        JSONObject request = new JSONObject(read(new File(dir, "request.json")));
        try (WorkshopStore store = new WorkshopStore(context)) {
            File runtime = new File(dir, "worker.json");
            if (!runtime.exists()) throw new IllegalStateException("Worker is starting; retry Stop when it is running");
            int pid = new JSONObject(read(runtime)).getInt("pid");
            store.job(id, request.getString("label"), "stopping", "Explicit stop requested");
            if (alive(context, pid)) android.os.Process.killProcess(pid);
            while (alive(context, pid)) Thread.sleep(50);
            retainLogs(dir);
            remove(new File(dir, "working"));
            remove(new File(dir, "input"));
            remove(new File(context.getCodeCacheDir(), "jadx/engine.part"));
            NativeJadx.cleanUnfinished(context);
            for (String name : new String[] {"progress.json.part", "request.json.part", "worker.json.part"}) remove(new File(dir, name));
            File report = new File(dir, "result/engine-report.json");
            String state = report.isFile() ? new JSONObject(read(report)).getString("status") : "stopped";
            store.job(id, request.getString("label"), state, report.isFile() ? "Job finished before Stop" : "Unfinished working files cleaned after explicit Stop");
            store.event("jadx", id + ": " + state + "; explicit Stop handled and unfinished files cleaned");
        }
    }

    public static JSONArray options(String value) {
        JSONArray result = new JSONArray();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escape = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escape) { token.append(ch); escape = false; }
            else if (ch == '\\') escape = true;
            else if (quote != 0) { if (ch == quote) quote = 0; else token.append(ch); }
            else if (ch == '\'' || ch == '"') quote = ch;
            else if (Character.isWhitespace(ch)) { if (token.length() > 0) { result.put(token.toString()); token.setLength(0); } }
            else token.append(ch);
        }
        if (escape || quote != 0) throw new IllegalArgumentException("Unfinished quote or escape in options");
        if (token.length() > 0) result.put(token.toString());
        return result;
    }
}
