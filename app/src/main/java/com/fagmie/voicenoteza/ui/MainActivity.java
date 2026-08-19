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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.fagmie.voicenoteza.R;
import com.fagmie.voicenoteza.databinding.ActivityMainBinding;
import com.fagmie.voicenoteza.tts.ChatterboxClient;
import com.fagmie.voicenoteza.tts.ElevenLabsClient;
import com.fagmie.voicenoteza.tts.GoogleTtsClient;
import com.fagmie.voicenoteza.util.PrefsHelper;

import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ExecutorService executor;
    private Handler mainHandler;
    private GoogleTtsClient googleClient;
    private ElevenLabsClient elevenLabsClient;
    private ChatterboxClient chatterboxClient;
    private PrefsHelper prefs;

    private static final int MAX_CHARS = 1000;

    private ActivityResultLauncher<String[]> mp3PickerLauncher;
    private ActivityResultLauncher<String[]> voicePickerLauncher;
    private String pendingVoiceName;
    private String pendingTranscript;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        executor          = Executors.newSingleThreadExecutor();
        mainHandler       = new Handler(Looper.getMainLooper());
        prefs             = new PrefsHelper(this);
        googleClient      = new GoogleTtsClient(this);
        elevenLabsClient  = new ElevenLabsClient(this);
        chatterboxClient  = new ChatterboxClient(this);

        // Chatterbox is the only generative TTS source in this build
        prefs.setProvider(PrefsHelper.PROVIDER_CHATTERBOX);

        // Register MP3 file picker — must be done before onStart
        mp3PickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) handleImportedMp3(uri);
            }
        );

        // Register voice reference file picker
        voicePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) handleVoiceFileSelected(uri);
            }
        );

        setupCharCounter();
        setupButtons();
        updateVoiceInfoLabel();
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
        binding.btnImportMp3.setOnClickListener(v -> openMp3Picker());
        binding.btnClear.setOnClickListener(v -> {
            binding.etMessage.setText("");
            binding.etMessage.requestFocus();
        });
    }

    // ── MP3 Import (ElevenLabs downloaded files) ──────────────────────────────

    private void openMp3Picker() {
        // Open system file picker filtered to audio files
        mp3PickerLauncher.launch(new String[]{"audio/*", "audio/mpeg", "audio/mp3"});
    }

    private void handleImportedMp3(Uri sourceUri) {
        setUiLoading(true, "Importing audio file...");

        executor.execute(() -> {
            try {
                // Copy the picked file into our FileProvider cache dir
                File outFile = new File(
                    new File(getCacheDir(), "voice_notes"),
                    "voice_note_imported.mp3"
                );
                outFile.getParentFile().mkdirs();

                try (InputStream in = getContentResolver().openInputStream(sourceUri);
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    if (in == null) throw new Exception("Could not open selected file");
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }

                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    // Ask user where to share
                    new AlertDialog.Builder(this)
                        .setTitle("Send Voice Note")
                        .setMessage("Where do you want to send this voice note?")
                        .setPositiveButton("WhatsApp", (d, w) -> shareToWhatsApp(outFile))
                        .setNeutralButton("Other App", (d, w) -> shareToAnyApp(outFile))
                        .setNegativeButton("Preview First", (d, w) -> {
                            playAudioFile(outFile);
                            // Re-show dialog after preview with a small delay
                            mainHandler.postDelayed(() ->
                                new AlertDialog.Builder(this)
                                    .setTitle("Send Voice Note")
                                    .setPositiveButton("WhatsApp", (d2, w2) -> shareToWhatsApp(outFile))
                                    .setNeutralButton("Other App", (d2, w2) -> shareToAnyApp(outFile))
                                    .setNegativeButton("Cancel", null)
                                    .show(), 500);
                        })
                        .show();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    showErrorDialog("Import Failed", e.getMessage());
                });
            }
        });
    }

    // ── Voice picking ─────────────────────────────────────────────────────────

    private void pickVoice() {
        if (prefs.isElevenLabs()) {
            pickElevenLabsVoice();
        } else if (prefs.isChatterbox()) {
            showVoiceManagerDialog();
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

    // ── Chatterbox voice management ───────────────────────────────────────────

    private void showVoiceManagerDialog() {
        setUiLoading(true, "Loading voices…");
        executor.execute(() -> {
            try {
                List<ChatterboxClient.VoiceInfo> voices = chatterboxClient.fetchVoices(prefs.getChatterboxHost());
                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    buildVoicePickerDialog(voices);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    showErrorDialog("Voice List Error", e.getMessage());
                });
            }
        });
    }

    private void buildVoicePickerDialog(List<ChatterboxClient.VoiceInfo> voices) {
        String current = prefs.getChatterboxVoice();
        // Strip .wav from stored value for comparison (handles old prefs)
        if (current.endsWith(".wav")) current = current.substring(0, current.length() - 4);
        final String currentName = current;

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle("Reference Voices");

        if (voices.isEmpty()) {
            builder.setMessage("No voices on server yet. Add one to get started.");
            builder.setPositiveButton("Add Voice", (d, w) -> promptAddVoiceName());
            builder.setNegativeButton("Cancel", null);
        } else {
            String[] items = new String[voices.size()];
            int initialSel = 0;
            for (int i = 0; i < voices.size(); i++) {
                ChatterboxClient.VoiceInfo v = voices.get(i);
                items[i] = v.hasTranscript ? v.name : v.name + "  (no transcript)";
                if (v.name.equals(currentName)) initialSel = i;
            }
            final int[] chosen = {initialSel};

            builder.setSingleChoiceItems(items, initialSel, (d, which) -> chosen[0] = which);
            builder.setPositiveButton("Use Selected", (d, w) -> {
                ChatterboxClient.VoiceInfo picked = voices.get(chosen[0]);
                prefs.setChatterboxVoice(picked.name);
                updateVoiceInfoLabel();
                showToast("Voice: " + picked.name);
            });
            builder.setNeutralButton("Add Voice", (d, w) -> promptAddVoiceName());
            builder.setNegativeButton("Delete", (d, w) -> confirmDeleteVoice(voices.get(chosen[0]).name));
        }

        builder.show();
    }

    private void promptAddVoiceName() {
        int pad = (int)(16 * getResources().getDisplayMetrics().density);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);

        android.widget.EditText etName = new android.widget.EditText(this);
        etName.setHint("e.g. myvoice");
        etName.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        layout.addView(etName);

        android.widget.TextView tvLabel = new android.widget.TextView(this);
        tvLabel.setText("Reference transcript");
        tvLabel.setPadding(0, pad, 0, 0);
        layout.addView(tvLabel);

        android.widget.EditText etTranscript = new android.widget.EditText(this);
        etTranscript.setHint("Type the exact words spoken in your recording — this is required for cloning quality.");
        etTranscript.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etTranscript.setMinLines(3);
        etTranscript.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        layout.addView(etTranscript);

        new AlertDialog.Builder(this)
            .setTitle("Add Reference Voice")
            .setMessage("Short name (letters, digits). You'll pick the audio file next.")
            .setView(layout)
            .setPositiveButton("Choose File", (d, w) -> {
                String name = etName.getText().toString().trim();
                String transcript = etTranscript.getText().toString().trim();
                if (name.isEmpty()) { showToast("Name cannot be empty"); return; }
                if (transcript.isEmpty()) {
                    showToast("Transcript is required for cloning quality");
                    return;
                }
                pendingVoiceName = name;
                pendingTranscript = transcript;
                voicePickerLauncher.launch(new String[]{"audio/*", "video/mp4", "video/mp4v-es"});
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void handleVoiceFileSelected(Uri uri) {
        if (pendingVoiceName == null || pendingVoiceName.isEmpty()) {
            showErrorDialog("Error", "No voice name set before picking file.");
            return;
        }
        if (pendingTranscript == null || pendingTranscript.isEmpty()) {
            showErrorDialog("Error", "No transcript set before picking file.");
            return;
        }
        String voiceName = pendingVoiceName;
        String transcript = pendingTranscript;
        pendingVoiceName = null;
        pendingTranscript = null;

        setUiLoading(true, "Uploading voice…");
        executor.execute(() -> {
            try {
                // Read all bytes from the content URI
                byte[] bytes;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Could not open selected file");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) baos.write(buf, 0, len);
                    bytes = baos.toByteArray();
                }

                String mimeType = getContentResolver().getType(uri);
                if (mimeType == null) mimeType = "audio/mpeg";

                // Get display name for multipart filename field
                String originalFilename = voiceName + ".audio";
                try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) originalFilename = c.getString(idx);
                    }
                }

                String savedName = chatterboxClient.uploadVoice(
                    prefs.getChatterboxHost(), voiceName, bytes, mimeType, originalFilename,
                    transcript);

                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    prefs.setChatterboxVoice(savedName);
                    updateVoiceInfoLabel();
                    showToast("Voice '" + savedName + "' added and selected");
                    showVoiceManagerDialog();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setUiLoading(false, null);
                    showErrorDialog("Upload Failed", e.getMessage());
                });
            }
        });
    }

    private void confirmDeleteVoice(String name) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Voice")
            .setMessage("Delete '" + name + "' from the server?\nThis cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                setUiLoading(true, "Deleting voice…");
                executor.execute(() -> {
                    try {
                        chatterboxClient.deleteVoice(prefs.getChatterboxHost(), name);
                        String stored = prefs.getChatterboxVoice();
                        if (stored.endsWith(".wav")) stored = stored.substring(0, stored.length() - 4);
                        if (name.equals(stored)) {
                            prefs.setChatterboxVoice("myvoice");
                        }
                        mainHandler.post(() -> {
                            setUiLoading(false, null);
                            updateVoiceInfoLabel();
                            showToast("Voice deleted");
                            showVoiceManagerDialog();
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            setUiLoading(false, null);
                            showErrorDialog("Delete Failed", e.getMessage());
                        });
                    }
                });
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
                File audioFile;
                if (prefs.isElevenLabs()) {
                    audioFile = elevenLabsClient.synthesize(text, prefs.getElevenLabsApiKey(), prefs.getElevenLabsVoiceId());
                } else if (prefs.isChatterbox()) {
                    audioFile = chatterboxClient.synthesize(text, prefs.getChatterboxHost(), prefs.getChatterboxVoice());
                } else {
                    audioFile = googleClient.synthesize(text, prefs.getGoogleApiKey());
                }

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
                File audioFile;
                if (prefs.isElevenLabs()) {
                    audioFile = elevenLabsClient.synthesize(text, prefs.getElevenLabsApiKey(), prefs.getElevenLabsVoiceId());
                } else if (prefs.isChatterbox()) {
                    audioFile = chatterboxClient.synthesize(text, prefs.getChatterboxHost(), prefs.getChatterboxVoice());
                } else {
                    audioFile = googleClient.synthesize(text, prefs.getGoogleApiKey());
                }
                mainHandler.post(() -> { setUiLoading(false, null); playAudioFile(audioFile); });
            } catch (Exception e) {
                mainHandler.post(() -> { setUiLoading(false, null); showErrorDialog("Preview Error", e.getMessage()); });
            }
        });
    }

    // ── Sharing ───────────────────────────────────────────────────────────────

    private static class WhatsAppInstance {
        final String packageName;
        final String label;
        WhatsAppInstance(String p, String l) { packageName = p; label = l; }
    }

    /**
     * Detects all installed WhatsApp instances.
     *
     * Uses two strategies:
     * 1. Direct getPackageInfo() with MATCH_ALL — catches most cases
     * 2. Querying all installed packages and filtering by name — catches
     *    anything missed by strategy 1 due to visibility restrictions
     */
    private List<WhatsAppInstance> getInstalledWhatsAppInstances() {
        List<WhatsAppInstance> found = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Set<String> seen = new java.util.HashSet<>();

        // Strategy 1 — direct lookup with MATCH_ALL flag
        List<String[]> candidates = new ArrayList<>();
        candidates.add(new String[]{"com.whatsapp",     "WhatsApp"});
        candidates.add(new String[]{"com.whatsapp.w4b", "WhatsApp Business"});
        for (int i = 1; i <= 10; i++) {
            candidates.add(new String[]{"com.whatsapp.clone" + i, "WhatsApp Clone " + i});
        }

        for (String[] c : candidates) {
            try {
                // FLAG_MATCH_UNINSTALLED_PACKAGES (0x00002000) catches more cases
                // on Android 11+ than the default 0 flag
                pm.getPackageInfo(c[0], PackageManager.MATCH_UNINSTALLED_PACKAGES);
                if (!seen.contains(c[0])) {
                    seen.add(c[0]);
                    found.add(new WhatsAppInstance(c[0], getAppLabel(pm, c[0], c[1])));
                }
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        // Strategy 2 — scan ALL installed packages for anything with "whatsapp" in name
        // This catches clones with non-standard package names
        try {
            List<android.content.pm.ApplicationInfo> apps =
                pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);
            for (android.content.pm.ApplicationInfo app : apps) {
                String pkg = app.packageName.toLowerCase();
                if (pkg.contains("whatsapp") && !seen.contains(app.packageName)) {
                    seen.add(app.packageName);
                    found.add(new WhatsAppInstance(
                        app.packageName,
                        getAppLabel(pm, app.packageName, app.packageName)
                    ));
                }
            }
        } catch (Exception ignored) {}

        return found;
    }

    private String getAppLabel(PackageManager pm, String pkg, String fallback) {
        try {
            return pm.getApplicationLabel(
                pm.getApplicationInfo(pkg, 0)
            ).toString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private void shareToWhatsApp(File audioFile) {
        List<WhatsAppInstance> instances = getInstalledWhatsAppInstances();

        // Debug — show what was found (remove after confirming it works)
        if (instances.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("No WhatsApp Found")
                .setMessage("Could not detect any WhatsApp instances.\n\nPackages scanned:\ncom.whatsapp\ncom.whatsapp.clone1–10\n\nFalling back to system share sheet.")
                .setPositiveButton("Share Anyway", (d, w) -> shareToAnyApp(audioFile))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }

        if (instances.size() == 1) {
            sendToWhatsAppPackage(audioFile, instances.get(0).packageName);
            return;
        }

        // Build labels with package name shown for disambiguation
        String[] labels = new String[instances.size()];
        for (int i = 0; i < instances.size(); i++) {
            WhatsAppInstance inst = instances.get(i);
            // Show package name in brackets so you know exactly which is which
            labels[i] = inst.label + "\n  " + inst.packageName;
        }

        new AlertDialog.Builder(this)
            .setTitle("Send to which WhatsApp? (" + instances.size() + " found)")
            .setItems(labels, (dialog, which) ->
                sendToWhatsAppPackage(audioFile, instances.get(which).packageName)
            )
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void sendToWhatsAppPackage(File audioFile, String packageName) {
        String mimeType = audioFile.getName().endsWith(".mp3") ? "audio/mpeg" : "audio/ogg";
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", audioFile);

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setPackage(packageName);

        try {
            startActivity(intent);
        } catch (Exception e) {
            showErrorDialog("Could not open " + packageName,
                "Error: " + e.getMessage() + "\n\nTrying system share sheet instead.");
            shareToAnyApp(audioFile);
        }
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
        String[] providers = {
            "ElevenLabs (SA voices ✓)",
            "Chatterbox (self-hosted, no API key)",
            "Google Cloud TTS"
        };
        new AlertDialog.Builder(this)
            .setTitle("Choose TTS Provider")
            .setItems(providers, (d, which) -> {
                switch (which) {
                    case 0:
                        prefs.setProvider(PrefsHelper.PROVIDER_ELEVENLABS);
                        showElevenLabsSetup();
                        break;
                    case 1:
                        prefs.setProvider(PrefsHelper.PROVIDER_CHATTERBOX);
                        updateVoiceInfoLabel();
                        showToast("Chatterbox ready — host/voice editable in Settings");
                        break;
                    case 2:
                        prefs.setProvider(PrefsHelper.PROVIDER_GOOGLE);
                        showApiKeyDialog();
                        break;
                }
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
        } else if (prefs.isChatterbox()) {
            label = prefs.getChatterboxVoice() + " · " + prefs.getChatterboxHost();
        } else {
            String name = prefs.getGoogleVoiceName();
            label = name.isEmpty()
                ? "Google TTS — tap Pick Voice to choose"
                : "Google: " + name;
        }
        binding.tvVoiceInfo.setText(label);
    }

    private void setUiLoading(boolean loading, String statusMsg) {
        binding.layoutProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading && statusMsg != null) {
            binding.tvStatus.setText(statusMsg);
        }
        binding.btnSendWhatsApp.setEnabled(!loading);
        binding.btnShareOther.setEnabled(!loading);
        binding.btnPreview.setEnabled(!loading);
        binding.btnPickVoice.setEnabled(!loading);
        binding.etMessage.setEnabled(!loading);
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
