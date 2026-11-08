package com.ayrvokan.wireless;

import android.content.Context;
import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WirelessBackend {
    private final Context context;
    public final String id;
    public final File directory;
    public final JSONObject state;
    private final JSONObject devices = new JSONObject();
    private final String session;

    public WirelessBackend(Context context) throws Exception {
        this.context = context.getApplicationContext();
        id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        directory = new File(context.getFilesDir(), "wireless-jobs/" + id);
        directory.mkdirs();
        try (WorkshopStore store = new WorkshopStore(context)) { session = store.sessionId(); }
        state = new JSONObject().put("id", id).put("phase", "ready").put("pid", android.os.Process.myPid()).put("startedAt", System.currentTimeMillis()).put("lan", "idle").put("ble", "idle").put("devices", new JSONArray());
        event("started", "Wireless discovery session opened", new JSONObject());
        save();
    }

    public void event(String phase, String message, JSONObject payload) throws Exception {
        try (WorkshopStore store = new WorkshopStore(context)) {
            store.trace(phase.equals("error") ? "WARN" : "INFO", "wireless", phase, session, id, id + "-" + phase, payload.optString("key"), payload.optString("transport"), message, payload, null);
        }
    }

    public void observe(JSONObject device) throws Exception {
        String key = device.getString("key");
        boolean fresh = !devices.has(key);
        device.put("lastSeen", System.currentTimeMillis()).put("sessionId", id);
        if (devices.has(key)) device.put("firstSeen", devices.getJSONObject(key).getLong("firstSeen"));
        else device.put("firstSeen", System.currentTimeMillis());
        devices.put(key, device);
        if (fresh) event("discovered", "Wireless device observed", device);
    }

    public void lost(String key) throws Exception {
        if (devices.has(key)) { devices.getJSONObject(key).put("available", false); event("lost", "LAN service disappeared", devices.getJSONObject(key)); }
    }

    public void save() throws Exception {
        JSONArray rows = new JSONArray();
        java.util.Iterator<String> keys = devices.keys();
        while (keys.hasNext()) rows.put(devices.getJSONObject(keys.next()));
        state.put("devices", rows).put("updatedAt", System.currentTimeMillis());
        JadxJobs.write(new File(directory, "state.json"), state);
        JadxJobs.write(new File(context.getFilesDir(), "wireless-state.json"), state);
        JSONObject registry = registry(context);
        JSONObject saved = registry.getJSONObject("devices");
        for (int i = 0; i < rows.length(); i++) { JSONObject item = rows.getJSONObject(i); saved.put(item.getString("key"), item); }
        JadxJobs.write(new File(context.getFilesDir(), "wireless-devices.json"), registry);
    }

    public void finish(String reason) throws Exception {
        if (state.optString("phase").equals("completed")) return;
        state.put("phase", "completed").put("finishedAt", System.currentTimeMillis()).put("reason", reason);
        save();
        JadxJobs.write(new File(directory, "report.json"), state);
        event("completed", "Wireless discovery stopped: " + reason, new JSONObject().put("deviceCount", devices.length()));
    }

    public static JSONObject registry(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "wireless-devices.json");
        return file.isFile() ? new JSONObject(JadxJobs.read(file)) : new JSONObject().put("schema", "wireless-workshop.devices/1").put("devices", new JSONObject()).put("bindings", new JSONObject());
    }

    public static void bind(Context context, String key, String profile) throws Exception {
        if (!profile.equals("fanlamp") && !profile.equals("nspanel")) throw new IllegalArgumentException("Unknown gadget profile");
        JSONObject registry = registry(context);
        if (!registry.getJSONObject("devices").has(key)) throw new IllegalArgumentException("Device has not been observed");
        registry.getJSONObject("bindings").put(profile, key);
        JadxJobs.write(new File(context.getFilesDir(), "wireless-devices.json"), registry);
        try (WorkshopStore store = new WorkshopStore(context)) { store.event("wireless", "Discovery associated with " + profile + ": " + key); }
    }
}
