package com.ayrvokan.wireless;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

final class JobDiagnostics implements AutoCloseable {
    private final WorkshopStore store;
    private final String id;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread monitor;

    JobDiagnostics(WorkshopStore store, String id, File dir) throws Exception {
        this.store = store; this.id = id;
        store.jobTrace(id, "INFO", "bridge.call", "Invoke bundled JADX through JNI", new JSONObject().put("requestFile", "request.json"));
        monitor = new Thread(() -> {
            String last = ""; boolean runtime = false;
            while (running.get()) {
                try {
                    File start = new File(dir, "working/runtime-start.json");
                    if (!runtime && start.isFile()) { JSONObject value = new JSONObject(JadxJobs.read(start)); store.jobTrace(id, "INFO", "native.start", "Native JVM started", value); runtime = true; }
                    File progress = new File(dir, "progress.json");
                    if (progress.isFile()) { String value = JadxJobs.read(progress); if (!last.equals(value)) { JSONObject payload = new JSONObject(value); store.jobTrace(id, "DEBUG", "progress", payload.optString("phase"), payload); last = value; } }
                    Thread.sleep(1000);
                } catch (InterruptedException done) { Thread.currentThread().interrupt(); break; }
                catch (Exception error) { android.util.Log.e("WorkshopDiagnostics", "Unable to record JADX progress", error); break; }
            }
        }, "jadx-diagnostic-log");
        monitor.start();
    }

    void returned(JSONObject report) throws Exception {
        JSONObject value = new JSONObject();
        for (String key : new String[] {"runtimeBackend", "runtime", "workerPid", "status", "classes", "inputClassDefinitions", "visitedDexClassDefinitions", "exportedClasses", "resources", "errors", "warnings", "elapsedMillis", "staging"}) if (report.has(key)) value.put(key, report.get(key));
        store.jobTrace(id, report.optInt("errors") > 0 ? "WARN" : "INFO", "bridge.return", "Bundled JADX returned", value);
    }

    void failed(Throwable error) throws Exception {
        store.jobTrace(id, "ERROR", "bridge.error", error.toString(), new JSONObject().put("exception", error.getClass().getName()).put("stack", android.util.Log.getStackTraceString(error)));
    }

    @Override public void close() throws InterruptedException { running.set(false); monitor.interrupt(); monitor.join(); }
}
