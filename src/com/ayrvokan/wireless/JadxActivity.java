package com.ayrvokan.wireless;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public final class JadxActivity extends Activity {
    private static final int BG = Color.rgb(12, 19, 29);
    private static final int TEXT = Color.rgb(234, 241, 248);
    private static final int ACCENT = Color.rgb(110, 218, 196);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WorkshopStore store;
    private LinearLayout history;
    private TextView selected;
    private EditText className;
    private EditText advanced;
    private EditText vmOptions;
    private String source = "asset:workshop/jadx/fixtures/fixture.apk";
    private String label = "fixture.apk";
    private String exporting;
    private boolean active;
    private static volatile boolean exportInProgress;
    private String lastHistory = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderHistory();
            if (active) handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new WorkshopStore(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(24, 24, 24, 24);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(24 + insets.getSystemWindowInsetLeft(), 24 + insets.getSystemWindowInsetTop(), 24 + insets.getSystemWindowInsetRight(), 24 + insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("JADX HEADLESS", 24, ACCENT));
        root.addView(text("Embedded JVM · staged APK, DEX, Java and smali", 15, TEXT));
        try {
            java.io.ByteArrayOutputStream value = new java.io.ByteArrayOutputStream();
            try (InputStream input = getAssets().open("workshop/jadx/tool.json")) { JadxJobs.copy(input, value); }
            JSONObject tool = new JSONObject(value.toString("UTF-8"));
            root.addView(text("JADX " + tool.getString("version") + " · " + tool.getInt("dependencyCount") + " bundled dependency artifacts", 14, TEXT));
        } catch (Exception error) { root.addView(text(error.toString(), 14, TEXT)); }
        selected = text(label, 18, ACCENT);
        root.addView(selected);
        root.addView(button("Import APK / DEX / JAR / smali", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, 31);
        }));
        LinearLayout samples = new LinearLayout(this);
        for (String type : new String[] {"apk", "dex", "java", "smali"}) samples.addView(button(type.toUpperCase(java.util.Locale.ROOT) + " sample", () -> fixture(type)), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(samples);
        root.addView(button("Use this installed APK", () -> {
            source = getApplicationInfo().sourceDir;
            label = "wireless-workshop.apk";
            selected.setText(label);
        }));
        root.addView(button("Choose DEX from this APK", () -> {
            try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(getApplicationInfo().sourceDir)) {
                java.util.List<String> names = new java.util.ArrayList<>();
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = archive.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.matches("classes[0-9]*\\.dex")) names.add(name);
                }
                java.util.Collections.sort(names);
                new AlertDialog.Builder(this).setTitle("Choose a DEX member").setItems(names.toArray(new String[0]), (dialog, index) -> {
                    source = "apk-dex:" + names.get(index);
                    label = names.get(index);
                    selected.setText("This APK / " + label);
                }).setNegativeButton("Close", null).show();
            } catch (Exception error) { message(error.toString()); }
        }));
        root.addView(text("Class filter (optional; empty exports all classes)", 15, TEXT));
        className = edit("com.example.ClassName");
        root.addView(className);
        root.addView(text("Advanced JADX options (input and output paths are managed by the app)", 15, TEXT));
        advanced = edit("For example: --show-bad-code");
        root.addView(advanced);
        root.addView(text("JVM options (optional; empty uses the bundled JVM defaults)", 15, TEXT));
        vmOptions = edit("For example: -Xmx3g");
        root.addView(vmOptions);
        root.addView(button("Run JADX", this::runJob));
        root.addView(button("Refresh jobs", () -> { lastHistory = ""; renderHistory(); }));
        history = column();
        root.addView(history);
        root.addView(button("Back to workspace", this::finish));
        if (saved != null) {
            source = saved.getString("source", source);
            label = saved.getString("label", label);
            className.setText(saved.getString("className", ""));
            advanced.setText(saved.getString("advanced", ""));
            vmOptions.setText(saved.getString("vmOptions", ""));
            exporting = saved.getString("exporting");
            selected.setText(label);
        }
        new Thread(() -> {
            try {
                JadxJobs.recover(getApplicationContext());
                File pending = new File(getFilesDir(), "jadx-export.json");
                if (!exportInProgress) JadxJobs.remove(new File(pending.getPath() + ".part"));
                if (pending.isFile() && !exportInProgress) {
                    JSONObject interrupted = new JSONObject(JadxJobs.read(pending));
                    if (!interrupted.optString("state").equals("completed")) DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(interrupted.getString("uri")));
                    JadxJobs.remove(pending);
                }
            }
            catch (Exception error) { message(error.toString()); }
            runOnUiThread(() -> {
                if (saved == null && getIntent().hasExtra("stopJob")) {
                    String id = getIntent().getStringExtra("stopJob");
                    new Thread(() -> {
                        try { JadxJobs.stop(getApplicationContext(), id); }
                        catch (Exception error) { message(error.toString()); }
                    }, "jadx-explicit-stop").start();
                    return;
                }
                if (saved == null && (getIntent().hasExtra("fixture") || getIntent().hasExtra("input"))) {
                    if (getIntent().hasExtra("fixture")) fixture(getIntent().getStringExtra("fixture"));
                    else { source = getIntent().getStringExtra("input"); label = source.startsWith("apk-dex:") ? source.substring(8) : new File(source).getName(); selected.setText(label); }
                    className.setText(getIntent().getStringExtra("selectedClass"));
                    advanced.setText(getIntent().getStringExtra("options"));
                    vmOptions.setText(getIntent().getStringExtra("vmOptions"));
                    if (getIntent().getBooleanExtra("run", false)) runJob();
                }
            });
        }, "jadx-job-recovery").start();
    }

    private void fixture(String type) {
        String file = type.equals("java") ? "SeedProbe.class" : type.equals("smali") ? "SeedProbe.smali" : "fixture." + type;
        source = "asset:workshop/jadx/fixtures/" + file;
        label = file;
        selected.setText(label);
    }

    private void runJob() {
        String input = source;
        String name = label;
        String filter = className.getText().toString().trim();
        try {
            JSONArray options = JadxJobs.options(advanced.getText().toString());
            JSONArray jvm = JadxJobs.options(vmOptions.getText().toString());
            new Thread(() -> {
                try {
                    String id = JadxJobs.start(getApplicationContext(), input, name, filter, options, jvm);
                    message("Started " + id);
                } catch (Exception error) { message(error.toString()); }
            }, "jadx-start").start();
        } catch (Exception error) { message(error.toString()); }
    }

    private void renderHistory() {
        if (history == null) return;
        try {
            JSONArray jobs = store.jobs();
            StringBuilder signature = new StringBuilder(jobs.toString());
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                if (job.getString("state").equals("running")) {
                    File progress = new File(JadxJobs.directory(this, job.getString("id")), "progress.json");
                    if (progress.exists()) signature.append(JadxJobs.read(progress));
                }
            }
            if (signature.toString().equals(lastHistory)) return;
            lastHistory = signature.toString();
            history.removeAllViews();
            history.addView(text("JOBS", 18, ACCENT));
            if (jobs.length() == 0) history.addView(text("Choose a sample or import a file, then run JADX.", 15, TEXT));
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                String id = job.getString("id");
                String state = job.getString("state");
                File dir = JadxJobs.directory(this, id);
                LinearLayout card = column();
                card.setPadding(16, 24, 16, 24);
                card.setBackgroundColor(Color.rgb(23, 35, 49));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.setMargins(0, 10, 0, 10);
                history.addView(card, params);
                card.addView(text(job.getString("label") + " · " + state, 18, ACCENT));
                card.addView(text(id + "\n" + job.getString("detail"), 14, TEXT));
                if (state.equals("running") || state.equals("queued")) {
                    File progress = new File(dir, "progress.json");
                    if (progress.exists()) {
                        JSONObject value = new JSONObject(JadxJobs.read(progress));
                        card.addView(text(value.optString("phase") + " " + value.optLong("completed") + "/" + value.optLong("total"), 14, TEXT));
                    }
                    card.addView(button("Stop this job", () -> new Thread(() -> {
                        try { JadxJobs.stop(getApplicationContext(), id); }
                        catch (Exception error) { message(error.toString()); }
                    }, "jadx-stop").start()));
                }
                File result = new File(dir, "result");
                if (result.isDirectory()) {
                    card.addView(button("Browse completed output", () -> browse(result, result)));
                    card.addView(button("View engine report", () -> viewFile(new File(result, "engine-report.json"))));
                    card.addView(button("Export completed output ZIP", () -> {
                        if (exportInProgress) { message("An export is already running"); return; }
                        exporting = id;
                        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, "jadx-" + id + ".zip");
                        startActivityForResult(save, 32);
                    }));
                }
                File log = new File(result, "jadx.log");
                if (!log.exists()) log = new File(dir, "working/jadx.log");
                if (!log.exists()) log = new File(dir, "jadx.log");
                if (log.exists()) {
                    File selectedLog = log;
                    card.addView(button("View JADX log", () -> viewFile(selectedLog)));
                }
                File console = new File(result, "console.log");
                if (!console.exists()) console = new File(dir, "console.log");
                if (console.exists()) {
                    File selectedConsole = console;
                    card.addView(button("View CLI output", () -> viewFile(selectedConsole)));
                }
                if (new File(dir, "failure.json").isFile()) card.addView(button("View failure", () -> viewFile(new File(dir, "failure.json"))));
            }
        } catch (Exception error) { message(error.toString()); }
    }

    private void browse(File base, File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing((File value) -> !value.isDirectory()).thenComparing(File::getName));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = (files[i].isDirectory() ? "[folder] " : "") + files[i].getName();
        new AlertDialog.Builder(this).setTitle(folder.equals(base) ? "Completed output" : folder.getName()).setItems(names, (dialog, index) -> {
            if (files[index].isDirectory()) browse(base, files[index]); else viewFile(files[index]);
        }).setNegativeButton("Close", null).show();
    }

    private void viewFile(File file) {
        new Thread(() -> {
            try {
                String value = JadxJobs.read(file);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    TextView text = text(value, 13, TEXT);
                    text.setBackgroundColor(BG);
                    text.setTypeface(Typeface.MONOSPACE);
                    text.setTextIsSelectable(true);
                    text.setPadding(20, 20, 20, 20);
                    ScrollView view = new ScrollView(this);
                    view.addView(text);
                    new AlertDialog.Builder(this).setTitle(file.getName()).setView(view).setPositiveButton("Close", null).show();
                });
            } catch (Exception error) { message(error.toString()); }
        }, "jadx-read-output").start();
    }

    private void zip(File base, File file, ZipOutputStream output) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) zip(base, child, output);
        else {
            output.putNextEntry(new ZipEntry(base.toPath().relativize(file.toPath()).toString()));
            try (InputStream input = new FileInputStream(file)) { JadxJobs.copy(input, output); }
            output.closeEntry();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == 31) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception error) { android.util.Log.d("WorkshopJadx", error.toString()); }
            source = uri.toString();
            label = "input.apk";
            try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) label = cursor.getString(0);
            }
            selected.setText(label);
        } else if (request == 32 && exporting != null) {
            String id = exporting;
            exporting = null;
            exportInProgress = true;
            new Thread(() -> {
                File pending = new File(getFilesDir(), "jadx-export.json");
                try {
                    JSONObject record = new JSONObject().put("id", id).put("uri", uri.toString()).put("state", "writing");
                    JadxJobs.write(pending, record);
                    try (OutputStream stream = getContentResolver().openOutputStream(uri, "w"); ZipOutputStream output = new ZipOutputStream(stream)) {
                        File complete = new File(JadxJobs.directory(this, id), "result");
                        zip(complete, complete, output);
                    }
                    record.put("state", "completed");
                    JadxJobs.write(pending, record);
                    JadxJobs.remove(pending);
                    message("Completed output exported");
                } catch (Exception error) {
                    try {
                        DocumentsContract.deleteDocument(getContentResolver(), uri);
                        JadxJobs.remove(pending);
                    } catch (Exception cleanup) { message("Export failed; incomplete document cleanup needs retry: " + cleanup); }
                    message("Export failed: " + error);
                } finally {
                    try { JadxJobs.remove(new File(pending.getPath() + ".part")); } catch (Exception error) { message("Export metadata cleanup needs retry: " + error); }
                    exportInProgress = false;
                }
            }, "jadx-export").start();
        }
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private EditText edit(String hint) {
        EditText view = new EditText(this);
        view.setTextColor(TEXT);
        view.setHintTextColor(Color.GRAY);
        view.setHint(hint);
        view.setSingleLine(true);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(clicked -> action.run());
        return view;
    }

    private void message(String value) {
        runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_LONG).show());
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        handler.post(refresh);
    }

    @Override protected void onPause() {
        active = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("source", source);
        state.putString("label", label);
        state.putString("className", className.getText().toString());
        state.putString("advanced", advanced.getText().toString());
        state.putString("vmOptions", vmOptions.getText().toString());
        state.putString("exporting", exporting);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }
}
