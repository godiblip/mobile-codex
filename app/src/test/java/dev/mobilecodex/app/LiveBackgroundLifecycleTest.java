package dev.mobilecodex.app;

import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=MobileCodexApp.class)
public class LiveBackgroundLifecycleTest {
    static Object get(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    static void drain(Engine engine) throws Exception { engine.io.submit(()->{}).get(5,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle(); }
    @Test public void backgroundAndActivityRecreationRetainNativeCallUntilServiceStops() throws Exception {
        var controller=Robolectric.buildActivity(MainActivity.class).setup();
        var audio=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var activity=controller.get(); var engine=(Engine)get(activity,"engine");
            audio.get().begin("one",()->{});
            set(activity,"liveAudioKey","one"); set(activity,"liveMicrophoneAllowed",true); set(activity,"liveAudioService",audio.get());
            controller.pause().stop(); drain(engine);
            assertEquals("one",get(activity,"liveAudioKey")); assertEquals(true,get(activity,"liveMicrophoneAllowed"));
            assertFalse(((Collection<?>)get(engine,"observers")).contains(activity));
            assertTrue(((Collection<?>)get(engine,"observers")).contains(audio.get()));
            controller.start().resume(); drain(engine);
            assertEquals("one",get(activity,"liveAudioKey")); assertFalse(((Collection<?>)get(engine,"observers")).contains(activity));
            controller.pause().stop().destroy(); drain(engine);
            assertFalse(((Collection<?>)get(engine,"observers")).contains(activity));
            assertTrue(((Collection<?>)get(engine,"observers")).contains(audio.get()));
            assertEquals("one", audio.get().snapshot().optString("key"));
            assertNotNull(get(audio.get(),"wakeLock"));
            try (var replacement = Robolectric.buildActivity(MainActivity.class).setup()) {
                assertEquals("one",get(replacement.get(),"liveAudioKey"));
                assertEquals(true,get(replacement.get(),"liveMicrophoneAllowed"));
                audio.get().stop("one"); drain(engine);
                assertNull(get(audio.get(),"wakeLock"));
                replacement.pause().stop().start().resume();
                assertEquals("",get(replacement.get(),"liveAudioKey"));
            }
        } finally { audio.destroy(); }
    }
    @Test public void pendingMicrophonePermissionCannotStartCaptureAfterLeavingApp() throws Exception {
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            var activity=controller.get();
            set(activity,"liveAudioKey","pending"); set(activity,"livePermissionRequest","permission");
            controller.pause().stop();
            assertEquals("",get(activity,"liveAudioKey")); assertNull(get(activity,"livePermissionRequest"));
            activity.onRequestPermissionsResult(83,new String[]{android.Manifest.permission.RECORD_AUDIO},new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
            assertEquals(false,get(activity,"liveMicrophoneAllowed")); assertNull(get(activity,"liveAudioConnection"));
        }
    }
    @Test public void grantedPermissionWaitsForResumeBeforeBindingAudio() throws Exception {
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            var activity=controller.get();
            shadowOf(activity.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
            set(activity,"liveAudioKey","pending"); set(activity,"livePermissionRequest","permission");
            controller.pause();
            activity.onRequestPermissionsResult(83,new String[]{android.Manifest.permission.RECORD_AUDIO},new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
            assertNull(get(activity,"liveAudioConnection")); assertEquals(true,get(activity,"livePermissionReady"));
            controller.resume();
            assertNotNull(get(activity,"liveAudioConnection")); assertNull(get(activity,"livePermissionRequest"));
        }
    }
    @Test public void leavingAfterPermissionResultCancelsDeferredCapture() throws Exception {
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            var activity=controller.get();
            shadowOf(activity.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
            set(activity,"liveAudioKey","pending"); set(activity,"livePermissionRequest","permission");
            controller.pause();
            activity.onRequestPermissionsResult(83,new String[]{android.Manifest.permission.RECORD_AUDIO},new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
            controller.stop().start().resume();
            assertNull(get(activity,"liveAudioConnection")); assertEquals(false,get(activity,"livePermissionReady"));
        }
    }
    @Test public void returningAfterHiddenNotificationStopClearsVoiceUi() throws Exception {
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            var audio=Robolectric.buildService(LiveAudioService.class).create();
            try {
                var activity=controller.get(); var engine=(Engine)get(activity,"engine");
                audio.get().begin("hidden-call",()->{});
                set(activity,"liveAudioKey","hidden-call"); set(activity,"liveMicrophoneAllowed",true); set(activity,"liveAudioService",audio.get());
                controller.pause().stop(); drain(engine);
                ((Collection<?>)get(activity,"pendingUiEvents")).clear();
                audio.get().stop("hidden-call"); drain(engine);
                controller.start().resume(); drain(engine);
                assertEquals("",get(activity,"liveAudioKey"));
                assertTrue(((Collection<?>)get(activity,"pendingUiEvents")).stream().anyMatch(value ->
                    value.toString().contains("live.audio") && value.toString().contains("hidden-call") && value.toString().contains("ended")));
            } finally { audio.destroy(); }
        }
    }
}
