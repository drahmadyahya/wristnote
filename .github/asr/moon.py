"""Moonshine Arabic models on the same clips (4 threads not configurable; CPU onnxruntime)."""
import sys, os, time, wave, struct, traceback
from moonshine_voice import Transcriber, ModelArch
from moonshine_voice.download import get_model_for_language

audio_dir, out = sys.argv[1], sys.argv[2]
KEYTERMS = ["فكرني", "ظبطلي", "المنبه", "نوتة", "الشغل", "المشتريات", "معاد", "ميدكينج", "ميديو", "الهيستولوجي", "الفسيولوجي"]


def load(path):
    with wave.open(path) as w:
        n = w.getnframes(); raw = w.readframes(n)
    return [s / 32768.0 for s in struct.unpack("<%dh" % n, raw)], 16000


def text_of(tr):
    lines = getattr(tr, "lines", None)
    if lines is None:
        return str(tr)
    return " ".join((getattr(l, "text", "") or "").strip() for l in lines).strip()


for label, arch, keyterms in [("moon_tiny_ar", ModelArch(2), False), ("moon_tiny_ar_keys", ModelArch(2), True), ("moon_base_ar", ModelArch(1), False)]:
    try:
        path, a = get_model_for_language("ar", arch)
        print(label, path, a, os.listdir(path), flush=True)
        t = Transcriber(model_path=path, model_arch=a, options={"max_tokens_per_second": 13.0})
        if keyterms:
            t.set_keyterms(KEYTERMS)
        with open(out, "a") as f:
            for name in sorted(os.listdir(audio_dir)):
                pcm, sr = load(os.path.join(audio_dir, name))
                s = time.time()
                txt = text_of(t.transcribe_without_streaming(pcm, sr))
                f.write(f"{label}\t{name[:-4]}\t{time.time() - s:.2f}\t{txt.replace(chr(9), ' ').replace(chr(10), ' ')}\n")
        t.close()
        print("done", label, flush=True)
    except Exception:
        traceback.print_exc()
