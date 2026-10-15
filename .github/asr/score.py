"""Summary per model: command accuracy, character error rate, speed."""
import sys, re, collections

tsv, ref_path, cmds_path, out = sys.argv[1:5]
ref = dict(l.strip().split("|", 1) for l in open(ref_path, encoding="utf8") if "|" in l)

DIG = {"0": "صفر", "1": "واحد", "2": "اتنين", "3": "تلاته", "4": "اربعه", "5": "خمسه", "6": "سته", "7": "سبعه", "8": "تمانيه", "9": "تسعه"}


def norm(s):
    s = re.sub(r"[ً-ْـ]", "", s)
    s = re.sub("[إأآٱ]", "ا", s).replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")
    s = s.translate(str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789"))
    s = re.sub(r"\d", lambda m: " " + DIG[m.group()] + " ", s)
    s = re.sub(r"[^\w\s]", " ", s)
    return " ".join(s.split())


def ed(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


stats = collections.defaultdict(lambda: {"n": 0, "err": 0, "chars": 0, "werr": 0, "words": 0, "t": 0.0, "cmd": 0, "cn": 0, "audio": collections.Counter()})
order = []
for line in open(tsv, encoding="utf8"):
    p = line.rstrip("\n").split("\t")
    if len(p) < 4:
        continue
    m, clip, t, hyp = p[0], p[1], float(p[2]), p[3]
    if m not in order:
        order.append(m)
    r = norm(ref[clip.split("_")[0]]); h = norm(hyp)
    s = stats[m]
    s["n"] += 1; s["t"] += t
    s["err"] += ed(r, h); s["chars"] += len(r)
    s["werr"] += ed(r.split(), h.split()); s["words"] += len(r.split())

for line in open(cmds_path, encoding="utf8"):
    p = line.rstrip("\n").split("\t")
    s = stats[p[0]]; s["cn"] += 1; s["cmd"] += int(p[2])

with open(out, "w", encoding="utf8") as f:
    f.write("| model | commands right | CER | WER | avg sec/clip |\n|---|---|---|---|---|\n")
    for m in order:
        s = stats[m]
        f.write(f"| {m} | {s['cmd']}/{s['cn']} | {100*s['err']/max(1,s['chars']):.1f}% | {100*s['werr']/max(1,s['words']):.1f}% | {s['t']/max(1,s['n']):.2f} |\n")
print(open(out, encoding="utf8").read())
