#!/system/bin/sh
# HMG-API: for a head unit whose Android doesn't start after an HMG-API install. Puts only
# the stock app_process back (stock bytes, md5 checked, and its stock SELinux label) and turns
# the hooks off, so the next start is Android as stock; needs no Android and no Java. Over adb,
# as full root (NEMESIS's daemon starts without Android):  ./hmgapi-adb.sh rescue
# Then restart, and once Android is up uninstall as usual (./hmgapi-adb.sh uninstall), or
# install again. HMGAPI_FORCE=1: restore a backup whose md5 isn't a known stock build.
# ROOT prefixes every path, for off-car tests only.
umask 077
H=${0%/*}
ROOT=${ROOT:-}
. "$H/lib.sh"
D=$ROOT/data/hmgapi
LOG=$D/install.log
B=$ROOT/system/bin/app_process
BAK=$D/stock/system/bin/app_process
remount() {
    [ -n "$ROOT" ] && return 0
    if [ -x /system/xbin/nemesis ] && nemesis remount "$1" >/dev/null 2>&1; then return 0; fi
    /system/bin/hmgapid remount "$1" >/dev/null 2>&1
}
log "HMG-API rescue $(date) (SELinux $(sel_mode))"
[ -f "$BAK" ] || fail "no stock app_process backup in $D/stock: nothing changed"
ORIG=$(sum "$BAK")
known=
while read m label; do [ "$m" = "$ORIG" ] && known=$label; done < "$H/stock.txt"
[ -n "$known" ] || [ "$HMGAPI_FORCE" = 1 ] || fail "the app_process backup (md5 $ORIG) isn't a known stock build: nothing changed. At your own risk: HMGAPI_FORCE=1."
: > "$D/hook/disable" 2>/dev/null
LBL=$(cat "$D/stock/app_process.label" 2>/dev/null)
remount rw || fail "can't remount /system rw (is NEMESIS's daemon running? see the NEMESIS watchdog note in the README)"
REMOUNTED=1
if [ "$(sum "$B")" != "$ORIG" ]; then
    cp "$BAK" "$B.hmgapi-new" || fail "copy the app_process backup"
    chmod 755 "$B.hmgapi-new"; chown root:shell "$B.hmgapi-new" 2>/dev/null
    [ "$(sum "$B.hmgapi-new")" = "$ORIG" ] || { rm -f "$B.hmgapi-new"; fail "app_process md5 mismatch after copy"; }
    label_set "$LBL" "$B.hmgapi-new" || log "warning: couldn't set the stock SELinux label ($LBL)"
    mv "$B.hmgapi-new" "$B" || { rm -f "$B.hmgapi-new"; fail "rename onto app_process"; }
fi
# no saved label (SELinux was off at install): what the unit's file_contexts says
if [ -n "$LBL" ]; then label_fix "$LBL" "$B"; elif [ -e "$ROOT$SEL" ]; then restorecon "$B" 2>/dev/null; fi
sync
remount ro
REMOUNTED=
[ "$(sum "$B")" = "$ORIG" ] || fail "app_process isn't stock after the rescue"
log "rescue: app_process is stock ($ORIG${LBL:+, $LBL}), hooks off. Restart the head unit; uninstall or install again once Android is up."
