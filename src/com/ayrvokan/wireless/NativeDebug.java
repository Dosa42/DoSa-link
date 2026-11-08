package com.ayrvokan.wireless;

import android.content.Context;
import android.os.Build;
import java.io.File;
import org.json.JSONObject;

public final class NativeDebug {
    static { System.loadLibrary("workshop_debug"); }
    private static boolean attached;
    public static synchronized void attach(Context context) throws Exception {
        if (attached) return;
        if (Build.VERSION.SDK_INT < 28) throw new UnsupportedOperationException("In-app ART agent attachment requires Android9/API28 or newer");
        android.os.Debug.attachJvmtiAgent(new File(context.getApplicationInfo().nativeLibraryDir, "libworkshop_debug.so").getPath(), "", NativeDebug.class.getClassLoader());
        attached = true;
    }
    public static JSONObject arm(Context context, JSONObject request, File directory) throws Exception {
        attach(context);
        Class<?> type = Class.forName(request.getString("class"), false, NativeDebug.class.getClassLoader());
        return new JSONObject(arm(type, request.getString("method"), request.getString("signature"), request.optLong("location", -1), request.optInt("line"), directory.getPath(), request.getInt("controllerPid"), request.getString("controllerStartTicks")));
    }
    private static native String arm(Class<?> type, String method, String signature, long location, int line, String directory, int controller, String ticks);
    public static native String control(String action, int depth, int slot, int value);
    public static native String nativeInfo();
    public static native int nativeProbe();
}
