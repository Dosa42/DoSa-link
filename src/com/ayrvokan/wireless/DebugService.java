package com.ayrvokan.wireless;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class DebugService extends Service {
    public static volatile boolean busy;
    private String active;
    private volatile NativeDebugger nativeDebugger;
    private final LinkedBlockingQueue<JSONObject> commands = new LinkedBlockingQueue<>();

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getStringExtra("action"), id = intent.getStringExtra("id");
        if (!"start".equals(action)) {
            if (id != null && id.equals(active)) {
                try {
                    JSONObject command = new JSONObject(intent.hasExtra("values") ? intent.getStringExtra("values") : "{}").put("action", action);
                    queue(command);
                } catch (Exception error) { android.util.Log.e("WorkshopDebug", "Control failed", error); }
            } else if (active == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (active != null) return START_NOT_STICKY;
        active = id; busy = true;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("debug-jobs", "Debugger sessions", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 24, new Intent(this, DebugActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(24, new Notification.Builder(this, "debug-jobs").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("App debugger").setContentText("Debug session active. Open to inspect, resume or detach.").setContentIntent(open).setOngoing(true).build());
        new Thread(() -> execute(id), "debugger-session").start();
        return START_NOT_STICKY;
    }

    private void queue(JSONObject command) {
        String action = command.optString("action");
        if (nativeDebugger != null && (action.equals("interrupt") || action.equals("detach"))) {
            new Thread(() -> {
                try { NativeDebugger debugger = nativeDebugger; if (debugger != null) debugger.interrupt(); }
                catch (Exception error) { android.util.Log.e("WorkshopDebug", "Native interrupt failed", error); }
                finally { commands.offer(command); }
            }, "debugger-interrupt").start();
        } else commands.offer(command);
    }
    private void event(File dir, String phase, String message, JSONObject payload) throws Exception {
        JSONObject row = new JSONObject().put("recordedAt", System.currentTimeMillis()).put("phase", phase).put("message", message).put("payload", payload);
        try (java.io.FileWriter out = new java.io.FileWriter(new File(dir, "controller-events.jsonl"), true)) { out.write(row.toString() + "\n"); }
    }
    private void record(String id, JSONObject snapshot) throws Exception {
        event(DebugJobs.directory(this, id), snapshot.optString("phase", "snapshot"), "Debug target observed", snapshot);
    }
    private void flush(WorkshopStore store, String id, File dir) throws Exception {
        File file = new File(dir, "controller-events.jsonl"); if (!file.isFile()) return;
        String session; long started;
        try (android.database.Cursor cursor = store.getReadableDatabase().rawQuery("SELECT session_id,started_at FROM jobs WHERE id=?", new String[] {id})) { cursor.moveToFirst(); session = cursor.getString(0); started = cursor.getLong(1); }
        try (java.io.BufferedReader input = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = input.readLine()) != null) { JSONObject row = new JSONObject(line); long time = row.getLong("recordedAt"); store.traceAt(time, "INFO", "debug", row.getString("phase"), session, id, id + "-debug", "", "debugger", row.getString("message"), row.getJSONObject("payload"), time - started); }
        }
    }

    private void execute(String id) {
        File dir = DebugJobs.directory(this, id); JSONObject request = null; String label = id;
        try (WorkshopStore store = new WorkshopStore(this)) {
            try {
                request = new JSONObject(JadxJobs.read(new File(dir, "request.json")));
                int pid = request.getInt("targetPid"); String mode = request.getString("mode"); label = mode + " • PID " + pid;
                if (!TerminalJobs.ticks(pid).equals(request.getString("targetStartTicks"))) throw new IllegalStateException("The selected target process changed");
                JadxJobs.write(new File(dir, "worker.json"), new JSONObject().put("pid", android.os.Process.myPid()).put("startTicks", TerminalJobs.ticks(android.os.Process.myPid())));
                store.job("debug", id, label, "running", "Attaching " + mode + " debugger");
                request.put("controllerPid", android.os.Process.myPid()).put("controllerStartTicks", TerminalJobs.ticks(android.os.Process.myPid())); JadxJobs.write(new File(dir, "request.json"), request);
                JadxJobs.write(new File(getFilesDir(), "debugger-live.json"), new JSONObject().put("id", id).put("pid", android.os.Process.myPid()).put("startTicks", TerminalJobs.ticks(android.os.Process.myPid())));
                if (mode.equals("java")) {
                    DebugJobs.send(this, id, pid, "arm", new JSONObject());
                    String last = ""; boolean ending = false;
                    for (;;) {
                        JSONObject command = commands.poll(150, TimeUnit.MILLISECONDS);
                        if (command != null) {
                            String action = command.getString("action");
                            if (action.equals("interrupt")) { event(dir, "control.unavailable", "Java uses configured breakpoints; native backend supports process-wide Interrupt", command); continue; }
                            DebugJobs.send(this, id, pid, action, command); ending = action.equals("detach");
                            event(dir, "control.request", action, command);
                        }
                        File error = new File(dir, "endpoint-error.json"); if (error.isFile()) throw new IllegalStateException(JadxJobs.read(error));
                        File state = new File(dir, "state.json");
                        if (state.isFile()) {
                            String text = JadxJobs.read(state);
                            if (!text.equals(last)) {
                                last = text; JSONObject snapshot = new JSONObject(text); record(id, snapshot);
                                String phase = snapshot.getString("phase");
                                if (phase.equals("error") || phase.equals("controller-gone")) throw new IllegalStateException(snapshot.toString());
                                if (phase.equals("detached")) { break; }
                            }
                        }
                        if (!DebugJobs.alive(this, pid)) throw new IllegalStateException(ending ? "Target exited during detach" : "Target process exited");
                    }
                } else {
                    NativeDebugger debugger = new NativeDebugger(this, pid, dir); nativeDebugger = debugger;
                    try {
                        if (!request.optString("address").isEmpty()) debugger.breakpoint(NativeDebugger.address(request.getString("address")), true);
                        JSONObject snapshot = debugger.snapshot(); JadxJobs.write(new File(dir, "state.json"), snapshot); record(id, snapshot);
                        if (request.optBoolean("fixture")) {
                            DebugJobs.send(this, id, pid, "probe", new JSONObject().put("mode", "native"));
                            running(id, dir); debugger.resume(false);
                            snapshot = debugger.snapshot(); annotate(request, debugger, snapshot); JadxJobs.write(new File(dir, "state.json"), snapshot); record(id, snapshot);
                        }
                        for (;;) {
                            JSONObject command = commands.take(); String action = command.getString("action");
                            event(dir, "control.request", action, command);
                            if (action.equals("detach")) { debugger.detach(); break; }
                            if (action.equals("resume") || action.equals("step")) { running(id, dir); debugger.resume(action.equals("step")); }
                            if (action.equals("add-breakpoint") || action.equals("remove-breakpoint")) debugger.breakpoint(NativeDebugger.address(command.getString("address")), action.equals("add-breakpoint"));
                            JSONObject observed = debugger.snapshot();
                            if (action.equals("read-memory")) observed.put("memory", new JSONObject().put("address", command.getString("address")).put("bytes", debugger.memory(NativeDebugger.address(command.getString("address")), command.getInt("bytes"))));
                            if (action.equals("write-memory")) observed.put("memory", new JSONObject().put("address", command.getString("address")).put("bytes", debugger.writeMemory(NativeDebugger.address(command.getString("address")), command.getString("data"))));
                            annotate(request, debugger, observed); JadxJobs.write(new File(dir, "state.json"), observed); record(id, observed);
                        }
                    } finally { nativeDebugger = null; debugger.close(); }
                }
                JSONObject report = new JSONObject().put("status", "completed").put("mode", mode).put("targetPid", pid).put("targetResumedOnDetach", true).put("completedUtc", java.time.Instant.now().toString());
                File result = new File(dir, "probe-result.json"); if (result.isFile()) report.put("probe", new JSONObject(JadxJobs.read(result)));
                JadxJobs.write(new File(dir, "report.json"), report);
                flush(store, id, dir);
                store.job("debug", id, label, "completed", "Detached; target resumed; observations retained");
            } catch (Throwable error) {
                if (request != null && request.optString("mode").equals("java")) try { DebugJobs.send(this, id, request.getInt("targetPid"), "detach", new JSONObject()); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                JadxJobs.write(new File(dir, "failure.json"), new JSONObject().put("error", error.toString()).put("stack", android.util.Log.getStackTraceString(error)));
                store.job("debug", id, label, "failed", error.toString());
            }
        } catch (Exception error) { android.util.Log.e("WorkshopDebug", "Debug finalization failed", error); }
        finally {
            if (request != null) try { DebugJobs.stopProbe(this, request); } catch (Exception error) { android.util.Log.e("WorkshopDebug", "Probe cleanup failed", error); }
            for (String name : new String[] {"request.json.part", "worker.json.part", "state.json.part", "report.json.part", "failure.json.part"}) new File(dir, name).delete();
            new File(getFilesDir(), "debugger-live.json").delete();
            active = null; commands.clear(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); busy = false;
        }
    }

    private void running(String id, File dir) throws Exception {
        JadxJobs.write(new File(dir, "state.json"), new JSONObject().put("backend", "lldb-server/GDB remote").put("phase", "running"));
        event(dir, "running", "Native target running; Interrupt remains available", new JSONObject());
    }
    private void annotate(JSONObject request, NativeDebugger debugger, JSONObject snapshot) throws Exception {
        if (!request.optString("variableAddress").isEmpty()) { String bytes = debugger.memory(NativeDebugger.address(request.getString("variableAddress")), 4); snapshot.put("variable", new JSONObject().put("address", request.getString("variableAddress")).put("type", "volatile int32").put("bytesLittleEndian", bytes)); }
    }
    @Override public void onTimeout(int startId, int type) {
        try { queue(new JSONObject().put("action", "detach")); }
        catch (Exception error) { android.util.Log.e("WorkshopDebug", "Timeout detach failed", error); }
    }
}
