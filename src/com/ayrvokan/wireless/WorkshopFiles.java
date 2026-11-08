package com.ayrvokan.wireless;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

public final class WorkshopFiles {
    private static final java.util.Set<String> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static File pending(Context context, String id, File part, Uri document) throws Exception {
        File folder = new File(context.getFilesDir(), "file-pending"); folder.mkdirs();
        File file = new File(folder, id + ".json");
        JSONObject value = new JSONObject().put("id", id).put("part", part == null ? "" : part.getPath()).put("document", document == null ? "" : document.toString()).put("completed", false);
        try (WorkshopStore store = new WorkshopStore(context)) { value.put("session", store.sessionId()); }
        JadxJobs.write(file, value);
        return file;
    }

    public static synchronized void recover(Context context) throws Exception {
        File folder = new File(context.getFilesDir(), "file-pending");
        File[] files = folder.listFiles(); if (files == null) return;
        for (File file : files) {
            if (!file.getName().endsWith(".json")) continue;
            JSONObject value = new JSONObject(JadxJobs.read(file)); String id = value.getString("id");
            if (active.contains(id)) continue;
            if (!value.optString("part").isEmpty()) Files.deleteIfExists(new File(value.getString("part")).toPath());
            if (!value.optBoolean("completed") && !value.optString("document").isEmpty()) android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), Uri.parse(value.getString("document")));
            try (WorkshopStore store = new WorkshopStore(context)) { store.trace("WARN", "files", "interrupted", value.getString("session"), id, "", "", "local-files", "Recovered unfinished file operation; temporary output removed", value, null); }
            Files.delete(file.toPath());
        }
        File[] parts = folder.listFiles();
        if (parts != null) for (File file : parts) if (file.getName().endsWith(".json.part") && !active.contains(file.getName().substring(0, file.getName().length() - 10))) Files.deleteIfExists(file.toPath());
    }

    public static JSONObject perform(Context context, String action, File source, File target, String text, Uri document) throws Exception {
        JSONObject input = new JSONObject().put("source", source == null ? "" : source.getPath()).put("target", target == null ? "" : target.getPath());
        try (OperationLog log = new OperationLog(context, "files", action, "", "local-files", input)) {
            active.add(log.id);
            File record = null, part = null;
            boolean exported = false;
            try {
                String request = log.request(action, input);
                if (action.equals("mkdir")) Files.createDirectory(target.toPath());
                else if (action.equals("move")) Files.move(source.toPath(), target.toPath());
                else if (action.equals("delete")) Files.delete(source.toPath());
                else if (action.equals("export")) {
                    record = pending(context, log.id, null, document);
                    try (InputStream from = new FileInputStream(source); OutputStream to = context.getContentResolver().openOutputStream(document, "wt")) { JadxJobs.copy(from, to); }
                    exported = true;
                    JSONObject state = new JSONObject(JadxJobs.read(record)).put("completed", true); JadxJobs.write(record, state);
                } else {
                    if (action.equals("copy") && target.exists()) throw new java.io.IOException("Destination already exists");
                    part = new File(target.getAbsoluteFile().getParentFile(), ".workshop-" + log.id + ".part");
                    record = pending(context, log.id, part, null);
                    if (!part.createNewFile()) throw new java.io.IOException("Temporary output already exists");
                    try {
                        try (FileOutputStream to = new FileOutputStream(part)) {
                            if (action.equals("save")) to.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            else try (InputStream from = action.equals("import") ? context.getContentResolver().openInputStream(document) : new FileInputStream(source)) { JadxJobs.copy(from, to); }
                            to.getFD().sync();
                        }
                        if (action.equals("save")) Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        else Files.move(part.toPath(), target.toPath());
                    } finally { Files.deleteIfExists(part.toPath()); }
                }
                JSONObject result = new JSONObject().put("action", action).put("operationId", log.id);
                File observed = target == null ? source : target;
                if (observed != null) {
                    result.put("path", observed.getPath()).put("exists", observed.exists()).put("bytes", observed.length());
                    if (observed.isFile()) result.put("sha256", JadxJobs.hash(observed));
                }
                log.response(request, "File operation completed", result); log.observed(request, "File state read", result); log.finish(result);
                return result;
            } catch (Exception error) { log.fail(error); throw error; }
            finally {
                try {
                    if (part != null) Files.deleteIfExists(part.toPath());
                    if (record != null) {
                        if (action.equals("export") && !exported) android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), document);
                        Files.deleteIfExists(record.toPath());
                        Files.deleteIfExists(new File(record.getPath() + ".part").toPath());
                    }
                } finally { active.remove(log.id); }
            }
        }
    }

    public static JSONObject check(Context context) throws Exception {
        File root = new File(TerminalJobs.workspace(context), "file-check-" + java.util.UUID.randomUUID());
        File a = new File(root, "original.txt"), b = new File(root, "copy.txt"), c = new File(root, "renamed.txt");
        org.json.JSONArray operations = new org.json.JSONArray();
        try {
            operations.put(perform(context, "mkdir", null, root, null, null));
            operations.put(perform(context, "save", null, a, "Workshop file tools ✓\n", null));
            operations.put(perform(context, "copy", a, b, null, null));
            operations.put(perform(context, "move", b, c, null, null));
            boolean passed = JadxJobs.read(c).equals("Workshop file tools ✓\n") && JadxJobs.hash(a).equals(JadxJobs.hash(c)) && !b.exists();
            operations.put(perform(context, "delete", a, null, null, null));
            operations.put(perform(context, "delete", c, null, null, null));
            operations.put(perform(context, "delete", root, null, null, null));
            return new JSONObject().put("passed", passed && !root.exists()).put("operations", operations);
        } finally { if (root.exists()) JadxJobs.remove(root); }
    }
}
