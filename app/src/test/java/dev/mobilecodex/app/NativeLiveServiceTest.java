package dev.mobilecodex.app;

import android.app.Application;
import android.media.AudioManager;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static dev.mobilecodex.app.core.Json.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={29,35}, application=Application.class)
public class NativeLiveServiceTest {
    static class Rtc implements LiveRtc {
        Listener listener; String answer; boolean muted, closed;
        public void start(Listener listener) { this.listener = listener; }
        public void answer(String sdp) { answer = sdp; }
        public void mute(boolean value) { muted = value; }
        public void close() { closed = true; }
    }
    void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    @Test public void nativePeerOwnsSignalingMuteAndCleanupWithoutWebView() {
        var controller = Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service = controller.get(); var rtc = new Rtc(); List<String> calls = new ArrayList<>();
            service.transport = () -> rtc;
            service.backend = (action,args,reply) -> { calls.add(action);
                if (action.equals("live.start")) { assertEquals("native offer",args.optString("sdp")); reply.complete(obj("threadId","thread"),null); }
                else reply.complete(obj(),null);
            };
            var manager = service.getSystemService(AudioManager.class); manager.setMode(AudioManager.MODE_NORMAL);
            service.begin("one",()->{});
            assertEquals(AudioManager.MODE_IN_COMMUNICATION,manager.getMode());
            AtomicInteger replies = new AtomicInteger();
            service.connect(obj("key","one","model","chosen-model"),(result,error)->{assertNull(error); replies.incrementAndGet();});
            rtc.listener.offer("native offer"); idle(); assertEquals(1,replies.get());
            service.event("live.event",obj("key","one","method","thread/realtime/sdp","params",obj("sdp","native answer"))); idle();
            assertEquals("native answer",rtc.answer);
            rtc.listener.connection("connected"); rtc.listener.levels(.3,.6,100,200); idle();
            assertTrue(service.snapshot().optBoolean("connected")); assertEquals(200,service.snapshot().optLong("played"));
            service.mute("one",true); assertTrue(rtc.muted); assertEquals(0,service.snapshot().optDouble("input"),0);
            service.stop("stale"); assertFalse(rtc.closed);
            service.stop("one"); assertTrue(rtc.closed); assertNull(LiveAudioService.active());
            assertEquals(AudioManager.MODE_NORMAL,manager.getMode());
            assertEquals(List.of("live.start","live.stop"),calls);
        } finally { controller.destroy(); }
    }
    @Test public void cancellationRejectsPendingStartAndIgnoresLateAudioEvents() {
        var controller = Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service=controller.get(); var rtc=new Rtc(); service.transport=()->rtc;
            List<Engine.Reply> backendReply=new ArrayList<>(); List<String> stopped=new ArrayList<>();
            service.backend=(action,args,reply)->{ if(action.equals("live.start"))backendReply.add(reply); else stopped.add(args.optString("key")); };
            service.begin("one",()->{}); AtomicInteger replies=new AtomicInteger();
            service.connect(obj("key","one"),(result,error)->{assertNotNull(error);replies.incrementAndGet();});
            rtc.listener.offer("offer"); idle(); service.stop("one");
            assertEquals(1,replies.get()); assertTrue(rtc.closed);
            backendReply.get(0).complete(obj("threadId","old"),null);
            rtc.listener.connection("connected"); rtc.listener.levels(1,1,1,1); idle();
            assertEquals("",service.snapshot().optString("key")); assertEquals(1,replies.get());
            assertEquals(List.of("one","one"),stopped);
        } finally { controller.destroy(); }
    }
    @Test public void nativeFailureAndTimeoutReleaseAudioModeAndPendingRequest() {
        var controller=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service=controller.get(); var rtc=new Rtc(); service.transport=()->rtc; service.backend=(a,p,r)->{};
            service.begin("one",()->{}); AtomicInteger replies=new AtomicInteger();
            service.connect(obj("key","one"),(r,e)->{assertNotNull(e);replies.incrementAndGet();});
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46));
            assertEquals(1,replies.get()); assertTrue(rtc.closed); assertNull(LiveAudioService.active());
            assertEquals(AudioManager.MODE_NORMAL,service.getSystemService(AudioManager.class).getMode());
        } finally { controller.destroy(); }
    }
    @Test public void oldTransportCannotAffectReplacementSession() {
        var controller=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service=controller.get(); var old=new Rtc(); service.transport=()->old; service.backend=(a,p,r)->{};
            service.begin("one",()->{}); service.connect(obj("key","one"),(r,e)->{}); service.stop("one");
            var next=new Rtc(); service.transport=()->next; service.begin("two",()->{}); service.connect(obj("key","two"),(r,e)->{});
            old.listener.error("old failure"); old.listener.connection("connected"); idle();
            assertEquals("two",service.snapshot().optString("key")); assertFalse(next.closed);
            next.listener.error("new failure"); idle(); assertTrue(next.closed);
        } finally { controller.destroy(); }
    }
}
