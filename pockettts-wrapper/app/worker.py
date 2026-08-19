#!/usr/bin/env python3
"""worker.py — one-shot Pocket TTS generation in an isolated process.

argv[1] = JSON {voice_src, text, language, [export_to]}
argv[2] = output wav path

Writes WAV to argv[2] and a sidecar "<out>.ok" on success, then hard-exits via
os._exit(0) to skip the torch native-teardown abort that would otherwise crash
a long-lived parent server.
"""
import sys
import os
import json
import torch

torch.set_num_threads(int(os.environ.get("POCKET_THREADS", "2")))

import scipy.io.wavfile
from pocket_tts import TTSModel


def main():
    req = json.loads(sys.argv[1])
    out_path = sys.argv[2]
    m = TTSModel.load_model(language=req.get("language", "english"))
    state = m.get_state_for_audio_prompt(req["voice_src"])

    # Optional: persist a fast-loading voice state for future requests.
    export_to = req.get("export_to")
    if export_to:
        try:
            from pocket_tts import export_model_state
            export_model_state(state, export_to)
        except Exception as e:  # noqa: BLE001
            sys.stderr.write(f"export warning: {e}\n")

    audio = m.generate_audio(state, req["text"])
    scipy.io.wavfile.write(out_path, m.sample_rate, audio.numpy())
    with open(out_path + ".ok", "w") as f:
        f.write(str(len(audio)))
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)


if __name__ == "__main__":
    main()
