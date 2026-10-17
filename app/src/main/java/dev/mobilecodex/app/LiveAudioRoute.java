package dev.mobilecodex.app;

import android.content.Context;
import android.media.*;
import android.os.Build;
import android.os.Handler;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Call-scoped routing. External communication devices take precedence over the speaker. */
final class LiveAudioRoute implements AutoCloseable {
    private final AudioManager manager;
    private final Handler handler;
    private int previousMode;
    private boolean previousSpeaker, started, sco;
    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] values) { select(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] values) { select(); }
    };
    LiveAudioRoute(Context context, Handler handler) { manager = context.getSystemService(AudioManager.class); this.handler = handler; }
    void begin() {
        previousMode = manager.getMode(); previousSpeaker = manager.isSpeakerphoneOn(); started = true;
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        manager.registerAudioDeviceCallback(devices, handler); select();
    }
    static int priority(int type) {
        return switch (type) {
            case AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 1;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2;
            default -> 99;
        };
    }
    @SuppressWarnings("deprecation")
    private void select() {
        if (!started) return;
        try {
            List<AudioDeviceInfo> available = Build.VERSION.SDK_INT >= 31 ? manager.getAvailableCommunicationDevices()
                : Arrays.asList(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
            AudioDeviceInfo preferred = available.stream().filter(d -> priority(d.getType()) < 99)
                .min(Comparator.comparingInt(d -> priority(d.getType()))).orElse(null);
            if (Build.VERSION.SDK_INT >= 31) {
                if (preferred != null && !manager.setCommunicationDevice(preferred)) manager.clearCommunicationDevice();
            } else {
                boolean bluetooth = preferred != null && preferred.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
                if (bluetooth && !sco) { manager.startBluetoothSco(); sco = true; manager.setBluetoothScoOn(true); }
                else if (!bluetooth && sco) { manager.stopBluetoothSco(); manager.setBluetoothScoOn(false); sco = false; }
                manager.setSpeakerphoneOn(preferred == null || preferred.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            }
        } catch (SecurityException ignored) {
            // Bluetooth access is optional. Android's permitted wired/built-in route remains usable.
        }
    }
    @SuppressWarnings("deprecation")
    @Override public void close() {
        if (!started) return;
        started = false; manager.unregisterAudioDeviceCallback(devices);
        if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice();
        else {
            if (sco) { manager.stopBluetoothSco(); manager.setBluetoothScoOn(false); sco = false; }
            manager.setSpeakerphoneOn(previousSpeaker);
        }
        // Do not overwrite a phone call or another application's new audio mode.
        if (manager.getMode() == AudioManager.MODE_IN_COMMUNICATION) manager.setMode(previousMode);
    }
}
