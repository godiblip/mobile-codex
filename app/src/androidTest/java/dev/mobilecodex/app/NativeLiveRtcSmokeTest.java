package dev.mobilecodex.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Loads the shipped ARM64 library and negotiates an audio offer without an account/network. */
@RunWith(AndroidJUnit4.class)
public class NativeLiveRtcSmokeTest {
    @Test public void bundledNativeLibraryCreatesAudioAndDataChannelOffer() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var rtc=new NativeLiveRtc(context); var done=new CountDownLatch(1);
        var offer=new AtomicReference<String>(); var error=new AtomicReference<String>();
        try {
            rtc.start(new LiveRtc.Listener() {
                public void offer(String sdp) { offer.set(sdp); done.countDown(); }
                public void connection(String state) {}
                public void levels(double in,double out,long captured,long played) {}
                public void error(String message) { error.set(message); done.countDown(); }
            });
            assertTrue("Native offer timed out",done.await(15,TimeUnit.SECONDS));
            assertNull(error.get()); assertNotNull(offer.get());
            assertTrue(offer.get().contains("m=audio")); assertTrue(offer.get().contains("opus/48000"));
            assertTrue(offer.get().contains("m=application"));
        } finally { rtc.close(); }
    }
}
