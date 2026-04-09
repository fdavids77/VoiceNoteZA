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
import com.fagmie.voicenoteza.tts.ElevenLabsClient;
import com.fagmie.voicenoteza.tts.GoogleTtsClient;
import com.fagmie.voicenoteza.util.PrefsHelper;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ExecutorService executor;
    private Handler mainHandler;
    private GoogleTtsClient googleClient;
    private ElevenLabsClient elevenLabsClient;
    private PrefsHelper prefs;

    private static final int MAX_CHARS = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        executor         = Executors.newSingleThreadExecutor();
        mainHandler      = new Handler(Looper.getMainLooper());
        prefs            = new PrefsHelper(this);
        googleClient     = new GoogleTtsClient(this);
        elevenLabsClient = new ElevenLabsClient(this);

        setupCharCounter();
        setupButtons();
        updateVoiceInfoLabel();

        if (!prefs.isFullyConfigured()) {
            showProviderSetupDialog();
        }
    }

    private void setupCharCounter() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                int len = s.length();
                binding.tvCharCount.setText(len + " / " + MAX_CHARS);
                binding.tvCharCount.setTextColor(len > MAX_CHARS * 0.9
                    ? getColor(R.color.warning_red)
                    : getColor(R.color.text_secondary));
                if (len > MAX_CHARS) s.delete(MAX_CHARS, len);
            }
        });
    }

    private void setupButtons() {
        binding.btnSendWhatsApp.setOnClickListener(v -> generateAndShare(true));
        binding.btnShareOther.setOnClickListener(v -> generateAndShare(false));
        binding.btnPreview.setOnClickListener(v -> previewAudio());
        binding.btnPickVoice.setOnClickListener(v -> pickVoice());
        binding.btnClear.setOnClickListener(v -> {
            binding.etMessage.setText("");
            binding.etMessage.requestFocus();
        });
    }

    // ── Voice picking ─────────────────────────────────────────────────────────

    private void pickVoice() {
        if (prefs.isElevenLabs()) {
            pickElevenLabsVoice();
        } else {
            pickGoogleVoice();
        }
    }

    private void pickElevenLabsVoice() {
        String apiKey = prefs.getElevenLabsApiKey();
        if (apiKey.isEmpty()) { showElevenLabsSetup(); return; }

        setUiLoading(true, "Fetching ElevenLabs voices...");
        executor.execute(() -> {
            try {
                List<ElevenLabsClient.VoiceInfo> voices = elevenLabsClient.fetchAvailableVoices(apiKey);
                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    if (voices.isEmpty()) {
                        showErrorDialog("No Voices", "No voices found. Check your API key.");
                        return;
                    }
                    showElevenLabsVoicePicker(voices);
                });
            } catch (ElevenLabsClient.ApiKeyException e) {
                mainHandler.post(() -> { setUiLoading(false, null); showElevenLabsSetup(); });
            } catch (Exception e) {
                mainHandler.post(() -> { setUiLoading(false, null); showErrorDialog("Error", e.getMessage()); });
            }
        });
    }

    private void showElevenLabsVoicePicker(List<ElevenLabsClient.VoiceInfo> voices) {
        String currentId = prefs.getElevenLabsVoiceId();
        String[] labels  = new String[voices.size()];
        int selected = 0;
        for (int i = 0; i < voices.size(); i++) {
            labels[i] = voices.get(i).label();
            if (voices.get(i).voiceId.equals(currentId)) selected = i;
        }
        final int[] chosen = {selected};
        new AlertDialog.Builder(this)
            .setTitle("Choose Voice (SA voices first)")
            .setSingleChoiceItems(labels, selected, (d, w) -> chosen[0] = w)
            .setPositiveButton("Select", (d, w) -> {
                ElevenLabsClient.VoiceInfo v = voices.get(chosen[0]);
                prefs.setElevenLabsVoiceId(v.voiceId);
                prefs.setElevenLabsVoiceName(v.name);
                updateVoiceInfoLabel();
                showToast("Voice: " + v.shortLabel());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pickGoogleVoice() {
        String apiKey = prefs.getGoogleApiKey();
        if (apiKey.isEmpty()) { showApiKeyDialog(); return; }

        setUiLoading(true, "Fetching Google voices...");
        executor.execute(() -> {
            try {
                List<GoogleTtsClient.VoiceInfo> voices = googleClient.fetchAvailableVoices(apiKey);
                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    if (voices.isEmpty()) { showErrorDialog("No Voices", "No English female voices found."); return; }
                    showGoogleVoicePicker(voices);
                });
            } catch (Exception e) {
                mainHandler.post(() -> { setUiLoading(false, null); showErrorDialog("Error", e.getMessage()); });
            }
        });
    }

    private void showGoogleVoicePicker(List<GoogleTtsClient.VoiceInfo> voices) {
        String current = prefs.getGoogleVoiceName();
        String[] labels = new String[voices.size()];
        int selected = 0;
        for (int i = 0; i < voices.size(); i++) {
            labels[i] = voices.get(i).label();
            if (voices.get(i).name.equals(current)) selected = i;
        }
        final int[] chosen = {selected};
        new AlertDialog.Builder(this)
            .setTitle("Choose Google Voice")
            .setSingleChoiceItems(labels, selected, (d, w) -> chosen[0] = w)
            .setPositiveButton("Select", (d, w) -> {
                GoogleTtsClient.VoiceInfo v = voices.get(chosen[0]);
                prefs.setGoogleVoiceName(v.name);
                updateVoiceInfoLabel();
                showToast("Voice: " + v.label());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Audio generation ──────────────────────────────────────────────────────

    private void generateAndShare(boolean whatsAppDirect) {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) { showToast("Please type a message first"); return; }
        if (!prefs.isFullyConfigured()) { showProviderSetupDialog(); return; }

        setUiLoading(true, "Generating voice...");
        executor.execute(() -> {
            try {
                File audioFile = prefs.isElevenLabs()
                    ? elevenLabsClient.synthesize(text, prefs.getElevenLabsApiKey(), prefs.getElevenLabsVoiceId())
                    : googleClient.synthesize(text, prefs.getGoogleApiKey());

                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    if (whatsAppDirect) shareToWhatsApp(audioFile);
                    else shareToAnyApp(audioFile);
                });
            } catch (ElevenLabsClient.ApiKeyException | GoogleTtsClient.ApiKeyException e) {
                mainHandler.post(() -> { setUiLoading(false, null); showErrorDialog("API Key Error", e.getMessage()); });
            } catch (Exception e) {
                mainHandler.post(() -> { setUiLoading(false, null); showErrorDialog("TTS Error", e.getClass().getSimpleName() + ": " + e.getMessage()); });
            }
        });
    }

    private void previewAudio() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) { showToast("Please type a message first"); return; }
        if (!prefs.isFullyConfigured()) { showProviderSetupDialog(); return; }

        setUiLoading(true, "Generating preview...");
        executor.execute(() -> {
            try {
                File audioFile = prefs.isElevenLabs()
                    ? elevenLabsClient.synthesize(text, prefs.getElevenLabsApiKey(), prefs.getElevenLabsVoiceId())
                    : googleClient.synthesize(text, prefs.getGoogleApiKey());
                mainHandler.post(() -> { setUiLoading(false, null); playAudioFile(audioFile); });
            } catch (Exception e) {
                mainHandler.post(() -> { setUiLoading(false, null); showErrorDialog("Preview Error", e.getMessage()); });
            }
        });
    }

    // ── Sharing ───────────────────────────────────────────────────────────────

    private void shareToWhatsApp(File audioFile) {
        String mimeType = audioFile.getName().endsWith(".mp3") ? "audio/mpeg" : "audio/ogg";
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", audioFile);

        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};
        for (String pkg : packages) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeType);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setPackage(pkg);
                startActivity(intent);
                return;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        showToast("WhatsApp not found — sharing to other apps");
        shareToAnyApp(audioFile);
    }

    private void shareToAnyApp(File audioFile) {
        String mimeType = audioFile.getName().endsWith(".mp3") ? "audio/mpeg" : "audio/ogg";
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", audioFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("", uri));
        startActivity(Intent.createChooser(intent, "Share voice note via..."));
    }

    private void playAudioFile(File f) {
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.start();
            showToast("Playing preview...");
            mp.setOnCompletionListener(p -> p.release());
        } catch (Exception e) {
            showErrorDialog("Playback Error", e.getMessage());
        }
    }

    // ── Setup dialogs ─────────────────────────────────────────────────────────

    private void showProviderSetupDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Choose TTS Provider")
            .setMessage("Which service do you want to use for voice generation?")
            .setPositiveButton("ElevenLabs (SA voices ✓)", (d, w) -> {
                prefs.setProvider(PrefsHelper.PROVIDER_ELEVENLABS);
                showElevenLabsSetup();
            })
            .setNegativeButton("Google Cloud", (d, w) -> {
                prefs.setProvider(PrefsHelper.PROVIDER_GOOGLE);
                showApiKeyDialog();
            })
            .setCancelable(false)
            .show();
    }

    private void showElevenLabsSetup() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("sk_...");
        et.setText(prefs.getElevenLabsApiKey());
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
            .setTitle("ElevenLabs API Key")
            .setMessage("Sign up free at elevenlabs.io\nProfile → API Keys → Copy key\n\nFree tier: 10,000 chars/month")
            .setView(et)
            .setPositiveButton("Save & Pick Voice", (d, w) -> {
                String key = et.getText().toString().trim();
                if (!key.isEmpty()) {
                    prefs.setElevenLabsApiKey(key);
                    pickElevenLabsVoice();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showApiKeyDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("AIza...");
        et.setText(prefs.getGoogleApiKey());
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
            .setTitle("Google Cloud API Key")
            .setMessage("console.cloud.google.com → APIs & Services → Credentials → API key")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String key = et.getText().toString().trim();
                if (!key.isEmpty()) { prefs.setGoogleApiKey(key); showToast("API key saved ✓"); }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateVoiceInfoLabel() {
        String label;
        if (prefs.isElevenLabs()) {
            String name = prefs.getElevenLabsVoiceName();
            label = name.isEmpty()
                ? "ElevenLabs — tap Pick Voice to choose"
                : "ElevenLabs: " + name;
        } else {
            String name = prefs.getGoogleVoiceName();
            label = name.isEmpty()
                ? "Google TTS — tap Pick Voice to choose"
                : "Google: " + name;
        }
        binding.tvVoiceInfo.setText(label);
    }

    private void setUiLoading(boolean loading, String statusMsg) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSendWhatsApp.setEnabled(!loading);
        binding.btnShareOther.setEnabled(!loading);
        binding.btnPreview.setEnabled(!loading);
        binding.btnPickVoice.setEnabled(!loading);
        binding.etMessage.setEnabled(!loading);
        if (loading && statusMsg != null) {
            binding.tvStatus.setText(statusMsg);
            binding.tvStatus.setVisibility(View.VISIBLE);
        } else {
            binding.tvStatus.setVisibility(View.GONE);
        }
    }

    private void showErrorDialog(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
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
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateVoiceInfoLabel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }
}
