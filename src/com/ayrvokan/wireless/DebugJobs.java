package com.ayrvokan.wireless;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DebugJobs {
    public static File directory(Context context, String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid debug session ID");
        return new File(new File(context.getFilesDir(), "debug-jobs"), id);
    }
    public static boolean alive(Context context, int pid) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo p : processes) if (p.pid == pid && p.uid == android.os.Process.myUid() && p.processName.startsWith(context.getPackageName())) return true;
        return false;
    }
    static boolean active(String state) { return state.equals("queued") || state.equals("running") || state.equals("paused") || state.equals("stopping"); }
    public static boolean live(Context context) throws Exception {
        File marker = new File(context.getFilesDir(), "debugger-live.json");
        if (!marker.isFile()) return false;
        JSONObject value = new JSONObject(JadxJobs.read(marker)); int pid = value.getInt("pid");
        try { return alive(context, pid) && TerminalJobs.ticks(pid).equals(value.getString("startTicks")); } catch (java.io.FileNotFoundException gone) { return false; }
    }
    public static void awaitAvailable(Context context) throws Exception {
        if (DebugService.busy) {
            File marker = new File(context.getFilesDir(), "debugger-live.json");
            if (marker.isFile()) {
                JSONObject current = new JSONObject(JadxJobs.read(marker)); File dir = directory(context, current.getString("id"));
                if (!new File(dir, "report.json").isFile() && !new File(dir, "failure.json").isFile()) throw new IllegalStateException("A debug session is already active");
            }
            while (DebugService.busy) Thread.sleep(25);
        }
        if (live(context)) throw new IllegalStateException("A debug session is already active");
        try (WorkshopStore store = new WorkshopStore(context)) {
            JSONArray jobs = store.jobs("debug");
            for (int i = 0; i < jobs.length(); i++) if (active(jobs.getJSONObject(i).getString("state"))) throw new IllegalStateException("A debug session is already active");
        }
        while (DebugService.busy) Thread.sleep(25);
    }
    public static String start(Context context, JSONObject request) throws Exception {
        recover(context);
        awaitAvailable(context);
        int pid = request.getInt("targetPid");
        if (!alive(context, pid)) throw new IllegalStateException("Choose a running process of this app");
        if (pid == android.os.Process.myPid()) throw new IllegalArgumentException("The debugger controller must run separately from its target");
        try (WorkshopStore store = new WorkshopStore(context)) {
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8); File dir = directory(context, id);
            try {
                JSONArray jobs = store.jobs("debug");
                for (int i = 0; i < jobs.length(); i++) if (active(jobs.getJSONObject(i).getString("state"))) throw new IllegalStateException("A debug session is already active");
                if (!dir.mkdirs()) throw new java.io.IOException("Unable to create debug session");
                request.put("id", id).put("targetStartTicks", TerminalJobs.ticks(pid)).put("controllerPid", android.os.Process.myPid()).put("controllerStartTicks", TerminalJobs.ticks(android.os.Process.myPid()));
                JadxJobs.write(new File(dir, "request.json"), request);
                store.job("debug", id, request.getString("mode") + " • PID " + pid, "queued", "Starting debugger attachment");
                db.setTransactionSuccessful();
            } catch (Exception error) { JadxJobs.remove(dir); throw error; }
            finally { db.endTransaction(); }
            try { context.startForegroundService(new Intent(context, DebugService.class).putExtra("id", id).putExtra("action", "start")); }
            catch (Exception error) { store.job("debug", id, request.getString("mode"), "failed", error.toString()); throw error; }
            return id;
        }
    }
    public static void send(Context context, String id, int pid, String action, JSONObject values) throws Exception {
        Intent intent = new Intent(context.getPackageName() + ".DEBUG").setPackage(context.getPackageName()).putExtra("pid", pid).putExtra("id", id).putExtra("action", action);
        for (String key : new String[] {"depth", "slot", "value"}) intent.putExtra(key, values.optInt(key));
        intent.putExtra("mode", values.optString("mode")).putExtra("finishProbe", values.optBoolean("finishProbe"));
        context.sendBroadcast(intent);
    }
    public static void action(Context context, String id, String action, JSONObject values) {
        context.startService(new Intent(context, DebugService.class).putExtra("id", id).putExtra("action", action).putExtra("values", values.toString()));
    }
    public static void stopProbe(Context context, JSONObject request) throws Exception {
        if (!request.optBoolean("fixture")) return;
        int target = request.getInt("targetPid"); File file = new File(context.getFilesDir(), "debug-probe.json");
        if (!file.isFile() || !alive(context, target)) return;
        JSONObject identity = new JSONObject(JadxJobs.read(file));
        if (identity.getInt("pid") != target || !identity.getString("startTicks").equals(request.getString("targetStartTicks"))) return;
        context.stopService(new Intent(context, DebugProbeService.class));
        while (new File("/proc/" + target).exists()) Thread.sleep(25);
    }
    public static void recover(Context context) throws Exception {
        if (live(context) || DebugService.busy) return;
        File marker = new File(context.getFilesDir(), "debugger-live.json");
        if (marker.isFile()) {
            JSONObject value = new JSONObject(JadxJobs.read(marker)); String id = value.getString("id");
            File dir = directory(context, id); JSONObject request = new JSONObject(JadxJobs.read(new File(dir, "request.json")));
            if (request.getString("mode").equals("java") && alive(context, request.getInt("targetPid"))) send(context, id, request.getInt("targetPid"), "detach", new JSONObject());
            File server = new File(dir, "server-process.json");
            if (request.getString("mode").equals("native") && server.isFile()) {
                JSONObject identity = new JSONObject(JadxJobs.read(server)); int pid = identity.getInt("pid");
                try { if (TerminalJobs.ticks(pid).equals(identity.getString("startTicks"))) android.system.Os.kill(pid, android.system.OsConstants.SIGTERM); }
                catch (java.io.FileNotFoundException ignored) { }
                catch (android.system.ErrnoException error) { if (error.errno != android.system.OsConstants.ESRCH) throw error; }
            }
            stopProbe(context, request);
            java.nio.file.Files.deleteIfExists(marker.toPath());
        }
        try (WorkshopStore store = new WorkshopStore(context)) {
            JSONArray jobs = store.jobs("debug");
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i); if (!active(job.getString("state"))) continue;
                File dir = directory(context, job.getString("id")); File worker = new File(dir, "worker.json");
                if (!worker.exists() && System.currentTimeMillis() - job.getLong("updated") < 10000) continue;
                if (worker.isFile() && alive(context, new JSONObject(JadxJobs.read(worker)).getInt("pid"))) continue;
                JSONObject request = new JSONObject(JadxJobs.read(new File(dir, "request.json")));
                if (request.getString("mode").equals("java") && alive(context, request.getInt("targetPid")) && TerminalJobs.ticks(request.getInt("targetPid")).equals(request.getString("targetStartTicks"))) send(context, job.getString("id"), request.getInt("targetPid"), "detach", new JSONObject());
                File server = new File(dir, "server-process.json");
                if (request.getString("mode").equals("native") && server.isFile()) {
                    JSONObject identity = new JSONObject(JadxJobs.read(server)); int process = identity.getInt("pid");
                    try { if (TerminalJobs.ticks(process).equals(identity.getString("startTicks"))) android.system.Os.kill(process, android.system.OsConstants.SIGTERM); }
                    catch (java.io.FileNotFoundException ignored) { }
                    catch (android.system.ErrnoException error) { if (error.errno != android.system.OsConstants.ESRCH) throw error; }
                }
                stopProbe(context, request);
                for (String name : new String[] {"request.json.part", "worker.json.part", "state.json.part", "report.json.part", "failure.json.part", "server.json.part", "server-process.json.part", "guardian-report.json.part"}) java.nio.file.Files.deleteIfExists(new File(dir, name).toPath());
                File report = new File(dir, "report.json");
                if (!report.isFile()) JadxJobs.write(report, new JSONObject().put("status", "interrupted").put("mode", request.getString("mode")).put("targetPid", request.getInt("targetPid")).put("recoveredUtc", java.time.Instant.now().toString()).put("lastSnapshotRetained", true));
                store.job("debug", job.getString("id"), job.getString("label"), report.isFile() ? new JSONObject(JadxJobs.read(report)).getString("status") : "interrupted", report.isFile() ? "Finished debug session recovered" : "Controller exited; debug session recovered");
            }
        }
    }
}
