#!/bin/sh
# Off-car test of install.sh / uninstall.sh against a fake head unit: install,
# update, uninstall must leave /system byte-identical; refusals change nothing.
# UNIT_SH: the shell to run them with (busybox ash is closest to the unit's mksh).
# Needs out/payload (./build.sh payload).
set -e
cd "$(dirname "$0")/../.."
SH=${UNIT_SH:-sh}
PL=$PWD/out/payload
[ -f "$PL/MANIFEST" ] || { echo "run ./build.sh payload first"; exit 1; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
# stubs for the unit's tools
mkdir -p $T/bin
printf '#!/bin/sh\n[ -f "$1" ] || exit 1\nset -- $(md5sum "$1"); echo "$1 $2"\n' > $T/bin/md5
printf '#!/bin/sh\nexit 0\n' > $T/bin/chown
# Android's tools, for the app's install and open.sh: what they're asked goes to calls.log
printf '#!/bin/sh\necho "${FAKE_BOOT:-0}"\n' > $T/bin/getprop
printf '#!/bin/sh\necho "setprop $*" >> "%s/calls.log"\n' "$T" > $T/bin/setprop
# pm reads the apk through a helper that isn't root: a file others can't read is
# INSTALL_FAILED_INVALID_URI, as on the car (#187)
printf '#!/bin/sh\necho "pm $*" >> "%s/calls.log"\ncase "$1" in path) [ -n "$FAKE_HAVE_APP" ] && echo package:/data/app/hmgapi.app-1.apk ;; install) case "$(ls -l "$3" | cut -c8)" in r) echo "${FAKE_PM:-Success}" ;; *) echo "Failure [INSTALL_FAILED_INVALID_URI]" ;; esac ;; esac\nexit 0\n' "$T" > $T/bin/pm
printf '#!/bin/sh\necho "am $*" >> "%s/calls.log"\n' "$T" > $T/bin/am
chmod +x $T/bin/*
calls() { cat $T/calls.log 2>/dev/null || true; : > $T/calls.log; }
run() { set +e; out=$(cd $T && env PATH="$T/bin:$PATH" ROOT=$R CAPCHECK=0 $SH "$@" 2>&1); rc=$?; set -e; }
tree() { (cd $R/system && find . -type f | sort | while read f; do echo "$(md5sum < "$f" | cut -c1-32) $f"; done) }

mkdir -p $R/system/bin $R/system/lib $R/system/framework $R/system/etc $R/data/dalvik-cache $R/dev
# a car's own app_process when tools/cars.py runs this on its stock tree (HMGAPI_CAR, docs/car-trees.md)
STOCK=${HMGAPI_CAR:+$HMGAPI_CAR/bin/app_process}
if [ -f "$STOCK" ]; then cp $STOCK $R/system/bin/app_process; else { printf '\177ELF'; head -c 9000 /dev/urandom; } > $R/system/bin/app_process; fi   # a binary: nothing else is taken for stock
chmod 755 $R/system/bin/app_process
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > $R/system/etc/install-recovery.sh; chmod 755 $R/system/etc/install-recovery.sh
printf 'RED="\\x1b[1;31m"\necho qb stock\n# NEMESIS_QB_HOOK\nnemesis watchdog' > $R/system/etc/qb_runtime.sh   # backslashes and no newline at the end: the uninstall still gives these bytes back
echo stock > $R/system/lib/libc.so
# NEMESIS: uninstall <id> runs the package's preuninstall as native/pkg.c does (cwd the package,
# the caller's environment), then removes it; every call goes to nemesis.log. A package another
# one depends on (its depends= names it) isn't uninstalled: exit 4, as NEMESIS does.
mkdir -p $R/system/xbin $R/data/nemesis/apps
cat > $R/system/xbin/nemesis <<NEM
#!/bin/sh
echo "nemesis \$* sync=\${HMGAPI_NEMESIS_SYNC:-}" >> $T/nemesis.log
[ "\$1" = uninstall ] || exit 0
for o in $R/data/nemesis/apps/*/NEMESIS.data; do
    [ -f "\$o" ] || continue
    case ",\$(sed -n 's/^depends="\(.*\)"\$/\1/p' "\$o")," in *",\$2,"*|*",\$2@"*) echo "dependent \$o"; exit 4 ;; esac
done
A=$R/data/nemesis/apps/\$2
(cd "\$A" && ROOT=$R $SH scripts/preuninstall.sh) || { echo "preuninstall failed: package stays"; exit 5; }
rm -rf "\$A"
NEM
chmod 755 $R/system/xbin/nemesis
BEFORE=$(tree)
# the payload's binaries, with this tree's scripts (so a script edit needs no rebuild)
P=$T/payload; cp -r $PL $P
cp install/common/install.sh install/common/uninstall.sh install/common/lib.sh install/common/stock.txt install/common/app-pending.sh install/common/app-open.sh install/common/hook-check.sh install/common/boot.sh $P/
rm -rf $P/nemesis; mkdir -p $P/nemesis/mod $P/nemesis/bin
cp -r install/nemesis/scripts $P/nemesis/scripts; cp -r install/nemesis/mod/scripts $P/nemesis/mod/scripts
cp install/nemesis/bin/open.sh install/nemesis/bin/start.sh $P/nemesis/bin/; cp install/nemesis/conflicts.txt $P/nemesis/
[ -f "$STOCK" ] || echo "$(md5sum < $R/system/bin/app_process | cut -c1-32) test stock" > $P/stock.txt
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 > MANIFEST)

# refusals first: nothing may change
cp $R/system/bin/app_process $T/real; echo x >> $R/system/bin/app_process
run $P/install.sh $P
has "unknown app_process refused" "isn't a known stock build, so nothing was changed" "$out"
has "... asks for an issue with the line, the car and its software" "and this one isn't checked yet. Please open an issue at https://github.com/Open-HMG/HMG-API/issues/new/choose with this line, your car and its head unit software version (e.g. 260507), and we'll add your build." "$out"
check "... and names no way to force it (maintainers only, for now)" "" "$(echo "$out" | grep -i 'force' || true)"
check "refusal exit code" 1 $rc
check "nothing installed" no "$([ -e $R/system/bin/hmgapid ] && echo yes || echo no)"
cp $T/real $R/system/bin/app_process
echo junk >> $P/lpatch
run $P/install.sh $P
has "damaged payload refused" "payload file lpatch is damaged" "$out"
cp $PL/lpatch $P/lpatch
mv $P/maps $T/maps.away   # unzipped where MAPS.md5 and maps/ were one name (beta 3 and earlier)
run $P/install.sh $P
has "payload without its hook maps refused" "the hook maps folder (payload/maps) is missing" "$out"
has "... before anything changed" "Nothing was changed." "$out"
mv $T/maps.away $P/maps
check "still stock after refusals" "$BEFORE" "$(tree)"

run $P/install.sh $P
has "install finishes" "installed. Restart the head unit" "$out"
check "app_process is the wrapper" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process)"
check "stock app_process kept beside it" "$(md5sum < $T/real)" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "stock app_process backed up" "$(md5sum < $T/real)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
for f in bin/hmgapid bin/lpatch lib/libhmgapi_hook.so framework/hmgapi-hook.jar; do
    check "installed $f" "$(md5sum < $P/${f##*/})" "$(md5sum < $R/system/$f 2>/dev/null)"
done
check "boot hook in install-recovery.sh" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/install-recovery.sh)"
check "boot hook in qb_runtime.sh" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/qb_runtime.sh)"
check "boot hook: only hmgapid boot, which returns at once (NEMESIS's start comes after it)" "if [ -x /system/bin/hmgapid ]; then /system/bin/hmgapid boot; fi" "$(sed -n 3p $R/system/etc/install-recovery.sh)"
check "... and NEMESIS's own lines still follow it" "# NEMESIS|nemesis watchdog" "$(sed -n 5p $R/system/etc/install-recovery.sh)|$(sed -n 6p $R/system/etc/install-recovery.sh)"
check "boot.sh (the boot steps hmgapid boot runs) kept, root only" "-rwx------" "$(ls -l $R/data/hmgapi/boot.sh | cut -c1-10)"
check "this boot marked done, so a boot service NEMESIS starts after the install runs no boot steps (no engine recover mid-install)" yes "$([ -f $R/dev/.hmgapi-boot ] && echo yes)"
check "hooks on at the next start" "yes" "$([ -f $R/data/hmgapi/hook/enable ] && echo yes)"
check "updates off by default" '{"updates": false}' "$(cat $R/data/hmgapi/settings/hmgapi.json)"
check "/data/hmgapi: traverse only for apps" "drwx--x--x" "$(ls -ld $R/data/hmgapi | cut -c1-10)"
check "Android down: app kept for later, readable by the package manager" "-rw-r--r--" "$(ls -l $R/data/hmgapi/public/HmgApi.apk | cut -c1-10)"
check "... and its installer kept" yes "$([ -f $R/data/hmgapi/app-pending.sh ] && echo yes)"
has "boot.sh starts it while the app waits" "app-pending.sh" "$(cat $R/data/hmgapi/boot.sh)"
check "uninstaller kept on the unit" yes "$([ -f $R/data/hmgapi/uninstall.sh ] && echo yes)"
check "a copy of the app kept, readable by the package manager" "-rw-r--r--" "$(ls -l $R/data/hmgapi/HmgApi.apk | cut -c1-10)"
check "open.sh kept" yes "$([ -f $R/data/hmgapi/open.sh ] && echo yes)"
check "hook-check.sh kept, root only" "-rwx------" "$(ls -l $R/data/hmgapi/hook-check.sh | cut -c1-10)"
has "boot.sh: puts a rewritten boot script's block back (hook-check.sh)" "hook-check.sh" "$(cat $R/data/hmgapi/boot.sh)"
check "HMG-API's NEMESIS scripts and conflicts kept, for engine nemesis-sync" yes "$([ -f $R/data/hmgapi/nemesis/scripts/preuninstall.sh ] && [ -f $R/data/hmgapi/nemesis/mod/scripts/preuninstall.sh ] && [ -f $R/data/hmgapi/nemesis/conflicts.txt ] && echo yes)"
has "boot.sh: engine nemesis-sync when NEMESIS is there" "engine nemesis-sync" "$(cat $R/data/hmgapi/boot.sh)"
check "hook maps installed" yes "$([ -f $R/data/hmgapi/maps/kia/ev6/usa/260507/car.json ] && [ -s $R/data/hmgapi/maps/points.txt ] && echo yes)"
has "install record" "file $(md5sum < $P/app_process | cut -c1-32) /system/bin/app_process" "$(cat $R/data/hmgapi/installed.txt)"
echo '{"updates": true}' > $R/data/hmgapi/settings/hmgapi.json

run $P/install.sh $P
has "update over itself" "HMG-API's wrapper is in place" "$out"
check "update keeps the stock copy" "$(md5sum < $T/real)" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "update keeps one boot hook" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/install-recovery.sh)"
check "update keeps the owner's settings" '{"updates": true}' "$(cat $R/data/hmgapi/settings/hmgapi.json)"

# the app's install with Android up (stand-ins for pm and the rest)
rm -f $R/data/hmgapi/public/HmgApi.apk; calls >/dev/null
# export, then unset: VAR=1 run (a function) leaves VAR set after it in bash 3.2's sh (macOS)
export FAKE_BOOT=1 FAKE_PM='Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]' HMGAPI_TEST_ANDROID=1; run $P/install.sh $P; unset FAKE_BOOT FAKE_PM HMGAPI_TEST_ANDROID
has "app install failed: said, with the ways to fix it" "the HMG-API app didn't install: Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]. It's tried again at the next restart; or NEMESIS Manager > HMG-API > Launch" "$out"
check "... kept waiting, so the next restart tries again" yes "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes)"
c=$(calls)
has "... pm ran with sys.boot_completed 0" "setprop sys.boot_completed 0
pm install -r $R/data/hmgapi/public/HmgApi.apk
setprop sys.boot_completed 1" "$c"
has "... a toast on the screen" "Restart the head unit to try again, or use Launch in NEMESIS Manager" "$c"
export FAKE_BOOT=1 HMGAPI_TEST_ANDROID=1; run $P/install.sh $P; unset FAKE_BOOT HMGAPI_TEST_ANDROID
has "app install works: said" "installed the HMG-API app" "$out"
check "... nothing left waiting" no "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes || echo no)"
# the USB installer stages the payload with umask 077: pm still gets a copy it can read (#187)
chmod 600 $P/HmgApi.apk
export FAKE_BOOT=1 HMGAPI_TEST_ANDROID=1; run $P/install.sh $P; unset FAKE_BOOT HMGAPI_TEST_ANDROID
chmod 644 $P/HmgApi.apk
has "app staged root-only (USB): installed all the same" "installed the HMG-API app" "$out"
check "... nothing left waiting" no "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes || echo no)"

# open.sh: open the app, installing it first if it's missing
calls >/dev/null
export FAKE_BOOT=1 FAKE_HAVE_APP=1; run $R/data/hmgapi/open.sh; unset FAKE_BOOT FAKE_HAVE_APP
check "open.sh: app there, opened (quietly: Manager would toast any output)" "" "$out"
check "... nothing installed" "pm path hmgapi.app
am start -n hmgapi.app/.MainActivity" "$(calls)"
cp $P/HmgApi.apk $R/data/hmgapi/public/HmgApi.apk
export FAKE_BOOT=1; run $R/data/hmgapi/open.sh; unset FAKE_BOOT
check "open.sh: app missing, installed from the kept copy, then opened" "HMG-API: installed the app" "$out"
has "... pm on the kept copy, then am start" "pm install -r $R/data/hmgapi/HmgApi.apk
setprop sys.boot_completed 1
am start -n hmgapi.app/.MainActivity" "$(calls)"
check "... nothing left waiting" no "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes || echo no)"
export FAKE_BOOT=1 FAKE_PM='Failure [X]'; run $R/data/hmgapi/open.sh; unset FAKE_BOOT FAKE_PM
check "open.sh: install fails, says why" "1 HMG-API: the app didn't install: Failure [X]" "$rc $out"
check "... once (Manager toasts the output; no toast of its own)" "" "$(calls | grep TOAST || true)"
run $R/data/hmgapi/open.sh
check "open.sh: Android not up" "1 HMG-API: Android isn't up yet: try again in a minute" "$rc $out"
mv $R/data/hmgapi/HmgApi.apk $T/apk.away
export FAKE_BOOT=1; run $R/data/hmgapi/open.sh; unset FAKE_BOOT
has "open.sh: no copy of the app" "there's no copy to install: install HMG-API again" "$out"
mv $T/apk.away $R/data/hmgapi/HmgApi.apk

# listed in NEMESIS: HMG-API and mods, demo's package with an older script copy that would uninstall,
# user's depending on base's (its "requires"), which sorts first
for id in hmgapi hmgapi.demo hmgapi.base hmgapi.user; do
    mkdir -p $R/data/nemesis/apps/$id/scripts $R/data/nemesis/apps/$id/mod
    printf '[nemesis]\nid=%s\npackage-version=1.0.0\n' $id > $R/data/nemesis/apps/$id/NEMESIS.data
    [ $id = hmgapi ] || { echo ${id#hmgapi.} > $R/data/nemesis/apps/$id/mod/id; echo 'depends="hmgapi@>=1.0"' >> $R/data/nemesis/apps/$id/NEMESIS.data; }
done
echo 'depends="hmgapi@>=1.0,hmgapi.base@>=1.0.0"' >> $R/data/nemesis/apps/hmgapi.user/NEMESIS.data
cp install/nemesis/scripts/*.sh $R/data/nemesis/apps/hmgapi/scripts/
printf '#!/system/bin/sh\necho "old script: would remove the mod"\nexit 1\n' > $R/data/nemesis/apps/hmgapi.demo/scripts/preuninstall.sh
: > $T/nemesis.log
run $R/data/hmgapi/uninstall.sh
has "uninstall finishes" "HMG-API uninstalled" "$out"
check "... NEMESIS: the mods' packages unlisted first (base again once user's is gone), then HMG-API's, list-only" \
    "nemesis uninstall hmgapi.base sync=1|nemesis uninstall hmgapi.demo sync=1|nemesis uninstall hmgapi.user sync=1|nemesis uninstall hmgapi.base sync=1|nemesis uninstall hmgapi sync=1" \
    "$(tr '\n' '|' < $T/nemesis.log | sed 's/|$//')"
check "... none listed any more (the old script copy refreshed first)" "" "$(ls $R/data/nemesis/apps)"
has "... said" "NEMESIS: hmgapi unlisted" "$out"
check "the app's copy, open.sh, hook-check.sh and boot.sh gone" no "$([ -e $R/data/hmgapi/HmgApi.apk ] || [ -e $R/data/hmgapi/open.sh ] || [ -e $R/data/hmgapi/hook-check.sh ] || [ -e $R/data/hmgapi/boot.sh ] && echo yes || echo no)"
check "/system byte-identical to stock" "$BEFORE" "$(tree)"
check "settings kept without --purge" yes "$([ -f $R/data/hmgapi/settings/hmgapi.json ] && echo yes)"
check "hooks off" no "$([ -f $R/data/hmgapi/hook/enable ] && echo yes || echo no)"
check "waiting app removed" no "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes || echo no)"

run $P/install.sh $P
mkdir -p $R/data/nemesis/apps/hmgapi/scripts; printf '[nemesis]\nid=hmgapi\n' > $R/data/nemesis/apps/hmgapi/NEMESIS.data; : > $T/nemesis.log
export HMGAPI_FROM_NEMESIS=1; run $R/data/hmgapi/uninstall.sh; unset HMGAPI_FROM_NEMESIS
check "uninstall run by NEMESIS: NEMESIS isn't called (it's unlisting it already)" "" "$(cat $T/nemesis.log)"
rm -rf $R/data/nemesis/apps/hmgapi
run $P/install.sh $P
run $R/data/hmgapi/uninstall.sh --purge
check "reinstall + purge: /system stock" "$BEFORE" "$(tree)"
check "purge removes /data/hmgapi" no "$([ -e $R/data/hmgapi ] && echo yes || echo no)"
# ---- a firmware update after the install: /system back to stock (a new app_process, the boot
# scripts without HMG-API's block, HMG-API's files gone), /data/hmgapi as it was
printf '#!/bin/sh\necho "engine $*" >> "%s/calls.log"\necho "firmware update: 1 file(s) are the new firmware'"'"'s stock now"\n' "$T" > $T/bin/engine; chmod +x $T/bin/engine
firmware_update() {
    rm -f $R/system/bin/hmgapid $R/system/bin/lpatch $R/system/lib/libhmgapi_hook.so $R/system/framework/hmgapi-hook.jar $R/system/bin/app_process.hmgapi-stock
    cp $T/newfw $R/system/bin/app_process
    printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > $R/system/etc/install-recovery.sh
    printf 'echo qb stock\n# NEMESIS_QB_HOOK\nnemesis watchdog\n' > $R/system/etc/qb_runtime.sh
}
{ printf '\177ELF'; head -c 9000 /dev/urandom; } > $T/newfw; chmod 755 $T/newfw
echo "$(md5sum < $T/newfw | cut -c1-32) test new firmware" >> $P/stock.txt
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 > MANIFEST)
export HMGAPI_TEST_ENGINE=$T/bin/engine
run $P/install.sh $P
mkdir -p $R/data/hmgapi/state; echo '{"files": {}, "settings": {}}' > $R/data/hmgapi/state/files.json
firmware_update; NEWFW=$(tree); calls >/dev/null

# (a) installed again over it
run $P/install.sh $P
has "firmware update, then install: found" "a firmware update replaced /system since HMG-API was installed" "$out"
check "... the engine drops its old-firmware records first" "engine firmware-reset" "$(calls | grep '^engine firmware' || true)"
has "... said" "file(s) are the new firmware's stock now" "$out"
has "... installs" "installed. Restart the head unit" "$out"
check "... the new firmware's app_process is the backup now" "$(md5sum < $T/newfw)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
check "... and kept beside the wrapper" "$(md5sum < $T/newfw)" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "... one boot hook" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/install-recovery.sh)"
run $P/install.sh $P
check "an update after that isn't a firmware reset" "" "$(calls | grep '^engine firmware' || true)"
run $R/data/hmgapi/uninstall.sh
has "... uninstall finishes" "HMG-API uninstalled" "$out"
check "... /system is the new firmware's stock" "$NEWFW" "$(tree)"

# (b) uninstalled after it (NEMESIS Manager's Uninstall; hmgapid and the engine are gone)
run $P/install.sh $P
cp $T/real $R/data/hmgapi/stock/system/bin/app_process   # the old firmware's backup, as an install before the update took it
firmware_update; calls >/dev/null
mkdir -p $R/data/nemesis/apps/hmgapi/scripts; printf '[nemesis]\nid=hmgapi\n' > $R/data/nemesis/apps/hmgapi/NEMESIS.data
cp install/nemesis/scripts/*.sh $R/data/nemesis/apps/hmgapi/scripts/
set +e; out=$(cd $R/data/nemesis/apps/hmgapi && env PATH="$T/bin:$PATH" ROOT=$R CAPCHECK=0 $R/system/xbin/nemesis uninstall hmgapi 2>&1); rc=$?; set -e
check "firmware update, then NEMESIS uninstall: works" 0 $rc
has "... found the update" "a firmware update replaced /system since HMG-API was installed" "$out"
has "... says what stays" "Settings mods changed stay as they are" "$out"
check "... unlisted" no "$([ -d $R/data/nemesis/apps/hmgapi ] && echo yes || echo no)"
check "... the old firmware's app_process never put back: /system is the new firmware's stock" "$NEWFW" "$(tree)"
check "... marked for the next install" yes "$([ -f $R/data/hmgapi/firmware-reset ] && echo yes)"
run $P/install.sh $P
has "... install after that: starts from the new firmware" "a firmware update replaced /system since HMG-API was installed" "$out"
check "... engine firmware-reset" "engine firmware-reset" "$(calls | grep '^engine firmware' || true)"
check "... new backup" "$(md5sum < $T/newfw)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
check "... mark gone" no "$([ -f $R/data/hmgapi/firmware-reset ] && echo yes || echo no)"
run $R/data/hmgapi/uninstall.sh --purge
check "... uninstall: /system is the new firmware's stock" "$NEWFW" "$(tree)"

# (c) a firmware update after an uninstall (only the backup tells): reinstall takes a new one
run $P/install.sh $P
run $R/data/hmgapi/uninstall.sh
cp $T/real $R/data/hmgapi/stock/system/bin/app_process
cp $T/real $R/system/bin/app_process
calls >/dev/null
run $P/install.sh $P
check "uninstalled, same firmware: no reset" "" "$(calls | grep '^engine firmware' || true)"
run $R/data/hmgapi/uninstall.sh
cp $T/real $R/data/hmgapi/stock/system/bin/app_process; cp $T/newfw $R/system/bin/app_process
run $P/install.sh $P
check "uninstalled, then a firmware update: the stale backup found, reset" "engine firmware-reset" "$(calls | grep '^engine firmware' || true)"
check "... new backup" "$(md5sum < $T/newfw)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
unset HMGAPI_TEST_ENGINE

# NEMESIS 1.17's LivePatch: on every boot its watchdog writes its own script at app_process, and
# the first time keeps the binary it finds there as app_process.real (native/livepatch.c,
# lp_wrapper_install, called from svc.c whether or not a LivePatch package is installed)
NLP='#!/system/bin/sh
# NEMESIS LivePatch: apply patches before the zygote starts, then run the real binary.
case " $* " in
*" --zygote "*) [ -x /system/xbin/nemesis ] && /system/xbin/nemesis boot-apply ;;
esac
exec /system/bin/app_process.real "$@"'
nemesis_boot() {
    grep -q '^# NEMESIS LivePatch' $R/system/bin/app_process || [ -f $R/system/bin/app_process.real ] \
        || cp $R/system/bin/app_process $R/system/bin/app_process.real
    printf '%s\n' "$NLP" > $R/system/bin/app_process
}
# what the zygote start runs, NEMESIS's script and HMG-API's wrapper followed: the binary at the end
# (wherever each one is: a script or wrapper kept as a "stock copy" is followed too, and a chain
# that comes back on itself starts nothing)
chain() {
    local f=$R/system/bin/app_process n=0
    while [ $n -lt 8 ]; do
        [ -f "$f" ] || { echo "nothing: $f is gone"; return; }
        if grep -q '^# NEMESIS LivePatch' "$f"; then f=$R/system/bin/app_process.real
        elif case " $WRAPPERS " in *" $(md5sum < "$f" | cut -c1-32) "*) true ;; *) false ;; esac; then f=$R/system/bin/app_process.hmgapi-stock
        else md5sum < "$f"; return; fi
        n=$((n + 1))
    done
    echo "nothing: a loop"
}
WRAPPERS="$(md5sum < $P/app_process | cut -c1-32)"
run $R/data/hmgapi/uninstall.sh
STK=$(md5sum < $T/newfw)
check "NEMESIS 1.17 cases start from stock" "$STK" "$(md5sum < $R/system/bin/app_process)"
NLPSUM=$(printf '%s\n' "$NLP" | md5sum)

# (a) HMG-API first, then NEMESIS 1.17: NEMESIS keeps HMG-API's wrapper as app_process.real
run $P/install.sh $P
nemesis_boot
check "HMG-API, then NEMESIS 1.17: the zygote start ends at stock" "$STK" "$(chain)"
# an update under NEMESIS's script: the new wrapper goes where NEMESIS runs it
cp $P/app_process $T/wrapper.old; echo update >> $P/app_process; WRAPPERS="$WRAPPERS $(md5sum < $P/app_process | cut -c1-32)"
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 > MANIFEST)
run $P/install.sh $P
has "... an update under NEMESIS's script finishes" "installed. Restart the head unit" "$out"
check "... the new wrapper is app_process.real" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process.real)"
check "... NEMESIS's script left as it is" "$NLPSUM" "$(md5sum < $R/system/bin/app_process)"
has "... install record: the wrapper's md5" "file $(md5sum < $P/app_process | cut -c1-32) /system/bin/app_process" "$(cat $R/data/hmgapi/installed.txt)"
nemesis_boot
check "... and after NEMESIS's next boot the zygote start still ends at stock" "$STK" "$(chain)"
run $R/data/hmgapi/uninstall.sh
has "... uninstall finishes" "HMG-API uninstalled" "$out"
check "... the stock binary is back in app_process.real" "$STK" "$(md5sum < $R/system/bin/app_process.real)"
check "... NEMESIS's script still there" "$NLPSUM" "$(md5sum < $R/system/bin/app_process)"
check "... HMG-API's stock copy gone" no "$([ -e $R/system/bin/app_process.hmgapi-stock ] && echo yes || echo no)"
nemesis_boot
check "... and after NEMESIS's next boot the zygote still starts: stock" "$STK" "$(chain)"

# (b) NEMESIS 1.17 first (its script at app_process, stock in .real), then HMG-API
rm -rf $R/data/hmgapi
run $P/install.sh $P
has "NEMESIS 1.17, then HMG-API: installs (no unknown-app_process refusal)" "installed. Restart the head unit" "$out"
check "... the wrapper is app_process.real" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process.real)"
check "... NEMESIS's script left as it is" "$NLPSUM" "$(md5sum < $R/system/bin/app_process)"
check "... stock kept beside it" "$STK" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "... the backup is the stock binary, not NEMESIS's script" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
nemesis_boot
check "... the zygote start ends at stock" "$STK" "$(chain)"
run $R/data/hmgapi/uninstall.sh
has "... uninstall finishes" "HMG-API uninstalled" "$out"
check "... the stock binary is back in app_process.real" "$STK" "$(md5sum < $R/system/bin/app_process.real)"
nemesis_boot
check "... and after NEMESIS's next boot the zygote still starts: stock" "$STK" "$(chain)"

# (d) an update by an older HMG-API under NEMESIS's script (#396 review): it wrote its wrapper over
# NEMESIS's script and left the wrapper before it in app_process.real. Before the next boot, this
# release's update, and then its uninstall, with no boot in between
mkdir -p $R/data/hmgapi; rm -rf $R/data/hmgapi
cp $T/wrapper.old $P/app_process   # an older wrapper first
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 > MANIFEST)
run $P/install.sh $P                                       # into .real, under NEMESIS's script
cp $P/app_process $T/wrapper.older; echo older >> $T/wrapper.older
cp $T/wrapper.older $R/system/bin/app_process.real          # the wrapper before the old update...
cp $P/app_process $R/system/bin/app_process                 # ...and the old update's, over NEMESIS's script
WRAPPERS="$WRAPPERS $(md5sum < $T/wrapper.older | cut -c1-32)"
echo update >> $P/app_process; WRAPPERS="$WRAPPERS $(md5sum < $P/app_process | cut -c1-32)"
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 > MANIFEST)
run $P/install.sh $P
has "older update over NEMESIS's script, then this one: finishes" "installed. Restart the head unit" "$out"
check "... app_process.real gets the new wrapper (not left stale)" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process.real)"
nemesis_boot
check "... after NEMESIS's next boot the new wrapper runs, then stock" "$(md5sum < $P/app_process)|$STK" "$(md5sum < $R/system/bin/app_process.real)|$(chain)"
# the same, then an uninstall before any boot
cp $T/wrapper.older $R/system/bin/app_process.real; cp $P/app_process $R/system/bin/app_process
run $R/data/hmgapi/uninstall.sh
has "... uninstall with no boot between finishes" "HMG-API uninstalled" "$out"
check "... app_process stock" "$STK" "$(md5sum < $R/system/bin/app_process)"
check "... app_process.real stock" "$STK" "$(md5sum < $R/system/bin/app_process.real)"
nemesis_boot
check "... and after NEMESIS's next boot the zygote still starts: stock" "$STK" "$(chain)"
# (e) the brick an older uninstall leaves on NEMESIS 1.17, caught before the next boot: stock over
# NEMESIS's script, .real still a wrapper, app_process.hmgapi-stock gone. Installing this release
# takes the stock bytes from app_process and wraps both
rm -rf $R/data/hmgapi
cp $T/newfw $R/system/bin/app_process; cp $T/wrapper.older $R/system/bin/app_process.real; rm -f $R/system/bin/app_process.hmgapi-stock
check "older uninstall's brick, before the boot: the zygote start would fail" "nothing: $R/system/bin/app_process.hmgapi-stock is gone" "$(nemesis_boot; chain)"
cp $T/newfw $R/system/bin/app_process
run $P/install.sh $P
has "... installing this release finishes" "installed. Restart the head unit" "$out"
check "... stock kept from app_process" "$STK" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
nemesis_boot
check "... and after NEMESIS's next boot the zygote start ends at stock" "$STK" "$(chain)"
run $R/data/hmgapi/uninstall.sh
nemesis_boot
check "... an uninstall after that leaves a starting zygote" "$STK" "$(chain)"
rm -f $R/system/bin/app_process.real; cp $T/newfw $R/system/bin/app_process
nemesis_boot

# (f) #398 review: the wrapper is the same bytes from release to release (rc.7's and rc.8's are this
# one; (d) and (e) use a different older one, which hid this). An older uninstall on NEMESIS 1.17,
# then one restart: NEMESIS's script at app_process, .real this very wrapper, app_process.hmgapi-stock
# gone, /data/hmgapi/stock kept. Not an update: the stock copy comes back from the backup
rm -rf $R/data/hmgapi; cp $T/newfw $R/system/bin/app_process; rm -f $R/system/bin/app_process.real
run $P/install.sh $P; nemesis_boot
cp $T/newfw $R/system/bin/app_process; rm -f $R/system/bin/app_process.hmgapi-stock $R/data/hmgapi/installed.txt  # the old uninstall
nemesis_boot                                                                                              # the restart that still works
check "old uninstall + one restart, same wrapper: the next restart would fail" "nothing: $R/system/bin/app_process.hmgapi-stock is gone" "$(chain)"
run $P/install.sh $P
has "... installing this release rebuilds the stock copy" "its stock copy is gone (an older uninstall, #397): putting it back" "$out"
has "... and finishes" "installed. Restart the head unit" "$out"
check "... the stock copy is stock" "$STK" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "... the backup kept (no firmware reset)" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
nemesis_boot
check "... after NEMESIS's next boot the zygote start ends at stock" "$STK" "$(chain)"

# (g) the same with /data/hmgapi purged (uninstall --purge): refused, nothing changed
rm -f $R/system/bin/app_process.hmgapi-stock; rm -rf $R/data/hmgapi
B4=$(tree)
run $P/install.sh $P
has "same, /data/hmgapi purged: refused, pointing at #397" "there's no stock backup to take it from, so nothing was changed" "$out"
has "... names #397" "issues/397" "$out"
check "... /system unchanged" "$B4" "$(tree)"
rm -f $R/system/bin/app_process.real; cp $T/newfw $R/system/bin/app_process; nemesis_boot

# (h) without NEMESIS: our wrapper at app_process, app_process.hmgapi-stock gone, the backup kept
rm -f $R/system/bin/app_process.real; cp $T/newfw $R/system/bin/app_process; rm -rf $R/data/hmgapi
run $P/install.sh $P
rm -f $R/system/bin/app_process.hmgapi-stock
check "no NEMESIS, wrapper without its stock copy: the next start would fail" "nothing: $R/system/bin/app_process.hmgapi-stock is gone" "$(chain)"
run $P/install.sh $P
has "... installing rebuilds the stock copy" "its stock copy is gone (an older uninstall, #397): putting it back" "$out"
has "... and finishes" "installed. Restart the head unit" "$out"
check "... app_process is still the wrapper (no NEMESIS script written)" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process)"
check "... the zygote start ends at stock" "$STK" "$(chain)"
run $R/data/hmgapi/uninstall.sh
check "... and an uninstall after that gives stock back" "$STK" "$(md5sum < $R/system/bin/app_process)"
nemesis_boot

# (i) the same as (f) after an rc.5-or-older uninstall: the wrapper left in .real has other bytes
# (it still names app_process.hmgapi-stock, as every version's does). Rescued too, also when
# forced, and never taken for a stock build (#398 review: forcing dropped the stock backup)
w5() { cp $P/app_process $T/w5; printf 'rc.5 /system/bin/app_process.hmgapi-stock\n' >> $T/w5; }
old_uninstall_restart() {  # installed under NEMESIS's script, then an rc.5 uninstall, then a restart
    rm -rf $R/data/hmgapi; cp $T/newfw $R/system/bin/app_process; rm -f $R/system/bin/app_process.real
    run $P/install.sh $P; nemesis_boot
    w5; cp $T/w5 $R/system/bin/app_process.real
    cp $T/newfw $R/system/bin/app_process; rm -f $R/system/bin/app_process.hmgapi-stock $R/data/hmgapi/installed.txt
    nemesis_boot
}
old_uninstall_restart
run $P/install.sh $P
has "rc.5 uninstall + one restart (older wrapper): rebuilds the stock copy" "its stock copy is gone (an older uninstall, #397): putting it back" "$out"
check "... the stock copy is stock" "$STK" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "... the backup kept" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
check "... the new wrapper in app_process.real" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process.real)"
nemesis_boot
check "... after NEMESIS's next boot the zygote start ends at stock" "$STK" "$(chain)"
old_uninstall_restart
HMGAPI_FORCE=1 run $P/install.sh $P
has "... forced: rescued the same way" "its stock copy is gone (an older uninstall, #397): putting it back" "$out"
check "... forced: the stock backup kept, not replaced by the wrapper" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
check "... forced: the stock copy is stock" "$STK" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
old_uninstall_restart
rm -rf $R/data/hmgapi
B4=$(tree)
HMGAPI_FORCE=1 run $P/install.sh $P
has "... purged and forced: refused, pointing at #397" "there's no stock backup to take it from, so nothing was changed" "$out"
check "... /system unchanged" "$B4" "$(tree)"
rm -f $R/system/bin/app_process.real; cp $T/newfw $R/system/bin/app_process; nemesis_boot

# (j) a factory reset wipes /data (HMG-API's backups included) while HMG-API stays on /system, no
# NEMESIS. Reinstalling the same version: the backup comes from the stock copy, not the wrapper
rm -f $R/system/bin/app_process.real; cp $T/newfw $R/system/bin/app_process; rm -rf $R/data/hmgapi
run $P/install.sh $P; rm -rf $R/data/hmgapi
run $P/install.sh $P
has "/data wiped, same version reinstalled: finishes" "installed. Restart the head unit" "$out"
check "... the backup is stock, not the wrapper" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
run $R/data/hmgapi/uninstall.sh
has "... and the uninstall finishes" "HMG-API uninstalled" "$out"
check "... giving stock back" "$STK" "$(md5sum < $R/system/bin/app_process)"
# (k) the same with an older wrapper on /system: an update, not an unknown stock build; forced the same
for force in 0 1; do
    cp $T/newfw $R/system/bin/app_process; rm -f $R/system/bin/app_process.hmgapi-stock; rm -rf $R/data/hmgapi
    run $P/install.sh $P; w5; cp $T/w5 $R/system/bin/app_process; rm -rf $R/data/hmgapi
    HMGAPI_FORCE=$force run $P/install.sh $P
    has "/data wiped, older wrapper (FORCE=$force): an update" "HMG-API's wrapper is in place" "$out"
    check "... the stock copy kept as stock" "$STK" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
    check "... the backup is stock" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
    check "... the zygote start ends at stock" "$STK" "$(chain)"
done
# (l) a backup an older install took from the wrapper after a wipe: taken again from the stock copy
cp $P/app_process $R/data/hmgapi/stock/system/bin/app_process
run $P/install.sh $P
has "backup that is a wrapper: taken again" "an HMG-API wrapper, not stock" "$out"
check "... now stock" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
# (m) a stock copy that is itself a wrapper: refused even when forced, nothing changed
w5; cp $T/w5 $R/system/bin/app_process.hmgapi-stock
B4=$(tree)
HMGAPI_FORCE=1 run $P/install.sh $P
has "stock copy that is a wrapper: refused even forced" "is itself an HMG-API wrapper, so nothing was changed" "$out"
check "... /system unchanged" "$B4" "$(tree)"
cp $T/newfw $R/system/bin/app_process.hmgapi-stock; run $R/data/hmgapi/uninstall.sh
rm -f $R/system/bin/app_process.real $R/system/bin/app_process.hmgapi-stock; cp $T/newfw $R/system/bin/app_process; rm -rf $R/data/hmgapi; nemesis_boot

# (n) NEMESIS 1.17 first, then a release before #396 installed with FORCE (it refused NEMESIS's
# script as an unknown app_process): it kept the script as its stock copy and backup and wrote its
# wrapper over the script. The stock binary is only in .real. rc.9 took that for an update and
# wrote its wrapper over .real: wrapper -> script -> wrapper, no zygote (#397, a Korean K5)
w5; WRAPPERS="$WRAPPERS $(md5sum < $T/w5 | cut -c1-32)"
unset HMGAPI_FORCE   # HMGAPI_FORCE=1 run (a function) leaves it set after it in bash 3.2's sh (macOS)
forced_old() {  # $1: the stock binary NEMESIS finds
    rm -rf $R/data/hmgapi; rm -f $R/system/bin/app_process.real $R/system/bin/app_process.hmgapi-stock
    cp "$1" $R/system/bin/app_process; nemesis_boot
    mkdir -p $R/data/hmgapi/stock/system/bin $R/data/hmgapi/state
    cp $R/system/bin/app_process $R/data/hmgapi/stock/system/bin/app_process
    cp $R/system/bin/app_process $R/system/bin/app_process.hmgapi-stock
    cp $T/w5 $R/system/bin/app_process
    printf 'version 1.0.2\nfile %s /system/bin/app_process\n' "$(md5sum < $T/w5 | cut -c1-32)" > $R/data/hmgapi/installed.txt
    echo '{"files": {}, "settings": {}}' > $R/data/hmgapi/state/files.json
}
forced_old $T/newfw
check "forced older install over NEMESIS's script: still starts (wrapper, script, stock)" "$STK" "$(chain)"
check "... its stock copy is NEMESIS's script" "$NLPSUM" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
for boot in no yes; do
    forced_old $T/newfw; [ $boot = yes ] && nemesis_boot
    run $P/install.sh $P
    has "... (restart since: $boot) this release sees the script" "is NEMESIS LivePatch's script, not stock (an older release installed with FORCE, #397)" "$out"
    has "... and finishes" "installed. Restart the head unit" "$out"
    check "... not taken for a firmware update (the engine's backups stay)" "" "$(echo "$out" | grep 'a firmware update replaced' || true)"
    check "... the stock copy is stock, from .real" "$STK" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
    check "... the backup is stock" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
    check "... the wrapper is app_process.real" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process.real)"
    check "... the zygote start ends at stock" "$STK" "$(chain)"
    nemesis_boot
    check "... and after NEMESIS's next boot" "$STK" "$(chain)"
    run $R/data/hmgapi/uninstall.sh
    has "... the uninstall finishes" "HMG-API uninstalled" "$out"
    check "... stock in .real, NEMESIS's script at app_process" "$STK|$NLPSUM" "$(md5sum < $R/system/bin/app_process.real)|$(md5sum < $R/system/bin/app_process)"
    nemesis_boot
    check "... and the zygote still starts" "$STK" "$(chain)"
done
# the same on a build stock.txt doesn't know (the Korean K5's): refused unless forced, then from .real
{ printf '\177ELF'; head -c 9000 /dev/urandom; } > $T/kfw; chmod 755 $T/kfw; KST=$(md5sum < $T/kfw)
forced_old $T/kfw; nemesis_boot
B4=$(tree)
run $P/install.sh $P
has "... unknown build in .real: refused" "app_process.real md5 $(echo $KST | cut -c1-32) isn't a known stock build, so nothing was changed" "$out"
check "... /system unchanged" "$B4" "$(tree)"
HMGAPI_FORCE=1 run $P/install.sh $P; unset HMGAPI_FORCE
has "... forced: installs" "installed. Restart the head unit" "$out"
check "... the stock copy and backup are .real's binary, not the script" "$KST|$KST" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)|$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
nemesis_boot
check "... the zygote start ends at that binary" "$KST" "$(chain)"
# NEMESIS uninstalled since the forced install: its uninstall moved app_process.real back to
# app_process (native/livepatch.c lp_wrapper_remove; usb_bundle/uninstall_nemesis.sh), so the stock
# binary is there, .real is gone, and the stock copy and backup are still the script. Refusing this
# left one way out, the older release's own uninstall, which asks for FORCE and then writes the
# script over app_process with NEMESIS gone: no zygote. Installed again from app_process instead
for build in known unknown; do
    if [ $build = known ]; then f=$T/newfw; want=$STK; else f=$T/kfw; want=$KST; fi
    forced_old $f; nemesis_boot; mv $R/system/bin/app_process.real $R/system/bin/app_process
    check "forced older install, NEMESIS uninstalled since ($build build): still starts (stock at app_process)" "$want" "$(chain)"
    if [ $build = unknown ]; then
        B4=$(tree)
        run $P/install.sh $P
        has "... ($build) refused unless forced, naming app_process" "app_process md5 $(echo $want | cut -c1-32) isn't a known stock build, so nothing was changed" "$out"
        check "... ($build) /system unchanged" "$B4" "$(tree)"
        HMGAPI_FORCE=1 run $P/install.sh $P; unset HMGAPI_FORCE
    else
        run $P/install.sh $P
    fi
    has "... ($build) this release sees the script and installs from app_process" "installing again from app_process, the stock binary" "$out"
    has "... ($build) and finishes" "installed. Restart the head unit" "$out"
    check "... ($build) not taken for a firmware update" "" "$(echo "$out" | grep 'a firmware update replaced' || true)"
    check "... ($build) the stock copy and backup are the stock binary" "$want|$want" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)|$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
    check "... ($build) the wrapper is app_process, and no app_process.real was made" "$(md5sum < $P/app_process)|no" "$(md5sum < $R/system/bin/app_process)|$([ -e $R/system/bin/app_process.real ] && echo yes || echo no)"
    check "... ($build) the zygote start ends at stock" "$want" "$(chain)"
    if [ $build = unknown ]; then
        B4=$(tree)
        run $R/data/hmgapi/uninstall.sh
        has "... ($build) the uninstall refuses an unknown backup, asking for the line" "isn't a known stock build, so nothing was changed and HMG-API is still installed. HMG-API only takes app_process builds it has checked, and this one isn't checked yet. Please open an issue at https://github.com/Open-HMG/HMG-API/issues/new/choose with this line, your car and its head unit software version (e.g. 260507), and we'll help you finish the uninstall." "$out"
        check "... ($build) and names no way to force it" "" "$(echo "$out" | grep -i 'HMGAPI_FORCE\|HMGAPI/FORCE' || true)"
        check "... ($build) /system unchanged" "$B4" "$(tree)"
        export HMGAPI_FORCE=1   # the maintainers' override over adb
    fi
    run $R/data/hmgapi/uninstall.sh; unset HMGAPI_FORCE
    has "... ($build) the uninstall finishes" "HMG-API uninstalled" "$out"
    check "... ($build) and gives stock back at app_process" "$want" "$(md5sum < $R/system/bin/app_process)"
done
# what rc.9 left: the wrapper in .real, the stock copy and backup NEMESIS's script. Nothing stock is
# left on the unit: refused even when forced, nothing changed
forced_old $T/kfw; nemesis_boot; cp $P/app_process $R/system/bin/app_process.real
check "rc.9 over a forced older install: no zygote" "nothing: a loop" "$(chain)"
B4=$(tree)
HMGAPI_FORCE=1 run $P/install.sh $P; unset HMGAPI_FORCE
has "... this release refuses, even forced" "is NEMESIS LivePatch's script (an older release installed with FORCE), and app_process.real isn't a stock binary either, so nothing was changed" "$out"
check "... /system unchanged" "$B4" "$(tree)"
# ... and its uninstall doesn't send the owner back to installing: it says no stock app_process is
# left and what that takes (#406 review: the two used to point at each other)
cp $P/uninstall.sh $P/lib.sh $P/stock.txt $R/data/hmgapi/
HMGAPI_FORCE=1 run $R/data/hmgapi/uninstall.sh; unset HMGAPI_FORCE
has "... the uninstall on that unit: refused, even forced, saying nothing stock is left" "no other stock app_process is left on this unit" "$out"
has "... and that installing again can't help, but the stock file or the firmware can" "Installing HMG-API again can't bring the stock file back. It needs the stock app_process of this exact firmware build" "$out"
check "... not the old advice to install again first" "" "$(echo "$out" | grep 'Install this HMG-API release again first' || true)"
check "... /system unchanged" "$B4" "$(tree)"
# an empty app_process.real (a copy NEMESIS didn't finish) is no stock binary either, forced or not:
# forcing copied its zero bytes into the stock copy and the backup, and put the wrapper over it
forced_old $T/kfw; nemesis_boot; : > $R/system/bin/app_process.real
B4=$(tree)
HMGAPI_FORCE=1 run $P/install.sh $P; unset HMGAPI_FORCE
has "empty app_process.real: refused, even forced" "and app_process.real isn't a stock binary either, so nothing was changed" "$out"
check "... /system unchanged" "$B4" "$(tree)"
# an uninstall never puts NEMESIS's script back as stock, even forced (it would run itself in
# .real): the stock bytes come from the stock copy the wrapper runs
script_backup() {
    forced_old $T/newfw; nemesis_boot; cp $P/app_process $R/system/bin/app_process.real; cp $T/newfw $R/system/bin/app_process.hmgapi-stock
    cp $P/uninstall.sh $P/lib.sh $P/stock.txt $R/data/hmgapi/; mkdir -p $R/data/hmgapi/hook
}
for force in 0 1; do
    script_backup
    HMGAPI_FORCE=$force run $R/data/hmgapi/uninstall.sh; unset HMGAPI_FORCE
    has "uninstall with NEMESIS's script as the backup (FORCE=$force): the stock copy goes back, not the script" "the stock bytes come from /system/bin/app_process.hmgapi-stock instead" "$out"
    has "... (FORCE=$force) and it finishes" "HMG-API uninstalled" "$out"
    check "... (FORCE=$force) stock in .real, NEMESIS's script at app_process" "$STK|$NLPSUM" "$(md5sum < $R/system/bin/app_process.real)|$(md5sum < $R/system/bin/app_process)"
    nemesis_boot
    check "... (FORCE=$force) the zygote still starts" "$STK" "$(chain)"
done
script_backup
run $P/install.sh $P
has "... installing again takes the backup again" "app_process backup: NEMESIS LivePatch's script, not stock" "$out"
check "... now stock" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
run $R/data/hmgapi/uninstall.sh
has "... and then the uninstall finishes" "HMG-API uninstalled" "$out"
nemesis_boot
check "... the zygote still starts" "$STK" "$(chain)"

# (o) #406 review: an install that fails or is cut off while copying the backup. The copy went
# straight to the backup's name, so a short file stayed there; the next install kept it ("once"),
# the uninstall refused it as an unknown build and named HMGAPI_FORCE, and the forced uninstall put
# the short file into app_process.real and removed the stock copy: no zygote
forced_old $T/newfw; nemesis_boot
printf '#!/bin/sh\ncase "$2" in */data/hmgapi/stock/system/bin/app_process*) head -c 16 "$1" > "$2"; exit 1 ;; esac\nexec /bin/cp "$@"\n' > $T/bin/cp; chmod +x $T/bin/cp
B4=$(tree)
run $P/install.sh $P
rm -f $T/bin/cp
has "backup copy fails part-way: the install stops" "FAIL: backup /system/bin/app_process" "$out"
check "... /system unchanged" "$B4" "$(tree)"
check "... and no short file is left as the backup" "" "$(ls $R/data/hmgapi/stock/system/bin/)"
# ... the short file itself, as a release before this left it: taken again from what it's a copy of
head -c 16 $T/newfw > $R/data/hmgapi/stock/system/bin/app_process
run $P/install.sh $P
has "a short backup from an install that was cut off: taken again" "app_process backup: not the bytes of /system/bin/app_process.real (a copy that was cut off?): taking it again" "$out"
has "... and the install finishes" "installed. Restart the head unit" "$out"
check "... the backup is the stock binary" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
# ... on an update too (the backup is checked against the stock copy the wrapper runs)
head -c 16 $T/newfw > $R/data/hmgapi/stock/system/bin/app_process
run $P/install.sh $P
has "... on an update: taken again from the stock copy" "app_process backup: not the bytes of /system/bin/app_process.hmgapi-stock (a copy that was cut off?): taking it again" "$out"
check "... the backup is the stock binary" "$STK" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
# ... and the uninstall never puts a short backup back, forced or not: the stock copy goes back
for force in 0 1; do
    head -c 16 $T/newfw > $R/data/hmgapi/stock/system/bin/app_process
    cp -r $R $T/keep
    HMGAPI_FORCE=$force run $R/data/hmgapi/uninstall.sh; unset HMGAPI_FORCE
    has "uninstall with a short backup (FORCE=$force): the stock copy goes back" "isn't the bytes of app_process.hmgapi-stock (a copy that was cut off?): the stock bytes come from /system/bin/app_process.hmgapi-stock instead" "$out"
    has "... (FORCE=$force) and it finishes" "HMG-API uninstalled" "$out"
    check "... (FORCE=$force) stock in .real" "$STK" "$(md5sum < $R/system/bin/app_process.real)"
    nemesis_boot
    check "... (FORCE=$force) the zygote still starts" "$STK" "$(chain)"
    [ $force = 1 ] || { rm -rf $R; mv $T/keep $R; }
done
rm -rf $T/keep
# (p) another tool's script at app_process (not NEMESIS's, no .real): never kept as the stock copy,
# forced or not. It would work until that tool rewrote or removed its script
rm -rf $R/data/hmgapi; rm -f $R/system/bin/app_process.real $R/system/bin/app_process.hmgapi-stock
printf '#!/system/bin/sh\n# some other tool\nexec /system/bin/app_process.orig "$@"\n' > $R/system/bin/app_process
B4=$(tree)
HMGAPI_FORCE=1 run $P/install.sh $P; unset HMGAPI_FORCE
has "another tool's script at app_process: refused, even forced" "isn't a stock binary (an HMG-API wrapper, NEMESIS LivePatch's script, another script or an empty file)" "$out"
check "... /system unchanged" "$B4" "$(tree)"
rm -f $R/system/bin/app_process.real $R/system/bin/app_process.hmgapi-stock; cp $T/newfw $R/system/bin/app_process; rm -rf $R/data/hmgapi; nemesis_boot

# (c) NEMESIS's script with no app_process.real: refused, nothing changed
rm -f $R/system/bin/app_process.real; rm -rf $R/data/hmgapi
B4=$(tree)
run $P/install.sh $P
has "NEMESIS's script without app_process.real refused" "app_process.real, the binary it runs, is missing, so nothing was changed" "$out"
check "... /system unchanged" "$B4" "$(tree)"
cp $T/wrapper.old $P/app_process
exit $fail
