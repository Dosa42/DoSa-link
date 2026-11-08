package com.ayrvokan.wireless;

import android.content.Context;
import android.database.Cursor;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DiagnosticExport {
    public static JSONObject write(Context context, WorkshopStore.TraceFilter filter, OutputStream destination) throws Exception {
        JSONObject manifest = new JSONObject().put("schema", "wireless-workshop.diagnostics/1").put("package", context.getPackageName()).put("version", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName).put("exportedUtc", java.time.Instant.now().toString()).put("filters", filter.json());
        Set<String> jobs = new LinkedHashSet<>();
        Set<String> terminals = new LinkedHashSet<>();
        Set<String> debuggers = new LinkedHashSet<>();
        JSONArray files = new JSONArray();
        long count = 0;
        try (WorkshopStore store = new WorkshopStore(context); ZipOutputStream zip = new ZipOutputStream(destination)) {
            long maximum = 0;
            try (Cursor c = store.getReadableDatabase().rawQuery("SELECT max(id) FROM events", null)) { if (c.moveToFirst()) maximum = c.getLong(0); }
            manifest.put("maximumEventId", maximum);
            zip.putNextEntry(new ZipEntry("events.jsonl"));
            if (maximum > 0) try (Cursor c = store.traceCursor(filter, 0, maximum, 0, true)) {
                while (c.moveToNext()) {
                    JSONObject row = store.traceRow(c);
                    zip.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8)); count++;
                    String operation = row.getString("operation_id");
                    if (operation.matches("[a-zA-Z0-9-]+")) {
                        if (row.getString("module").equals("jadx")) jobs.add(operation);
                        if (row.getString("module").equals("terminal")) terminals.add(operation);
                        if (row.getString("module").equals("debug")) debuggers.add(operation);
                    }
                }
            }
            zip.closeEntry();
            for (String id : jobs) {
                File directory = JadxJobs.directory(context, id);
                for (String name : new String[] {"request.json", "worker.json", "progress.json", "engine-report.json", "runtime-start.json", "native-jvm.log", "console.log", "jadx.log", "staged-inputs.json", "stages-progress.json", "failure.json"}) {
                    File file = new File(directory, "result/" + name);
                    if (!file.isFile()) file = new File(directory, name);
                    if (!file.isFile()) file = new File(directory, "working/" + name);
                    if (!file.isFile()) continue;
                    attach(zip, files, file, "jobs/" + id + "/" + name);
                }
            }
            for (String id : terminals) {
                File directory = TerminalJobs.directory(context, id);
                for (String name : new String[] {"request.json", "command.sh", "worker.json", "process.json", "stdin.log", "stdout.log", "stderr.log", "report.json", "failure.json"}) {
                    File file = new File(directory, name);
                    if (file.isFile()) attach(zip, files, file, "jobs/terminal/" + id + "/" + name);
                }
            }
            for (String id : debuggers) {
                File directory = DebugJobs.directory(context, id);
                for (String name : new String[] {"request.json", "worker.json", "state.json", "agent-events.jsonl", "controller-events.jsonl", "server.json", "server-process.json", "guardian-events.jsonl", "guardian-report.json", "server-stdout.log", "server-stderr.log", "rsp.jsonl", "probe-result.json", "report.json", "failure.json", "endpoint-error.json"}) {
                    File file = new File(directory, name); if (file.isFile()) attach(zip, files, file, "jobs/debug/" + id + "/" + name);
                }
            }
            manifest.put("eventCount", count).put("files", files).put("scope", "All matching events through maximumEventId; attached job logs and reports, excluding source/resource trees.");
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return manifest;
    }
    private static void attach(ZipOutputStream zip, JSONArray files, File file, String entry) throws Exception {
        long length = file.length();
        zip.putNextEntry(new ZipEntry(entry));
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; long left = length;
            while (left > 0) { int n = input.read(buffer, 0, (int) Math.min(buffer.length, left)); if (n < 0) throw new java.io.EOFException("Diagnostic file changed during export: " + entry); zip.write(buffer, 0, n); digest.update(buffer, 0, n); left -= n; }
        }
        zip.closeEntry();
        StringBuilder hash = new StringBuilder(); for (byte part : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
        files.put(new JSONObject().put("path", entry).put("bytes", length).put("sha256", hash.toString()));
    }
}
