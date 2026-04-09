package com.fagmie.voicenoteza.tts;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ElevenLabsClient — Calls ElevenLabs Text-to-Speech API.
 *
 * Free tier: 10,000 characters/month
 * Output: MP3 (converted to OGG for WhatsApp sharing)
 * API docs: https://elevenlabs.io/docs/api-reference/text-to-speech
 *
 * Key difference from Google TTS:
 * - Returns MP3 bytes directly (not base64 JSON)
 * - Voices fetched from /v1/voices endpoint
 * - Auth via xi-api-key header (not query param)
 */
public class ElevenLabsClient {

    private static final String BASE_URL       = "https://api.elevenlabs.io/v1";
    private static final String VOICES_URL     = BASE_URL + "/voices";
    private static final String TTS_URL        = BASE_URL + "/text-to-speech/%s"; // %s = voice_id

    // Best model for quality — multilingual v2 supports SA English naturally
    private static final String MODEL_ID = "eleven_multilingual_v2";

    // Output format: mp3 at 44.1kHz — WhatsApp accepts MP3 voice notes too
    private static final String OUTPUT_FORMAT = "mp3_44100_128";

    private final Context context;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public static class ApiKeyException extends RuntimeException {
        public ApiKeyException(String msg) { super(msg); }
    }

    public static class VoiceInfo {
        public final String voiceId;
        public final String name;
        public final String category;
        public final String description;
        public final String accent;
        public final String gender;

        public VoiceInfo(String voiceId, String name, String category,
                         String description, String accent, String gender) {
            this.voiceId     = voiceId;
            this.name        = name;
            this.category    = category;
            this.description = description;
            this.accent      = accent;
            this.gender      = gender;
        }

        /** Display label for the picker dialog */
        public String label() {
            StringBuilder sb = new StringBuilder(name);
            if (accent != null && !accent.isEmpty()) sb.append(" · ").append(accent);
            if (gender != null && !gender.isEmpty()) sb.append(" · ").append(gender);
            return sb.toString();
        }

        /** Short label for the saved preference display */
        public String shortLabel() {
            return name + (accent != null && !accent.isEmpty() ? " (" + accent + ")" : "");
        }
    }

    public ElevenLabsClient(Context context) {
        this.context = context;
        this.gson    = new Gson();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Fetches all available voices from ElevenLabs.
     * Sorted: South African accent first, then other English accents, then rest.
     */
    public List<VoiceInfo> fetchAvailableVoices(String apiKey) throws IOException {
        Request request = new Request.Builder()
            .url(VOICES_URL)
            .addHeader("xi-api-key", apiKey)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "empty";
            if (response.code() == 401) {
                throw new ApiKeyException("Invalid ElevenLabs API key");
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + body);
            }

            JsonObject json   = gson.fromJson(body, JsonObject.class);
            JsonArray  arr    = json.getAsJsonArray("voices");
            List<VoiceInfo> voices = new ArrayList<>();

            for (com.google.gson.JsonElement el : arr) {
                JsonObject v       = el.getAsJsonObject();
                String voiceId     = v.get("voice_id").getAsString();
                String name        = v.get("name").getAsString();
                String category    = v.has("category") ? v.get("category").getAsString() : "";

                // Extract labels (accent, gender, description)
                String accent      = "";
                String gender      = "";
                String description = "";

                if (v.has("labels") && !v.get("labels").isJsonNull()) {
                    JsonObject labels = v.getAsJsonObject("labels");
                    if (labels.has("accent"))      accent      = labels.get("accent").getAsString();
                    if (labels.has("gender"))      gender      = labels.get("gender").getAsString();
                    if (labels.has("description")) description = labels.get("description").getAsString();
                }

                voices.add(new VoiceInfo(voiceId, name, category, description, accent, gender));
            }

            // Sort: South African first, then female, then alphabetical
            voices.sort((a, b) -> {
                int sa = isSouthAfrican(a) ? 0 : isEnglish(a) ? 1 : 2;
                int sb = isSouthAfrican(b) ? 0 : isEnglish(b) ? 1 : 2;
                if (sa != sb) return sa - sb;
                // Within same group, female first
                int ga = "female".equalsIgnoreCase(a.gender) ? 0 : 1;
                int gb = "female".equalsIgnoreCase(b.gender) ? 0 : 1;
                if (ga != gb) return ga - gb;
                return a.name.compareTo(b.name);
            });

            return voices;
        }
    }

    private boolean isSouthAfrican(VoiceInfo v) {
        String accent = v.accent.toLowerCase();
        return accent.contains("south african") || accent.contains("south africa")
            || accent.contains("sa ") || accent.equals("sa");
    }

    private boolean isEnglish(VoiceInfo v) {
        String accent = v.accent.toLowerCase();
        return accent.contains("british") || accent.contains("american")
            || accent.contains("australian") || accent.contains("english");
    }

    /**
     * Synthesises text via ElevenLabs TTS API.
     *
     * @param text    Text to speak
     * @param apiKey  ElevenLabs API key
     * @param voiceId ElevenLabs voice ID
     * @return        MP3 file ready to share as WhatsApp voice note
     */
    public File synthesize(String text, String apiKey, String voiceId) throws IOException {
        // Build request body
        JsonObject voiceSettings = new JsonObject();
        voiceSettings.addProperty("stability",        0.5);
        voiceSettings.addProperty("similarity_boost", 0.85);
        voiceSettings.addProperty("style",            0.0);
        voiceSettings.addProperty("use_speaker_boost", true);

        JsonObject body = new JsonObject();
        body.addProperty("text",     text);
        body.addProperty("model_id", MODEL_ID);
        body.add("voice_settings",   voiceSettings);

        String url = String.format(TTS_URL, voiceId) + "?output_format=" + OUTPUT_FORMAT;

        Request request = new Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(RequestBody.create(
                gson.toJson(body),
                MediaType.get("application/json; charset=utf-8")
            ))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 401) {
                throw new ApiKeyException("Invalid ElevenLabs API key");
            }
            if (response.code() == 422) {
                String err = response.body() != null ? response.body().string() : "unknown";
                throw new IOException("Invalid request: " + err);
            }
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "unknown";
                throw new IOException("HTTP " + response.code() + ": " + err);
            }

            // Response is raw MP3 bytes
            byte[] audioBytes = response.body().bytes();
            if (audioBytes.length == 0) {
                throw new IOException("Empty audio response from ElevenLabs");
            }

            File outputFile = getOutputFile();
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(audioBytes);
            }

            return outputFile;
        }
    }

    /**
     * Returns remaining character quota for the current billing period.
     * Returns -1 if unable to fetch.
     */
    public int getRemainingChars(String apiKey) {
        try {
            Request request = new Request.Builder()
                .url(BASE_URL + "/user/subscription")
                .addHeader("xi-api-key", apiKey)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return -1;
                String body = response.body().string();
                JsonObject json = gson.fromJson(body, JsonObject.class);
                int limit = json.get("character_limit").getAsInt();
                int used  = json.get("character_count").getAsInt();
                return limit - used;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private File getOutputFile() {
        File cacheDir = new File(context.getCacheDir(), "voice_notes");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        // MP3 — WhatsApp also accepts mp3 as a voice note
        return new File(cacheDir, "voice_note.mp3");
    }
}
