package com.fagmie.voicenoteza.tts;

import android.content.Context;

import com.fagmie.voicenoteza.util.PrefsHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * GoogleTtsClient — Calls Google Cloud Text-to-Speech API.
 *
 * Voice: en-ZA-Neural2-A (South African English, Female)
 * Output: OGG_OPUS format (native WhatsApp voice note format)
 * Free tier: 1 million characters/month — sufficient for personal use.
 *
 * API docs: https://cloud.google.com/text-to-speech/docs/reference/rest
 */
public class GoogleTtsClient {

    private static final String TTS_ENDPOINT =
        "https://texttospeech.googleapis.com/v1/text:synthesize";

    // South African English female voices (ordered by quality)
    // Neural2 = highest quality, WaveNet = very good, Standard = fallback
    public static final String[] SA_FEMALE_VOICES = {
        "en-ZA-Neural2-A",   // SA English Female — Neural2 (best)
        "en-ZA-Wavenet-A",   // SA English Female — WaveNet (fallback)
        "en-ZA-Standard-A",  // SA English Female — Standard (free tier always works)
    };

    private final Context context;
    private final PrefsHelper prefs;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public static class ApiKeyException extends RuntimeException {
        public ApiKeyException(String msg) { super(msg); }
    }

    public GoogleTtsClient(Context context) {
        this.context = context;
        this.prefs = new PrefsHelper(context);
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Synthesises text using the Google Cloud TTS API.
     *
     * @param text   The text to speak (max ~5000 chars per API call)
     * @param apiKey Google Cloud API key with TTS enabled
     * @return       File pointing to the generated OGG audio
     * @throws ApiKeyException      if the API key is rejected (HTTP 400/403)
     * @throws IOException          on network errors
     */
    /**
     * Fetches all available voices from the API.
     * Returns a list of VoiceInfo objects sorted: en-ZA first, then en-GB, then others.
     */
    public List<VoiceInfo> fetchAvailableVoices(String apiKey) throws IOException {
        String url = "https://texttospeech.googleapis.com/v1/voices?key=" + apiKey;

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "empty";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + body);
            }

            JsonObject json = gson.fromJson(body, JsonObject.class);
            List<VoiceInfo> voices = new ArrayList<>();

            for (com.google.gson.JsonElement el : json.getAsJsonArray("voices")) {
                JsonObject v = el.getAsJsonObject();
                String name = v.get("name").getAsString();
                String gender = v.get("ssmlGender").getAsString();
                String langCode = v.getAsJsonArray("languageCodes").get(0).getAsString();

                // Only include English voices
                if (!langCode.startsWith("en-")) continue;

                // Only include female voices
                if (!gender.equals("FEMALE")) continue;

                voices.add(new VoiceInfo(name, langCode, gender));
            }

            // Sort: en-ZA first, en-GB second, rest alphabetically
            voices.sort((a, b) -> {
                int pa = a.langCode.equals("en-ZA") ? 0 : a.langCode.equals("en-GB") ? 1 : 2;
                int pb = b.langCode.equals("en-ZA") ? 0 : b.langCode.equals("en-GB") ? 1 : 2;
                if (pa != pb) return pa - pb;
                return a.name.compareTo(b.name);
            });

            return voices;
        }
    }

    public static class VoiceInfo {
        public final String name;
        public final String langCode;
        public final String gender;

        public VoiceInfo(String name, String langCode, String gender) {
            this.name = name;
            this.langCode = langCode;
            this.gender = gender;
        }

        /** Display label shown in the picker dialog */
        public String label() {
            // e.g. "en-ZA-Standard-A" → "Standard-A  (en-ZA)"
            String shortName = name.replace(langCode + "-", "");
            return shortName + "  (" + langCode + ")";
        }
    }

    public File synthesize(String text, String apiKey) throws IOException {
        String voiceName = prefs.getVoiceName();
        float speakingRate = prefs.getSpeakingRate();
        float pitch = prefs.getPitch();

        // Build JSON request body
        JsonObject input = new JsonObject();
        input.addProperty("text", text);

        JsonObject voice = new JsonObject();
        voice.addProperty("languageCode", "en-ZA");
        voice.addProperty("name", voiceName);

        JsonObject audioConfig = new JsonObject();
        // OGG_OPUS = WhatsApp's native voice note format
        audioConfig.addProperty("audioEncoding", "OGG_OPUS");
        audioConfig.addProperty("speakingRate", speakingRate);
        audioConfig.addProperty("pitch", pitch);
        // 16000 Hz sample rate — optimal for voice notes
        audioConfig.addProperty("sampleRateHertz", 16000);

        JsonObject requestBody = new JsonObject();
        requestBody.add("input", input);
        requestBody.add("voice", voice);
        requestBody.add("audioConfig", audioConfig);

        String url = TTS_ENDPOINT + "?key=" + apiKey;

        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(
                gson.toJson(requestBody),
                MediaType.get("application/json; charset=utf-8")
            ))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "empty response";
            if (!response.isSuccessful()) {
                // Only throw ApiKeyException for true auth failures
                if (response.code() == 403) {
                    throw new ApiKeyException("HTTP 403 — key may lack TTS API permission. Body: " + body);
                }
                // For everything else, throw a plain IOException with the full body
                // so the real cause is shown to the user
                throw new IOException("HTTP " + response.code() + ": " + body);
            }

            String responseBody = response.body().string();
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            String audioContent = json.get("audioContent").getAsString();

            // Decode base64 audio and save to cache
            byte[] audioBytes = Base64.getDecoder().decode(audioContent);
            File outputFile = getOutputFile();
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(audioBytes);
            }

            return outputFile;
        }
    }

    /**
     * Returns the output file path — always the same file (overwritten each time)
     * to avoid filling up the cache directory.
     */
    private File getOutputFile() {
        File cacheDir = new File(context.getCacheDir(), "voice_notes");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        return new File(cacheDir, "voice_note.ogg");
    }
}
