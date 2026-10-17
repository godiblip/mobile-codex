package dev.mobilecodex.app;

/** Audio transport owned by the service, with no Activity or WebView dependency. */
interface LiveRtc {
    interface Listener {
        void offer(String sdp);
        void connection(String state);
        void levels(double input, double output, long recorded, long played);
        void error(String message);
    }
    void start(Listener listener);
    void answer(String sdp);
    void mute(boolean muted);
    void close();
}
