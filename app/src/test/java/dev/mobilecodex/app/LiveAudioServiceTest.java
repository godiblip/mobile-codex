package dev.mobilecodex.app;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.AudioManager;
import android.os.PowerManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={29,35}, application=Application.class)
public class LiveAudioServiceTest {
    PowerManager.WakeLock lock(LiveAudioService service) throws Exception {
        Field field=LiveAudioService.class.getDeclaredField("wakeLock"); field.setAccessible(true);
        return (PowerManager.WakeLock)field.get(service);
    }
    @Test public void notificationStopReleasesOnlyVoiceResourcesAndDoesNotRestart() throws Exception {
        var controller=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service=controller.get(); var stopped=new AtomicInteger();
            service.begin("one",stopped::incrementAndGet);
            org.robolectric.Shadows.shadowOf(service).getNextStartedService(); // Service now owns its lifetime.
            var lock=lock(service); assertTrue(lock.isHeld());
            var notices=service.getSystemService(NotificationManager.class).getActiveNotifications();
            assertEquals(1,notices.length); assertEquals(LiveAudioService.NOTIFICATION_ID,notices[0].getId());
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                assertEquals("android.app.Notification$CallStyle",notices[0].getNotification().extras.getString(android.app.Notification.EXTRA_TEMPLATE));
                assertTrue(notices[0].getNotification().extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER));
            }
            notices[0].getNotification().actions[0].actionIntent.send();
            Intent stop=org.robolectric.Shadows.shadowOf(service).getNextStartedService();
            assertEquals(LiveAudioService.STOP,stop.getAction()); assertEquals("one",stop.getStringExtra("key"));
            assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(stop,0,1));
            assertFalse(lock.isHeld()); assertEquals(1,stopped.get());
            assertEquals(0,service.getSystemService(NotificationManager.class).getActiveNotifications().length);
            service.stop("one"); assertEquals(1,stopped.get());
            assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(null,0,2));
            assertNull(lock(service));
        } finally { controller.destroy(); }
    }
    @Test public void staleNotificationCannotStopReplacementCall() throws Exception {
        var controller=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service=controller.get(); var stopped=new AtomicInteger();
            service.begin("one",()->{}); service.stop("one");
            service.begin("two",stopped::incrementAndGet);
            service.onStartCommand(new Intent().setAction(LiveAudioService.STOP).putExtra("key","one"),0,3);
            assertTrue(lock(service).isHeld()); assertEquals(0,stopped.get());
            service.stop("two"); assertEquals(1,stopped.get());
        } finally { controller.destroy(); }
    }
    @Test public void serviceDestructionAndTaskRemovalReleaseCapture() throws Exception {
        var controller=Robolectric.buildService(LiveAudioService.class).create();
        var service=controller.get(); var stopped=new AtomicInteger();
        service.begin("one",stopped::incrementAndGet); var lock=lock(service);
        service.onTaskRemoved(new Intent()); assertFalse(lock.isHeld()); assertEquals(1,stopped.get());
        service.begin("two",stopped::incrementAndGet); lock=lock(service);
        controller.destroy(); assertFalse(lock.isHeld()); assertEquals(2,stopped.get());
    }
    @Test public void rejectedAudioFocusLeavesNoNotificationOrWakeLock() throws Exception {
        var controller=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service=controller.get();
            org.robolectric.Shadows.shadowOf(service.getSystemService(AudioManager.class)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
            try { service.begin("one",()->fail("Never started")); fail("Must reject denied focus"); }
            catch (IllegalStateException expected) { assertNotNull(expected.getMessage()); }
            assertNull(lock(service));
            assertEquals(0,service.getSystemService(NotificationManager.class).getActiveNotifications().length);
        } finally { controller.destroy(); }
    }
    @Test public void losingAudioFocusStopsOnceAndReleasesResources() throws Exception {
        var controller=Robolectric.buildService(LiveAudioService.class).create();
        try {
            var service=controller.get(); var stopped=new AtomicInteger();
            service.begin("one",stopped::incrementAndGet); var lock=lock(service);
            Field field=LiveAudioService.class.getDeclaredField("focus"); field.setAccessible(true);
            var focus=(android.media.AudioFocusRequest)field.get(service);
            org.robolectric.Shadows.shadowOf(service.getSystemService(AudioManager.class)).getLastAudioFocusRequest().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
            assertFalse(lock.isHeld()); assertEquals(1,stopped.get());
            assertEquals(focus,org.robolectric.Shadows.shadowOf(service.getSystemService(AudioManager.class)).getLastAbandonedAudioFocusRequest());
        } finally { controller.destroy(); }
    }
}
