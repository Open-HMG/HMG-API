#!/bin/sh
# Install, update or uninstall HMG-API on a head unit over adb (USB or Wi-Fi).
#   ./hmgapi-adb.sh install            install or update (HMG-API's payload/ next to this script)
#   ./hmgapi-adb.sh uninstall [--purge] everything back to stock (--purge: also /data/hmgapi)
#   ./hmgapi-adb.sh status             what's installed
# Root comes from hmgapid once HMG-API is installed; the first install needs
# NEMESIS (its daemon, through a non-SUID copy: the SUID nemesis is a weak root
# from adb). HMGAPI_FORCE=1 is passed on (see install.sh; maintainers only). Restart the head unit afterwards.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
P=${HMGAPI_PAYLOAD:-$HERE/payload}
[ -f "$P/MANIFEST" ] || P=$HERE/../../out/payload
STAGE=/data/local/tmp/hmgapi-payload
cmd=${1:-}
[ -n "$cmd" ] || { sed -n '2,8s/^# \{0,1\}//p' "$0"; exit 2; }

command -v adb >/dev/null || { echo "adb not found: install Android platform-tools"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no head unit on adb (adb devices shows none)"; exit 1; }

# root <command>: run as full root on the unit
if adb shell "/system/bin/hmgapid ping >/dev/null 2>&1 && echo yes" | grep -q yes; then
    root() { adb shell "/system/bin/hmgapid sh '$1'"; }
    how=hmgapid
elif adb shell "[ -x /system/xbin/nemesis ] && echo yes" | grep -q yes; then
    adb shell "cp /system/xbin/nemesis /data/local/tmp/nemesis-d && chmod 755 /data/local/tmp/nemesis-d"
    root() { adb shell "/data/local/tmp/nemesis-d root -c '$1'"; }
    how=NEMESIS
else
    echo "no root on this unit: the first install needs NEMESIS (or HMG-API's USB stick)"
    exit 1
fi
F=${HMGAPI_FORCE:+HMGAPI_FORCE=1 }
echo "root through $how"

case $cmd in
install)
    [ -f "$P/MANIFEST" ] || { echo "no HMG-API payload found next to this script"; exit 1; }
    echo "HMG-API $(cat "$P/VERSION") ($(cat "$P/BUILD"))"
    adb shell "rm -r $STAGE 2>/dev/null; mkdir -p $STAGE"
    for f in $(cut -c35- "$P/MANIFEST") VERSION BUILD MANIFEST; do
        adb push "$P/$f" "$STAGE/$f" >/dev/null 2>&1 || { echo "couldn't copy $f to the head unit"; exit 1; }
    done
    # the hook maps: a folder (MAPS.md5, in the MANIFEST, lists its files and md5s; install.sh checks them)
    if [ -d "$P/maps" ]; then
        adb push "$P/maps" "$STAGE/maps" >/dev/null 2>&1 || { echo "couldn't copy the hook maps to the head unit"; exit 1; }
    fi
    log=$(mktemp)
    root "${F}sh $STAGE/install.sh $STAGE" | tr -d '\r' | tee "$log"
    root "rm -r $STAGE" >/dev/null 2>&1 || true
    grep -q "installed. Restart" "$log" && rc=0 || rc=1
    rm -f "$log"
    exit $rc ;;
uninstall)
    root "${F}sh /data/hmgapi/uninstall.sh ${2:-}" | tr -d '\r' ;;
status)
    root "cat /data/hmgapi/installed.txt 2>/dev/null || echo not installed; /system/bin/hmgapid engine status 2>/dev/null" | tr -d '\r' ;;
*)
    echo "unknown command: $cmd"; exit 2 ;;
esac
