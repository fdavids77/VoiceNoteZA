package com.fagmie.voicenoteza.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

/**
 * PrefsHelper — centralised access to all user preferences.
 * Supports both Google Cloud TTS and ElevenLabs providers.
 */
public class PrefsHelper {

    private final SharedPreferences prefs;

    public static final float DEFAULT_RATE  = 1.0f;
    public static final float DEFAULT_PITCH = 0.0f;

    public static final String PROVIDER_GOOGLE     = "google";
    public static final String PROVIDER_ELEVENLABS = "elevenlabs";

    public PrefsHelper(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    // Provider
    public String getProvider() { return prefs.getString("tts_provider", PROVIDER_ELEVENLABS); }
    public void setProvider(String p) { prefs.edit().putString("tts_provider", p).apply(); }
    public boolean isElevenLabs() { return PROVIDER_ELEVENLABS.equals(getProvider()); }

    // Google
    public String getGoogleApiKey() { return prefs.getString("google_api_key", "").trim(); }
    public void setGoogleApiKey(String k) { prefs.edit().putString("google_api_key", k.trim()).apply(); }
    public String getGoogleVoiceName() { return prefs.getString("google_voice_name", ""); }
    public void setGoogleVoiceName(String n) { prefs.edit().putString("google_voice_name", n).apply(); }

    // ElevenLabs
    public String getElevenLabsApiKey() { return prefs.getString("elevenlabs_api_key", "").trim(); }
    public void setElevenLabsApiKey(String k) { prefs.edit().putString("elevenlabs_api_key", k.trim()).apply(); }
    public String getElevenLabsVoiceId() { return prefs.getString("elevenlabs_voice_id", ""); }
    public void setElevenLabsVoiceId(String id) { prefs.edit().putString("elevenlabs_voice_id", id).apply(); }
    public String getElevenLabsVoiceName() { return prefs.getString("elevenlabs_voice_name", ""); }
    public void setElevenLabsVoiceName(String n) { prefs.edit().putString("elevenlabs_voice_name", n).apply(); }

    // Generic
    public String getActiveApiKey() { return isElevenLabs() ? getElevenLabsApiKey() : getGoogleApiKey(); }
    public boolean isFullyConfigured() {
        if (isElevenLabs()) return !getElevenLabsApiKey().isEmpty() && !getElevenLabsVoiceId().isEmpty();
        return !getGoogleApiKey().isEmpty() && !getGoogleVoiceName().isEmpty();
    }

    // Legacy compat
    public String getApiKey() { return getGoogleApiKey(); }
    public void setApiKey(String k) { setGoogleApiKey(k); }
    public String getVoiceName() { return getGoogleVoiceName(); }
    public void setVoiceName(String n) { setGoogleVoiceName(n); }

    // Audio
    public float getSpeakingRate() {
        try { return Float.parseFloat(prefs.getString("speaking_rate", "1.0")); }
        catch (NumberFormatException e) { return DEFAULT_RATE; }
    }
    public float getPitch() {
        try { return Float.parseFloat(prefs.getString("pitch", "0.0")); }
        catch (NumberFormatException e) { return DEFAULT_PITCH; }
    }
}
