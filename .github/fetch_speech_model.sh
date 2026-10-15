#!/usr/bin/env bash
# Puts Moonshine's Arabic speech model (tiny streaming, ~32 MB, MIT) inside the phone app.
set -euo pipefail
dst=mobile/src/main/assets/moonshine-ar; mkdir -p $dst
base=https://download.moonshine.ai/model/tiny-streaming-ar/quantized_26_08_24
for f in adapter.ort cross_kv.ort decoder_kv.ort encoder.ort frontend.model.ort frontend.weights.ort streaming_config.json tokenizer.bin; do
  curl -fsSL --retry 3 -o $dst/$f $base/$f
done
ls -la $dst
