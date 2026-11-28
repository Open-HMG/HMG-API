#!/bin/sh
# Off-car test of the engine's journal and backups: recover puts back settings, live props
# and binds as well as files (a trial, an apply cut off part-way); stock backups are taken
# checked and used only when they're still the stock file; room is checked before an
# apply; a damaged journal or saved copy is set aside instead of failing every start; the
# Dalvik cache entries touched are only the app's own.
# Needs a JDK, python3 with bsdiff4, and out/lpatch-host (./build.sh lpatch).
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
[ -x out/lpatch-host ] || { echo "run ./build.sh lpatch first"; exit 1; }
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
D=$R/data/hmgapi
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
md5() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo absent; }
yes_no() { if [ -e "$1" ]; then echo yes; else echo no; fi; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 HMGAPI_ONEBIN=cvev_us \
        HMGAPI_LPATCH=$REPO/out/lpatch-host java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
rec() { python3 -c 'import json,sys; s=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$@"; }

# ---- a fake head unit: a library, an app in /system/app and one in /system/priv-app
mkdir -p $R/system/lib $R/system/app $R/system/priv-app $R/system/etc/permissions $D/mods $D/settings $R/data/dalvik-cache
printf 'ro.build.product=CVEV.USA.0000.V015\npersist.ium.support=none\n' > $R/system/build.prop
python3 - "$T" "$R" <<'PY'
import sys, random, zipfile
T, R = sys.argv[1], sys.argv[2]
random.seed(5)
stock = bytearray(random.getrandbits(8) for _ in range(65536))
open(R + "/system/lib/libtest.so", "wb").write(stock)
a = bytearray(stock); a[0x1000:0x1004] = b"\x01\x02\x03\x04"; open(T + "/lib.a", "wb").write(a)
b = bytearray(stock); b[0x3000:0x3004] = b"\x05\x06\x07\x08"; open(T + "/lib.b", "wb").write(b)
for d, n in (("app", "Test"), ("priv-app", "Priv")):
    with zipfile.ZipFile(R + "/system/%s/%s.apk" % (d, n), "w") as z:
        z.writestr("AndroidManifest.xml", b"manifest " + n.encode())
odex = bytes(random.getrandbits(8) for _ in range(8192))
open(R + "/system/app/Test.odex", "wb").write(odex)
open(R + "/system/priv-app/Priv.odex", "wb").write(odex)   # the same bytes, so one delta fits both
open(T + "/classes.dex", "wb").write(b"dex\n035\0" + bytes(random.getrandbits(8) for _ in range(4096)))
PY
STOCK_LIB=$(md5 $R/system/lib/libtest.so); STOCK_APK=$(md5 $R/system/app/Test.apk); STOCK_ODEX=$(md5 $R/system/app/Test.odex)
STOCK_PRIV=$(md5 $R/system/priv-app/Priv.apk); cp $R/system/lib/libtest.so $T/lib.stock
mkdir -p $T/m
python3 tools/mkrecipe.py ra /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.a > $T/m/ra.recipe
python3 tools/mkrecipe.py rb /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.b > $T/m/rb.recipe
python3 tools/mkdelta.py $R/system/app/Test.odex $T/classes.dex $T/m/Test.lpd > /dev/null
mod() {   # mod <id> <patches.json changes>
    d=$D/mods/$1; rm -rf $d; mkdir -p $d/patches/ev6 $d/files
    printf '{"id": "%s", "version": "1.0.0"}\n' "$1" > $d/mod.json
    printf '{"cars": {"CVEV.USA.": "ev6"}, "changes": [%s]}\n' "$2" > $d/patches.json
    printf 'libtest.so %s\nTest.apk %s\nTest.odex %s\nPriv.apk %s\nPriv.odex %s\n' $STOCK_LIB $STOCK_APK $STOCK_ODEX $STOCK_PRIV $STOCK_ODEX > $d/patches/ev6/stock
    cp $T/m/* $d/; echo bin > $d/files/x
}
APPS='{"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "ra.recipe"},
  {"type": "app", "path": "/system/app/Test.apk", "delta": "Test.lpd"},
  {"type": "app", "path": "/system/priv-app/Priv.apk", "delta": "Test.lpd"}'

# ---- Dalvik cache: only the app's own entry, by its real name (priv-app too)
echo old > $R/data/dalvik-cache/system@app@Test.apk@classes.dex
echo other > $R/data/dalvik-cache/system@app@MyTest.apk@classes.dex
echo old > $R/data/dalvik-cache/system@priv-app@Priv.apk@classes.dex
mod base "$APPS"
eng apply
check "apply: two apps and a library" 0 $rc
check "the app's own cache entry dropped" absent "$(md5 $R/data/dalvik-cache/system@app@Test.apk@classes.dex)"
check "... the priv-app's by its own name" absent "$(md5 $R/data/dalvik-cache/system@priv-app@Priv.apk@classes.dex)"
check "... an app whose name ends the same way untouched" other "$(cat $R/data/dalvik-cache/system@app@MyTest.apk@classes.dex)"
check "odex backup taken" "$STOCK_ODEX" "$(md5 $D/stock/system/app/Test.odex)"
check "... its md5 recorded" "$STOCK_ODEX" "$(rec $D/state/files.json 's["files"]["/system/app/Test.apk"].get("odex")')"
check "no half-written backup left" "" "$(find $D/stock -name '*.hmgapi-new')"
eng restore
check "restore" 0 $rc
check "... the stock odex back" "$STOCK_ODEX" "$(md5 $R/system/app/Test.odex)"
check "... its cache entry filled from it" "$STOCK_ODEX" "$(md5 $R/data/dalvik-cache/system@app@Test.apk@classes.dex)"
check "... the priv-app's entry under system@priv-app@" "$STOCK_ODEX" "$(md5 $R/data/dalvik-cache/system@priv-app@Priv.apk@classes.dex)"
check "... no system@app@ entry made for the priv-app" absent "$(md5 $R/data/dalvik-cache/system@app@Priv.apk@classes.dex)"
check "... the other app's entry still untouched" other "$(cat $R/data/dalvik-cache/system@app@MyTest.apk@classes.dex)"

# ---- a trial's settings, live props and binds: undone by recover with its files
printf 'global/foo=1\n' > $R/settings.txt
mod base "$APPS"
eng apply
BEFORE_LIB=$(md5 $R/system/lib/libtest.so)
mod tx '{"type": "prop", "key": "persist.test.x", "value": "1"},
  {"type": "setting", "namespace": "global", "key": "foo", "value": "2"},
  {"type": "bind", "src": "files/x", "target": "/sbin/adbd", "service": "adbd"},
  {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rb.recipe"}'
rm -f $R/actions.log
eng apply --trial
check "trial apply" 0 $rc
has "... the prop set" "prop/persist.test.x=1" "$(cat $R/settings.txt)"
has "... the setting set" "global/foo=2" "$(cat $R/settings.txt)"
check "... the bind listed" "/data/hmgapi/mods/tx/files/x /sbin/adbd adbd" "$(cat $D/state/binds.txt)"
eng status
has "status: a trial in place" "trial apply in place" "$out"
rm -f $R/actions.log
eng recover
check "recover" 0 $rc
has "... says the trial is undone" "trial undone" "$out"
check "... the library back" "$BEFORE_LIB" "$(md5 $R/system/lib/libtest.so)"
hasnt "... the trial's prop unset again" "persist.test.x" "$(cat $R/settings.txt)"
has "... the setting back to its old value" "global/foo=1" "$(cat $R/settings.txt)"
check "... binds as before the trial" "" "$(cat $D/state/binds.txt 2>/dev/null)"
has "... hmgapid told to undo the bind" "binds" "$(cat $R/actions.log 2>/dev/null)"
has "... and said so" "rolled back setting prop/persist.test.x = (unset)" "$out"
eng apply
check "the next apply" 0 $rc
check "... records the prop's stock as unset (not the trial's 1)" None "$(rec $D/state/files.json 's["settings"]["prop/persist.test.x"]["stock"]')"
check "... and the setting's as 1" 1 "$(rec $D/state/files.json 's["settings"]["global/foo"]["stock"]')"
rm -r $D/mods/tx
eng apply
hasnt "the mod off: its prop unset" "persist.test.x" "$(cat $R/settings.txt)"
has "... the setting stock" "global/foo=1" "$(cat $R/settings.txt)"

# ---- an apply cut off after its settings, before its state: recover puts them back
mod cut '{"type": "prop", "key": "persist.cut.y", "value": "1"}, {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rb.recipe"}'
BEFORE_LIB=$(md5 $R/system/lib/libtest.so); BEFORE_STATE=$(md5 $D/state/files.json)
export HMGAPI_TEST_CUT=settings; eng apply; unset HMGAPI_TEST_CUT
check "cut off (power lost)" 137 $rc
has "... the prop was set" "prop/persist.cut.y=1" "$(cat $R/settings.txt)"
check "... the journal left" yes "$(yes_no $D/state/journal.json)"
eng status
has "status: an apply didn't finish" "an apply didn't finish" "$out"
eng apply
check "another apply waits for recover" 1 $rc
eng recover
check "recover" 0 $rc
has "... an unfinished apply, not a trial" "unfinished apply rolled back" "$out"
hasnt "... the prop unset" "persist.cut.y" "$(cat $R/settings.txt)"
check "... the library back" "$BEFORE_LIB" "$(md5 $R/system/lib/libtest.so)"
check "... the state as before" "$BEFORE_STATE" "$(md5 $D/state/files.json)"
mod cut '{"type": "bind", "src": "files/x", "target": "/sbin/adbd", "service": "adbd"}'
export HMGAPI_TEST_CUT=binds; eng apply; unset HMGAPI_TEST_CUT
check "cut off after the binds" 137 $rc
eng recover
check "... binds as before" "" "$(cat $D/state/binds.txt 2>/dev/null)"
rm -r $D/mods/cut

# ---- room: checked before anything is written
mod rb '{"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rb.recipe"}'
BEFORE_LIB=$(md5 $R/system/lib/libtest.so)
export HMGAPI_TEST_FREE="system=1000"; eng apply; unset HMGAPI_TEST_FREE
check "/system full: refused" 3 $rc
has "... saying how much it needs" "not enough room on /system: this apply needs about" "$out"
check "... nothing written" "$BEFORE_LIB" "$(md5 $R/system/lib/libtest.so)"
check "... no journal" no "$(yes_no $D/state/journal.json)"
export HMGAPI_TEST_FREE="data=1000"; eng apply; unset HMGAPI_TEST_FREE
check "/data full: refused" 3 $rc
has "... saying so" "not enough room on /data" "$out"
# a /system that filled up mid-apply: recover makes room (a cut-off copy, then the file itself)
eng apply --trial
check "a trial" 0 $rc
echo half > $R/system/lib/libtest.so.hmgapi-new
export HMGAPI_TEST_FREE="system=0"; eng recover; unset HMGAPI_TEST_FREE
check "recover with /system full" 0 $rc
check "... the cut-off copy beside the file removed" absent "$(md5 $R/system/lib/libtest.so.hmgapi-new)"
check "... the file put back" "$BEFORE_LIB" "$(md5 $R/system/lib/libtest.so)"
has "... saying why it went first" "/system is short of room" "$out"
check "... no journal left" no "$(yes_no $D/state/journal.json)"
rm -r $D/mods/rb

# ---- a damaged journal, a saved copy that's gone: set aside, not failing at every start
echo '{"trial": true, "entr' > $D/state/journal.json
eng status
has "status: the journal is damaged" "its journal (state/journal.json) is damaged" "$out"
eng keep
has "keep: says recover sets it aside" "damaged; \`engine recover\` sets it aside" "$out"
eng restore
check "restore waits for recover" 1 $rc
eng recover
check "recover with a damaged journal" 0 $rc
has "... set aside" "set aside as state/journal.bad.json" "$out"
hasnt "... claims nothing rolled back (the boot step restarts on that)" "rolled back" "$out"
check "... the journal gone" no "$(yes_no $D/state/journal.json)"
check "... kept to look at" yes "$(yes_no $D/state/journal.bad.json)"
eng apply
check "apply works again" 0 $rc
mod tx '{"type": "prop", "key": "persist.test.z", "value": "1"}, {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rb.recipe"}'
eng apply --trial
rm $D/state/prev/_system_lib_libtest.so
eng recover
check "recover with a saved copy gone" 0 $rc
has "... that file named" "can't put back /system/lib/libtest.so: its saved copy is gone" "$out"
hasnt "... the rest still put back (the prop)" "persist.test.z" "$(cat $R/settings.txt)"
check "... the journal set aside, not tried again at every start" no "$(yes_no $D/state/journal.json)"
eng recover
has "... a second recover has nothing to do" "nothing to recover" "$out"
eng apply
has "... the next apply says the file isn't HMG-API's result" "/system/lib/libtest.so: changed outside HMG-API" "$out"
eng apply --force
check "... --force builds it again" 0 $rc
rm -r $D/mods/tx
eng apply --trial
printf 'x' >> "$(ls $D/state/prev/* | head -n 1)"
eng recover
has "a saved copy that isn't what was saved: not written back" "its saved copy is damaged" "$out"
eng apply --force >/dev/null

# ---- backups: checked against the recorded md5 before every use
eng restore
check "restore to stock" 0 $rc
check "... library stock" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
mod base "$APPS"
eng apply
check "apply again" 0 $rc
head -c 4000 $T/classes.dex > $D/stock/system/app/Test.odex   # a cut-off odex backup
APK_NOW=$(md5 $R/system/app/Test.apk)
eng restore
check "a damaged odex backup: restore refused" 3 $rc
has "... saying which and what it should be" "HMG-API's stock backup /data/hmgapi/stock/system/app/Test.odex is damaged" "$out"
check "... the app left as it is (not a stock apk with a short odex)" "$APK_NOW" "$(md5 $R/system/app/Test.apk)"
# the priv-app's odex backup has the same stock bytes as Test.odex's
mkdir -p $R/data/nemesis/backup/pkg; cp $D/stock/system/priv-app/Priv.odex $R/data/nemesis/backup/pkg/Test.odex
eng restore
check "... a NEMESIS copy with the recorded md5 stands in" 0 $rc
has "... noted" "using the stock copy NEMESIS kept (pkg)" "$out"
check "... the stock odex back" "$STOCK_ODEX" "$(md5 $R/system/app/Test.odex)"
rm -r $R/data/nemesis
eng apply
head -c 30000 $R/system/lib/libtest.so > $D/stock/system/lib/libtest.so   # a cut-off backup of the library
mod rb '{"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rb.recipe"}'
eng plan
check "a damaged backup: no rebuild from it" 3 $rc
has "... saying so" "HMG-API's stock backup /data/hmgapi/stock/system/lib/libtest.so is damaged" "$out"
mkdir -p $R/data/nemesis/backup/pkg; cp $T/lib.stock $R/data/nemesis/backup/pkg/libtest.so
eng apply
check "... rebuilt from a NEMESIS copy with the recorded md5" 0 $rc
check "... the backup taken again, whole" "$STOCK_LIB" "$(md5 $D/stock/system/lib/libtest.so)"
rm -r $R/data/nemesis $D/mods/rb
eng apply
# an app's rebuild with a damaged odex backup: a NEMESIS copy that fits is used, and backed up again
mkdir -p $R/data/nemesis/backup/pkg; cp $D/stock/system/app/Test.odex $R/data/nemesis/backup/pkg/Test.odex
printf 'x' >> $D/stock/system/app/Test.odex
rm $R/system/app/Test.apk   # so --force writes the app again
eng apply --force
check "app rebuilt with its odex backup damaged" 0 $rc
check "... the odex backup is stock again" "$STOCK_ODEX" "$(md5 $D/stock/system/app/Test.odex)"
rm -r $R/data/nemesis
# a record from before the odex md5 was kept: a cut-short Dalvik odex is still caught by its header
python3 - $D/state/files.json $D/stock/system/app/Test.odex <<'PY'
import json, struct, sys
s = json.load(open(sys.argv[1])); del s["files"]["/system/app/Test.apk"]["odex"]; json.dump(s, open(sys.argv[1], "w"))
open(sys.argv[2], "wb").write(b"dey\n036\0" + struct.pack("<8I", 40, 1000, 1040, 10, 1050, 90000, 0, 0) + b"\0" * 1000)
PY
eng restore
check "an older record, odex shorter than its header says: refused" 3 $rc
has "... saying so" "is cut short" "$out"

exit $fail
