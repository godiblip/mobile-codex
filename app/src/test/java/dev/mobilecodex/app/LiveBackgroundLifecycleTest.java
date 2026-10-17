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
    @Test public void backgroundAndReturnRetainCaptureAndEngineEventsButDestroyStops() throws Exception {
        var controller=Robolectric.buildActivity(MainActivity.class).setup();
        var audio=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var activity=controller.get(); var engine=(Engine)get(activity,"engine");
            audio.get().begin("one",()->{});
            set(activity,"liveAudioKey","one"); set(activity,"liveMicrophoneAllowed",true); set(activity,"liveAudioService",audio.get());
            controller.pause().stop(); drain(engine);
            assertEquals("one",get(activity,"liveAudioKey")); assertEquals(true,get(activity,"liveMicrophoneAllowed"));
            assertTrue(((Collection<?>)get(engine,"observers")).contains(activity));
            controller.start().resume(); drain(engine);
            assertEquals("one",get(activity,"liveAudioKey")); assertFalse(((Collection<?>)get(engine,"observers")).contains(activity));
            controller.pause().stop().destroy(); drain(engine);
            assertEquals("",get(activity,"liveAudioKey")); assertEquals(false,get(activity,"liveMicrophoneAllowed"));
            assertFalse(((Collection<?>)get(engine,"observers")).contains(activity));
            assertNull(get(audio.get(),"wakeLock"));
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
}
