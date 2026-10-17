package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import org.json.JSONObject;
import static dev.mobilecodex.app.core.Json.*;

/** Keeps a user-started Live call audible and its microphone available off-screen. */
public final class LiveAudioService extends Service implements Engine.Ui {
    static final String STOP = "dev.mobilecodex.app.STOP_LIVE";
    static final int NOTIFICATION_ID = 22;
    private static final String CHANNEL = "codex_live";
    private String key = "";
    private Runnable stopped;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wakeLock;
    private static LiveAudioService running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LiveAudioRoute route;
    private LiveRtc rtc;
    private Engine engine;
    private Engine.Reply pending;
    private String phase = "connecting", threadId = "", error = "", userCaption = "", assistantCaption = "";
    private boolean muted;
    private double inputLevel, outputLevel;
    private long recorded, played;
    private Runnable connectTimeout;
    interface Backend { void handle(String action, JSONObject args, Engine.Reply reply); }
    Backend backend;
    java.util.function.Supplier<LiveRtc> transport = () -> new NativeLiveRtc(this);
    static LiveAudioService active() { return running != null && !running.key.isEmpty() ? running : null; }
    @Override public void onCreate() {
        super.onCreate();
        if (getApplication() instanceof MobileCodexApp app) { engine = app.engine(); backend = engine::handle; }
    }
    JSONObject snapshot() { return obj("key", key, "phase", phase, "connected", phase.equals("connected"), "muted", muted,
        "threadId", threadId, "input", muted ? 0 : inputLevel, "output", outputLevel,
        "recorded", recorded, "played", played, "error", error, "userCaption", userCaption, "assistantCaption", assistantCaption); }
    private void emit() { if (engine != null) engine.liveAudioChanged(snapshot()); }

    final class LocalBinder extends Binder { LiveAudioService service() { return LiveAudioService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }

    @SuppressLint("WakelockTimeout") // The call owns this lock; every stop/failure/destroy releases it.
    void begin(String sessionKey, Runnable onStopped) {
        if (sessionKey == null || sessionKey.isBlank() || !key.isEmpty()) throw new IllegalStateException("Live audio is already active or missing its session key");
        key = sessionKey;
        phase = "connecting"; threadId = ""; error = ""; userCaption = ""; assistantCaption = "";
        muted = false; recorded = played = 0; inputLevel = outputLevel = 0;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Codex Live", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 22, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Intent stopIntent = new Intent(this, LiveAudioService.class).setAction(STOP)
                .setData(android.net.Uri.parse("mobilecodex://live/" + sessionKey)).putExtra("key", sessionKey);
            PendingIntent stop = PendingIntent.getService(this, 22, stopIntent, PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.notification_icon).setContentTitle("Codex Live")
                .setContentText(t("음성 대화 중")).setContentIntent(open).setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL).setOnlyAlertOnce(true);
            if (Build.VERSION.SDK_INT >= 31) {
                notification.setStyle(Notification.CallStyle.forOngoingCall(
                    new Person.Builder().setName("Codex Live").setImportant(true).build(), stop))
                    .setUsesChronometer(true);
            } else notification.addAction(new Notification.Action.Builder(null, t("종료"), stop).build());
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (Build.VERSION.SDK_INT >= 30) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification.build(), types);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> { if (change < 0) stop(sessionKey); }).build();
            if (getSystemService(AudioManager.class).requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                throw new IllegalStateException(t("다른 앱에서 오디오를 사용 중입니다."));
            route = new LiveAudioRoute(this, main); route.begin();
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileCodex:LiveAudio");
            wakeLock.acquire();
            stopped = onStopped;
            running = this;
            if (engine != null) engine.observe(this);
            startService(new Intent(this, LiveAudioService.class));
            connectTimeout = () -> { if (sessionKey.equals(key)) fail(t("음성 연결 시간이 초과되었습니다.")); };
            main.postDelayed(connectTimeout, 45000);
        } catch (RuntimeException error) { stop(sessionKey); throw error; }
    }

    void connect(JSONObject parameters, Engine.Reply reply) {
        if (!parameters.optString("key").equals(key) || key.isEmpty() || rtc != null || backend == null) {
            reply.complete(null, new IllegalStateException(t("음성 연결을 시작할 수 없습니다."))); return;
        }
        String sessionKey = key; pending = reply;
        try {
            JSONObject args = new JSONObject(parameters.toString());
            rtc = transport.get();
            rtc.start(new LiveRtc.Listener() {
                public void offer(String sdp) { main.post(() -> {
                    if (!sessionKey.equals(key)) return;
                    try { args.put("sdp", sdp); } catch (Exception e) { fail(e.getMessage()); return; }
                    backend.handle("live.start", args, (result, failure) -> main.post(() -> {
                        if (!sessionKey.equals(key)) { backend.handle("live.stop", obj("key", sessionKey), (r,e) -> {}); return; }
                        if (failure != null) { fail(failure.getMessage()); return; }
                        threadId = result.optString("threadId");
                        Engine.Reply response = pending; pending = null;
                        if (response != null) response.complete(snapshot(), null);
                        emit();
                    }));
                }); }
                public void connection(String value) { main.post(() -> { if (sessionKey.equals(key)) {
                    phase = value;
                    if (value.equals("connected") && connectTimeout != null) { main.removeCallbacks(connectTimeout); connectTimeout = null; }
                    android.util.Log.i("CodexLive", "Native audio: " + value); emit();
                } }); }
                public void levels(double input, double output, long captured, long rendered) { main.post(() -> {
                    if (!sessionKey.equals(key)) return;
                    inputLevel = input; outputLevel = output; recorded = captured; played = rendered; emit();
                }); }
                public void error(String message) { main.post(() -> { if (sessionKey.equals(key)) fail(t(message)); }); }
            });
        } catch (Exception e) { fail(t("음성 연결에 실패했습니다. 다시 연결해 주세요.")); }
    }
    void mute(String sessionKey, boolean value) {
        if (!sessionKey.equals(key) || rtc == null) throw new IllegalStateException(t("음성 연결이 종료되었습니다."));
        muted = value; rtc.mute(value); emit();
    }
    private void fail(String message) { error = message == null ? t("음성 연결 오류") : message; stop(key); }
    @Override public void event(String name, JSONObject data) { main.post(() -> {
        if (!name.equals("live.event") || key.isEmpty() || !key.equals(data.optString("key"))) return;
        JSONObject params = data.optJSONObject("params"); if (params == null) params = obj();
        String method = data.optString("method");
        if (method.equals("thread/realtime/sdp") && rtc != null) rtc.answer(params.optString("sdp"));
        else if (method.equals("thread/realtime/error")) fail(params.optString("message"));
        else if (method.equals("thread/realtime/closed")) stop(key);
        else if (method.equals("thread/realtime/transcript/delta") || method.equals("thread/realtime/transcript/done")) {
            boolean user = "user".equals(params.optString("role"));
            String text = method.endsWith("/done") ? params.optString("text") : (user ? userCaption : assistantCaption) + params.optString("delta");
            text = text.substring(Math.max(0, text.length() - 1600));
            if (user) userCaption = text; else assistantCaption = text;
        }
    }); }
    @Override public void approval(Engine.Approval approval) { /* Existing activity/notification approval flow owns this. */ }

    void stop(String sessionKey) {
        if (key.isEmpty() || !key.equals(sessionKey)) return;
        phase = "ended";
        key = "";
        if (running == this) running = null;
        main.removeCallbacksAndMessages(null);
        connectTimeout = null;
        if (engine != null) engine.unobserve(this);
        if (rtc != null) { rtc.close(); rtc = null; }
        if (route != null) { route.close(); route = null; }
        Engine.Reply response = pending; pending = null;
        if (response != null) response.complete(null, new IllegalStateException(error.isEmpty() ? t("음성 연결이 취소되었습니다.") : error));
        if (backend != null) backend.handle("live.stop", obj("key", sessionKey), (r,e) -> {});
        if (engine != null) engine.liveAudioChanged(obj("key", sessionKey, "phase", "ended", "error", error));
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
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        // Device diagnostics without attaching a WebView debugger or exposing conversation content.
        writer.println("CodexLive native=" + (rtc != null) + " phase=" + phase + " muted=" + muted
            + " recorded=" + recorded + " played=" + played + " input=" + inputLevel + " output=" + outputLevel);
    }
}
