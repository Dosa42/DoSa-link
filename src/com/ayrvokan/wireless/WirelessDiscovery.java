package com.ayrvokan.wireless;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.LinkProperties;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WirelessDiscovery {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh;
    public final WirelessBackend backend;
    private final NsdManager nsd;
    private final ArrayList<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private final Set<String> resolving = new HashSet<>();
    private NsdManager.ResolveListener resolver;
    private BluetoothLeScanner scanner;
    private ScanCallback scan;
    private boolean closed;
    private boolean dirty;
    private NsdManager.RegistrationListener fixture;
    private java.net.ServerSocket fixtureSocket;

    public WirelessDiscovery(Context context, Runnable refresh) throws Exception {
        this.context = context;
        this.refresh = refresh;
        backend = new WirelessBackend(context);
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        JSONArray networks = new JSONArray();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network network : cm.getAllNetworks()) {
            LinkProperties link = cm.getLinkProperties(network);
            if (link != null) networks.put(new JSONObject().put("network", network.toString()).put("interface", link.getInterfaceName()).put("addresses", link.getLinkAddresses().toString()).put("routes", link.getRoutes().toString()));
        }
        backend.state.put("networks", networks);
        change();
    }

    private void perform(Action action) {
        handler.post(() -> {
            if (closed) return;
            try { action.run(); change(); }
            catch (Exception error) { fail("callback", error.toString()); }
        });
    }

    private interface Action { void run() throws Exception; }

    private void change() {
        if (dirty || closed) return;
        dirty = true;
        handler.postDelayed(() -> {
            dirty = false;
            if (closed) return;
            try { backend.save(); refresh.run(); }
            catch (Exception error) { android.util.Log.e("WorkshopWireless", "Discovery persistence failed", error); }
        }, 500);
    }

    private void fail(String adapter, String error) {
        try {
            backend.state.put(adapter, error);
            backend.event("error", adapter + ": " + error, new JSONObject().put("transport", adapter));
            change();
        } catch (Exception failure) { android.util.Log.e("WorkshopWireless", "Discovery error", failure); }
    }

    public void startLan(String types) throws Exception {
        if (!listeners.isEmpty()) return;
        Set<String> unique = new java.util.LinkedHashSet<>();
        for (String type : types.split("[,\\s]+")) {
            if (type.isEmpty()) continue;
            if (!type.matches("_[a-zA-Z0-9-]+\\._(tcp|udp)\\.?")) throw new IllegalArgumentException("Use service types such as _ewelink._tcp.");
            unique.add(type.endsWith(".") ? type : type + ".");
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("Enter at least one service type");
        backend.state.put("phase", "discovering").put("lan", "starting").put("serviceTypes", new JSONArray(unique));
        for (String type : unique) {
            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String serviceType) { perform(() -> { backend.state.put("lan", "discovering"); backend.event("listening", "LAN discovery started", new JSONObject().put("transport", "mdns").put("type", serviceType)); }); }
                public void onStartDiscoveryFailed(String serviceType, int code) { perform(() -> { listeners.remove(this); fail("lan", "start error " + code + " for " + serviceType); }); }
                public void onStopDiscoveryFailed(String serviceType, int code) { perform(() -> fail("lan", "stop error " + code)); }
                public void onDiscoveryStopped(String serviceType) { }
                public void onServiceFound(NsdServiceInfo info) { perform(() -> enqueue(info)); }
                public void onServiceLost(NsdServiceInfo info) { perform(() -> backend.lost(key(info))); }
            };
            listeners.add(listener);
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); }
            catch (Exception error) { listeners.remove(listener); fail("lan", error.toString()); }
        }
        change();
    }

    private String key(NsdServiceInfo info) { return WirelessBackend.lanKey(info.getServiceType(), info.getServiceName()); }

    private JSONObject service(NsdServiceInfo info, boolean resolved) throws Exception {
        JSONObject attrs = new JSONObject();
        for (java.util.Map.Entry<String, byte[]> item : info.getAttributes().entrySet()) attrs.put(item.getKey(), android.util.Base64.encodeToString(item.getValue() == null ? new byte[0] : item.getValue(), android.util.Base64.NO_WRAP));
        return new JSONObject().put("key", key(info)).put("name", info.getServiceName()).put("transport", "mdns").put("serviceType", info.getServiceType()).put("host", info.getHost() == null ? "" : info.getHost().getHostAddress()).put("port", info.getPort()).put("txtBase64", attrs).put("resolved", resolved).put("available", true);
    }

    private void enqueue(NsdServiceInfo info) throws Exception {
        backend.observe(service(info, false));
        if (resolving.add(key(info))) pending.add(info);
        resolveNext();
    }

    private void resolveNext() {
        if (closed || resolver != null || pending.isEmpty()) return;
        NsdServiceInfo current = pending.remove();
        resolver = new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info, int code) { perform(() -> { resolving.remove(key(current)); resolver = null; fail("resolve", key(current) + " error " + code); resolveNext(); }); }
            public void onServiceResolved(NsdServiceInfo info) { perform(() -> { resolving.remove(key(current)); resolver = null; backend.observe(service(info, true)); backend.event("resolved", "LAN endpoint resolved", service(info, true)); resolveNext(); }); }
        };
        try { nsd.resolveService(current, resolver); }
        catch (Exception error) { resolving.remove(key(current)); resolver = null; fail("resolve", error.toString()); resolveNext(); }
    }

    public String[] missingPermissions() {
        ArrayList<String> missing = new ArrayList<>();
        String[] required = Build.VERSION.SDK_INT >= 31 ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION} : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : required) if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        return missing.toArray(new String[0]);
    }

    public void startBle() throws Exception {
        if (scan != null) return;
        if (missingPermissions().length > 0) { backend.state.put("ble", "permission-required"); change(); return; }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { backend.state.put("ble", "unsupported"); change(); return; }
        if (!adapter.isEnabled()) { backend.state.put("ble", "bluetooth-off"); change(); return; }
        if (android.provider.Settings.Secure.getInt(context.getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE, 0) == 0) { backend.state.put("ble", "location-off"); change(); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { backend.state.put("ble", "scanner-unavailable"); change(); return; }
        scan = new ScanCallback() {
            public void onScanResult(int callbackType, ScanResult result) { perform(() -> advertisement(result)); }
            public void onBatchScanResults(java.util.List<ScanResult> results) { for (ScanResult result : results) onScanResult(0, result); }
            public void onScanFailed(int code) { perform(() -> { scan = null; fail("ble", "scan error " + code); }); }
        };
        try { scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan); }
        catch (Exception error) { scanner = null; scan = null; throw error; }
        backend.state.put("phase", "discovering").put("ble", "scanning");
        backend.event("listening", "BLE scan started", new JSONObject().put("transport", "ble"));
        change();
    }

    private void advertisement(ScanResult result) throws Exception {
        android.bluetooth.le.ScanRecord record = result.getScanRecord();
        String address = result.getDevice().getAddress();
        JSONArray uuids = new JSONArray();
        JSONObject manufacturers = new JSONObject();
        JSONObject services = new JSONObject();
        if (record != null) {
            if (record.getServiceUuids() != null) for (android.os.ParcelUuid uuid : record.getServiceUuids()) uuids.put(uuid.toString());
            android.util.SparseArray<byte[]> data = record.getManufacturerSpecificData();
            for (int i = 0; i < data.size(); i++) manufacturers.put(Integer.toString(data.keyAt(i)), android.util.Base64.encodeToString(data.valueAt(i), android.util.Base64.NO_WRAP));
            for (java.util.Map.Entry<android.os.ParcelUuid, byte[]> item : record.getServiceData().entrySet()) services.put(item.getKey().toString(), android.util.Base64.encodeToString(item.getValue(), android.util.Base64.NO_WRAP));
        }
        backend.observe(new JSONObject().put("key", "ble:" + address).put("transport", "ble").put("address", address).put("name", record == null || record.getDeviceName() == null ? "" : record.getDeviceName()).put("rssi", result.getRssi()).put("connectable", result.isConnectable()).put("serviceUuids", uuids).put("manufacturerDataBase64", manufacturers).put("serviceDataBase64", services).put("rawBase64", record == null ? "" : android.util.Base64.encodeToString(record.getBytes(), android.util.Base64.NO_WRAP)).put("available", true));
    }

    public void fixture() throws Exception {
        fixtureSocket = new java.net.ServerSocket(0);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Workshop-proof-" + backend.id);
        info.setServiceType("_workshop._tcp."); info.setPort(fixtureSocket.getLocalPort()); info.setAttribute("proof", backend.id);
        fixture = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo registered) { perform(() -> { JSONObject observed = service(info, false).put("name", registered.getServiceName()); backend.state.put("fixture", observed).put("fixtureRegistrationConfirmed", true); backend.event("fixture", "Controlled LAN fixture registered", observed); }); }
            public void onRegistrationFailed(NsdServiceInfo service, int code) { perform(() -> fail("fixture", "registration error " + code)); }
            public void onServiceUnregistered(NsdServiceInfo service) { }
            public void onUnregistrationFailed(NsdServiceInfo service, int code) { android.util.Log.w("WorkshopWireless", "Fixture unregister error " + code); }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, fixture);
        startLan("_workshop._tcp.");
    }

    public void close(String reason) {
        if (closed) return;
        closed = true;
        for (NsdManager.DiscoveryListener listener : listeners) try { nsd.stopServiceDiscovery(listener); } catch (Exception error) { android.util.Log.w("WorkshopWireless", "LAN stop", error); }
        listeners.clear(); pending.clear(); resolving.clear();
        if (Build.VERSION.SDK_INT >= 34 && resolver != null) try { nsd.stopServiceResolution(resolver); } catch (Exception error) { android.util.Log.w("WorkshopWireless", "Resolution stop", error); }
        resolver = null;
        if (scanner != null && scan != null) try { scanner.stopScan(scan); } catch (Exception error) { android.util.Log.w("WorkshopWireless", "BLE stop", error); }
        scanner = null; scan = null;
        if (fixture != null) try { nsd.unregisterService(fixture); } catch (Exception error) { android.util.Log.w("WorkshopWireless", "Fixture stop", error); }
        fixture = null;
        if (fixtureSocket != null) try { fixtureSocket.close(); } catch (Exception error) { android.util.Log.w("WorkshopWireless", "Fixture socket stop", error); }
        fixtureSocket = null;
        try { backend.state.put("lan", "stopped").put("ble", "stopped").put("cleanup", new JSONObject().put("discoveryListeners", listeners.size()).put("pendingResolutions", pending.size()).put("bleCallbackRetained", scan != null).put("fixtureRegistered", fixture != null).put("fixtureSocketOpen", fixtureSocket != null)); backend.finish(reason); refresh.run(); }
        catch (Exception error) { android.util.Log.e("WorkshopWireless", "Stop persistence", error); }
    }
}
