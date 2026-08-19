#!/usr/bin/env python3
"""
main.py — Pocket TTS FastAPI wrapper for the VoiceNoteZA pipeline.

Same surface as the NeuTTS / Chatterbox wrappers so VoiceNoteZA points at it
with no client changes:

    GET  /health     -> {status, engine, model_loaded, cloning, hf_token}
    GET  /reference  -> list voices (premade + cloned), NeuTTS-shaped objects
    POST /reference  -> upload reference wav -> clone -> cache safetensors.
                        Returns {name, has_transcript:false}. NO transcript needed.
    POST /voicenote  -> {voice, text, [format]} -> WhatsApp Opus/OGG (or wav)

Generation runs in an isolated one-shot subprocess (app/worker.py) that hard-exits
via os._exit to skip a torch native-teardown abort which otherwise crashes the
ASGI worker. This keeps the server rock-solid across unlimited requests.

Voice cloning needs the gated Kyutai weights: accept terms at
https://huggingface.co/kyutai/pocket-tts and set HF_TOKEN. Premade voices need no token.

Deps: pip install pocket-tts fastapi uvicorn python-multipart ; ffmpeg on PATH
"""
import os
import re
import sys
import json
import time
import shlex
import logging
import tempfile
import subprocess
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form, HTTPException, Request
from fastapi.responses import Response

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
log = logging.getLogger("pockettts")

LANGUAGE = os.environ.get("POCKET_LANGUAGE", "english")
VOICES_DIR = Path(os.environ.get("POCKET_VOICES_DIR", "/data/voices"))
EXPORTS_DIR = Path(os.environ.get("POCKET_EXPORTS_DIR", "/data/exports"))
OPUS_BITRATE = os.environ.get("POCKET_OPUS_BITRATE", "32k")
MAX_REF_SECONDS = int(os.environ.get("POCKET_MAX_REF_SECONDS", "30"))
GEN_TIMEOUT = int(os.environ.get("POCKET_GEN_TIMEOUT", "300"))
USE_DAEMON = os.environ.get("POCKET_DAEMON", "1") == "1"  # persistent worker by default
WORKER = str(Path(__file__).parent / "worker.py")
DAEMON = str(Path(__file__).parent / "worker_daemon.py")

VOICES_DIR.mkdir(parents=True, exist_ok=True)
EXPORTS_DIR.mkdir(parents=True, exist_ok=True)

PREMADE = [
    "cosette", "marius", "javert", "alba", "jean", "anna", "vera", "fantine",
    "charles", "paul", "eponine", "azelma", "george", "mary", "jane", "michael",
    "eve", "bill_boerst", "peter_yearsley", "stuart_bell", "caro_davy",
    "giovanni", "lola", "juergen", "rafael", "estelle",
]
_SAFE = re.compile(r"[^A-Za-z0-9_-]")
_cloning_ok = None


def _safe(name: str) -> str:
    name = os.path.basename((name or "").strip())
    name = re.sub(r"\.(wav|mp3|safetensors)$", "", name, flags=re.IGNORECASE)
    return _SAFE.sub("_", name) or "voice"


def _resolve_src(voice: str) -> str:
    """Map a voice name to a source pocket-tts can load."""
    safe = _safe(voice)
    st = EXPORTS_DIR / f"{safe}.safetensors"
    wav = VOICES_DIR / f"{safe}.wav"
    if st.exists():
        return str(st)
    if wav.exists():
        return str(wav)
    if voice in PREMADE:
        return voice
    raise HTTPException(404, f"Unknown voice '{voice}'. Upload a reference or use a premade voice.")


def _run_worker(voice_src: str, text: str) -> bytes:
    """Generate WAV bytes in an isolated subprocess."""
    with tempfile.TemporaryDirectory() as td:
        out = os.path.join(td, "out.wav")
        req = json.dumps({"voice_src": voice_src, "text": text, "language": LANGUAGE})
        env = dict(os.environ)
        try:
            proc = subprocess.run(
                [sys.executable, WORKER, req, out],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=GEN_TIMEOUT, env=env,
            )
        except subprocess.TimeoutExpired:
            raise HTTPException(504, "Generation timed out")
        if not os.path.exists(out + ".ok"):
            err = proc.stderr.decode("utf-8", "ignore")
            if "voice cloning" in err.lower() or "could not download" in err.lower():
                global _cloning_ok
                _cloning_ok = False
                raise HTTPException(403, "Voice cloning weights are gated. Accept terms at "
                                         "https://huggingface.co/kyutai/pocket-tts and set HF_TOKEN.")
            raise HTTPException(500, f"Generation failed: {err[:400]}")
        with open(out, "rb") as f:
            return f.read()


class _Daemon:
    """Manages a single persistent generation worker; restarts on failure."""
    def __init__(self):
        self.proc = None
        self.sample_rate = None
        self._n = 0
        import threading
        self._lock = threading.Lock()

    def _start(self):
        import threading  # noqa: F401
        self.proc = subprocess.Popen(
            [sys.executable, DAEMON], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, bufsize=1, env=dict(os.environ),
        )
        ready = self.proc.stdout.readline()
        info = json.loads(ready)
        self.sample_rate = info.get("sample_rate")
        log.info("Daemon worker ready (sr=%s)", self.sample_rate)

    def _alive(self):
        return self.proc is not None and self.proc.poll() is None

    def generate(self, voice_src, text, export_to=None) -> bytes:
        with self._lock:
            if not self._alive():
                self._start()
            self._n += 1
            rid = str(self._n)
            with tempfile.TemporaryDirectory() as td:
                out = os.path.join(td, "out.wav")
                req = {"id": rid, "voice_src": voice_src, "text": text,
                       "out": out, "export_to": export_to}
                try:
                    self.proc.stdin.write(json.dumps(req) + "\n")
                    self.proc.stdin.flush()
                    line = self.proc.stdout.readline()
                except (BrokenPipeError, ValueError):
                    self.proc = None
                    raise HTTPException(503, "Generation worker restarting; retry")
                if not line:
                    self.proc = None
                    raise HTTPException(500, "Generation worker died")
                resp = json.loads(line)
                if not resp.get("ok"):
                    err = resp.get("error", "unknown")
                    if "voice cloning" in err.lower() or "could not download" in err.lower():
                        global _cloning_ok
                        _cloning_ok = False
                        raise HTTPException(403, "Voice cloning weights are gated. Accept terms "
                                                 "at https://huggingface.co/kyutai/pocket-tts and set HF_TOKEN.")
                    raise HTTPException(500, f"Generation failed: {err}")
                with open(out, "rb") as f:
                    return f.read()


_daemon = _Daemon() if USE_DAEMON else None


def _to_opus_ogg(wav_bytes: bytes) -> bytes:
    cmd = (f"ffmpeg -hide_banner -loglevel error -y -i pipe:0 "
           f"-c:a libopus -b:a {shlex.quote(OPUS_BITRATE)} -ar 48000 -ac 1 "
           f"-application voip -f ogg pipe:1")
    proc = subprocess.run(shlex.split(cmd), input=wav_bytes,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if proc.returncode != 0:
        raise HTTPException(500, f"ffmpeg failed: {proc.stderr.decode('utf-8','ignore')[:300]}")
    return proc.stdout


app = FastAPI(title="Pocket TTS Wrapper", version="1.0")


@app.get("/health")
def health():
    return {"status": "ok", "engine": "pocket-tts", "language": LANGUAGE,
            "model_loaded": True, "cloning": _cloning_ok,
            "hf_token": bool(os.environ.get("HF_TOKEN"))}


@app.get("/reference")
def list_voices():
    cloned = sorted({p.stem for p in EXPORTS_DIR.glob("*.safetensors")}
                    | {p.stem for p in VOICES_DIR.glob("*.wav")})
    voices = [{"name": n, "has_transcript": False, "type": "cloned"} for n in cloned]
    voices += [{"name": n, "has_transcript": False, "type": "premade"} for n in PREMADE]
    return {"voices": voices}


@app.post("/reference")
async def add_reference(file: UploadFile = File(...), name: str = Form(None),
                        transcript: str = Form(None), ref_text: str = Form(None)):
    """Upload reference wav -> normalise -> clone -> cache safetensors.

    `transcript`/`ref_text` are accepted and ignored (Pocket needs no paired
    transcript); `ref_text` is the field name the VoiceNoteZA client sends.
    """
    global _cloning_ok
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "Empty upload")
    voice_name = _safe(name or file.filename or "voice")
    wav_path = VOICES_DIR / f"{voice_name}.wav"
    norm = (f"ffmpeg -hide_banner -loglevel error -y -i pipe:0 -t {MAX_REF_SECONDS} "
            f"-ar 24000 -ac 1 -f wav {shlex.quote(str(wav_path))}")
    p = subprocess.run(shlex.split(norm), input=raw, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, timeout=120)
    if p.returncode != 0:
        raise HTTPException(400, f"Could not decode reference: {p.stderr.decode('utf-8','ignore')[:200]}")

    # Encode the reference + export a safetensors state.
    # Prefer the persistent daemon: it already holds the model in memory, so we
    # avoid loading a SECOND ~430 MB copy in a one-shot worker (which thrashes /
    # times out on memory-constrained hosts). One-shot worker is the fallback.
    export_path = str(EXPORTS_DIR / f"{voice_name}.safetensors")
    try:
        if USE_DAEMON:
            _daemon.generate(str(wav_path), "warmup.", export_to=export_path)
        else:
            req = json.dumps({"voice_src": str(wav_path), "text": "warmup.",
                              "language": LANGUAGE, "export_to": export_path})
            with tempfile.TemporaryDirectory() as td:
                out = os.path.join(td, "w.wav")
                pr = subprocess.run([sys.executable, WORKER, req, out],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=GEN_TIMEOUT)
                if not os.path.exists(out + ".ok"):
                    err = pr.stderr.decode("utf-8", "ignore")
                    if "voice cloning" in err.lower() or "could not download" in err.lower():
                        _cloning_ok = False
                        raise HTTPException(403, "Voice cloning weights are gated. Accept terms at "
                                                 "https://huggingface.co/kyutai/pocket-tts and set HF_TOKEN.")
                    raise HTTPException(500, f"Cloning failed: {err[:400]}")
    except HTTPException:
        wav_path.unlink(missing_ok=True)
        raise
    except subprocess.TimeoutExpired:
        wav_path.unlink(missing_ok=True)
        raise HTTPException(504, "Cloning timed out — reference too long or host under memory pressure.")
    except Exception as e:  # noqa: BLE001
        wav_path.unlink(missing_ok=True)
        raise HTTPException(500, f"Cloning failed: {e}")
    _cloning_ok = True
    log.info("Cloned reference voice '%s'", voice_name)
    # `voice` mirrors `name` — the VoiceNoteZA client reads the `voice` key.
    return {"name": voice_name, "voice": voice_name, "has_transcript": False}


@app.delete("/reference/{name}")
def delete_reference(name: str):
    """Delete a cloned reference voice (safetensors + cached wav)."""
    safe = _safe(name)
    removed = False
    for p in (EXPORTS_DIR / f"{safe}.safetensors", VOICES_DIR / f"{safe}.wav"):
        if p.exists():
            p.unlink()
            removed = True
    if not removed:
        raise HTTPException(404, f"Voice '{name}' not found")
    return {"deleted": name}


@app.post("/voicenote")
async def voicenote(request: Request):
    # Accept both JSON (VoiceNoteZA client) and multipart/form (curl/tests),
    # matching the NeuTTS/Chatterbox wrappers which take JSON {text, voice}.
    ct = request.headers.get("content-type", "")
    if "application/json" in ct:
        data = await request.json()
        voice = data.get("voice")
        text = data.get("text")
        format = data.get("format", "ogg")
    else:
        form = await request.form()
        voice = form.get("voice")
        text = form.get("text")
        format = form.get("format", "ogg")
    if not voice:
        raise HTTPException(422, "Field 'voice' required")
    text = (text or "").strip()
    if not text:
        raise HTTPException(400, "Empty text")
    src = _resolve_src(voice)
    t0 = time.time()
    wav = _daemon.generate(src, text) if USE_DAEMON else _run_worker(src, text)
    log.info("voicenote voice=%s len=%dB in %.1fs", voice, len(wav), time.time() - t0)
    if format == "wav":
        return Response(wav, media_type="audio/wav")
    return Response(_to_opus_ogg(wav), media_type="audio/ogg",
                    headers={"Content-Disposition": 'inline; filename="voicenote.ogg"'})
