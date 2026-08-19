package com.fagmie.voicenoteza.ui;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.fagmie.voicenoteza.R;
import com.fagmie.voicenoteza.databinding.ActivitySettingsBinding;

/**
 * Settings screen — API key, voice selection, speaking rate, pitch.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivitySettingsBinding binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // Set inputType on the API key field so text is visible while typing
            EditTextPreference apiKeyEditPref = findPreference("api_key");
            if (apiKeyEditPref != null) {
                apiKeyEditPref.setOnBindEditTextListener(editText ->
                    editText.setInputType(
                        android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    )
                );
            }

            // Show current voice selection as summary
            ListPreference voicePref = findPreference("voice_name");
            if (voicePref != null) {
                voicePref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            }

            // API key summary — mask the key
            Preference apiKeyPref = findPreference("api_key");
            if (apiKeyPref != null) {
                apiKeyPref.setSummaryProvider(preference -> {
                    String key = getPreferenceManager()
                        .getSharedPreferences()
                        .getString("api_key", "");
                    if (key.isEmpty()) return "Not set — tap to enter";
                    if (key.length() > 8) {
                        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
                    }
                    return "****";
                });
            }

            // Self-hosted engine summary (ListPreference: show selected engine label + host)
            ListPreference chatterboxHostPref = findPreference("chatterbox_host");
            if (chatterboxHostPref != null) {
                chatterboxHostPref.setSummaryProvider(preference -> {
                    CharSequence label = chatterboxHostPref.getEntry();
                    String host = chatterboxHostPref.getValue();
                    if (host == null || host.isEmpty()) return "Not set — using default 192.168.0.85:8006";
                    return label != null ? label + " · " + host : host;
                });
            }

            // Chatterbox voice summary
            EditTextPreference chatterboxVoicePref = findPreference("chatterbox_voice");
            if (chatterboxVoicePref != null) {
                chatterboxVoicePref.setSummaryProvider(preference -> {
                    String voice = getPreferenceManager()
                        .getSharedPreferences()
                        .getString("chatterbox_voice", "myvoice.wav");
                    return voice.isEmpty() ? "Not set — using default myvoice.wav" : voice;
                });
            }
        }
    }
}
