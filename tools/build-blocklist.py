#!/usr/bin/env python3
"""Builds the adult-site block list the browser uses, from public lists of adult websites.

Sources (each under its own licence, see docs/blocklists.md): the adult-site extensions of Steven Black's
unified hosts lists (MIT), with the lists by Sinfonietta, Clefspeare13 and Tiuxo. The domains are merged,
cleaned, sorted and written to app/src/main/assets/blocklist-adult.txt, one per line (the APK compresses it).

Usage: python3 tools/build-blocklist.py
"""
import re
import urllib.request

SOURCES = [
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/extensions/porn/sinfonietta/hosts",
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/extensions/porn/clefspeare13/hosts",
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/extensions/porn/tiuxo/hosts",
]
NAME = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

domains = set()
for url in SOURCES:
    text = urllib.request.urlopen(url, timeout=120).read().decode("utf-8", "replace")
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip().lower()
        if not line:
            continue
        parts = line.split()
        host = parts[1] if len(parts) > 1 and parts[0] in ("0.0.0.0", "127.0.0.1") else parts[0]
        host = host.strip(".")
        if host.startswith("www."):
            host = host[4:]
        if NAME.match(host) and host not in ("localhost",):
            domains.add(host)

with open("app/src/main/assets/blocklist-adult.txt", "w", encoding="utf-8") as out:
    out.write("\n".join(sorted(domains)) + "\n")
print(len(domains), "domains")
