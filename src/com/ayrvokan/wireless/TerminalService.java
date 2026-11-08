package com.ayrvokan.wireless;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONObject;

public final class TerminalService extends Service {
    private String active;
    private volatile Process process;
    private volatile boolean stopping;
    private final Object inputLock = new Object();

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String id = intent.getStringExtra("id"), action = intent.getStringExtra("action");
        if (!"run".equals(action)) {
            if (id != null && id.equals(active)) new Thread(() -> control(action, intent.getStringExtra("text")), "terminal-input").start();
            else if (active == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (active != null) return START_NOT_STICKY;
        active = id;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("terminal-jobs", "Local terminal", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, TerminalActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(23, new Notification.Builder(this, "terminal-jobs").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Local terminal").setContentText("Command running; open to view output or stop.").setContentIntent(open).setOngoing(true).build());
        new Thread(() -> execute(id), "terminal-command").start();
        return START_NOT_STICKY;
    }

    private void control(String action, String text) {
        try (WorkshopStore store = new WorkshopStore(this)) {
            File dir = TerminalJobs.directory(this, active);
            if ("stop".equals(action)) {
                stopping = true;
                JSONObject request = new JSONObject(JadxJobs.read(new File(dir, "request.json")));
                store.job("terminal", active, request.getString("label"), "stopping", "Explicit stop requested");
                TerminalJobs.kill(dir);
            } else synchronized (inputLock) {
                Process child = process;
                if (child == null || !child.isAlive()) throw new IllegalStateException("Command is not accepting input");
                if ("eof".equals(action)) {
                    child.getOutputStream().close();
                    store.jobTrace(active, "INFO", "stdin.eof", "Standard input closed", new JSONObject());
                } else if ("input".equals(action)) {
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    child.getOutputStream().write(bytes); child.getOutputStream().flush();
                    try (FileOutputStream log = new FileOutputStream(new File(dir, "stdin.log"), true)) { log.write(bytes); }
                    store.jobTrace(active, "INFO", "stdin.write", "Standard input sent", new JSONObject().put("bytes", bytes.length));
                }
            }
        } catch (Exception error) {
            try (WorkshopStore store = new WorkshopStore(this)) { store.jobTrace(active, "ERROR", "control.error", error.toString(), new JSONObject()); }
        }
    }

    private void execute(String id) {
        File dir = TerminalJobs.directory(this, id);
        String label = id;
        try (WorkshopStore store = new WorkshopStore(this)) {
            try {
                JSONObject request = new JSONObject(JadxJobs.read(new File(dir, "request.json")));
                label = request.getString("label");
                JadxJobs.write(new File(dir, "worker.json"), new JSONObject().put("pid", android.os.Process.myPid()));
                File binaries = new File(getApplicationInfo().nativeLibraryDir);
                File shell = new File(binaries, "libworkshop_mksh.so");
                File launcher = new File(binaries, "libworkshop_terminal.so");
                File workspace = TerminalJobs.workspace(this);
                File temporary = new File(getCacheDir(), "terminal"); temporary.mkdirs();
                ProcessBuilder builder = new ProcessBuilder(launcher.getPath(), shell.getPath(), new File(dir, "command.sh").getPath(), new File(dir, "process.json").getPath());
                builder.directory(new File(request.getString("cwd")));
                builder.environment().put("HOME", workspace.getPath());
                builder.environment().put("TMPDIR", temporary.getPath());
                builder.environment().put("SHELL", shell.getPath());
                builder.environment().put("PATH", "/system/bin:/system/xbin");
                builder.environment().put("WORKSHOP_FILES", getFilesDir().getPath());
                builder.environment().put("WORKSHOP_JOB", dir.getPath());
                builder.environment().put("LANG", "C.UTF-8");
                JSONObject environment = request.getJSONObject("environment");
                Iterator<String> keys = environment.keys();
                while (keys.hasNext()) { String key = keys.next(); builder.environment().put(key, environment.getString(key)); }
                builder.redirectOutput(new File(dir, "stdout.log")); builder.redirectError(new File(dir, "stderr.log"));
                if (stopping) throw new InterruptedException("Stopped before process launch");
                process = builder.start();
                while (!new File(dir, "process.json").exists() && process.isAlive()) Thread.sleep(10);
                if (stopping) TerminalJobs.kill(dir);
                store.job("terminal", id, label, stopping ? "stopping" : "running", "Bundled mksh; " + request.getString("cwd"));
                JSONObject execution = new JSONObject().put("cwd", request.getString("cwd")).put("shell", shell.getPath()).put("shellSha256", JadxJobs.hash(shell)).put("launcherSha256", JadxJobs.hash(launcher)).put("workerPid", android.os.Process.myPid());
                File identity = new File(dir, "process.json");
                if (identity.isFile()) execution.put("process", new JSONObject(JadxJobs.read(identity)));
                store.jobTrace(id, "INFO", "process.start", "Bundled shell launched", execution);
                int code = process.waitFor();
                synchronized (inputLock) { process.getOutputStream().close(); }
                String status = stopping ? "stopped" : code == 0 ? "completed" : "failed";
                JSONObject report = execution.put("status", status).put("exitCode", code).put("stopped", stopping).put("completedUtc", java.time.Instant.now().toString());
                for (String name : new String[] {"stdout", "stderr"}) { File file = new File(dir, name + ".log"); report.put(name, new JSONObject().put("bytes", file.length()).put("sha256", JadxJobs.hash(file))); }
                JadxJobs.write(new File(dir, "report.json"), report);
                store.jobTrace(id, code == 0 || stopping ? "INFO" : "ERROR", "process.exit", "Exit status " + code, report);
                store.job("terminal", id, label, status, (stopping ? "Stopped; " : "") + "exit " + code + "; stdout " + report.getJSONObject("stdout").getLong("bytes") + " bytes; stderr " + report.getJSONObject("stderr").getLong("bytes") + " bytes");
            } catch (Throwable error) {
                try { TerminalJobs.kill(dir); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                if (process != null) { try { process.waitFor(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
                JadxJobs.write(new File(dir, "failure.json"), new JSONObject().put("error", error.toString()).put("stack", android.util.Log.getStackTraceString(error)));
                store.job("terminal", id, label, stopping ? "stopped" : "failed", error.toString());
            }
        } catch (Exception error) { android.util.Log.e("WorkshopTerminal", "Finalization failed", error); }
        finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) if (file.getName().endsWith(".part")) file.delete();
            process = null;
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override public void onTimeout(int startId, int type) { if (active != null) new Thread(() -> control("stop", ""), "terminal-stop").start(); }
    @Override public void onDestroy() { if (active != null && process != null && process.isAlive()) { stopping = true; try { TerminalJobs.kill(TerminalJobs.directory(this, active)); } catch (Exception error) { android.util.Log.e("WorkshopTerminal", "Stop failed", error); } } super.onDestroy(); }
}
