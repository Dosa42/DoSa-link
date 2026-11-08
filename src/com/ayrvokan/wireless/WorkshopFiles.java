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
    public static JSONObject perform(Context context, String action, File source, File target, String text, Uri document) throws Exception {
        JSONObject input = new JSONObject().put("source", source == null ? "" : source.getPath()).put("target", target == null ? "" : target.getPath());
        try (OperationLog log = new OperationLog(context, "files", action, "", "local-files", input)) {
            try {
                String request = log.request(action, input);
                if (action.equals("mkdir")) Files.createDirectory(target.toPath());
                else if (action.equals("move")) Files.move(source.toPath(), target.toPath());
                else if (action.equals("delete")) Files.delete(source.toPath());
                else if (action.equals("export")) {
                    try (InputStream from = new FileInputStream(source); OutputStream to = context.getContentResolver().openOutputStream(document, "wt")) { JadxJobs.copy(from, to); }
                } else {
                    if (action.equals("copy") && target.exists()) throw new java.io.IOException("Destination already exists");
                    File part = File.createTempFile(".workshop-", ".part", target.getAbsoluteFile().getParentFile());
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
