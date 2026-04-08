package com.fagmie.voicenoteza.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

/**
 * PrefsHelper — centralised access to all user preferences.
 */
public class PrefsHelper {

    private final SharedPreferences prefs;

    // Default: Standard SA Female — works on all accounts without billing
    public static final String DEFAULT_VOICE = "en-ZA-Standard-A";
    public static final float  DEFAULT_RATE  = 1.0f;
    public static final float  DEFAULT_PITCH = 0.0f;

    public PrefsHelper(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public String getApiKey() {
        return prefs.getString("api_key", "").trim();
    }

    public void setApiKey(String key) {
        prefs.edit().putString("api_key", key.trim()).apply();
    }

    public String getVoiceName() {
        return prefs.getString("voice_name", DEFAULT_VOICE);
    }

    /** Speaking rate: 0.25–4.0. 1.0 = normal speed. */
    public float getSpeakingRate() {
        String val = prefs.getString("speaking_rate", "1.0");
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { return DEFAULT_RATE; }
    }

    /** Pitch: -20.0–20.0 semitones. 0 = no change. */
    public float getPitch() {
        String val = prefs.getString("pitch", "0.0");
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { return DEFAULT_PITCH; }
    }
}
