# Pocket TTS Wrapper (port 8007)

A FastAPI wrapper around [Kyutai Pocket TTS](https://github.com/kyutai-labs/pocket-tts)
that exposes the **same `/voicenote`, `/reference`, `/health` surface** as the existing
NeuTTS (8006) and Chatterbox (8005) wrappers, so **VoiceNoteZA needs no client changes** —
just point the host at `:8007`.

## Why Pocket TTS

- CPU-native, ~438 MB model, faster-than-realtime on CPU
- **No paired transcript required** for reference audio (kills the NeuTTS
  `words count mismatch` / collapsed-output class of bugs entirely)
- Fast voice reuse via cached `.safetensors` states
- Streaming, infinite-length text, low latency

## Endpoints

| Method | Path                | Body                                          | Returns |
|--------|---------------------|-----------------------------------------------|---------|
| GET    | `/health`           | —                                             | status, `cloning`, `hf_token` |
| GET    | `/reference`        | —                                             | `{voices:[{name,has_transcript,type}]}` |
| POST   | `/reference`        | `file`, `name`, `transcript`/`ref_text`(ignored) | `{name, voice, has_transcript:false}` |
| DELETE | `/reference/{name}` | —                                             | `{deleted:name}` (404 if unknown) |
| POST   | `/voicenote`        | `voice`, `text`, `format`(ogg\|wav)           | audio bytes (WhatsApp Opus/OGG or WAV) |

### Client contract (matches NeuTTS / Chatterbox wrappers)

- **`POST /voicenote` accepts BOTH `application/json` and `multipart/form-data`.**
  VoiceNoteZA posts JSON `{"text":"…","voice":"…"[, "format":"ogg"]}`; curl/tests
  may use `-F` form fields. Both paths are handled.
- `POST /reference` accepts the client's `ref_text` field (an alias for the ignored
  `transcript` — Pocket needs no paired transcript) and returns a `voice` key mirroring
  `name`, which the VoiceNoteZA upload path reads.
- `DELETE /reference/{name}` removes a cloned voice (safetensors + cached wav) — the
  client's "delete voice" action.

## ⚠️ Voice cloning is gated

Cloning **your own** voice needs the gated Kyutai weights:
1. Accept terms at https://huggingface.co/kyutai/pocket-tts **while logged into the same
   HF account the token belongs to** — a token whose account hasn't accepted the gate
   still gets 403 (verify with `curl -I -H "Authorization: Bearer $HF_TOKEN"
   https://huggingface.co/kyutai/pocket-tts/resolve/main/embeddings/alba.safetensors` →
   302 = granted, 403 = not accepted).
2. Create an HF token (read scope), put it in `.env`: `HF_TOKEN=hf_...`
3. Rebuild/restart.

Premade catalog voices (alba, vera, anna, …26 total) work **without** a token —
useful to smoke-test the pipeline before you auth. `/health` shows `cloning` and
`hf_token` state.

Cloning your own voice for your own notes is fine under Kyutai's terms. Their
prohibited-use policy forbids cloning others without consent and presenting output
as genuine recordings — relevant if voice pretext ever comes up in an engagement.

## Run

```bash
# with cloning:
echo 'HF_TOKEN=hf_xxx' > .env
docker compose -f docker-compose.pockettts.yml up -d --build

# premade voices only (no token):
docker compose -f docker-compose.pockettts.yml up -d --build
```

First `/voicenote` after start loads the model (~5–7s); subsequent requests ~1.5–2s
(persistent daemon worker keeps the model in memory).

## LAN access (WSL2 mirrored + firewall)

Same pattern as your other ports. Add 8007 to your Windows firewall rule set for
Pixel 10 Pro access, matching 8004/8005/8006.

## Architecture note

Generation runs in an isolated worker process (`app/worker_daemon.py`, persistent;
`app/worker.py`, one-shot fallback). Pocket TTS's torch native teardown raises a
harmless `SIGABRT` at interpreter exit that crashes an in-process ASGI worker;
isolating generation keeps the server stable across unlimited requests. Toggle with
`POCKET_DAEMON=0` to use one-shot mode (slower: reloads model per request).

## Local (non-Docker) dev

```bash
pip install -r requirements.txt
POCKET_VOICES_DIR=./voices POCKET_EXPORTS_DIR=./exports \
  uvicorn app.main:app --host 0.0.0.0 --port 8007
```
