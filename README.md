# VoiceNoteZA 🎙️🇿🇦

> **Type text → Send as a voice note on WhatsApp — built for when you can't speak**

Originally built for post-throat-surgery communication, VoiceNoteZA converts typed text into natural-sounding voice notes and sends them directly to any WhatsApp instance — including clones.

---

## Features

- 🇿🇦 **South African voices** — via ElevenLabs community voice library
- 🤖 **Dual TTS provider** — ElevenLabs or Google Cloud TTS, switchable in-app
- 📂 **Import MP3** — download from ElevenLabs website, import and send as WhatsApp voice note (free tier workaround)
- 📱 **Multi-instance WhatsApp picker** — detects and targets original, Business, and clones 1–10
- 🔊 **Preview before sending** — listen to the audio before it goes out
- 🌐 **Live voice picker** — fetches available voices directly from the API
- ↗️ **Share to any app** — Telegram, Signal, or any audio-capable app
- 🆓 **Free for personal use** — stays within free tier limits for normal WhatsApp usage
- 🔒 **Private** — API keys stored on-device only, never transmitted

---

## How It Works

### Option A — API-generated voice note (Google or ElevenLabs paid tier)
1. Type your message in the app
2. Tap **Send to WhatsApp as Voice Note**
3. App calls TTS API → generates audio
4. Pick which WhatsApp to send to
5. Recipient hears a natural voice note

### Option B — Import ElevenLabs MP3 (free tier workaround ✅)
1. Go to **elevenlabs.io** in your browser
2. Pick a South African community voice
3. Type your message → Generate → **Download MP3**
4. Open VoiceNoteZA → tap **📂 Import ElevenLabs MP3 → WhatsApp**
5. Select the downloaded MP3 → Preview or send directly

---

## Setup

### Install the App

#### Option A — Download pre-built APK (easiest)
1. Go to the [Actions](../../actions) tab
2. Click the latest successful workflow run
3. Download **VoiceNoteZA-debug** artifact
4. Unzip → install `app-debug.apk` on your phone
5. Enable **Install from unknown sources** if prompted

#### Option B — Build from source
```bash
git clone https://github.com/fdavids77/VoiceNoteZA.git
cd VoiceNoteZA
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

### Provider Setup

On first launch the app asks which TTS provider to use:

#### ElevenLabs (recommended for SA voices)

1. Sign up free at [elevenlabs.io](https://elevenlabs.io)
2. Go to **Profile → API Keys** → copy your key (starts with `sk_...`)
3. Paste into the app when prompted
4. Tap **Pick Voice** to fetch your available voices
5. For SA community voices: go to **elevenlabs.io/voice-library** → search **"South African"** → **Add to My Voices** → they appear in the picker

> **Free tier note:** ElevenLabs free tier (10,000 chars/month) does not include Voice Library API access. Use **Option B (Import MP3)** above as a free workaround — generate on the website, download, import into the app.

#### Google Cloud TTS

1. Go to [console.cloud.google.com](https://console.cloud.google.com)
2. Create a project → **APIs & Services → Library** → enable **Cloud Text-to-Speech API**
3. **APIs & Services → Credentials → Create Credentials → API Key**
4. Link a billing account (required for Neural2/WaveNet voices — free tier still applies)
5. Paste key into the app → tap **Pick Voice**

> **SA voice availability:** Google Cloud en-ZA voices require billing to be linked even though usage stays free. Without billing only en-GB voices are available.

| Voice Type | Free Tier | Quality |
|------------|-----------|---------|
| Neural2 | 1M chars/month | ⭐⭐⭐⭐⭐ |
| WaveNet | 1M chars/month | ⭐⭐⭐⭐ |
| Standard | 4M chars/month | ⭐⭐⭐ |

---

## WhatsApp Multi-Instance Support

VoiceNoteZA detects all installed WhatsApp instances and shows a picker:

```
Send to which WhatsApp?
────────────────────────
  WhatsApp
  WhatsApp Business
  WhatsApp Clone 1
  WhatsApp Clone 3
  Cancel
```

Supported package names detected automatically:
- `com.whatsapp` — WhatsApp original
- `com.whatsapp.w4b` — WhatsApp Business
- `com.whatsapp.clone1` → `com.whatsapp.clone10` — clones

If only one instance is installed, it sends directly without showing the picker.

---

## Project Structure

```
app/src/main/java/com/fagmie/voicenoteza/
├── ui/
│   ├── MainActivity.java        # Main screen — text input, share, import MP3
│   └── SettingsActivity.java    # Settings — API key, voice, speed, pitch
├── tts/
│   ├── GoogleTtsClient.java     # Google Cloud TTS API client
│   └── ElevenLabsClient.java    # ElevenLabs TTS API client
└── util/
    └── PrefsHelper.java         # SharedPreferences — supports both providers
```

---

## Building a Signed Release APK

The GitHub Actions workflow (`.github/workflows/build.yml`) builds a debug APK on every push automatically.

To produce a signed release APK:

1. Generate a keystore:
```bash
keytool -genkey -v -keystore voicenoteza.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias voicenoteza
```

2. Add secrets in **GitHub → Settings → Secrets and variables → Actions**:

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 -w 0 voicenoteza.jks` |
| `KEY_STORE_PASSWORD` | your keystore password |
| `KEY_ALIAS` | `voicenoteza` |
| `KEY_PASSWORD` | your key password |

3. Create a GitHub Release tagged `v1.0.0` → the workflow attaches the signed APK automatically.

---

## Requirements

- Android 8.0+ (API 26)
- Internet connection (for API-based TTS)
- WhatsApp installed (original, Business, or clone)

---

## Privacy

- Typed text is sent to Google/ElevenLabs servers over HTTPS for audio generation
- API keys are stored locally on-device in SharedPreferences — never transmitted elsewhere
- No analytics, no tracking, no ads

---

## Tech Stack

- [ElevenLabs TTS API](https://elevenlabs.io/docs/api-reference)
- [Google Cloud Text-to-Speech](https://cloud.google.com/text-to-speech)
- [OkHttp](https://square.github.io/okhttp/)
- [Gson](https://github.com/google/gson)
- [Material Components for Android](https://material.io/develop/android)
- [AndroidX Preference](https://developer.android.com/jetpack/androidx/releases/preference)

---

## License

MIT License — see [LICENSE](LICENSE)

---

*Built with [Claude](https://claude.ai) — developed collaboratively via AI-assisted Android development* 🤖
