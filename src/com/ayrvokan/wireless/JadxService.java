package com.ayrvokan.wireless;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import org.json.JSONObject;

public final class JadxService extends Service {
    private String active;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (active != null) return START_NOT_STICKY;
        active = intent.getStringExtra("id");
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("jadx-jobs", "JADX jobs", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, JadxActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "jadx-jobs").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("JADX headless").setContentText("Decompilation is running. Open the tool to view progress or stop.").setContentIntent(open).setOngoing(true).build();
        startForeground(21, notification);
        new Thread(() -> execute(active), "jadx-engine").start();
        return START_NOT_STICKY;
    }

    private void execute(String id) {
        File dir = JadxJobs.directory(this, id);
        File working = new File(dir, "working");
        String label = id;
        try (WorkshopStore store = new WorkshopStore(this)) {
            try {
                JSONObject request = new JSONObject(JadxJobs.read(new File(dir, "request.json")));
                label = request.getString("label");
                JadxJobs.write(new File(dir, "worker.json"), new JSONObject().put("pid", android.os.Process.myPid()));
                store.job(id, label, "running", "Preparing bundled engine");
                File input;
                String source = request.getString("source");
                if (source.startsWith("apk-dex:")) {
                    String member = source.substring(8);
                    File folder = new File(dir, "input");
                    folder.mkdirs();
                    input = new File(folder, member);
                    try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(getApplicationInfo().sourceDir); InputStream from = archive.getInputStream(archive.getEntry(member)); FileOutputStream to = new FileOutputStream(input)) { JadxJobs.copy(from, to); }
                } else if (source.startsWith("asset:")) {
                    File folder = new File(dir, "input");
                    folder.mkdirs();
                    input = new File(folder, source.substring(source.lastIndexOf('/') + 1));
                    try (InputStream from = getAssets().open(source.substring(6)); FileOutputStream to = new FileOutputStream(input)) { JadxJobs.copy(from, to); }
                } else if (source.startsWith("content:")) {
                    File folder = new File(dir, "input");
                    folder.mkdirs();
                    input = new File(folder, label.replaceAll("[^a-zA-Z0-9._-]", "_"));
                    try (InputStream from = getContentResolver().openInputStream(android.net.Uri.parse(source)); FileOutputStream to = new FileOutputStream(input)) { JadxJobs.copy(from, to); }
                } else input = new File(source);
                request.put("input", input.getPath());
                request.put("inputSha256", JadxJobs.hash(input));
                JadxJobs.write(new File(dir, "request.json"), request);
                String reportText = NativeJadx.run(this, new File(dir, "request.json"), working, new File(dir, "progress.json"));
                JSONObject report = new JSONObject(reportText);
                report.put("inputSha256", request.getString("inputSha256"));
                JSONObject tool;
                try (InputStream from = getAssets().open("workshop/jadx/tool.json"); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    JadxJobs.copy(from, bytes);
                    tool = new JSONObject(bytes.toString("UTF-8"));
                }
                report.put("bundledEngineSha256", tool.getString("engineSha256"));
                report.put("bundledRuntimeSha256", tool.getString("runtimeSha256"));
                report.put("nativeBridgeSha256", tool.getString("nativeBridgeSha256"));
                report.put("androidWorkerPid", android.os.Process.myPid());
                if (source.startsWith("asset:")) {
                    File seed = new File(working, "sources/com/ayrvokan/fixture/SeedProbe.java");
                    boolean passed = seed.isFile() && JadxJobs.read(seed).contains("42") && JadxJobs.read(seed).contains("answer");
                    if (label.equals("fixture.apk")) {
                        File manifest = new File(working, "resources/AndroidManifest.xml");
                        File strings = new File(working, "resources/res/values/strings.xml");
                        passed = passed && manifest.isFile() && strings.isFile() && JadxJobs.read(strings).contains("Embedded engine verified");
                        report.put("fixtureResourcesVerified", passed);
                    }
                    report.put("fixtureVerified", passed);
                    if (!passed) throw new IllegalStateException("Fixture did not produce the expected SeedProbe.answer output");
                }
                JadxJobs.write(new File(working, "engine-report.json"), report);
                JadxJobs.remove(new File(working, ".runtime"));
                File result = new File(dir, "result");
                if (!working.renameTo(result)) throw new java.io.IOException("Unable to finalize completed result");
                JadxJobs.remove(new File(dir, "input"));
                store.job(id, label, report.getString("status"), report.getInt("classes") + " classes; " + report.getInt("errors") + " errors; " + report.getInt("warnings") + " warnings");
                store.event("jadx", id + ": " + report.getString("status") + " " + label);
            } catch (Throwable error) {
                Throwable cause = error instanceof java.lang.reflect.InvocationTargetException ? error.getCause() : error;
                String detail = cause.toString();
                try { JadxJobs.retainLogs(dir); }
                catch (Exception copyError) { detail += "\nLog retention failed: " + copyError; }
                JadxJobs.remove(working);
                JadxJobs.remove(new File(dir, "input"));
                JadxJobs.write(new File(dir, "failure.json"), new JSONObject().put("error", detail).put("stack", android.util.Log.getStackTraceString(cause)));
                store.job(id, label, "failed", detail);
                store.event("jadx", id + ": failed " + detail);
            }
        } catch (Exception failure) {
            android.util.Log.e("WorkshopJadx", "Job finalization failed", failure);
        } finally {
            for (String name : new String[] {"progress.json.part", "request.json.part", "worker.json.part"}) {
                try { JadxJobs.remove(new File(dir, name)); } catch (Exception error) { android.util.Log.e("WorkshopJadx", "Metadata cleanup failed", error); }
            }
            try { NativeJadx.cleanUnfinished(this); JadxJobs.remove(new File(getCodeCacheDir(), "jadx/engine.part")); } catch (Exception error) { android.util.Log.e("WorkshopJadx", "Engine cleanup failed", error); }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override public void onTimeout(int startId, int type) {
        try (WorkshopStore store = new WorkshopStore(this)) { store.event("jadx", active + ": Android stopped the foreground service"); }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
