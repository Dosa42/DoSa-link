package com.ayrvokan.wireless;

import android.app.Activity;
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

public final class TerminalActivity extends Activity {
    private EditText command, cwd, environment, input;
    private TextView status, stdout, stderr;
    private LinearLayout history;
    private String selected = "", historyState = "";
    private final Handler handler = new Handler();
    private boolean resumed, loading;
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); if (resumed) handler.postDelayed(this, 1000); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(20, 16, 20, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        text(body, "Local terminal", 24);
        text(body, "Bundled mksh • app UID " + android.os.Process.myUid() + " • Android utilities in /system/bin\nCommands use pipes, redirection and scripts. Output is streamed to files. This command console does not emulate a PTY or full-screen terminal.", 14);
        cwd = field(body, "Working directory", TerminalJobs.workspace(this).getPath(), 1);
        environment = field(body, "Environment overrides (JSON)", "{}", 2);
        command = field(body, "Command / script", "printf '%s\\n' \"$KSH_VERSION\"; pwd", 5);
        button(body, "Run command", () -> run(() -> start(command.getText().toString(), cwd.getText().toString(), new JSONObject(environment.getText().toString()))));
        input = field(body, "Standard input", "", 2);
        button(body, "Send input + newline", () -> run(() -> control("input", input.getText().toString() + "\n")));
        button(body, "Close stdin (EOF)", () -> run(() -> control("eof", "")));
        button(body, "Stop command and child processes", () -> run(() -> control("stop", "")));
        button(body, "Open files", () -> startActivity(new Intent(this, FilesActivity.class).putExtra("path", cwd.getText().toString())));
        button(body, "Diagnostic logs for this command", () -> startActivity(new Intent(this, DiagnosticsActivity.class).putExtra("operation", selected)));
        status = text(body, "", 15);
        text(body, "stdout", 18); stdout = text(body, "", 13); stdout.setTypeface(android.graphics.Typeface.MONOSPACE); stdout.setTextIsSelectable(true);
        text(body, "stderr", 18); stderr = text(body, "", 13); stderr.setTypeface(android.graphics.Typeface.MONOSPACE); stderr.setTextIsSelectable(true);
        text(body, "Command history", 18); history = new LinearLayout(this); history.setOrientation(1); body.addView(history);
        try {
            File draft = new File(getFilesDir(), "terminal-draft.json");
            if (draft.isFile()) { JSONObject d = new JSONObject(JadxJobs.read(draft)); command.setText(d.optString("command")); cwd.setText(d.optString("cwd", cwd.getText().toString())); environment.setText(d.optString("environment", "{}")); selected = d.optString("selected"); }
        } catch (Exception error) { status.setText(error.toString()); }
        handle(getIntent());
    }

    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }
    @Override public void onResume() { super.onResume(); resumed = true; handler.post(tick); }
    @Override public void onPause() {
        resumed = false; handler.removeCallbacks(tick);
        try { JadxJobs.write(new File(getFilesDir(), "terminal-draft.json"), new JSONObject().put("command", command.getText().toString()).put("cwd", cwd.getText().toString()).put("environment", environment.getText().toString()).put("selected", selected)); } catch (Exception error) { android.util.Log.e("WorkshopTerminal", "Draft save failed", error); }
        super.onPause();
    }

    private void handle(Intent intent) {
        String action = intent.getStringExtra("action");
        if (action == null) return;
        intent.removeExtra("action");
        run(() -> {
            TerminalJobs.recover(this);
            if ("run".equals(action)) start(intent.getStringExtra("command"), intent.hasExtra("cwd") ? intent.getStringExtra("cwd") : TerminalJobs.workspace(this).getPath(), new JSONObject(intent.hasExtra("environment") ? intent.getStringExtra("environment") : "{}"));
            else if (!"recover".equals(action)) { selected = intent.getStringExtra("id"); control(action, intent.getStringExtra("text")); }
        });
    }

    private void start(String script, String directory, JSONObject env) throws Exception {
        TerminalJobs.recover(this);
        selected = TerminalJobs.start(this, script, directory, env);
        JadxJobs.write(new File(getFilesDir(), "terminal-state.json"), new JSONObject().put("jobId", selected).put("action", "run"));
    }

    private void control(String action, String text) throws Exception {
        if (selected == null || selected.isEmpty()) throw new IllegalStateException("Select a command first");
        TerminalJobs.action(this, selected, action, text == null ? "" : text);
        JadxJobs.write(new File(getFilesDir(), "terminal-state.json"), new JSONObject().put("jobId", selected).put("action", action));
    }

    private void refresh() {
        if (loading) return; loading = true;
        new Thread(() -> {
            try {
                TerminalJobs.recover(this);
                JSONArray jobs;
                try (WorkshopStore store = new WorkshopStore(this)) { jobs = store.jobs("terminal"); }
                if (selected.isEmpty() && jobs.length() > 0) selected = jobs.getJSONObject(0).getString("id");
                String id = selected, details = "No command selected", out = "", err = "";
                for (int i = 0; i < jobs.length(); i++) { JSONObject job = jobs.getJSONObject(i); if (job.getString("id").equals(id)) details = id + "\n" + job.getString("state") + " • " + job.getString("detail"); }
                if (!id.isEmpty()) { File dir = TerminalJobs.directory(this, id); out = TerminalJobs.tail(new File(dir, "stdout.log")); err = TerminalJobs.tail(new File(dir, "stderr.log")); }
                String shown = details, output = out, errors = err;
                runOnUiThread(() -> {
                    status.setText(shown); stdout.setText(output); stderr.setText(errors);
                    if (!historyState.equals(jobs.toString())) {
                        historyState = jobs.toString(); history.removeAllViews();
                        for (int i = 0; i < jobs.length(); i++) try { JSONObject job = jobs.getJSONObject(i); String jobId = job.getString("id"); button(history, job.getString("state") + " • " + job.getString("label"), () -> { selected = jobId; refresh(); }); } catch (Exception error) { status.setText(error.toString()); }
                    }
                });
            } catch (Exception error) { runOnUiThread(() -> status.setText(error.toString())); }
            finally { loading = false; }
        }, "terminal-view").start();
    }

    private interface Work { void run() throws Exception; }
    private void run(Work work) { new Thread(() -> { try { work.run(); refresh(); } catch (Exception error) { try { JadxJobs.write(new File(getFilesDir(), "terminal-state.json"), new JSONObject().put("error", error.toString())); } catch (Exception ignored) { android.util.Log.e("WorkshopTerminal", "State save failed", ignored); } runOnUiThread(() -> status.setText(error.toString())); } }, "terminal-action").start(); }
    private TextView text(LinearLayout parent, String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, 8, 0, 8); parent.addView(view); return view; }
    private EditText field(LinearLayout parent, String label, String value, int lines) { text(parent, label, 14); EditText view = new EditText(this); view.setId(2000 + parent.getChildCount()); view.setText(value); view.setMinLines(lines); view.setSingleLine(lines == 1); parent.addView(view); return view; }
    private void button(LinearLayout parent, String label, Runnable action) { Button view = new Button(this); view.setText(label); view.setOnClickListener(v -> action.run()); parent.addView(view); }
}
