#!/usr/bin/env python3
"""Taps the first thing on the test phone's screen whose text matches. Usage: tap.py "Text" [contains]"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

want = sys.argv[1]
# A second word "contains" (or anything but "exact") matches loosely; a third, "0.9,0.5", taps that far across and down it.
loose = len(sys.argv) > 2 and sys.argv[2] != "exact"
at = tuple(map(float, sys.argv[3].split(","))) if len(sys.argv) > 3 else (0.5, 0.5)
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8", "replace")
try:
    root = ET.fromstring(xml)
except ET.ParseError:
    print(f"tap: could not read the screen (looking for {want!r})")
    sys.exit(1)
for node in root.iter("node"):
    for label in (node.get("text", ""), node.get("content-desc", "")):
        if label == want or (loose and want.lower() in label.lower()):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            x, y = int(x1 + (x2 - x1) * at[0]), int(y1 + (y2 - y1) * at[1])
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tap: {want!r} at {x},{y}")
            sys.exit(0)
seen = sorted({n.get("text") for n in root.iter("node") if n.get("text")})
print(f"tap: {want!r} not on screen. Visible: {seen[:40]}")
sys.exit(1)
