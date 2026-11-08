package com.ayrvokan.wireless;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DiagnosticsActivity extends Activity {
    private static final AtomicBoolean exporting = new AtomicBoolean();
    private WorkshopStore store;
    private Spinner levels, modules;
    private EditText session, operation, search;
    private TextView count;
    private LinearLayout rows;
    private long before;
    private WorkshopStore.TraceFilter active = new WorkshopStore.TraceFilter();
    private JSONObject exportFilter;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new WorkshopStore(this);
        try {
            if (!exporting.get()) {
                File pending = new File(getFilesDir(), "diagnostics-export.json");
                if (pending.isFile()) { JSONObject p = new JSONObject(JadxJobs.read(pending)); if (!p.optBoolean("completed")) DocumentsContract.deleteDocument(getContentResolver(), android.net.Uri.parse(p.getString("uri"))); JadxJobs.remove(pending); }
                JadxJobs.remove(new File(getCacheDir(), "diagnostics-preview.zip.part"));
                JadxJobs.remove(new File(getFilesDir(), "diagnostics-export.json.part"));
            }
            if (saved != null) { active = WorkshopStore.TraceFilter.from(new JSONObject(saved.getString("filter", "{}"))); if (saved.containsKey("exportFilter")) exportFilter = new JSONObject(saved.getString("exportFilter")); }
            else { active.level = getIntent().getStringExtra("level") == null ? "" : getIntent().getStringExtra("level"); active.module = getIntent().getStringExtra("module") == null ? "" : getIntent().getStringExtra("module"); active.session = getIntent().getStringExtra("session") == null ? "" : getIntent().getStringExtra("session"); active.operation = getIntent().getStringExtra("operation") == null ? "" : getIntent().getStringExtra("operation"); active.query = getIntent().getStringExtra("query") == null ? "" : getIntent().getStringExtra("query"); }
            build(); reload(true);
            if (saved == null && "storage-check".equals(getIntent().getStringExtra("action"))) checkStorage();
            if (saved == null && "preview-export".equals(getIntent().getStringExtra("action"))) preview();
        } catch (Exception error) { problem(error); }
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(28, 24, 28, 32); body.setBackgroundColor(Color.rgb(12, 19, 29)); scroll.addView(body); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); }
            else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.requestApplyInsets();
        body.addView(text("Diagnostic logs", 26));
        body.addView(text("Follow operations, requests, observed state and failures.", 14));
        levels = new Spinner(this); setChoices(levels, java.util.Arrays.asList("All levels", "DEBUG", "INFO", "WARN", "ERROR"), active.level); body.addView(levels);
        modules = new Spinner(this); refreshModules(active.module); body.addView(modules);
        session = edit("Session ID · empty shows all sessions", active.session); body.addView(session);
        operation = edit("Operation ID · empty shows all operations", active.operation); body.addView(operation);
        search = edit("Search message, payload, device or request ID", active.query); body.addView(search);
        body.addView(button("Apply filters / refresh", () -> { active = filters(); refreshModules(active.module); reload(true); }));
        body.addView(button("Use current session", () -> { session.setText(store.sessionId()); active = filters(); reload(true); }));
        body.addView(button("New diagnostic session", () -> { session.setText(store.newSession()); active = filters(); reload(true); }));
        body.addView(button("Run storage check", this::checkStorage));
        body.addView(button("Export matching evidence ZIP", () -> {
            try { if (exporting.get()) throw new IllegalStateException("An export is running"); exportFilter = filters().json(); startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, "workshop-diagnostics.zip"), 52); }
            catch (Exception error) { problem(error); }
        }));
        count = text("", 15); body.addView(count);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); body.addView(rows);
        body.addView(button("Load older events", () -> reload(false)));
    }

    private void setChoices(Spinner spinner, List<String> values, String selected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        int index = values.indexOf(selected); spinner.setSelection(Math.max(index, 0));
    }

    private void refreshModules(String selected) {
        List<String> names = new ArrayList<>(); names.add("All modules"); names.addAll(store.traceValues("module"));
        if (!selected.isEmpty() && !names.contains(selected)) names.add(selected);
        setChoices(modules, names, selected);
    }

    private WorkshopStore.TraceFilter filters() {
        WorkshopStore.TraceFilter f = new WorkshopStore.TraceFilter();
        f.level = levels.getSelectedItemPosition() == 0 ? "" : levels.getSelectedItem().toString(); f.module = modules.getSelectedItemPosition() == 0 ? "" : modules.getSelectedItem().toString();
        f.session = session.getText().toString().trim(); f.operation = operation.getText().toString().trim(); f.query = search.getText().toString(); return f;
    }

    private void reload(boolean reset) {
        try {
            if (reset) { rows.removeAllViews(); before = 0; }
            JSONArray page = store.tracePage(active, before, 100);
            count.setText(store.traceCount(active) + " matching events");
            for (int i = 0; i < page.length(); i++) {
                JSONObject event = page.getJSONObject(i); before = event.getLong("id");
                TextView view = text(event.getString("level") + " · " + event.getString("module") + " · " + event.getString("phase") + "\n" + event.getString("message") + "\n" + event.getString("utc"), 14);
                view.setPadding(12, 22, 12, 22); view.setOnClickListener(v -> details(event)); rows.addView(view);
            }
            JSONObject snapshot = new JSONObject().put("filters", active.json()).put("matchingEvents", store.traceCount(active)).put("pageEvents", page.length()).put("oldestEventId", before).put("sessionId", store.sessionId());
            JadxJobs.write(new File(getFilesDir(), "diagnostics-state.json"), snapshot);
        } catch (Exception error) { problem(error); }
    }

    private void details(JSONObject event) {
        try {
            TextView value = text(event.toString(2), 13); value.setTextIsSelectable(true); ScrollView scroll = new ScrollView(this); scroll.addView(value);
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Event " + event.getLong("id")).setView(scroll).setPositiveButton("Close", null);
            if (!event.getString("operation_id").isEmpty()) dialog.setNeutralButton("Follow operation", (d, w) -> { levels.setSelection(0); refreshModules(""); session.setText(""); search.setText(""); operation.setText(event.optString("operation_id")); active = filters(); reload(true); });
            dialog.show();
        } catch (Exception error) { problem(error); }
    }

    private void checkStorage() {
        new Thread(() -> {
            try {
                JSONObject result = OperationLog.storageCheck(getApplicationContext()); JadxJobs.write(new File(getFilesDir(), "diagnostics-check.json"), result);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { refreshModules(active.module); reload(true); message("Storage check passed"); } });
            } catch (Exception error) { problem(error); }
        }, "diagnostic-storage-check").start();
    }

    private void preview() {
        if (!exporting.compareAndSet(false, true)) return;
        WorkshopStore.TraceFilter selected = active;
        new Thread(() -> {
            File part = new File(getCacheDir(), "diagnostics-preview.zip.part");
            try {
                JSONObject manifest;
                try (FileOutputStream output = new FileOutputStream(part)) { manifest = DiagnosticExport.write(getApplicationContext(), selected, output); }
                java.nio.file.Files.move(part.toPath(), new File(getCacheDir(), "diagnostics-preview.zip").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                JadxJobs.write(new File(getFilesDir(), "diagnostics-preview.json"), manifest);
            } catch (Exception error) { problem(error); }
            finally { try { JadxJobs.remove(part); } catch (Exception error) { problem(error); } exporting.set(false); }
        }, "diagnostic-preview-export").start();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 52 || result != RESULT_OK || data == null || data.getData() == null || exportFilter == null) return;
        android.net.Uri uri = data.getData(); WorkshopStore.TraceFilter selected = WorkshopStore.TraceFilter.from(exportFilter); exportFilter = null;
        if (!exporting.compareAndSet(false, true)) { message("An export is running"); return; }
        new Thread(() -> {
            File pending = new File(getFilesDir(), "diagnostics-export.json");
            try (OperationLog op = new OperationLog(getApplicationContext(), "diagnostics", "export", "", "document", new JSONObject())) {
                try {
                    JadxJobs.write(pending, new JSONObject().put("uri", uri.toString()).put("completed", false));
                    JSONObject manifest;
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) { manifest = DiagnosticExport.write(getApplicationContext(), selected, output); }
                    JadxJobs.write(pending, new JSONObject().put("uri", uri.toString()).put("completed", true));
                    op.finish(manifest); JadxJobs.remove(pending); message("Diagnostic evidence exported");
                } catch (Exception error) { op.fail(error); throw error; }
            } catch (Exception error) {
                try { if (!pending.isFile() || !new JSONObject(JadxJobs.read(pending)).optBoolean("completed")) DocumentsContract.deleteDocument(getContentResolver(), uri); JadxJobs.remove(pending); }
                catch (Exception cleanup) { problem(cleanup); }
                problem(error);
            } finally { try { JadxJobs.remove(new File(pending.getPath() + ".part")); } catch (Exception error) { problem(error); } exporting.set(false); }
        }, "diagnostic-evidence-export").start();
    }

    private TextView text(String value, int size) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(Color.rgb(234, 241, 248)); return v; }
    private EditText edit(String hint, String value) { EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint); e.setText(value); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.LTGRAY); return e; }
    private Button button(String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b; }
    private void message(String value) { runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_LONG).show()); }
    private void problem(Exception error) { android.util.Log.e("WorkshopDiagnostics", "Diagnostic operation failed", error); message(error.toString()); }
    @Override protected void onSaveInstanceState(Bundle state) { try { state.putString("filter", filters().json().toString()); if (exportFilter != null) state.putString("exportFilter", exportFilter.toString()); } catch (Exception error) { problem(error); } super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { if (store != null) store.close(); super.onDestroy(); }
}
