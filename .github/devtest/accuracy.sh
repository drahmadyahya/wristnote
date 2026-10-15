#!/usr/bin/env bash
# Transcribes the test phrases with both models on the CI machine and compares text + time.
set -uo pipefail
OUT=devtest-out; mkdir -p $OUT
git clone -q --depth 1 --branch v1.9.5 https://github.com/ggml-org/whisper.cpp /tmp/wc
cmake -S /tmp/wc -B /tmp/wc/build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF >/dev/null
cmake --build /tmp/wc/build -j4 --target whisper-cli >/dev/null
for m in ggml-small-q5_1.bin ggml-large-v3-turbo-q5_0.bin; do
  curl -sL -o /tmp/$m https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$m
done
PROMPT="كلام بالعامية المصرية. فكرني بكرة الساعة خمسة ونص. ظبطلي المنبه الساعة ستة الصبح. احفظ المعاد ده في التقويم. ضيف في نوتة الشغل تحت عنوان المهام. النوت: المشتريات."
{
echo "# Accuracy: small (fast) vs large-v3-turbo (accurate), 4 CPU threads"
while IFS='|' read -r -u 3 name text expect; do
  [ -z "$name" ] && continue
  echo; echo "## $name"; echo "said : $text"
  for m in ggml-small-q5_1.bin ggml-large-v3-turbo-q5_0.bin; do
    s=$(date +%s.%N)
    heard=$(/tmp/wc/build/bin/whisper-cli -m /tmp/$m -f audio/$name.wav -l ar -nt -np -t 4 -bs 1 -bo 1 -nf \
            --prompt "$PROMPT" </dev/null 2>/dev/null | tr '\n' ' ' | sed 's/  */ /g')
    e=$(date +%s.%N)
    printf '%-6s (%.1fs): %s\n' "${m:5:5}" "$(echo "$e - $s" | bc)" "$heard"
  done
done 3< .github/devtest/phrases.txt
} | tee $OUT/accuracy.md
