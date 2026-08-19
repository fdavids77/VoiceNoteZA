# Pocket TTS wrapper — CPU-only, matches VoiceNoteZA /voicenote surface
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install torch CPU wheel first (avoids pulling CUDA packages)
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

# Persisted voice data (bind-mount or named volume in compose)
RUN mkdir -p /data/voices /data/exports
VOLUME ["/data"]

ENV POCKET_LANGUAGE=english \
    POCKET_THREADS=4 \
    POCKET_DAEMON=1 \
    POCKET_VOICES_DIR=/data/voices \
    POCKET_EXPORTS_DIR=/data/exports \
    POCKET_OPUS_BITRATE=32k \
    HF_HOME=/data/hf_cache

EXPOSE 8007

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8007/health || exit 1

CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8007", "--no-access-log"]
