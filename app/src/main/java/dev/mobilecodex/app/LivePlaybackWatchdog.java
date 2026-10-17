package dev.mobilecodex.app;

/** Uses PCM callback progress, not amplitude, so ordinary silence is healthy. */
final class LivePlaybackWatchdog {
    private int attempts;
    private long lastAttempt;
    boolean stalled(long now, boolean connected, boolean started, long lastCallback) {
        return connected && started && now - lastCallback > 5000 && now - lastAttempt > 5000;
    }
    boolean recover(long now) { lastAttempt = now; return ++attempts <= 2; }
}
