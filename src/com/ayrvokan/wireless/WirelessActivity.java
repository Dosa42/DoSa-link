package com.ayrvokan.wireless;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

public final class WirelessActivity extends Activity {
    private WirelessDiscovery discovery;
    private TextView status;
    private LinearLayout devices;
    private EditText types;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(24, 56, 24, 40);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        text(body, "Wireless discovery", 24);
        text(body, "Find local services and Bluetooth advertisements. Associate an observation with your gadget profile for later protocol testing.", 15);
        types = new EditText(this); types.setSingleLine(true); types.setText("_ewelink._tcp., _http._tcp., _matter._tcp., _matterc._udp."); body.addView(types);
        button(body, "Scan LAN services", () -> { ensure(); discovery.startLan(types.getText().toString()); });
        button(body, "Scan Bluetooth LE", this::ble);
        button(body, "Enable Bluetooth", () -> startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)));
        button(body, "Stop discovery", () -> stop("user"));
        button(body, "Check retained module compatibility", () -> startActivity(new Intent(this, CompatibilityActivity.class)));
        button(body, "Diagnostic logs", () -> startActivity(new Intent(this, DiagnosticsActivity.class).putExtra("module", "wireless")));
        status = text(body, "Ready. Discovery runs while this screen is open.", 15); status.setTextIsSelectable(true);
        devices = new LinearLayout(this); devices.setOrientation(1); body.addView(devices);
        render(); handle(getIntent());
    }

    private interface Action { void run() throws Exception; }
    private void action(Action run) { try { run.run(); } catch (Exception error) { status.setText(error.toString()); } }
    private void button(LinearLayout body, String label, Action action) { Button b = new Button(this); b.setText(label); body.addView(b); b.setOnClickListener(v -> action(action)); }
    private TextView text(LinearLayout body, String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); body.addView(view); return view; }
    private void ensure() throws Exception { if (discovery == null) discovery = new WirelessDiscovery(this, this::render); }
    private void stop(String reason) { if (discovery != null) { discovery.close(reason); discovery = null; } }

    private void ble() throws Exception {
        ensure();
        discovery.startBle();
        String[] missing = discovery.missingPermissions();
        if (missing.length > 0) requestPermissions(missing, 25);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 25) action(() -> { ensure(); discovery.startBle(); });
    }

    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }
    @Override public void onResume() { super.onResume(); if (status != null) render(); }
    @Override public void onStop() { stop("screen closed"); super.onStop(); }

    private void handle(Intent intent) {
        String command = intent.getStringExtra("action");
        if (command == null) return;
        intent.removeExtra("action");
        action(() -> {
            if (command.equals("stop")) stop("user");
            else if (command.equals("bind")) { WirelessBackend.bind(this, intent.getStringExtra("key"), intent.getStringExtra("profile")); render(); }
            else {
                ensure();
                if (command.equals("fixture")) discovery.fixture();
                else if (command.equals("lan")) discovery.startLan(intent.getStringExtra("types") == null ? types.getText().toString() : intent.getStringExtra("types"));
                else if (command.equals("ble")) discovery.startBle();
            }
        });
    }

    private void render() {
        action(() -> {
            if (discovery != null) status.setText("LAN: " + discovery.backend.state.optString("lan") + "\nBLE: " + discovery.backend.state.optString("ble") + "\n" + discovery.backend.id);
            else status.setText("Discovery stopped. Saved observations remain below.");
            JSONObject registry = WirelessBackend.registry(this);
            JSONObject saved = registry.getJSONObject("devices");
            JSONObject bindings = registry.getJSONObject("bindings");
            devices.removeAllViews();
            text(devices, "Saved observations: " + saved.length(), 19);
            java.util.Iterator<String> keys = saved.keys();
            while (keys.hasNext()) {
                String key = keys.next(); JSONObject item = saved.getJSONObject(key);
                String endpoint = item.optString("transport").equals("ble") ? item.optString("address") + " · RSSI " + item.optInt("rssi") : item.optString("host") + ":" + item.optInt("port");
                text(devices, item.optString("name", key) + "\n" + item.optString("transport") + " · " + endpoint + "\nLast observed: " + new java.util.Date(item.optLong("lastSeen")), 16);
                TextView detail = text(devices, item.toString(2), 12); detail.setTextIsSelectable(true);
                for (String profile : new String[] {"fanlamp", "nspanel"}) button(devices, (key.equals(bindings.optString(profile)) ? "Associated: " : "Associate with ") + profile, () -> { WirelessBackend.bind(this, key, profile); render(); });
            }
        });
    }
}
