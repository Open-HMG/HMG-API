#!/bin/sh
# Make HMG-API's install downloads in out/dist/:
#   hmgapi-<v>-usb.zip      a USB stick: DSP OTA (nothing installed yet), NEMESIS
#                           (NEMESIS.data type=install) and the app (HMGAPI/updates)
#   hmgapi-<v>-nemesis.zip  for NEMESIS, laid out like NEMESIS's own downloads: unzipped
#                           onto a stick, its NEMESIS.data (type=install) installs
#                           packages/hmgapi.zip, the package itself (NEMESIS.data [nemesis])
#   hmgapi-<v>-nemesis-package.zip  the package itself, for NEMESIS's Package Store (the
#                           portal) and nemesis install
#   hmgapi-<v>-adb.zip      hmgapi-adb.sh and the payload
# Every one carries the same payload (out/payload) and ends in install/common/install.sh.
# HMGAPI/updates gets the signed release (tools/mkrelease.sh, if the release key is
# here) and every signed mod package in out/packages (tools/mkmodpkg.sh).
set -e
cd "$(dirname "$0")/.."
./build.sh payload >/dev/null
V=$(cat out/payload/VERSION)
D=$PWD/out/dist
mkdir -p "$D"
rm -f "$D/hmgapi-$V-usb.zip" "$D/hmgapi-$V-nemesis.zip" "$D/hmgapi-$V-nemesis-package.zip" "$D/hmgapi-$V-adb.zip"
# NEMESIS's package-version: dotted numbers only, a beta before its release
PV=$(tools/nemesis-version.py "$V")
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

# zipdir <dir> <zip>: fixed order and times, so the same input gives the same zip
zipdir() { (cd "$1" && find . -type f | TZ=UTC xargs touch -d 2026-01-01T00:00:00 && find . -type f | sed 's#^\./##' | sort | zip -q -X -D "$2" -@); }
readme() { sed "s/@VERSION@/$V/" "$1" > "$2"; }

# the NEMESIS package (id hmgapi), and the stick that installs it: NEMESIS's USB handler
# only reads a NEMESIS.data at the stick's root, with type= and a relative path= (native/usb.c)
N=$W/nemesis
mkdir -p "$N"
sed "s/@VERSION@/$V/; s/@PACKAGE_VERSION@/$PV/" install/nemesis/NEMESIS.data.in > "$N/NEMESIS.data"
cp -r install/nemesis/scripts "$N/scripts"
cp -r install/nemesis/bin "$N/bin"
cp -r out/payload "$N/payload"
chmod 755 "$N"/scripts/*.sh "$N"/bin/*.sh
zipdir "$N" "$W/hmgapi.zip"
cp "$W/hmgapi.zip" "$D/hmgapi-$V-nemesis-package.zip"
NB=$W/nemesis-stick
mkdir -p "$NB/packages"
cp "$W/hmgapi.zip" "$NB/packages/hmgapi.zip"
printf '[nemesis]\nversion=1.0\ntype=install\npath=packages/hmgapi.zip\n' > "$NB/NEMESIS.data"
readme install/nemesis/README.txt "$NB/README.txt"
zipdir "$NB" "$D/hmgapi-$V-nemesis.zip"

# adb
A=$W/adb/hmgapi-$V-adb
mkdir -p "$A"
cp install/adb/hmgapi-adb.sh "$A/" && chmod 755 "$A/hmgapi-adb.sh"
cp -r out/payload "$A/payload"
readme install/adb/README.txt "$A/README.txt"
zipdir "$W/adb" "$D/hmgapi-$V-adb.zip"

# USB stick
U=$W/usb
mkdir -p "$U/HMGAPI/install" "$U/HMGAPI/updates"
python3 - install/usb/troubleShooter "$U/ota_package.tar" <<'PY'
import io, sys, tarfile
data = open(sys.argv[1], "rb").read()
# what the DSP agent runs reliably: one regular file, troubleShooter, 0755, root (no ./map/new_one)
with tarfile.open(sys.argv[2], "w", format=tarfile.GNU_FORMAT) as t:
    i = tarfile.TarInfo("troubleShooter"); i.size = len(data); i.mode = 0o755; i.uid = i.gid = 0; i.mtime = 0
    t.addfile(i, io.BytesIO(data))
PY
cp -r out/payload "$U/HMGAPI/install/payload"
cp "$W/hmgapi.zip" "$U/HMGAPI/install/hmgapi.zip"
printf '[nemesis]\nversion=1.0\ntype=install\npath=HMGAPI/install/hmgapi.zip\n' > "$U/NEMESIS.data"
RK=${HMGAPI_RELEASE_KEY:-$HOME/hmg-private/keys/leafia-release.key}
if [ -f "$RK" ]; then
    cp "$(tools/mkrelease.sh "$RK" | tail -1)" "$U/HMGAPI/updates/"
else
    echo "no release key here: HMGAPI/updates has no HMG-API release"
fi
for z in out/packages/*.zip; do [ -f "$z" ] && cp "$z" "$U/HMGAPI/updates/"; done
readme install/usb/README.txt "$U/README.txt"
zipdir "$U" "$D/hmgapi-$V-usb.zip"

for z in usb nemesis nemesis-package adb; do echo "$D/hmgapi-$V-$z.zip $(du -k "$D/hmgapi-$V-$z.zip" | cut -f1) KB"; done
