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
import java.net.URL;
import java.util.Enumeration;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;

public final class JadxService extends Service {
    private String active;

    private static final class EngineLoader extends DexClassLoader {
        EngineLoader(File file, File cache, ClassLoader parent) {
            super(file.getPath(), cache.getPath(), null, parent);
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") || name.startsWith("dalvik.") || name.startsWith("org.json.") || name.startsWith("org.xml.") || name.startsWith("org.w3c.")) return super.loadClass(name, resolve);
            synchronized (this) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try { loaded = findClass(name); }
                    catch (ClassNotFoundException error) { loaded = super.loadClass(name, false); }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override public URL getResource(String name) {
            URL own = findResource(name);
            return own == null ? super.getResource(name) : own;
        }

        @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
            if (name.startsWith("META-INF/services/")) return findResources(name);
            return super.getResources(name);
        }
    }

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

    private File engine() throws Exception {
        JSONObject seed;
        try (InputStream input = getAssets().open("workshop/jadx/tool.json"); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            JadxJobs.copy(input, output);
            seed = new JSONObject(output.toString("UTF-8"));
        }
        File cache = new File(getCodeCacheDir(), "jadx");
        cache.mkdirs();
        File target = new File(cache, seed.getString("engineSha256") + ".apk");
        if (!target.exists()) {
            File part = new File(cache, "engine.part");
            JadxJobs.remove(part);
            try (FileOutputStream output = new FileOutputStream(part); InputStream input = getAssets().open("workshop/jadx/engine.apk")) {
                if (!part.setReadOnly()) throw new java.io.IOException("Unable to prepare engine code file");
                JadxJobs.copy(input, output);
                output.getFD().sync();
            }
            if (!JadxJobs.hash(part).equals(seed.getString("engineSha256"))) throw new java.io.IOException("Bundled engine digest mismatch");
            if (!part.renameTo(target)) throw new java.io.IOException("Unable to finalize engine code file");
        }
        File[] old = cache.listFiles();
        if (old != null) for (File file : old) if (!file.equals(target)) JadxJobs.remove(file);
        return target;
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
                if (source.startsWith("asset:")) {
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
                File code = engine();
                EngineLoader loader = new EngineLoader(code, getCodeCacheDir(), getClassLoader());
                Thread.currentThread().setContextClassLoader(loader);
                Class<?> entry = loader.loadClass("com.ayrvokan.jadxengine.Runner");
                String reportText = (String) entry.getMethod("run", String.class, String.class, String.class).invoke(null, new File(dir, "request.json").getPath(), working.getPath(), new File(dir, "progress.json").getPath());
                JSONObject report = new JSONObject(reportText);
                report.put("inputSha256", request.getString("inputSha256"));
                report.put("bundledEngineSha256", JadxJobs.hash(code));
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
                for (String log : new String[] {"jadx.log", "console.log"}) {
                    if (new File(working, log).isFile()) {
                        try { java.nio.file.Files.copy(new File(working, log).toPath(), new File(dir, log).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                        catch (Exception copyError) { detail += "\nLog retention failed: " + copyError; }
                    }
                }
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
            try { JadxJobs.remove(new File(getCodeCacheDir(), "jadx/engine.part")); } catch (Exception error) { android.util.Log.e("WorkshopJadx", "Engine cleanup failed", error); }
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
