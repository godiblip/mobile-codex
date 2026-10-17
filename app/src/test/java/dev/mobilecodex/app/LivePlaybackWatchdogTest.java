package dev.mobilecodex.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LivePlaybackWatchdogTest {
    @Test public void silenceWithAdvancingCallbacksNeverRestartsPlayback() {
        var watch=new LivePlaybackWatchdog();
        for(long now=10000;now<3600000;now+=1000) assertFalse(watch.stalled(now,true,true,now-10));
    }
    @Test public void onlyStartedConnectedOutputCanStallAndRecoveryIsBounded() {
        var watch=new LivePlaybackWatchdog();
        assertFalse(watch.stalled(10000,false,true,0));
        assertFalse(watch.stalled(10000,true,false,0));
        assertTrue(watch.stalled(10000,true,true,100)); assertTrue(watch.recover(10000));
        assertFalse(watch.stalled(11000,true,true,100));
        assertTrue(watch.stalled(16000,true,true,100)); assertTrue(watch.recover(16000));
        assertTrue(watch.stalled(22000,true,true,100)); assertFalse(watch.recover(22000));
    }
}
