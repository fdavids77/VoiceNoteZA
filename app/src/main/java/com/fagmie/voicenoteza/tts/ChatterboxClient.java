package com.fagmie.voicenoteza.tts;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ChatterboxClient — Calls the NeuTTS Air wrapper (port 8006).
 *
 * POST /voicenote  body: {"text":"...","voice":"myvoice"}   (name, no .wav)
 * POST /reference  multipart: file, name, ref_text          (ref_text required)
 * GET  /reference  → {"voices":[{"name":..., "has_transcript":bool},...]}
 * Returns: audio/ogg (Opus 48 kHz mono, WhatsApp voice-note format)
 *
 * Read timeout is 300 s because CPU synthesis can be slow on the server.
 * Cleartext HTTP is permitted only for the configured LAN host
 * (see res/xml/network_security_config.xml — add entries there if you change the host).
 */
public class ChatterboxClient {

    public static class VoiceInfo {
        public final String name;
        public final boolean hasTranscript;
        public VoiceInfo(String name, boolean hasTranscript) {
            this.name = name;
            this.hasTranscript = hasTranscript;
        }
    }

    private final Context context;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public ChatterboxClient(Context context) {
        this.context = context;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Synthesises text via the NeuTTS wrapper and returns an OGG file.
     *
     * @param text  Text to speak
     * @param host  host:port of the wrapper (e.g. "192.168.0.85:8006")
     * @param voice Reference voice name (with or without .wav — extension is stripped)
     * @return      OGG file ready to share as a WhatsApp voice note
     */
    public File synthesize(String text, String host, String voice) throws IOException {
        String url = "http://" + host + "/voicenote";

        String voiceName = (voice != null && voice.endsWith(".wav"))
            ? voice.substring(0, voice.length() - 4)
            : voice;

        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        if (voiceName != null && !voiceName.isEmpty()) {
            body.addProperty("voice", voiceName);
        }

        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(
                body.toString(),
                MediaType.get("application/json; charset=utf-8")
            ))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "unknown";
                throw new IOException("HTTP " + response.code() + ": " + err);
            }
            byte[] audioBytes = response.body().bytes();
            if (audioBytes.length == 0) {
                throw new IOException("Empty audio response from Chatterbox");
            }
            File outFile = getOutputFile();
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(audioBytes);
            }
            return outFile;
        }
    }

    /**
     * Lists reference voices available on the server.
     * GET /reference → {"voices":[{"name":"myvoice","has_transcript":true},...]}
     */
    public List<VoiceInfo> fetchVoices(String host) throws IOException {
        Request request = new Request.Builder()
            .url("http://" + host + "/reference")
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "unknown";
                throw new IOException("HTTP " + response.code() + ": " + err);
            }
            JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
            JsonArray arr = json.getAsJsonArray("voices");
            List<VoiceInfo> voices = new ArrayList<>();
            for (com.google.gson.JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.get("name").getAsString();
                boolean hasTranscript = obj.has("has_transcript") && obj.get("has_transcript").getAsBoolean();
                voices.add(new VoiceInfo(name, hasTranscript));
            }
            return voices;
        }
    }

    /**
     * Uploads a reference voice via multipart POST to /reference.
     * POST /reference  file=<bytes>  name=<name>  ref_text=<transcript>
     * Returns the saved voice name (e.g. "myname").
     *
     * @param audioBytes       Raw bytes of the audio file
     * @param mimeType         MIME type (e.g. "audio/mpeg", "audio/mp4")
     * @param originalFilename Filename sent in the multipart Content-Disposition
     * @param refText          Exact transcript of the audio recording (required)
     */
    public String uploadVoice(String host, String name, byte[] audioBytes,
                              String mimeType, String originalFilename,
                              String refText) throws IOException {
        RequestBody fileBody = RequestBody.create(audioBytes, MediaType.get(mimeType));
        RequestBody requestBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", originalFilename, fileBody)
            .addFormDataPart("name", name)
            .addFormDataPart("ref_text", refText)
            .build();

        Request request = new Request.Builder()
            .url("http://" + host + "/reference")
            .post(requestBody)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "unknown";
                throw new IOException("HTTP " + response.code() + ": " + err);
            }
            JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
            return json.get("voice").getAsString();
        }
    }

    /**
     * Deletes a reference voice from the server.
     * DELETE /reference/<name>
     */
    public void deleteVoice(String host, String name) throws IOException {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
        Request request = new Request.Builder()
            .url("http://" + host + "/reference/" + encoded)
            .delete()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "unknown";
                throw new IOException("HTTP " + response.code() + ": " + err);
            }
        }
    }

    /** Pings /health. Returns true if server is reachable and returns 2xx. */
    public boolean checkHealth(String host) {
        try {
            Request request = new Request.Builder()
                .url("http://" + host + "/health")
                .get()
                .build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private File getOutputFile() {
        File dir = new File(context.getCacheDir(), "voice_notes");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "vn_" + System.currentTimeMillis() + ".ogg");
    }
}
