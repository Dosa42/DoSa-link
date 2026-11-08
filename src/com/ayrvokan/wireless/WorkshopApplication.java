package com.ayrvokan.wireless;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.File;
import org.json.JSONObject;

public final class WorkshopApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("debug-controls"); thread.start();
        BroadcastReceiver endpoint = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra("pid", -1) != android.os.Process.myPid()) return;
                String id = intent.getStringExtra("id");
                String action = intent.getStringExtra("action");
                try {
                    File dir = DebugJobs.directory(context, id);
                    if ("arm".equals(action)) {
                        JSONObject request = new JSONObject(JadxJobs.read(new File(dir, "request.json")));
                        JSONObject result = NativeDebug.arm(context, request, dir);
                        if (result.getString("phase").equals("armed") && request.optBoolean("fixture")) probe(context, id, "java", false);
                    } else if ("probe".equals(action)) probe(context, id, intent.getStringExtra("mode"), intent.getBooleanExtra("finishProbe", false));
                    else NativeDebug.control(action, intent.getIntExtra("depth", 0), intent.getIntExtra("slot", 0), intent.getIntExtra("value", 0));
                } catch (Throwable error) {
                    try { JadxJobs.write(new File(DebugJobs.directory(context, id), "endpoint-error.json"), new JSONObject().put("phase", "error").put("error", error.toString()).put("stack", android.util.Log.getStackTraceString(error))); }
                    catch (Exception failure) { android.util.Log.e("WorkshopDebug", "Endpoint failed", failure); }
                }
            }
        };
        IntentFilter filter = new IntentFilter(getPackageName() + ".DEBUG");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(endpoint, filter, null, new Handler(thread.getLooper()), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(endpoint, filter, null, new Handler(thread.getLooper()));
    }

    private void probe(Context context, String id, String mode, boolean finish) {
        new Thread(() -> {
            try {
                int result = "native".equals(mode) ? NativeDebug.nativeProbe() : DebugProbe.calculate(7, 9);
                JSONObject value = new JSONObject().put("mode", mode).put("result", result).put("pid", android.os.Process.myPid()).put("thread", Thread.currentThread().getName());
                if ("native".equals(mode)) value.put("native", new JSONObject(NativeDebug.nativeInfo()));
                JadxJobs.write(new File(DebugJobs.directory(context, id), "probe-result.json"), value);
            } catch (Throwable error) {
                try { JadxJobs.write(new File(DebugJobs.directory(context, id), "probe-result.json"), new JSONObject().put("error", error.toString())); }
                catch (Exception failure) { android.util.Log.e("WorkshopDebug", "Probe failed", failure); }
            } finally { if (finish) context.stopService(new Intent(context, DebugProbeService.class)); }
        }, "debug-" + mode + "-probe").start();
    }
}
