package com.ayrvokan.wireless;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import org.json.JSONObject;

public final class DebugProbeService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("debug-probe", "Debugger probe worker", NotificationManager.IMPORTANCE_LOW));
        startForeground(25, new Notification.Builder(this, "debug-probe").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Debugger probe worker").setContentText("Ready for Java or native breakpoints.").setOngoing(true).build());
        try { JadxJobs.write(new File(getFilesDir(), "debug-probe.json"), new JSONObject(NativeDebug.nativeInfo()).put("startTicks", TerminalJobs.ticks(android.os.Process.myPid())).put("token", intent.getStringExtra("token"))); }
        catch (Throwable error) { try { JadxJobs.write(new File(getFilesDir(), "debug-probe-error.json"), new JSONObject().put("token", intent.getStringExtra("token")).put("error", error.toString())); } catch (Exception failure) { android.util.Log.e("WorkshopDebug", "Probe failure save failed", failure); } android.util.Log.e("WorkshopDebug", "Probe startup failed", error); stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); android.os.Process.killProcess(android.os.Process.myPid()); }
    @Override public void onTimeout(int startId, int type) { stopSelf(); }
}
