#!/system/bin/sh
# HMG-API hook test: copy the built example mods (out/mods/<id>) into
# /data/hmgapi/mods/<id>, replacing those ids. Takes effect at the next restart.
#   adb shell /data/local/tmp/nemesis-d root -c 'sh /data/local/tmp/hmgapi/dev/mods-install.sh'
umask 077
HERE=${HERE:-/data/local/tmp/hmgapi}
MODS=${ROOT:-}/data/hmgapi/mods
mkdir -p "$MODS" || { echo "FAIL: mkdir $MODS"; exit 1; }
for d in "$HERE"/out/mods/*; do
    [ -f "$d/mod.json" ] || continue
    id=${d##*/}
    [ -n "$id" ] || continue
    rm -r "$MODS/$id" 2>/dev/null
    mkdir "$MODS/$id" || { echo "FAIL: mkdir $MODS/$id"; exit 1; }
    for f in "$d"/*; do cp -r "$f" "$MODS/$id/" || { echo "FAIL: copy $f"; exit 1; }; done   # -r: a mod with an app has app/
    echo "installed mod $id"
done
echo "restart the head unit to load them"
