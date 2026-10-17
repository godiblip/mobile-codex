package dev.mobilecodex.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** One native WebRTC peer per Live call. All peer mutations run on the RTC worker. */
final class NativeLiveRtc implements LiveRtc {
    private final Context context;
    private final HandlerThread thread = new HandlerThread("CodexLiveRtc");
    private final Handler worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Listener listener;
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule audio;
    private PeerConnection peer;
    private AudioSource source;
    private org.webrtc.AudioTrack microphone;
    private DataChannel channel;
    private boolean offered, connected;
    private final LivePlaybackWatchdog watchdog = new LivePlaybackWatchdog();
    private volatile long recorded, played, lastPlayback;
    private volatile double inputLevel, outputLevel;
    private volatile boolean playbackStarted;
    private long disconnectedAt;

    NativeLiveRtc(Context context) {
        this.context = context.getApplicationContext();
        thread.start(); worker = new Handler(thread.getLooper());
    }
    private void run(Runnable task) {
        worker.post(() -> {
            if (closed.get()) return;
            try { task.run(); } catch (RuntimeException | LinkageError error) { fail("음성 연결에 실패했습니다. 다시 연결해 주세요."); }
        });
    }
    private void fail(String message) { if (!closed.get() && listener != null) listener.error(message); }
    @Override public void start(Listener listener) {
        this.listener = listener;
        run(() -> {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false).createInitializationOptions());
            audio = JavaAudioDeviceModule.builder(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setUseStereoInput(false).setUseStereoOutput(false).setEnableVolumeLogger(false)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setSamplesReadyCallback(samples -> { recorded++; if (recorded % 10 == 0) inputLevel = level(samples); })
                .setPlaybackSamplesReadyCallback(samples -> {
                    played++; lastPlayback = SystemClock.elapsedRealtime();
                    if (played % 10 == 0) outputLevel = level(samples);
                })
                .setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback() {
                    public void onWebRtcAudioTrackStart() { playbackStarted = true; lastPlayback = SystemClock.elapsedRealtime(); }
                    public void onWebRtcAudioTrackStop() { /* Watchdog distinguishes teardown from an unexpected stop. */ }
                })
                .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback() {
                    public void onWebRtcAudioTrackInitError(String message) { run(() -> fail("음성 재생을 시작할 수 없습니다.")); }
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code, String message) { run(() -> fail("음성 재생을 시작할 수 없습니다.")); }
                    public void onWebRtcAudioTrackError(String message) { run(() -> recoverPlayback()); }
                })
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    public void onWebRtcAudioRecordInitError(String message) { run(() -> fail("마이크 연결이 종료되었습니다.")); }
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code, String message) { run(() -> fail("마이크 연결이 종료되었습니다.")); }
                    public void onWebRtcAudioRecordError(String message) { run(() -> fail("마이크 연결이 종료되었습니다.")); }
                }).createAudioDeviceModule();
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory();
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(List.of());
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
                public void onSignalingChange(PeerConnection.SignalingState state) {}
                public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
                public void onIceConnectionReceivingChange(boolean receiving) {}
                public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) run(() -> sendOffer());
                }
                public void onIceCandidate(IceCandidate candidate) {}
                public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                public void onAddStream(MediaStream stream) {}
                public void onRemoveStream(MediaStream stream) {}
                public void onDataChannel(DataChannel channel) {}
                public void onRenegotiationNeeded() {}
                public void onConnectionChange(PeerConnection.PeerConnectionState state) { run(() -> {
                    connected = state == PeerConnection.PeerConnectionState.CONNECTED;
                    disconnectedAt = state == PeerConnection.PeerConnectionState.DISCONNECTED ? SystemClock.elapsedRealtime() : 0;
                    listener.connection(state.name().toLowerCase(java.util.Locale.ROOT));
                    if (state == PeerConnection.PeerConnectionState.FAILED) fail("음성 연결이 끊겼습니다.");
                }); }
            });
            if (peer == null) throw new IllegalStateException("Native WebRTC unavailable");
            source = factory.createAudioSource(new MediaConstraints());
            microphone = factory.createAudioTrack("codex-microphone", source);
            peer.addTrack(microphone, List.of("codex-live"));
            channel = peer.createDataChannel("oai-events", new DataChannel.Init());
            channel.registerObserver(new DataChannel.Observer() {
                public void onBufferedAmountChange(long amount) {}
                public void onStateChange() {}
                public void onMessage(DataChannel.Buffer buffer) {
                    if (buffer.binary || buffer.data.remaining() > 262144) return;
                    byte[] bytes = new byte[buffer.data.remaining()]; buffer.data.get(bytes);
                    try {
                        JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                        if ("error".equals(data.optString("type"))) run(() -> fail("음성 연결 오류"));
                    } catch (Exception ignored) { }
                }
            });
            peer.createOffer(new Sdp() {
                @Override public void onCreateSuccess(SessionDescription description) { run(() ->
                    peer.setLocalDescription(new Sdp() {
                        @Override public void onSetSuccess() { worker.postDelayed(() -> { if (!closed.get()) sendOffer(); }, 2000); }
                    }, description)); }
            }, new MediaConstraints());
            worker.postDelayed(tick, 250);
        });
    }
    private void sendOffer() {
        if (offered || peer == null || peer.getLocalDescription() == null) return;
        offered = true; listener.offer(peer.getLocalDescription().description);
    }
    @Override public void answer(String sdp) { run(() -> peer.setRemoteDescription(new Sdp(), new SessionDescription(SessionDescription.Type.ANSWER, sdp))); }
    @Override public void mute(boolean muted) { run(() -> { microphone.setEnabled(!muted); audio.setMicrophoneMute(muted); }); }
    private void recoverPlayback() {
        if (!connected || closed.get()) return;
        if (!watchdog.recover(SystemClock.elapsedRealtime())) { fail("음성 재생이 중단되었습니다. 다시 연결해 주세요."); return; }
        peer.setAudioPlayout(false); peer.setAudioPlayout(true);
        lastPlayback = SystemClock.elapsedRealtime();
    }
    private final Runnable tick = new Runnable() { public void run() {
        if (closed.get()) return;
        long now = SystemClock.elapsedRealtime();
        if (disconnectedAt > 0 && now - disconnectedAt > 15000) fail("음성 연결이 끊겼습니다.");
        // PCM callbacks advance even for silence; user silence never triggers recovery.
        if (watchdog.stalled(now, connected, playbackStarted, lastPlayback)) recoverPlayback();
        listener.levels(inputLevel, outputLevel, recorded, played);
        worker.postDelayed(this, 250);
    }};
    static double level(JavaAudioDeviceModule.AudioSamples samples) {
        if (samples.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) return 0;
        byte[] data = samples.getData(); double sum = 0; int count = data.length / 2;
        for (int i = 0; i + 1 < data.length; i += 2) { double value = (short)((data[i] & 255) | (data[i + 1] << 8)) / 32768.0; sum += value * value; }
        return count == 0 ? 0 : Math.min(1, Math.sqrt(sum / count) * 5);
    }
    private class Sdp implements SdpObserver {
        public void onCreateSuccess(SessionDescription description) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String error) { run(() -> fail("음성 연결에 실패했습니다. 다시 연결해 주세요.")); }
        public void onSetFailure(String error) { run(() -> fail("음성 연결에 실패했습니다. 다시 연결해 주세요.")); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.post(() -> {
            worker.removeCallbacksAndMessages(null);
            if (channel != null) { channel.unregisterObserver(); channel.dispose(); }
            if (peer != null) peer.dispose();
            if (microphone != null) microphone.dispose();
            if (source != null) source.dispose();
            if (factory != null) factory.dispose();
            if (audio != null) audio.release();
            thread.quitSafely();
        });
    }
}
