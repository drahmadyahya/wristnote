"""Prints the centre of the first UI node whose text/description matches argv[2]."""
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[2]
root = ET.parse(sys.argv[1]).getroot()
for exact in (True, False):
    for n in root.iter("node"):
        for attr in ("text", "content-desc"):
            v = n.get(attr) or ""
            if (v == want) if exact else (want in v):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
