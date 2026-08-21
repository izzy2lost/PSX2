package com.izzy2lost.psx2;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

/**
 * Chooses which output device game audio plays through.
 *
 * A USB controller that also exposes an audio interface — the Amazon Luna pad is one —
 * makes Android route every app's audio to the controller's headphone jack the moment
 * it is plugged in. With nothing in that jack the phone goes silent. Forcing the phone
 * speaker keeps game audio on the handset without changing system-wide routing.
 */
public final class AudioOutputPreference {

    private static final String TAG = "AudioOutput";
    private static final String PREFS = "app_prefs";
    private static final String KEY = "audio_output";

    /** Whatever Android picks — stock behaviour. */
    public static final int MODE_SYSTEM = 0;
    /** Pin to the handset's built-in speaker. */
    public static final int MODE_PHONE_SPEAKER = 1;

    private AudioOutputPreference() {}

    public static int getMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, MODE_SYSTEM);
    }

    public static void setMode(Context ctx, int mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply();
    }

    /**
     * AAudio device id for the current mode, or 0 to let Android decide. Resolved at
     * apply time rather than stored, because device ids are not stable across reboots
     * or hot-plugs.
     */
    public static int resolveDeviceId(Context ctx, int mode) {
        if (mode != MODE_PHONE_SPEAKER) return 0;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return 0;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return d.getId();
                }
            }
            Log.w(TAG, "No built-in speaker reported; leaving routing to the system");
        } catch (Throwable t) {
            Log.w(TAG, "Could not resolve speaker device: " + t.getMessage());
        }
        return 0;
    }

    /** Push the saved preference into the emulator. */
    public static void apply(Context ctx) {
        int mode = getMode(ctx);
        int id = resolveDeviceId(ctx, mode);
        Log.d(TAG, "Applying audio output mode " + mode + " -> device id " + id);
        NativeApp.setAudioOutputDevice(id);
    }
}
