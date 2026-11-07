#!/usr/bin/env python3
"""NEMESIS's package-version for a HMG-API or mod version: dotted numbers only
(04-Packages.md; meta.c refuses anything else). A beta comes before its release:
the version before it, then .99.<beta> (1.0.0-beta.4 is 0.99.4, below 1.0.0).
Exits non-zero on a version form it has no rule for.

usage: tools/nemesis-version.py <version>
"""
import re, sys

v = sys.argv[1]
if re.fullmatch(r"\d+(\.\d+)*", v):
    print(v)
    sys.exit()
m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)-beta\.(\d+)", v)
if not m:
    sys.exit("no NEMESIS package-version for %s: add a rule to tools/nemesis-version.py" % v)
x, y, z, n = map(int, m.groups())
print("%d.%d.%d.99.%d" % (x, y, z - 1, n) if z else "%d.%d.99.%d" % (x, y - 1, n) if y else "%d.99.%d" % (x - 1, n))
