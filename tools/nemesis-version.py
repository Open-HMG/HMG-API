#!/usr/bin/env python3
"""NEMESIS's package-version for a HMG-API or mod version: dotted numbers only
(04-Packages.md; meta.c refuses anything else), sorting as the versions do
(docs/releasing.md). A pre-release comes before its release: the version before it,
then .97.<n> for -alpha.<n>, .98.<n> for -beta.<n>, .99.<n> for -rc.<n>
(1.1.0-beta.2 is 1.0.98.2, 1.1.0-rc.1 1.0.99.1, both below 1.1.0; 1.0.0-rc.1 is 0.99.1).
hmgapi.engine.NemesisSync.packageVersion is the same rule, for the head unit.
Exits non-zero on a version form it has no rule for.

usage: tools/nemesis-version.py <version>
"""
import re, sys

STAGE = {"alpha": 97, "beta": 98, "rc": 99}

v = sys.argv[1]
if re.fullmatch(r"\d+(\.\d+)*", v):
    print(v)
    sys.exit()
m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)-(alpha|beta|rc)\.(\d+)", v)
if not m or (m[5] != "0" and m[5].startswith("0")):
    sys.exit("no NEMESIS package-version for %s (x.y.z, x.y.z-alpha.N, -beta.N or -rc.N): add a rule to tools/nemesis-version.py" % v)
x, y, z, s, n = int(m[1]), int(m[2]), int(m[3]), STAGE[m[4]], int(m[5])
if not (x or y or z):
    sys.exit("no NEMESIS package-version for %s: nothing comes before 0.0.0" % v)
print("%d.%d.%d.%d.%d" % (x, y, z - 1, s, n) if z else "%d.%d.%d.%d" % (x, y - 1, s, n) if y else "%d.%d.%d" % (x - 1, s, n))
