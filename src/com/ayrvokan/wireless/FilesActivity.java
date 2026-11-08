package com.ayrvokan.wireless;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.Arrays;
import org.json.JSONObject;

public final class FilesActivity extends Activity {
    private EditText path, file, destination, editor;
    private TextView status;
    private LinearLayout listing;
    private String pending = "";
    private int offset;
    private String previewOnly = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(20, 16, 20, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        text(body, "Local files", 24);
        text(body, "Browse files available to the app. Import/export through Android’s document picker. Complete command logs are in files/terminal-jobs; decompilation results are in files/jadx-jobs.", 14);
        path = field(body, "Directory", getIntent().hasExtra("path") ? getIntent().getStringExtra("path") : TerminalJobs.workspace(this).getPath(), 1);
        button(body, "Browse directory", () -> { offset = 0; browse(); });
        button(body, "Parent directory", () -> { File parent = new File(path.getText().toString()).getParentFile(); if (parent != null) { path.setText(parent.getPath()); offset = 0; browse(); } });
        button(body, "App files", () -> { path.setText(getFilesDir().getPath()); offset = 0; browse(); });
        listing = new LinearLayout(this); listing.setOrientation(1); body.addView(listing);
        button(body, "Next 100 entries", () -> { offset += 100; browse(); });
        file = field(body, "Selected file / save path", new File(TerminalJobs.workspace(this), "notes.txt").getPath(), 1);
        destination = field(body, "Destination / new directory", "", 1);
        button(body, "Read text", this::read);
        editor = field(body, "Text editor", "", 6);
        button(body, "Save text", () -> operation("save"));
        button(body, "Create directory", () -> operation("mkdir"));
        button(body, "Copy file to destination", () -> operation("copy"));
        button(body, "Rename / move to destination", () -> operation("move"));
        button(body, "Delete selected file / empty directory", () -> operation("delete"));
        button(body, "Import document to selected path", () -> { pending = file.getText().toString(); startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 31); });
        button(body, "Export selected file", () -> { pending = file.getText().toString(); startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, new File(pending).getName()), 32); });
        button(body, "Run file tools check", () -> work(() -> JadxJobs.write(new File(getFilesDir(), "files-check.json"), WorkshopFiles.check(this))));
        status = text(body, "", 14);
        if (state != null) pending = state.getString("pending", "");
        if ("check".equals(getIntent().getStringExtra("action"))) work(() -> JadxJobs.write(new File(getFilesDir(), "files-check.json"), WorkshopFiles.check(this)));
        work(() -> WorkshopFiles.recover(this));
        browse();
    }

    @Override public void onSaveInstanceState(Bundle state) { state.putString("pending", pending); super.onSaveInstanceState(state); }

    private void browse() {
        String directory = path.getText().toString(); int page = offset;
        work(() -> {
            File root = new File(directory).getCanonicalFile(); File[] entries = root.listFiles();
            if (entries == null) throw new java.io.IOException("Cannot list " + root);
            Arrays.sort(entries, (a, b) -> { if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1; return a.getName().compareToIgnoreCase(b.getName()); });
            runOnUiThread(() -> {
                listing.removeAllViews();
                for (int i = page; i < Math.min(entries.length, page + 100); i++) { File entry = entries[i]; button(listing, (entry.isDirectory() ? "[directory] " : "[" + entry.length() + " bytes] ") + entry.getName(), () -> { if (entry.isDirectory()) { path.setText(entry.getPath()); offset = 0; browse(); } else file.setText(entry.getPath()); }); }
                status.setText(root + "\n" + entries.length + " entries • page " + (page / 100 + 1));
            });
        });
    }

    private void read() {
        String selected = file.getText().toString();
        work(() -> {
            File source = new File(selected);
            if (source.length() > 2 * 1024 * 1024) { previewOnly = source.getCanonicalPath(); String tail = TerminalJobs.tail(source); runOnUiThread(() -> { editor.setText(""); status.setText("File exceeds the 2 MiB text editor size. Use the terminal or export for the complete file.\n" + tail); }); return; }
            previewOnly = "";
            String value = JadxJobs.read(source); runOnUiThread(() -> editor.setText(value));
        });
    }

    private void operation(String action) {
        String source = file.getText().toString(), target = destination.getText().toString(), text = editor.getText().toString();
        work(() -> {
            File from = new File(source);
            if (action.equals("save") && from.getCanonicalPath().equals(previewOnly)) throw new IllegalStateException("This large file is in preview mode; choose another save path or use the terminal");
            File to = action.equals("save") ? from : action.equals("delete") ? null : resolve(target);
            JSONObject result = WorkshopFiles.perform(this, action, from, to, text, null);
            runOnUiThread(() -> { status.setText(result.toString()); browse(); });
        });
    }

    private File resolve(String value) throws Exception {
        if (value.trim().isEmpty()) throw new IllegalArgumentException("Enter a destination");
        File result = new File(value); return (result.isAbsolute() ? result : new File(path.getText().toString(), value)).getCanonicalFile();
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != 31 && request != 32) || result != RESULT_OK || data == null || data.getData() == null) return;
        File selected = new File(pending); Uri uri = data.getData();
        work(() -> {
            JSONObject done = WorkshopFiles.perform(this, request == 31 ? "import" : "export", selected, request == 31 ? selected : null, null, uri);
            runOnUiThread(() -> { status.setText(done.toString()); browse(); });
        });
    }

    private interface Work { void run() throws Exception; }
    private void work(Work work) { new Thread(() -> { try { work.run(); } catch (Exception error) { runOnUiThread(() -> status.setText(error.toString())); } }, "file-tools").start(); }
    private TextView text(LinearLayout parent, String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, 8, 0, 8); parent.addView(view); return view; }
    private EditText field(LinearLayout parent, String label, String value, int lines) { text(parent, label, 14); EditText view = new EditText(this); view.setId(1000 + parent.getChildCount()); view.setText(value); view.setSingleLine(lines == 1); view.setMinLines(lines); parent.addView(view); return view; }
    private void button(LinearLayout parent, String label, Runnable action) { Button view = new Button(this); view.setText(label); view.setOnClickListener(v -> action.run()); parent.addView(view); }
}
