#!/usr/bin/env python3
"""worker_daemon.py — persistent Pocket TTS generation worker.

Loads the model ONCE, then serves generation requests over a line-delimited
JSON protocol on stdin/stdout. This avoids the ~4.5s cold model load per
request that the one-shot worker incurs.

Protocol (one JSON object per line on stdin):
    {"id": "...", "voice_src": "...", "text": "...", "out": "/path/out.wav",
     "export_to": "/path/voice.safetensors"|null}
Response (one JSON object per line on stdout):
    {"id": "...", "ok": true, "samples": 51840}
    {"id": "...", "ok": false, "error": "..."}

The parent keeps this process alive and pipes requests to it. On protocol
error or crash, the parent restarts it. Voice states are cached in-process.

Note: this daemon deliberately does NOT call os._exit mid-run; the teardown
abort only matters at interpreter exit, which for a long-lived daemon happens
once at shutdown and is harmless (parent treats SIGABRT on shutdown as normal).
"""
import sys
import os
import json
import traceback

import torch
torch.set_num_threads(int(os.environ.get("POCKET_THREADS", "2")))

import scipy.io.wavfile
from pocket_tts import TTSModel

LANGUAGE = os.environ.get("POCKET_LANGUAGE", "english")


def main():
    model = TTSModel.load_model(language=LANGUAGE)
    cache = {}
    # signal readiness
    sys.stdout.write(json.dumps({"ready": True, "sample_rate": model.sample_rate}) + "\n")
    sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            rid = req.get("id")
            src = req["voice_src"]
            if src not in cache:
                cache[src] = model.get_state_for_audio_prompt(src)
            state = cache[src]
            if req.get("export_to"):
                from pocket_tts import export_model_state
                export_model_state(state, req["export_to"])
            audio = model.generate_audio(state, req["text"])
            scipy.io.wavfile.write(req["out"], model.sample_rate, audio.numpy())
            resp = {"id": rid, "ok": True, "samples": int(len(audio))}
        except Exception as e:  # noqa: BLE001
            resp = {"id": req.get("id") if 'req' in dir() else None,
                    "ok": False, "error": f"{e}", "trace": traceback.format_exc()[-500:]}
        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
