# VoiceNoteZA 🎙️🇿🇦

> **Type text → Send as a South African female voice note on WhatsApp**

Built for post-surgery or any situation where you can't speak but still want to
communicate naturally over WhatsApp voice notes.

---

## What It Does

1. You type your message
2. Tap **Send to WhatsApp as Voice Note**
3. The app calls Google Cloud TTS with a **South African English female voice**
4. Generates an `.ogg` audio file (WhatsApp's native voice note format)
5. Opens WhatsApp with the voice note ready to send to any chat

---

## Screenshots

| Main Screen | Settings |
|---|---|
| _(type message, one tap to WhatsApp)_ | _(API key, voice quality, speed, pitch)_ |

---

## Features

- 🇿🇦 **Authentic SA English female voice** — Neural2 quality (most natural)
- 📱 **Direct WhatsApp share** — opens as a voice note, not a file attachment
- 🔊 **Preview before sending** — listen to the audio first
- ⚙️ **Adjustable speed and pitch** — speak slower post-op if needed
- 🆓 **Completely free** for personal use (1M characters/month free tier)
- 🔒 **No data stored** — API key stays on your device in SharedPreferences

---

## Setup

### Step 1 — Get a free Google Cloud API key

1. Go to [console.cloud.google.com](https://console.cloud.google.com)
2. Sign in with your Google account
3. Create a new project (e.g. "VoiceNoteZA")
4. Go to **APIs & Services → Library**
5. Search for **"Cloud Text-to-Speech API"** → click **Enable**
6. Go to **APIs & Services → Credentials**
7. Click **Create Credentials → API Key**
8. Copy the key (starts with `AIza...`)

> **Cost:** Free up to **1 million characters/month** on Neural2 voice.
> A typical WhatsApp message is ~100 chars → that's 10,000 free messages/month.
> You will not be charged for personal use.

### Step 2 — Install the app

#### Option A — Download pre-built APK (easiest)
1. Go to the [Releases](../../releases) page
2. Download `VoiceNoteZA-debug.apk`
3. Enable **Install from unknown sources** on your phone
4. Install and open

#### Option B — Build from source
```bash
git clone https://github.com/fdavids77/VoiceNoteZA.git
cd VoiceNoteZA
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Step 3 — Enter your API key

On first launch, the app asks for your API key.  
Enter it and tap **Save**. You're ready.

---

## Voice Options

| Voice | Quality | Free Tier |
|-------|---------|-----------|
| `en-ZA-Neural2-A` | ⭐⭐⭐ Best — most natural SA female | 1M chars/month |
| `en-ZA-Wavenet-A` | ⭐⭐ Very good | 1M chars/month |
| `en-ZA-Standard-A` | ⭐ Basic | 4M chars/month |

Change voice in **Settings → Voice Quality**.

---

## How WhatsApp Voice Notes Work

WhatsApp voice notes are `.ogg` files encoded in **Opus** codec at 16kHz.
This app outputs exactly that format from the Google Cloud TTS API
(`OGG_OPUS` encoding), so WhatsApp receives it as a genuine voice note —
not a file attachment.

---

## Project Structure

```
app/src/main/java/com/fagmie/voicenoteza/
├── ui/
│   ├── MainActivity.java       # Main screen — text input + share buttons
│   └── SettingsActivity.java   # Settings screen (API key, voice, speed, pitch)
├── tts/
│   └── GoogleTtsClient.java    # Google Cloud TTS API client
└── util/
    └── PrefsHelper.java        # SharedPreferences wrapper
```

---

## Building a Signed Release APK via GitHub Actions

The included workflow (`.github/workflows/build.yml`) automatically builds
a debug APK on every push.

To build a **signed release APK**:

1. Generate a keystore:
   ```bash
   keytool -genkey -v -keystore voicenoteza.jks \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -alias voicenoteza
   ```

2. Add these secrets to your GitHub repo  
   (**Settings → Secrets and variables → Actions**):
   | Secret | Value |
   |--------|-------|
   | `KEYSTORE_BASE64` | `base64 -w 0 voicenoteza.jks` |
   | `KEY_STORE_PASSWORD` | your keystore password |
   | `KEY_ALIAS` | `voicenoteza` |
   | `KEY_PASSWORD` | your key password |

3. Create a GitHub Release (tag it `v1.0.0`) →  
   The workflow automatically attaches the signed APK.

---

## Requirements

- Android 8.0+ (API 26)
- Internet connection (for Google Cloud TTS API calls)
- WhatsApp installed (for direct voice note sharing)
- Google Cloud API key with Text-to-Speech API enabled

---

## Privacy

- Your typed text is sent to Google Cloud TTS API over HTTPS
- Audio is generated on Google's servers and returned to your device
- Nothing is stored remotely — the API key lives only on your device
- See [Google Cloud Privacy Policy](https://cloud.google.com/terms/cloud-privacy-notice)

---

## License

MIT License — see [LICENSE](LICENSE)

---

## Credits

Built with:
- [Google Cloud Text-to-Speech](https://cloud.google.com/text-to-speech)
- [OkHttp](https://square.github.io/okhttp/)
- [Gson](https://github.com/google/gson)
- [Material Components for Android](https://material.io/develop/android)
