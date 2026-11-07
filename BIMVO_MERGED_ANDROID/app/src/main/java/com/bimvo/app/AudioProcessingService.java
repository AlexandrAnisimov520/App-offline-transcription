package com.bimvo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayDeque;

/** User-initiated foreground processing with a visible cancel action. */
public final class AudioProcessingService extends Service {
    private static final String CHANNEL = "bimvo_offline_audio";
    private static final int NOTIFICATION_ID = 40;
    static final String START = "com.bimvo.app.PROCESS_AUDIO";
    static final String CANCEL = "com.bimvo.app.CANCEL_AUDIO";
    static final String KEY_RECORD = "recording_id";
    private String workingId = "";
    private int startToken;
    private final ArrayDeque<String> waiting = new ArrayDeque<>();
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());

    static void start(Context context, String recordingId) {
        Intent intent = new Intent(context, AudioProcessingService.class).setAction(START)
                .putExtra(KEY_RECORD, recordingId);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,"BIMVO · Обработка аудио",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startToken = startId;
        String action = intent == null ? "" : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra(KEY_RECORD);
        if (CANCEL.equals(action)) {
            if (id != null && id.equals(workingId))
                ((BimvoApplication) getApplication()).cancelProcessing(id);
            else waiting.remove(id);
            return START_NOT_STICKY;
        }
        if (id == null || !id.matches("[a-f0-9-]{36}")) {
            if (workingId.isEmpty()) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!workingId.isEmpty()) {
            // New imports/recordings share the one 4 GB RAM-friendly pipeline.
            // The first one completes before the next model session is loaded.
            if (!workingId.equals(id) && !waiting.contains(id)) waiting.addLast(id);
            return START_NOT_STICKY;
        }
        startNext(id);
        return START_NOT_STICKY;
    }

    private void startNext(String id) {
        workingId = id;
        showNotification(0);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BIMVO:audioProcessing");
        wakeLock.acquire(4L * 60L * 60L * 1000L);
        BimvoApplication app = (BimvoApplication) getApplication();
        if (!app.startJobFromService(id, () -> main.post(() -> {
            if (workingId.equals(id)) {
                releaseWakeLock();
                workingId = "";
                nextOrStop();
            }
        }))) {
            releaseWakeLock();
            workingId = "";
            nextOrStop();
        }
    }

    private void nextOrStop() {
        if (!waiting.isEmpty()) startNext(waiting.removeFirst());
        else {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startToken);
        }
    }

    void showNotification(int percent) {
        Intent view = new Intent(this,MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this,11,view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this,AudioProcessingService.class).setAction(CANCEL)
                .putExtra(KEY_RECORD,workingId);
        PendingIntent stop = PendingIntent.getService(this,12,cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = new Notification.Builder(this,CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("BIMVO · Локальное распознавание")
                .setContentText("Запись обрабатывается на устройстве")
                .setContentIntent(open).setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,"Отменить",stop).build())
                .setProgress(100,Math.max(0,percent),percent == 0);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID,n.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID,n.build());
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override public void onDestroy() { releaseWakeLock(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
