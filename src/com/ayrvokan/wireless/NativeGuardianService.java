package com.ayrvokan.wireless;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import org.json.JSONObject;

public final class NativeGuardianService extends Service {
    private volatile Process process;
    private String active;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || active != null) return START_NOT_STICKY;
        active = intent.getStringExtra("id");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("native-guardian", "Native debugger lifecycle", NotificationManager.IMPORTANCE_LOW));
        startForeground(26, new Notification.Builder(this, "native-guardian").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Native debugger lifecycle").setContentText("Maintains breakpoint cleanup and detach if the debugger UI exits.").setOngoing(true).build());
        new Thread(() -> execute(intent.getIntExtra("port", 0), intent.getIntExtra("targetPid", -1)), "native-guardian").start();
        return START_NOT_STICKY;
    }
    private void execute(int port, int target) {
        File dir = DebugJobs.directory(this, active);
        try {
            JadxJobs.write(new File(dir, "guardian-worker.json"), new JSONObject().put("pid", android.os.Process.myPid()).put("startTicks", TerminalJobs.ticks(android.os.Process.myPid())));
            File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
            process = new ProcessBuilder(new File(nativeDir, "libworkshop_lldb_launch.so").getPath(), new File(dir, "server-process.json").getPath(), new File(nativeDir, "libworkshop_lldb.so").getPath(), "127.0.0.1:" + port, Integer.toString(target)).redirectOutput(new File(dir, "server-stdout.log")).redirectError(new File(dir, "server-stderr.log")).start();
            int status = process.waitFor();
            JadxJobs.write(new File(dir, "guardian-worker-report.json"), new JSONObject().put("exitCode", status).put("pid", android.os.Process.myPid()));
        } catch (Throwable error) {
            try { JadxJobs.write(new File(dir, "guardian-failure.json"), new JSONObject().put("error", error.toString()).put("stack", android.util.Log.getStackTraceString(error))); }
            catch (Exception failure) { android.util.Log.e("WorkshopDebug", "Guardian failure save failed", failure); }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
    @Override public void onDestroy() { Process child = process; if (child != null && child.isAlive()) child.destroy(); super.onDestroy(); }
}
