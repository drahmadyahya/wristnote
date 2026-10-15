#!/usr/bin/env bash
# Compares speech models on Egyptian Arabic clips: text accuracy + speed (4 CPU threads).
# Output: out/transcripts.tsv (model \t clip \t seconds \t text), out/log.txt
set -uo pipefail
OUT=$PWD/out; mkdir -p $OUT; LOG=$OUT/log.txt
W=/tmp/w; mkdir -p $W
exec > >(tee -a $LOG) 2>&1

pip install -q edge-tts moonshine-voice torch transformers huggingface_hub numpy --extra-index-url https://download.pytorch.org/whl/cpu
command -v ffmpeg >/dev/null || sudo apt-get install -y -q ffmpeg

# ---- audio: male, female, male fast + street noise
mkdir -p $W/audio
while IFS='|' read -r -u 3 name text; do
  [ -z "$name" ] && continue
  edge-tts --voice ar-EG-ShakirNeural --text "$text" --write-media $W/m.mp3 </dev/null
  edge-tts --voice ar-EG-SalmaNeural --text "$text" --write-media $W/f.mp3 </dev/null
  edge-tts --voice ar-EG-ShakirNeural --rate=+15% --text "$text" --write-media $W/n.mp3 </dev/null
  ffmpeg -nostdin -loglevel error -y -i $W/m.mp3 -ar 16000 -ac 1 -c:a pcm_s16le $W/audio/${name}_m.wav
  ffmpeg -nostdin -loglevel error -y -i $W/f.mp3 -ar 16000 -ac 1 -c:a pcm_s16le $W/audio/${name}_f.wav
  ffmpeg -nostdin -loglevel error -y -i $W/n.mp3 -f lavfi -i "anoisesrc=color=pink:amplitude=0.05:r=16000" \
    -filter_complex "[0:a]aresample=16000[s];[s][1:a]amix=inputs=2:duration=first:weights=1 1" \
    -ar 16000 -ac 1 -c:a pcm_s16le $W/audio/${name}_n.wav
done 3< .github/asr/phrases.txt
ls $W/audio | wc -l

# ---- whisper.cpp + models
git clone -q --depth 1 --branch v1.9.5 https://github.com/ggml-org/whisper.cpp $W/wc
git clone -q --depth 1 https://github.com/openai/whisper $W/openai-whisper
cmake -S $W/wc -B $W/wc/build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF >/dev/null
cmake --build $W/wc/build -j4 --target whisper-cli whisper-quantize >/dev/null
CLI=$W/wc/build/bin/whisper-cli; Q=$W/wc/build/bin/whisper-quantize
curl -sL -o $W/small.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin

# Hugging Face fine-tunes -> ggml (q5_1 small, q5_0 medium)
convert() {  # repo  base  outname  qtype
  local d=$W/hf/$3
  python - "$1" "$d" "$2" <<'EOF' || return 1
import sys, os, shutil
from huggingface_hub import snapshot_download
repo, d, base = sys.argv[1:]
snapshot_download(repo, local_dir=d, allow_patterns=["*.json","*.safetensors","*.bin","*.txt"])
for f in ["vocab.json","added_tokens.json","normalizer.json","merges.txt"]:
    if not os.path.exists(os.path.join(d,f)):
        p = snapshot_download(base, allow_patterns=[f]); shutil.copy(os.path.join(p,f), d)
print(os.listdir(d))
EOF
  python $W/wc/models/convert-h5-to-ggml.py $d $W/openai-whisper $d >/dev/null || return 1
  $Q $d/ggml-model.bin $W/$3.bin $4 >/dev/null || return 1
  ls -la $W/$3.bin
}
convert MAdel121/whisper-small-egyptian-arabic openai/whisper-small small_egy q5_1 || echo "!! small_egy convert failed"
convert rahafvii/STT-EGY openai/whisper-small stt_egy q5_1 || echo "!! stt_egy convert failed"
convert MAdel121/whisper-medium-egy openai/whisper-medium medium_egy q5_0 || echo "!! medium_egy convert failed"

PROMPT="كلام بالعامية المصرية. فكرني بكرة الساعة خمسة ونص. ظبطلي المنبه الساعة ستة الصبح. احفظ المعاد ده في التقويم. ضيف في نوتة الشغل تحت عنوان المهام. النوت: الشغل، المشتريات."
run_whisper() {  # label model useprompt
  [ -f "$2" ] || { echo "skip $1"; return; }
  for wav in $W/audio/*.wav; do
    clip=$(basename $wav .wav)
    s=$(date +%s.%N)
    if [ "$3" = 1 ]; then
      txt=$($CLI -m $2 -f $wav -l ar -nt -np -t 4 -bs 1 -bo 1 -nf --prompt "$PROMPT" </dev/null 2>/dev/null | tr '\n\t' '  ' | sed 's/  */ /g;s/^ //')
    else
      txt=$($CLI -m $2 -f $wav -l ar -nt -np -t 4 -bs 1 -bo 1 -nf </dev/null 2>/dev/null | tr '\n\t' '  ' | sed 's/  */ /g;s/^ //')
    fi
    e=$(date +%s.%N)
    printf '%s\t%s\t%.2f\t%s\n' "$1" "$clip" "$(echo "$e - $s" | bc)" "$txt" >> $OUT/transcripts.tsv
  done
  echo "done $1"
}
: > $OUT/transcripts.tsv
run_whisper small_prompt $W/small.bin 1
run_whisper small_egy $W/small_egy.bin 0
run_whisper small_egy_prompt $W/small_egy.bin 1
run_whisper stt_egy $W/stt_egy.bin 0
python .github/asr/moon.py $W/audio $OUT/transcripts.tsv
run_whisper medium_egy $W/medium_egy.bin 0
ls -la $W/*.bin
