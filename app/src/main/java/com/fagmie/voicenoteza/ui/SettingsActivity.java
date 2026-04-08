package com.fagmie.voicenoteza.ui;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
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
        }
    }
}
