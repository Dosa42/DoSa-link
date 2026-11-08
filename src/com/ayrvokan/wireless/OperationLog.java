package com.ayrvokan.wireless;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.UUID;
import org.json.JSONObject;

public final class OperationLog implements AutoCloseable {
    public final String id = UUID.randomUUID().toString();
    private final WorkshopStore store;
    private final String session, module, action, device, transport;
    private final long started = android.os.SystemClock.elapsedRealtime();
    private boolean finished;

    public OperationLog(Context context, String module, String action, String device, String transport, JSONObject input) {
        store = new WorkshopStore(context.getApplicationContext());
        this.module = module; this.action = action; this.device = device; this.transport = transport; session = store.sessionId();
        event("INFO", "start", "", action + " started", input);
    }

    public void event(String level, String phase, String request, String message, JSONObject payload) {
        store.trace(level, module, phase, session, id, request, device, transport, message, payload, android.os.SystemClock.elapsedRealtime() - started);
    }

    public String request(String message, JSONObject payload) {
        String request = UUID.randomUUID().toString(); event("DEBUG", "request", request, message, payload); return request;
    }

    public void response(String request, String message, JSONObject payload) { event("DEBUG", "response", request, message, payload); }
    public void observed(String request, String message, JSONObject payload) { event("INFO", "observed", request, message, payload); }
    public void finish(JSONObject result) { event("INFO", "completed", "", action + " completed", result); finished = true; }
    public void fail(Throwable error) {
        JSONObject value = new JSONObject();
        try { value.put("exception", error.getClass().getName()).put("message", String.valueOf(error.getMessage())).put("stack", android.util.Log.getStackTraceString(error)); } catch (Exception failure) { throw new IllegalStateException(failure); }
        event("ERROR", "failed", "", error.toString(), value); finished = true;
    }

    @Override public void close() {
        try { if (!finished) event("WARN", "interrupted", "", action + " closed without a terminal result", new JSONObject()); }
        finally { store.close(); }
    }

    public static JSONObject storageCheck(Context context) throws Exception {
        File file = new File(context.getCacheDir(), "storage-check-" + UUID.randomUUID() + ".tmp");
        try (OperationLog op = new OperationLog(context, "storage", "write-read", "", "local-file", new JSONObject().put("path", file.getName()))) {
            try {
                byte[] expected = ("workshop-" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String request = op.request("Write diagnostic file", new JSONObject().put("bytes", expected.length));
                try (FileOutputStream to = new FileOutputStream(file)) { to.write(expected); to.getFD().sync(); }
                op.response(request, "Write completed", new JSONObject().put("bytes", file.length()));
                request = op.request("Read diagnostic file", new JSONObject().put("bytes", file.length()));
                byte[] actual;
                try (FileInputStream from = new FileInputStream(file); java.io.ByteArrayOutputStream to = new java.io.ByteArrayOutputStream()) { JadxJobs.copy(from, to); actual = to.toByteArray(); }
                op.response(request, "Read completed", new JSONObject().put("bytes", actual.length));
                if (!java.util.Arrays.equals(expected, actual)) throw new IllegalStateException("Storage contents did not match");
                op.observed(request, "Read bytes match written bytes", new JSONObject().put("matches", true));
                if (!file.delete()) throw new java.io.IOException("Unable to clean diagnostic file");
                JSONObject result = new JSONObject().put("operationId", op.id).put("verified", true).put("bytes", actual.length);
                op.finish(result); return result;
            } catch (Exception error) { op.fail(error); throw error; }
            finally { if (file.exists() && !file.delete()) throw new java.io.IOException("Unable to clean diagnostic file"); }
        }
    }
}
