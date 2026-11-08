package com.ayrvokan.wireless;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import org.json.JSONObject;

public final class PairingActivity extends Activity {
    private PairingSession pairing;
    private EditText address, ssid, password;
    private TextView status;
    private final Handler handler = new Handler();
    private boolean resumed;
    private boolean selectingMetadata;
    private final Runnable tick = new Runnable() { public void run() { if (!resumed) return; if (pairing != null) pairing.poll(); render(); handler.postDelayed(this, 750); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(24, 56, 24, 40);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        text(body, "Connect and pair", 24);
        text(body, "Inspect a discovered device, connect through its retained pairing module and configure its Wi-Fi network.", 15);
        address = field(body, "Bluetooth address"); ssid = field(body, "Wi-Fi network name"); password = field(body, "Wi-Fi password");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(body, "Inspect Bluetooth services", () -> start(false, false));
        button(body, "Connect and read device information", () -> start(true, false));
        button(body, "Scan Wi-Fi networks on device", () -> active().request("scanWifiList", 0));
        button(body, "Import device encryption metadata", () -> { active(); selectingMetadata = true; try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), 32); } catch (Exception error) { selectingMetadata = false; throw error; } });
        button(body, "Connect device to Wi-Fi", () -> active().provision(ssid.getText().toString(), password.getText().toString()));
        button(body, "Read device network status", () -> active().request("checkIpNetwork"));
        button(body, "Disconnect", this::disconnect);
        button(body, "Pairing diagnostics", () -> startActivity(new Intent(this, DiagnosticsActivity.class).putExtra("module", "pairing")));
        status = text(body, "Ready", 14); status.setTextIsSelectable(true); status.setTypeface(android.graphics.Typeface.MONOSPACE);
        try {
            JSONObject registry = WirelessBackend.registry(this); JSONObject devices = registry.getJSONObject("devices"); long newest = 0;
            java.util.Iterator<String> keys = devices.keys();
            while (keys.hasNext()) { JSONObject item = devices.getJSONObject(keys.next()); if (item.optString("transport").equals("ble") && item.optLong("lastSeen") >= newest) { address.setText(item.optString("address")); newest = item.optLong("lastSeen"); } }
            File input = new File(getFilesDir(), "pairing-input.json");
            if (input.isFile()) { JSONObject saved = new JSONObject(JadxJobs.read(input)); ssid.setText(saved.optString("ssid")); password.setText(saved.optString("password")); }
            else { android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE); String current = wifi.getConnectionInfo().getSSID(); if (current != null && !current.equals("<unknown ssid>")) ssid.setText(current.replaceAll("^\"|\"$", "")); }
        } catch (Exception error) { status.setText(error.toString()); }
        handle(getIntent());
    }

    private interface Action { void run() throws Exception; }
    private void run(Action action) { try { action.run(); } catch (Exception error) { status.setText(error.toString()); } }
    private void button(LinearLayout body, String label, Action action) { Button button = new Button(this); button.setText(label); body.addView(button); button.setOnClickListener(v -> run(action)); }
    private TextView text(LinearLayout body, String value, int size) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); body.addView(text); return text; }
    private EditText field(LinearLayout body, String hint) { EditText input = new EditText(this); input.setSingleLine(true); input.setHint(hint); body.addView(input); return input; }
    private PairingSession active() { if (pairing == null) throw new IllegalStateException("Connect the device first"); return pairing; }

    private void start(boolean connect, boolean provision) throws Exception {
        disconnect();
        String[] permissions = android.os.Build.VERSION.SDK_INT >= 31 ? new String[] {"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION"} : new String[] {"android.permission.ACCESS_FINE_LOCATION"};
        for (String permission : permissions) if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(permissions, 31); return; }
        pairing = new PairingSession(this, address.getText().toString().trim(), this::render);
        PairingSession expected = pairing;
        Runnable begin = new Runnable() { public void run() { if (pairing != expected) return; if (expected.state.optString("phase").equals("initializing")) { handler.postDelayed(this, 100); return; } PairingActivity.this.run(() -> expected.inspect(connect, provision)); } };
        handler.post(begin);
    }

    private void disconnect() { if (pairing != null) { pairing.close(); pairing = null; } }
    private void render() { if (pairing != null && status != null) try { status.setText(pairing.state.toString(2)); } catch (Exception error) { status.setText(error.toString()); } }
    @Override public void onResume() { super.onResume(); resumed = true; handler.post(tick); }
    @Override public void onPause() { resumed = false; handler.removeCallbacks(tick); super.onPause(); }
    @Override public void onStop() { if (!selectingMetadata) disconnect(); super.onStop(); }
    @Override public void onDestroy() { disconnect(); super.onDestroy(); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 32) return;
        selectingMetadata = false;
        if (result != RESULT_OK || data == null) return;
        run(() -> {
            try (java.io.InputStream input = getContentResolver().openInputStream(data.getData()); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) { if (output.size() + count > 65536) throw new IllegalArgumentException("Metadata file exceeds 64 KiB"); output.write(buffer, 0, count); }
                active().importMetadata(new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
        });
    }

    private void handle(Intent intent) {
        String action = intent.getStringExtra("action");
        if (intent.hasExtra("address")) address.setText(intent.getStringExtra("address"));
        if (action == null) return;
        intent.removeExtra("action");
        run(() -> {
            if (action.equals("inspect")) start(false, false);
            else if (action.equals("connect")) start(true, false);
            else if (action.equals("setup")) start(true, true);
            else if (action.equals("disconnect")) disconnect();
            else if (action.equals("info")) active().request("getDeviceInfo");
            else if (action.equals("wifi-scan")) active().request("scanWifiList", 0);
            else if (action.equals("network-status")) active().request("checkIpNetwork");
            else if (action.equals("provision")) active().provision(ssid.getText().toString(), password.getText().toString());
        });
    }
}
