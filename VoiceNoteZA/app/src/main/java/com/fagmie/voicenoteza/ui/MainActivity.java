package com.fagmie.voicenoteza.ui;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.fagmie.voicenoteza.R;
import com.fagmie.voicenoteza.databinding.ActivityMainBinding;
import com.fagmie.voicenoteza.tts.GoogleTtsClient;
import com.fagmie.voicenoteza.util.PrefsHelper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VoiceNoteZA — Main Activity
 *
 * Converts typed text to a South African female voice note (.ogg)
 * and shares it directly to WhatsApp as a voice message.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ExecutorService executor;
    private Handler mainHandler;
    private GoogleTtsClient ttsClient;
    private PrefsHelper prefs;

    // Max chars for a sensible voice note (≈ 2 minutes of speech)
    private static final int MAX_CHARS = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = new PrefsHelper(this);
        ttsClient = new GoogleTtsClient(this);

        setupCharCounter();
        setupButtons();
        checkApiKeyOnFirstLaunch();
    }

    private void setupCharCounter() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                int len = s.length();
                binding.tvCharCount.setText(len + " / " + MAX_CHARS);
                binding.tvCharCount.setTextColor(
                    len > MAX_CHARS * 0.9
                        ? getColor(R.color.warning_red)
                        : getColor(R.color.text_secondary)
                );
                // Trim at hard limit
                if (len > MAX_CHARS) {
                    s.delete(MAX_CHARS, len);
                }
            }
        });
    }

    private void setupButtons() {
        // Main action: Generate + share to WhatsApp
        binding.btnSendWhatsApp.setOnClickListener(v -> generateAndShare(true));

        // Secondary: Generate + share to any app (file manager, Telegram, etc.)
        binding.btnShareOther.setOnClickListener(v -> generateAndShare(false));

        // Preview: just play the audio, don't share
        binding.btnPreview.setOnClickListener(v -> previewAudio());

        // Clear
        binding.btnClear.setOnClickListener(v -> {
            binding.etMessage.setText("");
            binding.etMessage.requestFocus();
        });
    }

    private void checkApiKeyOnFirstLaunch() {
        if (prefs.getApiKey().isEmpty()) {
            showApiKeyDialog();
        }
    }

    private void generateAndShare(boolean whatsAppDirect) {
        String text = binding.etMessage.getText().toString().trim();

        if (text.isEmpty()) {
            showToast("Please type a message first");
            return;
        }

        String apiKey = prefs.getApiKey();
        if (apiKey.isEmpty()) {
            showApiKeyDialog();
            return;
        }

        setUiLoading(true);

        executor.execute(() -> {
            try {
                File audioFile = ttsClient.synthesize(text, apiKey);
                mainHandler.post(() -> {
                    setUiLoading(false);
                    if (whatsAppDirect) {
                        shareToWhatsApp(audioFile);
                    } else {
                        shareToAnyApp(audioFile);
                    }
                });
            } catch (GoogleTtsClient.ApiKeyException e) {
                mainHandler.post(() -> {
                    setUiLoading(false);
                    showToast("Invalid API key — please check Settings");
                    showApiKeyDialog();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setUiLoading(false);
                    showToast("Error: " + e.getMessage());
                });
            }
        });
    }

    private void previewAudio() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) {
            showToast("Please type a message first");
            return;
        }
        String apiKey = prefs.getApiKey();
        if (apiKey.isEmpty()) {
            showApiKeyDialog();
            return;
        }

        setUiLoading(true);

        executor.execute(() -> {
            try {
                File audioFile = ttsClient.synthesize(text, apiKey);
                mainHandler.post(() -> {
                    setUiLoading(false);
                    playAudioFile(audioFile);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setUiLoading(false);
                    showToast("Preview error: " + e.getMessage());
                });
            }
        });
    }

    private void shareToWhatsApp(File audioFile) {
        Uri audioUri = FileProvider.getUriForFile(
            this,
            getPackageName() + ".fileprovider",
            audioFile
        );

        // Try WhatsApp personal first, then Business
        String[] whatsappPackages = {"com.whatsapp", "com.whatsapp.w4b"};
        boolean launched = false;

        for (String pkg : whatsappPackages) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("audio/ogg");
                intent.putExtra(Intent.EXTRA_STREAM, audioUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setComponent(new ComponentName(pkg, pkg + ".ShareToWhatsAppActivity"));
                // Fallback component if above not found
                try {
                    startActivity(intent);
                } catch (Exception ex) {
                    intent.setComponent(null);
                    intent.setPackage(pkg);
                    startActivity(intent);
                }
                launched = true;
                break;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Try next package
            }
        }

        if (!launched) {
            showToast("WhatsApp not installed — sharing to other apps instead");
            shareToAnyApp(audioFile);
        }
    }

    private void shareToAnyApp(File audioFile) {
        Uri audioUri = FileProvider.getUriForFile(
            this,
            getPackageName() + ".fileprovider",
            audioFile
        );

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("audio/ogg");
        intent.putExtra(Intent.EXTRA_STREAM, audioUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // Also allow WhatsApp to see the file
        intent.setClipData(ClipData.newRawUri("", audioUri));

        startActivity(Intent.createChooser(intent, "Share voice note via..."));
    }

    private void playAudioFile(File audioFile) {
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(audioFile.getAbsolutePath());
            mp.prepare();
            mp.start();
            showToast("Playing preview...");
            mp.setOnCompletionListener(player -> player.release());
        } catch (Exception e) {
            showToast("Could not play audio: " + e.getMessage());
        }
    }

    private void showApiKeyDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_api_key, null);
        android.widget.EditText etKey = dialogView.findViewById(R.id.et_api_key);
        etKey.setText(prefs.getApiKey());

        new AlertDialog.Builder(this)
            .setTitle("Google Cloud API Key")
            .setMessage("Enter your Google Cloud TTS API key.\n\nGet it free at:\nconsole.cloud.google.com → APIs & Services → Credentials")
            .setView(dialogView)
            .setPositiveButton("Save", (d, w) -> {
                String key = etKey.getText().toString().trim();
                if (!key.isEmpty()) {
                    prefs.setApiKey(key);
                    showToast("API key saved ✓");
                }
            })
            .setNeutralButton("Settings", (d, w) -> openSettings())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void setUiLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSendWhatsApp.setEnabled(!loading);
        binding.btnShareOther.setEnabled(!loading);
        binding.btnPreview.setEnabled(!loading);
        binding.etMessage.setEnabled(!loading);
        if (loading) {
            binding.tvStatus.setText("Generating South African voice...");
            binding.tvStatus.setVisibility(View.VISIBLE);
        } else {
            binding.tvStatus.setVisibility(View.GONE);
        }
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            openSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }
}
