package com.ayrvokan.wireless;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TerminalJobs {
    public static File directory(Context context, String id) {
        if (!id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid job ID");
        return new File(new File(context.getFilesDir(), "terminal-jobs"), id);
    }

    public static File workspace(Context context) {
        File directory = new File(context.getFilesDir(), "workspace");
        directory.mkdirs();
        return directory;
    }

    static boolean active(String state) { return state.equals("queued") || state.equals("running") || state.equals("stopping"); }

    static boolean workerAlive(Context context, int pid) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo p : processes) if (p.pid == pid && p.uid == android.os.Process.myUid() && p.processName.equals(context.getPackageName() + ":terminal")) return true;
        return false;
    }

    static String ticks(int pid) throws Exception {
        String stat = JadxJobs.read(new File("/proc/" + pid + "/stat"));
        return stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19];
    }

    static void kill(File directory) throws Exception {
        File process = new File(directory, "process.json");
        if (!process.isFile()) return;
        JSONObject value = new JSONObject(JadxJobs.read(process));
        int supervisor = value.getInt("supervisorPid");
        try {
            if (!ticks(supervisor).equals(value.getString("supervisorStartTicks"))) return;
            Os.kill(supervisor, OsConstants.SIGTERM);
        } catch (java.io.FileNotFoundException error) {
            return;
        } catch (android.system.ErrnoException error) {
            if (error.errno != OsConstants.ESRCH) throw error;
        }
    }

    public static void recover(Context context) throws Exception {
        try (WorkshopStore store = new WorkshopStore(context)) {
            JSONArray jobs = store.jobs("terminal");
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                if (!active(job.getString("state"))) continue;
                File dir = directory(context, job.getString("id"));
                File worker = new File(dir, "worker.json");
                if (!worker.exists() && System.currentTimeMillis() - job.getLong("updated") < 10000) continue;
                if (worker.isFile() && workerAlive(context, new JSONObject(JadxJobs.read(worker)).getInt("pid"))) continue;
                kill(dir);
                File[] files = dir.listFiles();
                if (files != null) for (File file : files) if (file.getName().endsWith(".part")) Files.deleteIfExists(file.toPath());
                File report = new File(dir, "report.json");
                String state = report.isFile() ? new JSONObject(JadxJobs.read(report)).getString("status") : job.getString("state").equals("stopping") ? "stopped" : "interrupted";
                store.job("terminal", job.getString("id"), job.getString("label"), state, report.isFile() ? "Finished command recovered" : "Worker exited; command logs retained");
            }
        }
    }

    public static String start(Context context, String command, String cwd, JSONObject environment) throws Exception {
        if (command.trim().isEmpty()) throw new IllegalArgumentException("Enter a command or script");
        File working = new File(cwd).getCanonicalFile();
        if (!working.isDirectory()) throw new IllegalArgumentException("Working directory does not exist: " + working);
        String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        File dir = directory(context, id);
        try (WorkshopStore store = new WorkshopStore(context)) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                JSONArray jobs = store.jobs("terminal");
                for (int i = 0; i < jobs.length(); i++) {
                    JSONObject job = jobs.getJSONObject(i);
                    if (active(job.getString("state"))) throw new IllegalStateException("A terminal command is already active");
                    File worker = new File(directory(context, job.getString("id")), "worker.json");
                    if (worker.isFile() && workerAlive(context, new JSONObject(JadxJobs.read(worker)).getInt("pid"))) throw new IllegalStateException("Previous terminal worker is finishing; retry Run shortly");
                }
                if (!dir.mkdirs()) throw new java.io.IOException("Unable to create command directory");
                JadxJobs.write(new File(dir, "request.json"), new JSONObject().put("id", id).put("label", command.split("\n", 2)[0]).put("command", command).put("cwd", working.getPath()).put("environment", environment));
                Files.write(new File(dir, "command.sh").toPath(), (command + "\n").getBytes(StandardCharsets.UTF_8));
                store.job("terminal", id, command.split("\n", 2)[0], "queued", "Starting bundled mksh");
                db.setTransactionSuccessful();
            } catch (Exception error) { JadxJobs.remove(dir); throw error; }
            finally { db.endTransaction(); }
            try { context.startForegroundService(new Intent(context, TerminalService.class).putExtra("action", "run").putExtra("id", id)); }
            catch (Exception error) { store.job("terminal", id, command, "failed", error.toString()); throw error; }
        }
        return id;
    }

    public static void action(Context context, String id, String action, String text) {
        context.startService(new Intent(context, TerminalService.class).putExtra("id", id).putExtra("action", action).putExtra("text", text));
    }

    public static String tail(File file) throws Exception {
        if (!file.isFile()) return "";
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
            long size = input.length();
            int length = (int) Math.min(size, 32768);
            input.seek(size - length);
            byte[] bytes = new byte[length]; input.readFully(bytes);
            return (size > length ? "[Showing last 32 KiB; complete output retained in file]\n" : "") + new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
