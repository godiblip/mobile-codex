package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;

/** Keeps a user-started Live call audible and its microphone available off-screen. */
public final class LiveAudioService extends Service {
    static final String STOP = "dev.mobilecodex.app.STOP_LIVE";
    static final int NOTIFICATION_ID = 22;
    private static final String CHANNEL = "codex_live";
    private String key = "";
    private Runnable stopped;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wakeLock;

    final class LocalBinder extends Binder { LiveAudioService service() { return LiveAudioService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }

    @SuppressLint("WakelockTimeout") // The call owns this lock; every stop/failure/destroy releases it.
    void begin(String sessionKey, Runnable onStopped) {
        if (sessionKey == null || sessionKey.isBlank() || !key.isEmpty()) throw new IllegalStateException("Live audio is already active or missing its session key");
        key = sessionKey;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Codex Live", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 22, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Intent stopIntent = new Intent(this, LiveAudioService.class).setAction(STOP)
                .setData(android.net.Uri.parse("mobilecodex://live/" + sessionKey)).putExtra("key", sessionKey);
            PendingIntent stop = PendingIntent.getService(this, 22, stopIntent, PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.notification_icon).setContentTitle("Codex Live")
                .setContentText(t("음성 대화 중")).setContentIntent(open).setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, t("종료"), stop).build()).build();
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (Build.VERSION.SDK_INT >= 30) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification, types);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> { if (change < 0) stop(sessionKey); }).build();
            if (getSystemService(AudioManager.class).requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                throw new IllegalStateException(t("다른 앱에서 오디오를 사용 중입니다."));
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileCodex:LiveAudio");
            wakeLock.acquire();
            stopped = onStopped;
        } catch (RuntimeException error) { stop(sessionKey); throw error; }
    }

    void stop(String sessionKey) {
        if (key.isEmpty() || !key.equals(sessionKey)) return;
        key = "";
        Runnable callback = stopped; stopped = null;
        if (wakeLock != null) { if (wakeLock.isHeld()) wakeLock.release(); wakeLock = null; }
        if (focus != null) { getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); focus = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (callback != null) callback.run();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) stop(intent.getStringExtra("key"));
        // A stale notification must neither end a replacement call nor leave an idle service.
        if (key.isEmpty()) stopSelf(startId);
        return START_NOT_STICKY;
    }
    @Override public void onTaskRemoved(Intent rootIntent) { stop(key); super.onTaskRemoved(rootIntent); }
    @Override public void onDestroy() { stop(key); super.onDestroy(); }
}
