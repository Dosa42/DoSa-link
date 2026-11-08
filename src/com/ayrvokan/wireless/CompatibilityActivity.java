package com.ayrvokan.wireless;

import android.app.Activity;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CompatibilityActivity extends Activity {
    private TextView view;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this); view.setTextSize(15); view.setPadding(24, 60, 24, 24); view.setTextIsSelectable(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(view); setContentView(scroll);
        view.setText("Checking retained classes, native loading and JNI in a separate app process…");
        new Thread(this::check, "module-compatibility").start();
    }

    private void check() {
        try {
            String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
            File directory = new File(getFilesDir(), "compatibility-jobs/" + id); directory.mkdirs();
            JSONObject report = new JSONObject().put("schema", "wireless-workshop.compatibility/1").put("id", id).put("pid", android.os.Process.myPid()).put("process", getApplicationInfo().processName + ":compat").put("phase", "running").put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE)).put("sdk", android.os.Build.VERSION.SDK_INT);
            JadxJobs.write(new File(directory, "state.json"), report);
            JSONArray managed = new JSONArray(); report.put("managed", managed);
            for (String name : new String[] {"com.ckjmdns.RNReactNativeCkMdnsModule", "com.mdns.MdnsModule", "com.pair.bluetooth.coolkit.BLEPairModule", "com.pair.bluetooth.opulinks.BLEReactModule", "it.innove.BleManager"}) {
                JSONObject item = new JSONObject().put("class", name).put("scope", "class load without initialization and reflected method/constructor linkage");
                try {
                    Class<?> type = Class.forName(name, false, getClassLoader());
                    JSONArray methods = new JSONArray();
                    for (java.lang.reflect.Method method : type.getDeclaredMethods()) methods.put(method.toGenericString());
                    item.put("loaded", true).put("superclass", type.getSuperclass().getName()).put("constructors", type.getDeclaredConstructors().length).put("methods", methods);
                } catch (Throwable error) { item.put("loaded", false).put("error", error.toString()); }
                managed.put(item); JadxJobs.write(new File(directory, "state.json"), report);
            }
            JSONArray natives = new JSONArray(); report.put("nativeLoads", natives);
            JSONObject constructed = new JSONObject().put("class", "com.mdns.MdnsModule"); report.put("constructedModule", constructed);
            try {
                Class<?> react = Class.forName("com.facebook.react.bridge.ReactApplicationContext");
                Object reactContext = react.getConstructor(android.content.Context.class).newInstance(getApplicationContext());
                Class<?> module = Class.forName("com.mdns.MdnsModule");
                Object instance = module.getConstructor(react).newInstance(reactContext);
                constructed.put("constructed", true).put("getNameResult", module.getMethod("getName").invoke(instance));
            } catch (Throwable error) { constructed.put("constructed", false).put("error", error.toString()).put("cause", error.getCause() == null ? "" : error.getCause().toString()); }
            for (String name : new String[] {"TelinkCrypto", "IOTCAPIs"}) {
                JSONObject item = new JSONObject().put("library", "lib" + name + ".so");
                try { System.loadLibrary(name); item.put("loaded", true); }
                catch (Throwable error) { item.put("loaded", false).put("error", error.toString()); }
                natives.put(item); JadxJobs.write(new File(directory, "state.json"), report);
            }
            JSONObject jni = new JSONObject().put("method", "com.tutk.IOTC.IOTCAPIs.IOTC_Get_Version(int[])"); report.put("jni", jni);
            try {
                int[] version = new int[] {-1};
                Class.forName("com.tutk.IOTC.IOTCAPIs").getMethod("IOTC_Get_Version", int[].class).invoke(null, (Object) version);
                jni.put("executed", true).put("versionRaw", version[0]);
            } catch (Throwable error) { jni.put("executed", false).put("error", error.toString()).put("cause", error.getCause() == null ? "" : error.getCause().toString()); }
            JSONObject catalog;
            try (java.io.InputStream input = getAssets().open("workshop/catalog.json"); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) { JadxJobs.copy(input, output); catalog = new JSONObject(new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)); }
            JSONArray libraries = catalog.getJSONArray("libraries"); JSONArray elf = new JSONArray(); report.put("elf", elf);
            for (int i = 0; i < libraries.length(); i++) {
                JSONObject item = libraries.getJSONObject(i);
                File file = new File(getApplicationInfo().nativeLibraryDir, item.getString("name"));
                JSONObject result = new JSONObject().put("library", file.getName()).put("exists", file.isFile()).put("declaredDependencies", item.optJSONArray("needed")).put("runtimeDependencyResolution", "Only loaded-library maps and loader results establish resolution");
                if (file.isFile()) {
                    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                        byte[] header = new byte[64]; input.readFully(header); ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                        if (h.getInt(0) != 0x464c457f || header[4] != 2) throw new IllegalArgumentException("Expected ELF64");
                        long offset = h.getLong(32); int size = h.getShort(54) & 65535; int count = h.getShort(56) & 65535;
                        JSONArray alignments = new JSONArray(); boolean compatible = true;
                        for (int p = 0; p < count; p++) {
                            byte[] program = new byte[size]; input.seek(offset + (long) p * size); input.readFully(program); ByteBuffer ph = ByteBuffer.wrap(program).order(ByteOrder.LITTLE_ENDIAN);
                            if (ph.getInt(0) == 1) { long alignment = ph.getLong(48); alignments.put(alignment); compatible &= alignment >= report.getLong("pageSize") && (ph.getLong(8) - ph.getLong(16)) % report.getLong("pageSize") == 0; }
                        }
                        result.put("machine", h.getShort(18) & 65535).put("loadAlignments", alignments).put("compatibleWithCurrentPageSize", compatible).put("sha256", JadxJobs.hash(file));
                    } catch (Exception error) { result.put("error", error.toString()); }
                }
                elf.put(result);
            }
            JSONArray maps = new JSONArray();
            try (java.io.BufferedReader input = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
                String line; while ((line = input.readLine()) != null) if (line.contains(getApplicationInfo().nativeLibraryDir)) maps.put(line);
            }
            report.put("loadedMaps", maps).put("phase", "completed").put("finishedAt", System.currentTimeMillis()).put("scope", "Five retained module class checks, MdnsModule construction/getName, two native loaders, read-only IOTC version JNI and all100 vendor ELF files; no React runtime startup, login, pairing or device commands.");
            JadxJobs.write(new File(directory, "report.json"), report); JadxJobs.write(new File(directory, "state.json"), report); JadxJobs.write(new File(getFilesDir(), "compatibility-state.json"), report);
            try (WorkshopStore store = new WorkshopStore(this)) { store.trace("INFO", "compatibility", "completed", store.sessionId(), id, id + "-check", "", "local-loader", "Retained module compatibility observed", report, null); }
            runOnUiThread(() -> view.setText(report.toString()));
        } catch (Throwable error) {
            try { JadxJobs.write(new File(getFilesDir(), "compatibility-failure.json"), new JSONObject().put("error", error.toString()).put("stack", android.util.Log.getStackTraceString(error))); } catch (Exception failure) { android.util.Log.e("WorkshopCompatibility", "Report failed", failure); }
            runOnUiThread(() -> view.setText(error.toString()));
        }
    }
}
