#!/system/bin/sh
# NEMESIS preuninstall (full root, a child of the nemesis process). NEMESIS runs it
# for an uninstall and also before an update: its update runs the old package's
# preuninstall with the new package already staged in
# /data/local/tmp/nemesis/stage/hmgapi (native/pkg.c cmd_install). An update keeps
# HMG-API installed: the new postinstall updates it in place, mods and all.
# Only an HMG-API package in the stage counts, and never when the nemesis command
# that runs this is an uninstall: a stage left by an interrupted install mustn't
# turn an uninstall into nothing. A failure here keeps NEMESIS's package (exit 5).
# HMG-API's NEMESIS sync (HMGAPI_NEMESIS_SYNC=1) unlists or re-lists only: HMG-API did (or
# keeps) the real work. Otherwise this uninstalls HMG-API, if it's installed
# (installed.txt: uninstall.sh stays in /data/hmgapi after an uninstall).
umask 077
if [ "$HMGAPI_NEMESIS_SYNC" = 1 ]; then echo "HMG-API's NEMESIS sync: list only, nothing uninstalled"; exit 0; fi
STAGE=${ROOT:-}/data/local/tmp/nemesis/stage/hmgapi
caller=$(cat /proc/$PPID/cmdline 2>/dev/null)
case $caller in
    *uninstall*) ;;
    *)
        if grep -q '^id=hmgapi$' "$STAGE/NEMESIS.data" 2>/dev/null; then
            echo "update: HMG-API stays installed, the new package updates it"
            exit 0
        fi ;;
esac
[ -f "${ROOT:-}/data/hmgapi/installed.txt" ] && [ -f "${ROOT:-}/data/hmgapi/uninstall.sh" ] || { echo "HMG-API isn't installed"; exit 0; }
export HMGAPI_FROM_NEMESIS=1   # uninstall.sh: NEMESIS is unlisting it already, no NEMESIS sync
exec sh "${ROOT:-}/data/hmgapi/uninstall.sh"
