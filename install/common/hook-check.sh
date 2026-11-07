#!/system/bin/sh
# HMG-API: put a boot hook back if something else took it out. NEMESIS's install and
# its updates (1.16.6 included) write /system/etc/install-recovery.sh as a whole new
# file, which drops HMG-API's block from it; the one in qb_runtime.sh still starts
# HMG-API, and this puts the other back, so both are there again. Started by the boot
# hook through hmgapid, in the background; one at a time (the hook runs from both
# files). Only while HMG-API is installed. Each fix goes to install.log.
# ROOT prefixes every path, for off-car tests only.
exec 3>&- 4>&- 5>&- 6>&- 7>&- 8>&- 9>&-   # hmgapid waits for its pipe to close: don't hold it
umask 077
ROOT=${ROOT:-}
D=$ROOT/data/hmgapi
[ -f "$D/installed.txt" ] && [ -f "$D/lib.sh" ] || exit 0
. "$D/lib.sh"
L=$D/state/hook-check.pid
# another one running? (a stale pid after a restart may be any process: check it's this script)
if [ -f "$L" ]; then
    p=$(cat "$L")
    [ -n "$p" ] && [ "$p" != $$ ] && grep -q hook-check "/proc/$p/cmdline" 2>/dev/null && exit 0
fi
mkdir -p "$D/state" && echo $$ > "$L"
log() { echo "$1"; echo "$1" >> "$D/install.log" 2>/dev/null; }
missing=
for f in /system/etc/install-recovery.sh /system/etc/qb_runtime.sh; do
    grep -q "^$HOOK_BEGIN" "$ROOT$f" 2>/dev/null || missing="$missing $f"
done
if [ -z "$missing" ]; then rm -f "$L"; exit 0; fi
# remount rw|ro: NEMESIS if it's there, else hmgapid (toolbox mount is refused on these units)
remount() {
    [ -n "$ROOT" ] && return 0
    if [ -x /system/xbin/nemesis ] && nemesis remount "$1" >/dev/null 2>&1; then return 0; fi
    /system/bin/hmgapid remount "$1" >/dev/null 2>&1
}
if ! remount rw; then
    log "boot hook missing from$missing, and /system can't be made writable: install HMG-API again to put it back"
    rm -f "$L"; exit 1
fi
rc=0
for f in $missing; do
    case $f in *install-recovery.sh) w=top ;; *) w=end ;; esac
    if hook_add "$f" "$w"; then log "$(date): boot hook put back in $f (something else rewrote it, e.g. a NEMESIS install or update)"
    else log "boot hook: couldn't put it back in $f: install HMG-API again"; rc=1; fi
done
sync
remount ro || log "note: /system stays rw until the next restart"
rm -f "$L"
exit $rc
