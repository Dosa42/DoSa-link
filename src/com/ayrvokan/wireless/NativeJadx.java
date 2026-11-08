package com.ayrvokan.wireless;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NativeJadx {
    private static native String invoke(String home, String classpath, String request, String output, String progress, String[] options) throws java.io.IOException;

    private static void asset(Context context, String name, File target, String expected) throws Exception {
        target.getParentFile().mkdirs();
        if (target.isFile() && JadxJobs.hash(target).equals(expected)) return;
        File part = new File(target.getPath() + ".part");
        JadxJobs.remove(part);
        try (InputStream from = context.getAssets().open("workshop/jadx/" + name); FileOutputStream to = new FileOutputStream(part)) {
            JadxJobs.copy(from, to);
            to.getFD().sync();
        }
        if (!JadxJobs.hash(part).equals(expected)) throw new java.io.IOException("Bundled runtime digest mismatch: " + name);
        if (!part.renameTo(target)) throw new java.io.IOException("Unable to finalize runtime file: " + name);
    }

    public static String run(Context context, File request, File output, File progress) throws Exception {
        JSONObject tool;
        try (InputStream input = context.getAssets().open("workshop/jadx/tool.json"); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            JadxJobs.copy(input, bytes);
            tool = new JSONObject(bytes.toString("UTF-8"));
        }
        File root = new File(context.getCodeCacheDir(), "jadx-native/" + tool.getString("sourceFingerprint"));
        File runtime = new File(root, "runtime");
        File marker = new File(runtime, ".complete");
        JSONObject files = tool.getJSONObject("files");
        File archive = new File(root, "runtime.zip");
        if (!marker.isFile() || !JadxJobs.read(marker).equals(files.getString("runtime.zip"))) {
            asset(context, "runtime.zip", archive, files.getString("runtime.zip"));
            File part = new File(root, "runtime.part");
            JadxJobs.remove(part);
            part.mkdirs();
            try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    File target = new File(part, entry.getName());
                    if (!target.getCanonicalPath().startsWith(part.getCanonicalPath() + File.separator)) throw new java.io.IOException("Invalid bundled runtime entry");
                    if (entry.isDirectory()) target.mkdirs();
                    else {
                        target.getParentFile().mkdirs();
                        try (FileOutputStream to = new FileOutputStream(target)) { JadxJobs.copy(zip, to); }
                    }
                }
            }
            try (FileOutputStream to = new FileOutputStream(new File(part, ".complete"))) { to.write(files.getString("runtime.zip").getBytes(java.nio.charset.StandardCharsets.UTF_8)); to.getFD().sync(); }
            JadxJobs.remove(runtime);
            if (!part.renameTo(runtime)) throw new java.io.IOException("Unable to finalize bundled JVM");
            JadxJobs.remove(archive);
        }
        List<String> jars = new ArrayList<>();
        Iterator<String> names = files.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.startsWith("libs/") || !name.endsWith(".jar")) continue;
            File target = new File(root, name);
            asset(context, name, target, files.getString(name));
            jars.add(target.getPath());
        }
        java.util.Collections.sort(jars);
        String classpath = android.text.TextUtils.join(File.pathSeparator, jars);
        File bridge = new File(context.getApplicationInfo().nativeLibraryDir, "libworkshop_jvm.so");
        if (!JadxJobs.hash(bridge).equals(tool.getString("nativeBridgeSha256"))) throw new java.io.IOException("Bundled JNI bridge digest mismatch");
        System.load(bridge.getPath());
        output.mkdirs();
        JSONObject value = new JSONObject(JadxJobs.read(request));
        JSONArray configured = value.optJSONArray("vmOptions");
        String[] options = new String[configured == null ? 0 : configured.length()];
        for (int index = 0; index < options.length; index++) options[index] = configured.getString(index);
        return invoke(runtime.getPath(), classpath, request.getPath(), output.getPath(), progress.getPath(), options);
    }

    public static void cleanUnfinished(Context context) throws Exception {
        File root = new File(context.getCodeCacheDir(), "jadx-native");
        File[] versions = root.listFiles();
        if (versions == null) return;
        for (File version : versions) {
            JadxJobs.remove(new File(version, "runtime.part"));
            JadxJobs.remove(new File(version, "runtime.zip.part"));
            JadxJobs.remove(new File(version, "runtime.zip"));
            File[] jars = new File(version, "libs").listFiles();
            if (jars != null) for (File jar : jars) if (jar.getName().endsWith(".part")) JadxJobs.remove(jar);
        }
    }
}
