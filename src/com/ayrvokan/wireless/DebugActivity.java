package com.ayrvokan.wireless;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DebugActivity extends Activity {
    private EditText pid, mode, type, method, signature, location, line, address, depth, slot, value, memoryAddress, memoryBytes;
    private TextView status, observed;
    private LinearLayout processes, history;
    private String selected = "", lastHistory = "";
    private final Handler handler = new Handler();
    private boolean resumed;
    private volatile boolean loading;
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); if (resumed) handler.postDelayed(this, 600); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(24, 16, 24, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> { view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        text(body, "App debugger", 25);
        text(body, "ART/JVMTI Java breakpoints and bundled LLDB native debugging. This screen runs in its own process. Runtime DEX locations, source lines and native virtual addresses are shown separately.", 14);
        processes = new LinearLayout(this); processes.setOrientation(1); body.addView(processes);
        button(body, "Refresh app processes", this::processes);
        button(body, "Start probe worker", () -> startForegroundService(new Intent(this, DebugProbeService.class)));
        button(body, "Java breakpoint proof", () -> work(() -> proof("java")));
        button(body, "Native breakpoint proof", () -> work(() -> proof("native")));
        pid = field(body, "Target PID", "", 1); mode = field(body, "Backend: java or native", "java", 1);
        type = field(body, "Java class", "com.ayrvokan.wireless.DebugProbe", 1);
        method = field(body, "Method", "mark", 1); signature = field(body, "JVM method descriptor", "(I)I", 1);
        location = field(body, "ART DEX code-unit offset (-1 = method entry)", "-1", 1);
        line = field(body, "Source line (0 = use offset)", "0", 1);
        address = field(body, "Native breakpoint address (hex; blank = attach only)", "", 1);
        button(body, "Attach / arm breakpoint", () -> {
            try { JSONObject request = new JSONObject().put("targetPid", Integer.parseInt(pid.getText().toString())).put("mode", mode.getText().toString().trim()).put("class", type.getText().toString()).put("method", method.getText().toString()).put("signature", signature.getText().toString()).put("location", Long.parseLong(location.getText().toString())).put("line", Integer.parseInt(line.getText().toString())).put("address", address.getText().toString()); work(() -> start(request)); }
            catch (Exception error) { status.setText(error.toString()); }
        });
        button(body, "Continue", () -> control("resume", new JSONObject()));
        button(body, "Single step", () -> control("step", new JSONObject()));
        button(body, "Inspect stopped target", () -> control("inspect", new JSONObject()));
        button(body, "Interrupt native target", () -> control("interrupt", new JSONObject()));
        button(body, "Detach / end session", () -> control("detach", new JSONObject()));
        depth = field(body, "Java frame depth", "0", 1); slot = field(body, "Local-variable slot", "0", 1); value = field(body, "New int value", "84", 1);
        button(body, "Set Java int local", () -> { try { control("set-int", new JSONObject().put("depth", Integer.parseInt(depth.getText().toString())).put("slot", Integer.parseInt(slot.getText().toString())).put("value", Integer.parseInt(value.getText().toString()))); } catch (Exception error) { status.setText(error.toString()); } });
        memoryAddress = field(body, "Native memory address (hex)", "", 1); memoryBytes = field(body, "Read byte count / write hex bytes", "4", 1);
        button(body, "Read native memory", () -> { try { control("read-memory", new JSONObject().put("address", memoryAddress.getText().toString()).put("bytes", Integer.parseInt(memoryBytes.getText().toString()))); } catch (Exception error) { status.setText(error.toString()); } });
        button(body, "Write native memory", () -> { try { control("write-memory", new JSONObject().put("address", memoryAddress.getText().toString()).put("data", memoryBytes.getText().toString())); } catch (Exception error) { status.setText(error.toString()); } });
        button(body, "Add native breakpoint", () -> { try { control("add-breakpoint", new JSONObject().put("address", address.getText().toString())); } catch (Exception error) { status.setText(error.toString()); } });
        button(body, "Remove native breakpoint", () -> { try { control("remove-breakpoint", new JSONObject().put("address", address.getText().toString())); } catch (Exception error) { status.setText(error.toString()); } });
        button(body, "Session diagnostic logs / export", () -> startActivity(new Intent(this, DiagnosticsActivity.class).putExtra("operation", selected)));
        status = text(body, "", 14); observed = text(body, "", 12); observed.setTypeface(android.graphics.Typeface.MONOSPACE); observed.setTextIsSelectable(true);
        text(body, "Session history", 18); history = new LinearLayout(this); history.setOrientation(1); body.addView(history);
        if (state != null) selected = state.getString("selected", "");
        processes(); handle(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }
    @Override public void onResume() { super.onResume(); resumed = true; handler.post(tick); }
    @Override public void onPause() { resumed = false; handler.removeCallbacks(tick); super.onPause(); }
    @Override public void onSaveInstanceState(Bundle state) { state.putString("selected", selected); super.onSaveInstanceState(state); }

    private void processes() {
        processes.removeAllViews(); ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> entries = manager.getRunningAppProcesses();
        if (entries != null) for (ActivityManager.RunningAppProcessInfo p : entries) if (p.uid == android.os.Process.myUid() && p.processName.startsWith(getPackageName()) && p.pid != android.os.Process.myPid()) button(processes, p.pid + " • " + p.processName, () -> pid.setText(Integer.toString(p.pid)));
    }
    private void proof(String backend) throws Exception {
        DebugJobs.recover(this); DebugJobs.awaitAvailable(this);
        String token = java.util.UUID.randomUUID().toString();
        startForegroundService(new Intent(this, DebugProbeService.class).putExtra("token", token));
        long started = android.os.SystemClock.elapsedRealtime();
        JSONObject probe = null;
        while (probe == null) {
            File file = new File(getFilesDir(), "debug-probe.json");
            if (file.isFile()) { JSONObject candidate = new JSONObject(JadxJobs.read(file)); if (token.equals(candidate.optString("token")) && DebugJobs.alive(this, candidate.getInt("pid")) && TerminalJobs.ticks(candidate.getInt("pid")).equals(candidate.getString("startTicks"))) probe = candidate; }
            File error = new File(getFilesDir(), "debug-probe-error.json"); if (error.isFile()) { JSONObject failure = new JSONObject(JadxJobs.read(error)); if (token.equals(failure.optString("token"))) throw new IllegalStateException(failure.getString("error")); }
            if (android.os.SystemClock.elapsedRealtime() - started > 15000) throw new java.io.IOException("Probe worker did not finish startup");
            if (probe == null) Thread.sleep(60);
        }
        start(new JSONObject().put("targetPid", probe.getInt("pid")).put("mode", backend).put("class", "com.ayrvokan.wireless.DebugProbe").put("method", "mark").put("signature", "(I)I").put("location", -1).put("line", 0).put("fixture", true).put("address", backend.equals("native") ? probe.getString("breakpointAddress") : "").put("variableAddress", backend.equals("native") ? probe.getString("variableAddress") : ""));
    }
    private void start(JSONObject request) throws Exception {
        if (!request.getString("mode").equals("java") && !request.getString("mode").equals("native")) throw new IllegalArgumentException("Backend must be java or native");
        selected = DebugJobs.start(this, request);
        JadxJobs.write(new File(getFilesDir(), "debugger-state.json"), new JSONObject().put("jobId", selected).put("request", request));
    }
    private void control(String action, JSONObject values) { work(() -> { if (selected == null || selected.isEmpty()) throw new IllegalStateException("Select a session first"); DebugJobs.action(this, selected, action, values); }); }
    private void handle(Intent intent) {
        String action = intent.getStringExtra("action"); if (action == null) return; intent.removeExtra("action");
        work(() -> {
            DebugJobs.recover(this);
            if (action.equals("java-proof") || action.equals("native-proof")) proof(action.startsWith("java") ? "java" : "native");
            else if (action.equals("start")) start(new JSONObject(intent.getStringExtra("request")));
            else if (!action.equals("recover")) { selected = intent.getStringExtra("id"); DebugJobs.action(this, selected, action, new JSONObject(intent.hasExtra("values") ? intent.getStringExtra("values") : "{}")); }
        });
    }
    private void refresh() {
        if (loading) return; loading = true;
        new Thread(() -> {
            try {
                DebugJobs.recover(this); JSONArray jobs; try (WorkshopStore store = new WorkshopStore(this)) { jobs = store.jobs("debug"); }
                if (selected.isEmpty() && jobs.length() > 0) selected = jobs.getJSONObject(0).getString("id");
                String detail = "No session selected", text = "";
                for (int i = 0; i < jobs.length(); i++) { JSONObject job = jobs.getJSONObject(i); if (job.getString("id").equals(selected)) detail = selected + "\n" + job.getString("state") + " • " + job.getString("detail"); }
                if (!selected.isEmpty()) {
                    File dir = DebugJobs.directory(this, selected); File file = new File(dir, "state.json"); if (file.isFile()) text = new JSONObject(JadxJobs.read(file)).toString(2);
                    File failure = new File(dir, "failure.json"); if (failure.isFile()) text += "\n" + JadxJobs.read(failure);
                    File report = new File(dir, "report.json"); if (report.isFile()) text += "\n" + JadxJobs.read(report);
                    File result = new File(dir, "probe-result.json"); if (result.isFile()) text += "\n" + JadxJobs.read(result);
                }
                String shown = detail, snapshot = text;
                runOnUiThread(() -> {
                    status.setText(shown); observed.setText(snapshot);
                    if (!lastHistory.equals(jobs.toString())) { lastHistory = jobs.toString(); history.removeAllViews(); for (int i = 0; i < jobs.length(); i++) try { JSONObject job = jobs.getJSONObject(i); String id = job.getString("id"); button(history, job.getString("state") + " • " + job.getString("label"), () -> { selected = id; refresh(); }); } catch (Exception error) { status.setText(error.toString()); } }
                });
            } catch (Exception error) { runOnUiThread(() -> status.setText(error.toString())); }
            finally { loading = false; }
        }, "debugger-view").start();
    }
    private interface Work { void run() throws Exception; }
    private void work(Work work) { new Thread(() -> { try { work.run(); refresh(); } catch (Exception error) { try { JadxJobs.write(new File(getFilesDir(), "debugger-state.json"), new JSONObject().put("error", error.toString())); } catch (Exception failure) { android.util.Log.e("WorkshopDebug", "State failed", failure); } runOnUiThread(() -> status.setText(error.toString())); } }, "debugger-control").start(); }
    private TextView text(LinearLayout parent, String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, 8, 0, 8); parent.addView(view); return view; }
    private EditText field(LinearLayout parent, String label, String value, int lines) { text(parent, label, 14); EditText view = new EditText(this); view.setId(3000 + parent.getChildCount()); view.setText(value); view.setSingleLine(lines == 1); view.setMinLines(lines); parent.addView(view); return view; }
    private void button(LinearLayout parent, String label, Runnable action) { Button view = new Button(this); view.setText(label); view.setOnClickListener(v -> action.run()); parent.addView(view); }
}
