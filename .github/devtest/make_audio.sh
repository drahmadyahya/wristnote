#!/usr/bin/env bash
# Builds 16 kHz mono WAVs of Egyptian Arabic speech for each test phrase.
set -euo pipefail
out="$1"; mkdir -p "$out"
pip install -q edge-tts
command -v ffmpeg >/dev/null || sudo apt-get install -y -q ffmpeg
while IFS='|' read -r name text expect; do
  [ -z "$name" ] && continue
  edge-tts --voice ar-EG-ShakirNeural --text "$text" --write-media "/tmp/$name.mp3"
  ffmpeg -loglevel error -y -i "/tmp/$name.mp3" -ar 16000 -ac 1 -c:a pcm_s16le "$out/$name.wav"
done < .github/devtest/phrases.txt
ls -la "$out"
