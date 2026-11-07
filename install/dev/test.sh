#!/bin/sh
# Off-car test of the dev install scripts (step1-install, step2/3-enable,
# hooks-off, restore, mods-install/remove) against a fake root with stubbed
# md5/nemesis/chown. Uses out/ (run ./build.sh first). UNIT_SH picks the shell
# (an ARM busybox ash under qemu is closest to the unit's mksh; any POSIX sh works).
set -e
cd "$(dirname "$0")/../.."
[ -f out/app_process ] || { echo "run ./build.sh first"; exit 1; }
SH=${UNIT_SH:-sh}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/root/system/bin" "$T/root/system/lib" "$T/root/system/framework" "$T/root/data/dalvik-cache" "$T/bin"
cp "${STOCK_APP_PROCESS:?path to a stock app_process}" "$T/root/system/bin/app_process"
STOCK=$(md5sum "$T/root/system/bin/app_process" | cut -d' ' -f1)
printf '#!/bin/sh\n[ -f "$1" ] && md5sum "$1"\n' > "$T/bin/md5"
printf '#!/bin/sh\nexit 0\n' > "$T/bin/nemesis"
printf '#!/bin/sh\nexit 0\n' > "$T/bin/chown"
chmod +x "$T/bin/"*
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted $2, got $3"; fail=1; fi; }
u() { s=$1; shift; env CAPCHECK=0 PATH="$T/bin:$PATH" ROOT="$T/root" HERE="$PWD" $SH "install/dev/$s" "$@" >"$T/out" 2>&1; }
W=$(md5sum out/app_process | cut -d' ' -f1)
A="$T/root/system/bin/app_process"
BAK="$T/root/data/hmgapi/stock/system/bin/app_process"

set +e; u step1-install.sh; rc=$?; set -e
check "install exit" 0 $rc
check "app_process is wrapper" "$W" "$(md5sum "$A" | cut -d' ' -f1)"
check "stock kept" "$STOCK" "$(md5sum "$A.hmgapi-stock" | cut -d' ' -f1)"
check "backup taken" "$STOCK" "$(md5sum "$BAK" | cut -d' ' -f1)"
u step1-install.sh
check "second install is a no-op" "already installed (app_process is the wrapper)" "$(sed -n 2p "$T/out")"

set +e; u step2-enable.sh; rc=$?; set -e
check "step 2 exit" 0 $rc
check "library installed" "$(md5sum out/libhmgapi_hook.so | cut -d' ' -f1)" "$(md5sum "$T/root/system/lib/libhmgapi_hook.so" | cut -d' ' -f1)"
check "enable file" "yes" "$([ -e "$T/root/data/hmgapi/hook/enable" ] && echo yes || echo no)"
check "starts reset" "0" "$(cat "$T/root/data/hmgapi/hook/starts")"
echo stale > "$T/root/data/dalvik-cache/system@framework@hmgapi-hook.jar@classes.dex"
set +e; u step3-enable.sh; rc=$?; set -e
check "step 3 exit" 0 $rc
check "jar installed" "$(md5sum out/hmgapi-hook.jar | cut -d' ' -f1)" "$(md5sum "$T/root/system/framework/hmgapi-hook.jar" | cut -d' ' -f1)"
check "stale jar cache removed" "no" "$([ -e "$T/root/data/dalvik-cache/system@framework@hmgapi-hook.jar@classes.dex" ] && echo yes || echo no)"
u hooks-off.sh
check "hooks-off writes disable" "yes" "$([ -e "$T/root/data/hmgapi/hook/disable" ] && echo yes || echo no)"

echo "old wrapper" > "$A"
set +e; u step1-install.sh; rc=$?; set -e
check "updates an earlier wrapper" 0 $rc
check "updated app_process is wrapper" "$W" "$(md5sum "$A" | cut -d' ' -f1)"
check "update keeps stock copy" "$STOCK" "$(md5sum "$A.hmgapi-stock" | cut -d' ' -f1)"

check "backup dir root-only" "drwx------" "$(ls -ld "$T/root/data/hmgapi/stock/system/bin" | cut -c1-10)"
cp "$BAK" "$T/bak.good"; echo evil > "$BAK"
set +e; u restore.sh; rc=$?; set -e
check "tampered backup refused" 1 $rc
check "tampered backup not installed" "$W" "$(md5sum "$A" | cut -d' ' -f1)"
cp "$T/bak.good" "$BAK"

set +e; u restore.sh; rc=$?; set -e
check "restore exit" 0 $rc
check "app_process is stock" "$STOCK" "$(md5sum "$A" | cut -d' ' -f1)"
check "kept copy removed" "no" "$([ -e "$A.hmgapi-stock" ] && echo yes || echo no)"
check "only app_process left" "app_process" "$(ls "$T/root/system/bin")"
check "library removed" "" "$(ls "$T/root/system/lib")"
check "jar removed" "" "$(ls "$T/root/system/framework")"
check "enable removed" "no" "$([ -e "$T/root/data/hmgapi/hook/enable" ] && echo yes || echo no)"

set +e; u step2-enable.sh; rc=$?; set -e
check "step 2 refused without wrapper" 1 $rc
check "step 2 left /system/lib alone" "" "$(ls "$T/root/system/lib")"

echo junk > "$A"
set +e; u step1-install.sh; rc=$?; set -e
check "unknown md5 refused" 1 $rc
check "unknown md5 left alone" "junk" "$(cat "$A")"
# export, then unset: VAR=1 u (a function) leaves VAR set after it in bash 3.2's sh (macOS)
set +e; export HMGAPI_FORCE=1; u step1-install.sh; rc=$?; unset HMGAPI_FORCE; set -e
check "HMGAPI_FORCE installs" 0 $rc
check "forced: first backup kept" "$STOCK" "$(md5sum "$BAK" | cut -d' ' -f1)"
set +e; env PATH="$T/bin:$PATH" ROOT="$T/root" HERE="$PWD" $SH install/dev/restore.sh >"$T/out" 2>&1; rc=$?; set -e
check "refuses without full root" 1 $rc
# mods
set +e; u mods-install.sh; rc=$?; set -e
check "mods install" 0 $rc
check "mod files copied" "hooks.jar mod.json settings.json" "$(ls "$T/root/data/hmgapi/mods/hello" | tr '\n' ' ' | sed 's/ $//')"
check "mods dir root-only" "drwx------" "$(ls -ld "$T/root/data/hmgapi/mods" | cut -c1-10)"
set +e; u mods-remove.sh hello; rc=$?; set -e
check "mod remove" "0 no" "$rc $([ -e "$T/root/data/hmgapi/mods/hello" ] && echo yes || echo no)"
set +e; env CAPCHECK=0 PATH="$T/bin:$PATH" ROOT="$T/root" $SH install/dev/mods-remove.sh ../x >/dev/null 2>&1; rc=$?; set -e
check "mod remove refuses a path" 2 $rc
exit $fail
