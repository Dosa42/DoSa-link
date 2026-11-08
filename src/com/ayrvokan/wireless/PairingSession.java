package com.ayrvokan.wireless;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayDeque;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PairingSession {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh;
    public final JSONObject state;
    public final String id;
    public final File directory;
    private final String session;
    private RetainedBleBridge bridge;
    private BluetoothGatt gatt;
    private final ArrayDeque<BluetoothGattCharacteristic> reads = new ArrayDeque<>();
    private boolean closed;
    private boolean autoConnect;
    private boolean autoProvision;
    private int mode = 6;
    private String pending = "";
    private long requestSequence;
    private PairingCrypto crypto;
    private String appUuid;
    private String provisionSsid;
    private String provisionPassword;
    private boolean authenticated;

    public PairingSession(Context context, String address, Runnable refresh) throws Exception {
        this.context = context.getApplicationContext(); this.refresh = refresh;
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IllegalArgumentException("Select a Bluetooth address");
        id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        directory = new File(context.getFilesDir(), "pairing-jobs/" + id); directory.mkdirs();
        try (WorkshopStore store = new WorkshopStore(context)) { session = store.sessionId(); }
        state = new JSONObject().put("schema", "wireless-workshop.pairing/1").put("id", id).put("address", address).put("phase", "initializing").put("startedAt", System.currentTimeMillis()).put("pid", android.os.Process.myPid()).put("results", new JSONObject());
        save();
        new Thread(() -> {
            try {
                RetainedBleBridge created = new RetainedBleBridge(this.context, (method, success, value) -> handler.post(() -> result(method, success, value)));
                handler.post(() -> { if (closed) created.close(); else { bridge = created; update("ready", "Retained BLEPairModule initialized", new JSONObject()); } });
            } catch (Throwable error) { handler.post(() -> update("module-error", error.toString(), new JSONObject())); }
        }, "retained-ble-initialize").start();
    }

    public static JSONObject redacted(Object value) throws Exception {
        JSONObject wrapper = new JSONObject(new JSONObject().put("value", value).toString()); redact(wrapper); return wrapper;
    }

    private static void redact(JSONObject value) throws Exception {
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object item = value.get(key);
            if (key.matches("(?i).*(password|apikey|devicekey|secret|authcode|appcode).*")) value.put(key, "[stored locally]");
            else if (item instanceof JSONObject) redact((JSONObject) item);
            else if (item instanceof JSONArray) for (int i = 0; i < ((JSONArray) item).length(); i++) if (((JSONArray) item).opt(i) instanceof JSONObject) redact(((JSONArray) item).getJSONObject(i));
        }
    }

    private void save() throws Exception {
        state.put("updatedAt", System.currentTimeMillis());
        JadxJobs.write(new File(directory, "state.json"), state); JadxJobs.write(new File(context.getFilesDir(), "pairing-state.json"), state);
        refresh.run();
    }

    private void update(String phase, String message, JSONObject data) {
        if (closed) return;
        try {
            state.put("phase", phase).put("message", message);
            try (WorkshopStore store = new WorkshopStore(context)) { store.trace(phase.endsWith("error") ? "WARN" : "INFO", "pairing", phase, session, id, id + "-" + requestSequence, state.getString("address"), "ble", message, data, null); }
            save();
        } catch (Exception error) { android.util.Log.e("WorkshopPairing", "Pairing state failed", error); }
    }

    public void inspect(boolean connectAfter, boolean provisionAfter) throws Exception {
        if (gatt != null || !pending.isEmpty()) throw new IllegalStateException("A connection operation is already active");
        autoConnect = connectAfter; autoProvision = provisionAfter;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothDevice device = manager.getAdapter().getRemoteDevice(state.getString("address"));
        update("connecting-gatt", "Connecting to selected Bluetooth device", new JSONObject());
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        BluetoothGatt expected = gatt;
        handler.postDelayed(() -> { if (!closed && gatt == expected && state.optString("phase").equals("connecting-gatt")) { closeGatt(); update("connection-error", "No connection response; re-enter device pairing mode and retry", new JSONObject()); } }, 25000);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int next) {
            handler.post(() -> {
                if (closed || connection != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS && next == BluetoothProfile.STATE_CONNECTED) { update("discovering-services", "Bluetooth connected; reading GATT services", new JSONObject()); if (!connection.discoverServices()) { closeGatt(); update("connection-error", "Service discovery could not start", new JSONObject()); } }
                else if (next == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) { closeGatt(); update("connection-error", "Bluetooth disconnected, status " + status, new JSONObject()); }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            handler.post(() -> {
                if (closed || connection != gatt) return;
                try {
                    if (status != BluetoothGatt.GATT_SUCCESS) throw new IllegalStateException("GATT discovery status " + status);
                    JSONArray services = new JSONArray(); boolean tx = false, rx = false;
                    for (BluetoothGattService service : connection.getServices()) {
                        JSONArray chars = new JSONArray();
                        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                            JSONArray descriptors = new JSONArray(); for (android.bluetooth.BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) descriptors.put(descriptor.getUuid().toString());
                            String uuid = characteristic.getUuid().toString();
                            chars.put(new JSONObject().put("uuid", uuid).put("properties", characteristic.getProperties()).put("permissions", characteristic.getPermissions()).put("descriptors", descriptors));
                            if (uuid.equals("0000bbb0-0000-1000-8000-00805f9b34fb")) { tx = true; mode = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? 6 : 0; }
                            if (uuid.equals("0000bbb1-0000-1000-8000-00805f9b34fb")) rx = true;
                            if ((service.getUuid().toString().startsWith("0000180a-") || uuid.startsWith("00002a00-")) && (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) reads.add(characteristic);
                        }
                        services.put(new JSONObject().put("uuid", service.getUuid().toString()).put("characteristics", chars));
                    }
                    state.put("services", services).put("coolkitCompatible", tx && rx).put("moduleMode", mode).put("standardInfo", new JSONObject());
                    update("gatt-inspected", "GATT services captured", new JSONObject().put("services", services).put("coolkitCompatible", tx && rx));
                    nextRead();
                } catch (Exception error) { closeGatt(); update("inspection-error", error.toString(), new JSONObject()); }
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) { receivedRead(connection, characteristic, characteristic.getValue(), status); }
        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value, int status) { receivedRead(connection, characteristic, value, status); }
    };

    private void receivedRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        handler.post(() -> { if (closed || connection != gatt) return; try { state.getJSONObject("standardInfo").put(characteristic.getUuid().toString(), new JSONObject().put("status", status).put("text", value == null ? "" : new String(value, java.nio.charset.StandardCharsets.UTF_8)).put("base64", value == null ? "" : android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP))); nextRead(); } catch (Exception error) { update("inspection-error", error.toString(), new JSONObject()); } });
    }

    private void nextRead() throws Exception {
        while (gatt != null && !reads.isEmpty()) if (gatt.readCharacteristic(reads.remove())) return;
        closeGatt();
        update("inspected", state.optBoolean("coolkitCompatible") ? "Retained CoolKit GATT contract matched" : "GATT captured; retained CoolKit contract did not match", new JSONObject());
        if (autoConnect && state.optBoolean("coolkitCompatible")) handler.postDelayed(() -> { try { connect(); } catch (Exception error) { update("connection-error", error.toString(), new JSONObject()); } }, 350);
    }

    public void connect() throws Exception { if (bridge == null) throw new IllegalStateException("Retained module is still initializing"); closeGatt(); request("connectBLEDevice", state.getString("address"), mode); }

    public void poll() {
        if (closed || bridge == null) return;
        try { String status = bridge.connectionState(); if (!status.equals(state.optString("sdkState"))) { state.put("sdkState", status); save(); } }
        catch (Exception error) { update("module-error", error.toString(), new JSONObject()); }
    }

    public void request(String method, Object... args) throws Exception {
        if (bridge == null) throw new IllegalStateException("Retained module is unavailable");
        if (!pending.isEmpty()) throw new IllegalStateException("Waiting for " + pending);
        pending = method; long sequence = ++requestSequence;
        update("request", "Retained module: " + method, new JSONObject().put("method", method));
        try { bridge.call(method, args); } catch (Exception error) { pending = ""; throw error; }
        handler.postDelayed(() -> {
            if (closed || sequence != requestSequence || !method.equals(pending)) return;
            pending = "";
            try { state.getJSONObject("results").put(method, new JSONObject().put("success", false).put("error", "No response")); }
            catch (Exception error) { android.util.Log.w("WorkshopPairing", "Timeout record", error); }
            if (method.equals("connectBLEDevice")) bridge.close();
            update("response-timeout", method + " did not respond; check device pairing mode", new JSONObject());
        }, method.equals("connectWifiBySsid") ? 90000 : 25000);
    }

    private void result(String method, boolean success, Object value) {
        if (closed) return;
        try {
            Object json = RetainedBleBridge.json(value);
            if (method.startsWith("event:")) { update("module-event", method, redacted(json)); return; }
            if (!method.equals(pending)) return;
            pending = "";
            Object recorded = method.equals("blueToothAuth") ? "[authentication response]" : json;
            state.getJSONObject("results").put(method, new JSONObject().put("success", success).put("value", redacted(recorded).get("value")));
            update(success ? "response" : "module-error", method + (success ? " completed" : " rejected"), redacted(recorded));
            if (!success) { provisionPassword = null; return; }
            if (method.equals("connectBLEDevice")) request("getDeviceInfo");
            else if (method.equals("blueToothAuth")) {
                String deviceUuid = crypto.decrypt(String.valueOf(json));
                if (deviceUuid.isEmpty()) throw new IllegalStateException("Device authentication returned an empty UUID");
                request("blueToothConfirmAuth", crypto.encrypt(appUuid + "_" + deviceUuid));
            } else if (method.equals("blueToothConfirmAuth")) {
                if (!Boolean.TRUE.equals(json)) throw new IllegalStateException("Device rejected Bluetooth authentication");
                authenticated = true; state.put("authenticated", true);
                sendWifi();
            } else if (method.equals("getDeviceInfo")) {
                if (json instanceof JSONObject) {
                    File file = new File(context.getFilesDir(), "paired-devices.json"); JSONObject saved = file.isFile() ? new JSONObject(JadxJobs.read(file)) : new JSONObject();
                    JSONObject record = (JSONObject) json; record.put("address", state.getString("address")).put("module", "com.pair.bluetooth.coolkit.BLEPairModule").put("lastVerified", System.currentTimeMillis());
                    saved.put(state.getString("address"), record); JadxJobs.write(file, saved);
                    state.put("deviceId", record.optString("deviceid")).put("deviceRecordSaved", true);
                }
                request("scanWifiList", 0);
            } else if (method.equals("scanWifiList")) {
                if (json instanceof JSONArray) state.put("wifiNetworks", json);
                update("ready-to-provision", "Device information and Wi-Fi scan received", new JSONObject());
                if (autoProvision) provisionFromFile();
            } else if (method.equals("connectWifiBySsid")) {
                update("wifi-response", "Device returned Wi-Fi provisioning status", redacted(json));
                request("checkIpNetwork");
            } else if (method.equals("checkIpNetwork")) update("network-status", "Device network status received", redacted(json));
        } catch (Throwable error) { pending = ""; provisionPassword = null; update("module-error", error.toString(), new JSONObject()); }
    }

    public void provision(String ssid, String password) throws Exception {
        if (ssid == null || ssid.isEmpty()) throw new IllegalArgumentException("Enter a Wi-Fi network name");
        if (!pending.isEmpty()) throw new IllegalStateException("Waiting for " + pending);
        File metadataFile = new File(context.getFilesDir(), "pairing-metadata.json");
        String deviceId = state.optString("deviceId");
        JSONObject metadata = metadataFile.isFile() ? new JSONObject(JadxJobs.read(metadataFile)).optJSONObject(deviceId) : null;
        if (metadata == null) { update("metadata-required", "Device encryption metadata is required. Import metadata for " + deviceId + " to continue Wi-Fi provisioning.", new JSONObject()); return; }
        crypto = new PairingCrypto(metadata.getString("secretKey"), metadata.optString("iv", "0000000000000000"));
        provisionSsid = ssid; provisionPassword = password;
        state.put("wifiSsid", ssid);
        if (authenticated) sendWifi();
        else { appUuid = UUID.randomUUID().toString(); request("blueToothAuth", crypto.encrypt(appUuid)); }
    }

    private void sendWifi() throws Exception {
        int connected = 0; JSONArray networks = state.optJSONArray("wifiNetworks");
        if (networks != null) for (int i = 0; i < networks.length(); i++) if (provisionSsid.equals(networks.getJSONObject(i).optString("ssid"))) connected = networks.getJSONObject(i).optInt("connected", 0);
        String encrypted = provisionPassword == null || provisionPassword.isEmpty() ? "" : crypto.encrypt(provisionPassword);
        provisionPassword = null;
        state.put("passwordEncrypted", true).put("wifiConnectedFlag", connected);
        request("connectWifiBySsid", provisionSsid, encrypted, connected);
    }

    public void importMetadata(String text) throws Exception {
        JSONObject imported = new JSONObject(text); String deviceId = state.optString("deviceId");
        if (deviceId.isEmpty() || !deviceId.equals(imported.optString("deviceid"))) throw new IllegalArgumentException("Metadata deviceid must match the connected device");
        new PairingCrypto(imported.getString("secretKey"), imported.optString("iv", "0000000000000000"));
        File file = new File(context.getFilesDir(), "pairing-metadata.json");
        JSONObject metadata = file.isFile() ? new JSONObject(JadxJobs.read(file)) : new JSONObject();
        metadata.put(deviceId, new JSONObject().put("secretKey", imported.getString("secretKey")).put("iv", imported.optString("iv", "0000000000000000")));
        JadxJobs.write(file, metadata);
        authenticated = false;
        update("metadata-imported", "Device encryption metadata imported for " + deviceId, new JSONObject());
    }

    private void provisionFromFile() throws Exception { JSONObject input = new JSONObject(JadxJobs.read(new File(context.getFilesDir(), "pairing-input.json"))); provision(input.getString("ssid"), input.getString("password")); }
    private void closeGatt() { BluetoothGatt connection = gatt; gatt = null; reads.clear(); if (connection != null) { connection.disconnect(); connection.close(); } }

    public void close() {
        if (closed) return;
        provisionPassword = null; crypto = null; appUuid = null;
        closeGatt(); if (bridge != null) bridge.close();
        update("completed", "Pairing session disconnected", new JSONObject());
        closed = true;
        try { state.put("cleanup", new JSONObject().put("gattClosed", true).put("retainedModuleDisconnected", bridge != null)).put("finishedAt", System.currentTimeMillis()); JadxJobs.write(new File(directory, "report.json"), state); JadxJobs.write(new File(directory, "state.json"), state); JadxJobs.write(new File(context.getFilesDir(), "pairing-state.json"), state); }
        catch (Exception error) { android.util.Log.e("WorkshopPairing", "Final pairing report failed", error); }
    }
}
