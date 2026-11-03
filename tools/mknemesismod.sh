#!/bin/sh
# Make a NEMESIS package for a signed HMG-API mod (tools/mkmodpkg.sh's zip), so it
# installs from NEMESIS Manager, a USB stick or the Package Store. The package
# (id hmgapi.<mod id>, depends on hmgapi) carries the mod zip as it is; its scripts
# (install/nemesis/mod) install and remove it with HMG-API's engine, through hmgapid.
#   tools/mknemesismod.sh <signed mod zip> [out dir, default out/dist]
# Makes, in the out dir:
#   <id>-<v>-nemesis.zip          for a USB stick, laid out like NEMESIS's own downloads
#   <id>-<v>-nemesis-package.zip  the package itself (Package Store, nemesis install)
set -e
cd "$(dirname "$0")/.."
Z=${1:?usage: mknemesismod.sh <signed mod zip> [out dir]}
D=${2:-$PWD/out/dist}
case $Z in /*) ;; *) Z=$OLDPWD/$Z ;; esac
case $D in /*) ;; *) D=$OLDPWD/$D ;; esac
[ -f "$Z" ] || { echo "no mod package at $Z"; exit 1; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
unzip -q "$Z" -d "$W/mod"
M=$W/mod
[ -f "$M/mod.json" ] || { echo "$Z isn't a HMG-API mod package (no mod.json at its top)"; exit 1; }
[ -f "$M/META-HMGAPI/signature" ] || { echo "$Z isn't signed: make it with tools/mkmodpkg.sh"; exit 1; }
# id, version, the descriptor's texts and os= from mod.json (each system entry: MODEL.REGION.*.FIRMWARE globs)
python3 - "$M/mod.json" > "$W/vars" <<'PY'
import json, re, sys
m = json.load(open(sys.argv[1]))
mid, ver = m["id"], m["version"]
if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", mid) or mid == "hmgapi":
    sys.exit("bad mod id %r" % mid)
pkg = "hmgapi." + mid
if len(pkg) > 64:
    sys.exit("NEMESIS ids are at most 64 characters: %s is too long (mod id at most 57)" % pkg)
def text(s, n):   # one line, no double quotes, at most n characters
    s = " ".join(str(s).replace('"', "'").split())
    return s if len(s) <= n else s[:n - 3].rstrip() + "..."
name = text(m.get("name") or mid, 64)
short = text("HMG-API mod: " + name, 64)
detail = text("%s %s, a HMG-API mod (needs HMG-API). %s" % (name, ver, m.get("description", "")), 256)
# os= globs of ro.build.product. "system" (SystemCheck.java): an object or a list of them, or "all";
# each field a value or a list, "all"/"*" any. What a glob can't say (a range like ">=260206", a "!x"
# exclusion, the other fields) is left to the engine's own check, so it widens to "*" here.
def one(v):
    v = v if isinstance(v, list) else [v] if v is not None else ["*"]
    v = [str(x) for x in v]
    if any(x.startswith("!") or not re.fullmatch(r"[A-Za-z0-9_]+", x) or x == "all" for x in v):
        return ["*"]
    return v
sysv = m.get("system")
sysv = [] if sysv in (None, "all", "*") else sysv if isinstance(sysv, list) else [sysv]
osl = []
for s in sysv:
    if not isinstance(s, dict):
        osl = []
        break
    prod = s.get("product")
    if prod is not None and "model" not in s:   # ro.build.product prefixes
        prod = [str(x) for x in (prod if isinstance(prod, list) else [prod])]
        globs = ["*.*.*.*"] if any(x.startswith("!") or x in ("all", "*") for x in prod) else [x + "*" for x in prod]
    else:
        globs = ["%s.%s.*.%s" % (mo, r, f) for mo in one(s.get("model")) for r in one(s.get("region")) for f in one(s.get("firmware"))]
    for g in globs:
        if g not in osl:
            osl.append(g)
os_ = "any" if not osl or "*.*.*.*" in osl else ",".join(osl)
for k, v in (("ID", mid), ("VERSION", ver), ("PACKAGE_ID", pkg), ("NAME", name), ("SHORT_DESC", short),
             ("DETAILED_DESC", detail), ("OS", os_)):
    print("%s=%s" % (k, v))
PY
var() { grep "^$1=" "$W/vars" | cut -d= -f2-; }
ID=$(var ID); V=$(var VERSION); PKG=$(var PACKAGE_ID)
PV=$(tools/nemesis-version.py "$V")
# fill a template: @KEY@ from the vars (and PACKAGE_VERSION), any character in the values
fill() { python3 - "$W/vars" "$PV" "$1" > "$2" <<'PY'
import sys
v = dict(l.rstrip("\n").split("=", 1) for l in open(sys.argv[1]))
v["PACKAGE_VERSION"] = sys.argv[2]
t = open(sys.argv[3]).read()
for k, x in v.items():
    t = t.replace("@%s@" % k, x)
sys.stdout.write(t)
PY
}
zipdir() { (cd "$1" && find . -type f | TZ=UTC xargs touch -d 2026-01-01T00:00:00 && find . -type f | sed 's#^\./##' | sort | zip -q -X -D "$2" -@); }

N=$W/nemesis
mkdir -p "$N/mod" "$N/bin"
fill install/nemesis/mod/NEMESIS.data.in "$N/NEMESIS.data"
cp -r install/nemesis/mod/scripts "$N/scripts"
cp install/nemesis/bin/open.sh "$N/bin/open.sh"   # Launch: the HMG-API app
cp "$Z" "$N/mod/package.zip"
printf '%s\n' "$ID" > "$N/mod/id"
chmod 755 "$N"/scripts/*.sh "$N"/bin/*.sh
mkdir -p "$D"
P=$D/$ID-$V-nemesis-package.zip S=$D/$ID-$V-nemesis.zip
rm -f "$P" "$S"
zipdir "$N" "$P"
NB=$W/stick
mkdir -p "$NB/packages"
cp "$P" "$NB/packages/$PKG.zip"
printf '[nemesis]\nversion=1.0\ntype=install\npath=packages/%s.zip\n' "$PKG" > "$NB/NEMESIS.data"
fill install/nemesis/mod/README.txt "$NB/README.txt"
zipdir "$NB" "$S"
echo "$S"
echo "$P"
