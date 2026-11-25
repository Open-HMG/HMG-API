#!/bin/sh
# Off-car test of the engine with NEMESIS LivePatch (NemesisFiles.java, NEMESIS 1.17.1 or newer): the
# mods' file changes become HMG-API's NEMESIS package ("hmgapi": [file.N], [prop.N], livepatch-first=1),
# handed to NEMESIS's queue, and /system isn't written. A fake NEMESIS whose "stock" is /system (as the
# real one's is: it reads under its own mounts), a queue that keeps what it's given, and a step that
# installs the queued package as NEMESIS would list it. Needs a JDK, python3 with bsdiff4, out/lpatch-host.
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
[ -x out/lpatch-host ] || { echo "run ./build.sh lpatch first"; exit 1; }
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
md5() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo absent; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 HMGAPI_ONEBIN=cvev_us \
        HMGAPI_LPATCH=$REPO/out/lpatch-host java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
V=$(sed -n 's/.*HMGAPI_VERSION = "\(.*\)";/\1/p' core/runtime/java/hmgapi/engine/Engine.java)
PV=$(tools/nemesis-version.py "$V")
LP=$R/data/hmgapi/livepatch/files

# ---- a fake head unit, HMG-API installed (its NEMESIS scripts as install.sh keeps them)
mkdir -p $R/system/lib $R/system/app $R/system/xbin $R/system/onebin/cvev_us $R/system/etc/permissions \
    $R/data/hmgapi/mods $R/data/hmgapi/settings $R/data/nemesis/apps $R/data/nemesis/queue
printf 'ro.build.product=CVEV.USA.0000.V015\npersist.ium.support=none\npersist.drop=1\nro.other=1\n' > $R/system/build.prop
printf '%s\n' "-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml" "-/system/etc/permissions/x.xml" > $R/system/onebin/cvev_us/featurexml.cfg
N=$R/data/hmgapi/nemesis
mkdir -p $N/mod $N/bin
cp -r install/nemesis/scripts $N/scripts; cp -r install/nemesis/mod/scripts $N/mod/scripts
cp install/nemesis/bin/open.sh install/nemesis/bin/start.sh $N/bin/; cp install/nemesis/conflicts.txt $N/conflicts.txt
python3 - "$T" "$R" <<'PY'
import random, sys, zipfile
T, R = sys.argv[1], sys.argv[2]
random.seed(7)
stock = bytearray(random.getrandbits(8) for _ in range(65536))
open(R + "/system/lib/libtest.so", "wb").write(stock)
a = bytearray(stock); a[0x1000:0x1004] = b"\x01\x02\x03\x04"; open(T + "/lib.a", "wb").write(a)
with zipfile.ZipFile(R + "/system/app/Test.apk", "w") as z:
    z.writestr("AndroidManifest.xml", b"manifest"); z.writestr("resources.arsc", b"res")
open(R + "/system/app/Test.odex", "wb").write(bytes(random.getrandbits(8) for _ in range(8192)))
open(T + "/classes.dex", "wb").write(b"dex\n035\0" + bytes(random.getrandbits(8) for _ in range(4096)))
PY
STOCK_PROP=$(md5 $R/system/build.prop); STOCK_CFG=$(md5 $R/system/onebin/cvev_us/featurexml.cfg)
STOCK_LIB=$(md5 $R/system/lib/libtest.so); STOCK_APK=$(md5 $R/system/app/Test.apk); STOCK_ODEX=$(md5 $R/system/app/Test.odex)

# NEMESIS 1.17.1: version, livepatch stock (/system: nothing here writes it), queue add, remount
cat > $T/nemesis <<EOF
#!/bin/sh
echo "\$*" >> $T/calls
case \$1 in
    version) cat $T/nemesis.version ;;
    livepatch) [ "\$2" = stock ] || exit 1; [ -f "$R\$3" ] || exit 2; cp "$R\$3" "\$4" ;;
    queue) [ "\$2" = add ] && [ "\$3" = --move ] || exit 1
        [ -z "\$HMGAPI_NEMESIS_SYNC" ] || { echo "HMGAPI_NEMESIS_SYNC passed to the queue"; exit 1; }
        n=\$(ls $T/q.*.zip 2>/dev/null | wc -l | tr -d ' '); mv "\$4" $T/q.\$n.zip; echo "queued \$n hmgapi" ;;
    remount) shift; echo "\$*" >> $T/remounts ;;
    install|uninstall) exit 0 ;;
    *) exit 1 ;;
esac
EOF
chmod 755 $T/nemesis
echo 1.17.1 > $T/nemesis.version
nemesis_on() { cp $T/nemesis $R/system/xbin/nemesis; }
nemesis_off() { rm -f $R/system/xbin/nemesis; }
queued() { ls $T/q.*.zip 2>/dev/null | wc -l | tr -d ' '; }
lastq() { ls $T/q.*.zip | sort -t. -k2 -n | tail -1; }
qdata() { unzip -p "$(lastq)" NEMESIS.data; }
qfile() { unzip -p "$(lastq)" "files$1" | md5sum | cut -d' ' -f1; }
# NEMESIS ran the queued job: it lists HMG-API's package at that version
nemesis_ran() { mkdir -p $R/data/nemesis/apps/hmgapi; qdata > $R/data/nemesis/apps/hmgapi/NEMESIS.data; }

mod() {   # mod <id> <version> <patches.json changes> [mod.json extra]
    d=$R/data/hmgapi/mods/$1; mkdir -p $d/patches/ev6
    printf '{"id": "%s", "version": "%s"%s}\n' "$1" "$2" "$4" > $d/mod.json
    printf '{"cars": {"CVEV.USA.": "ev6"}, "changes": [%s]}\n' "$3" > $d/patches.json
    printf 'libtest.so %s\nTest.odex %s\nTest.apk %s\n' $STOCK_LIB $STOCK_ODEX $STOCK_APK > $d/patches/ev6/stock
}
python3 tools/mkrecipe.py ra /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.a > $T/ra.recipe
python3 tools/mkdelta.py $R/system/app/Test.odex $T/classes.dex $T/Test.lpd > /dev/null
echo '<permissions/>' > $T/a.xml
mod moda 1.0.0 '
  {"type": "props", "path": "/system/build.prop", "set": {"persist.ium.support": "carplay"}, "stock": "any"},
  {"type": "lines", "path": "/system/onebin/{onebin}/featurexml.cfg", "comment": ["-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml"], "stock": "any"},
  {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "ra.recipe"},
  {"type": "add", "path": "/system/etc/permissions/a.xml", "src": "a.xml"},
  {"type": "app", "path": "/system/app/Test.apk", "delta": "Test.lpd"},
  {"type": "props", "path": "/system/build.prop", "set": {"persist.wifi.state": "3"}, "stock": "any", "part": "extra"}' \
  ', "parts": [{"id": "main"}, {"id": "extra", "when": "settings.extra"}]'
cp $T/ra.recipe $T/a.xml $T/Test.lpd $R/data/hmgapi/mods/moda/
printf '{"settings": [{"key": "extra", "type": "toggle", "default": false}]}\n' > $R/data/hmgapi/mods/moda/settings.json
stock_system() {   # /system untouched
    check "$1: build.prop stock" "$STOCK_PROP" "$(md5 $R/system/build.prop)"
    check "$1: featurexml.cfg stock" "$STOCK_CFG" "$(md5 $R/system/onebin/cvev_us/featurexml.cfg)"
    check "$1: library stock" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
    check "$1: apk stock" "$STOCK_APK" "$(md5 $R/system/app/Test.apk)"
    check "$1: no added file in /system" absent "$(md5 $R/system/etc/permissions/a.xml)"
}

# ---- NEMESIS too old: nothing changes (HMG-API writes /system as before)
nemesis_on; echo 1.17 > $T/nemesis.version
eng plan
check "NEMESIS 1.17: plan exit" 0 $rc
hasnt "NEMESIS 1.17: no stock copies asked for" "livepatch stock" "$(cat $T/calls 2>/dev/null)"
echo 1.17.1 > $T/nemesis.version
: > $T/calls

# ---- plan and apply through LivePatch
eng plan
check "plan exit" 0 $rc
has "plan lists the library" "change   /system/lib/libtest.so [moda]" "$out"
has "plan asked NEMESIS for the stock library" "livepatch stock /system/lib/libtest.so" "$(cat $T/calls)"
check "plan queued nothing" 0 "$(queued)"
eng apply
check "apply exit" 0 $rc
has "apply: in NEMESIS's queue" "is in NEMESIS's queue, with the mods' file changes (4 file(s))" "$out"   # 5 on the unit: dexopt makes the odex
has "apply: says the files go to LivePatch" "LivePatch /system/lib/libtest.so" "$out"
hasnt "apply: never says it wrote /system" "wrote    /system/lib" "$out"
stock_system "apply"
check "no remount: /system not written" absent "$(md5 $T/remounts)"
check "queued once" 1 "$(queued)"
D=$(qdata)
has "package id hmgapi" "id=hmgapi" "$D"
has "livepatch-first" "livepatch-first=1" "$D"
has "version: HMG-API's, then generation 1" "package-version=$PV.1" "$D"
has "featurexml.cfg is a [file.N]" "target=/system/onebin/cvev_us/featurexml.cfg" "$D"
has "the added file is a [file.N]" "target=/system/etc/permissions/a.xml" "$D"
has "the rebuilt apk is a [file.N]" "target=/system/app/Test.apk" "$D"
hasnt "build.prop isn't a [file.N]" "target=/system/build.prop" "$D"
has "build.prop's key is a [prop.N]" "key=persist.ium.support
value=carplay" "$D"
check "the library in the package is HMG-API's result" "$(md5 $T/lib.a)" "$(qfile /system/lib/libtest.so)"
check "... and HMG-API's view of it" "$(md5 $T/lib.a)" "$(md5 $LP/system/lib/libtest.so)"
check "the app in the package has classes.dex" "$(md5 $T/classes.dex)" "$(unzip -p "$(lastq)" files/system/app/Test.apk | python3 -c 'import sys,io,zipfile,hashlib; print(hashlib.md5(zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())).read("classes.dex")).hexdigest())')"
eng status
check "status exit" 0 $rc
has "status: owned files ok" "ok       /system/lib/libtest.so" "$out"
has "status says where the files are" "NEMESIS LivePatch outputs" "$out"

# ---- NEMESIS ran it; nothing to do, nothing queued again
nemesis_ran
eng apply
has "second apply: nothing to do" "nothing to do" "$out"
check "second apply queued nothing" 1 "$(queued)"
eng nemesis-sync
check "sync: HMG-API's package as NEMESIS has it, nothing queued" 1 "$(queued)"

# ---- a part on: one more build.prop key, the next generation
echo '{"extra": true}' > $R/data/hmgapi/settings/moda.json
eng apply
check "part on: queued again" 2 "$(queued)"
D=$(qdata)
has "part on: generation 2" "package-version=$PV.2" "$D"
has "part on: the second key" "key=persist.wifi.state" "$D"
check "part on: build.prop still stock" "$STOCK_PROP" "$(md5 $R/system/build.prop)"
nemesis_ran

# ---- a key removed: a [prop.N] can't, so HMG-API writes build.prop itself (NEMESIS's remount)
mod modc 1.0.0 '{"type": "props", "path": "/system/build.prop", "remove": ["persist.drop"], "stock": "any"}'
eng apply
check "remove: apply exit" 0 $rc
has "remove: says it writes build.prop" "HMG-API writes /system/build.prop itself (a mod removes persist.drop)" "$out"
hasnt "remove: /system's build.prop has no removed key" "persist.drop" "$(cat $R/system/build.prop)"
has "remove: and the set key" "persist.ium.support=carplay" "$(cat $R/system/build.prop)"
check "remove: through nemesis remount rw --bypass-livepatch then ro" "rw --bypass-livepatch ro" "$(tr '\n' ' ' < $T/remounts | sed 's/ $//')"
hasnt "remove: no [prop.N] now" "[prop." "$(qdata)"
has "remove: the other files still go to NEMESIS" "target=/system/lib/libtest.so" "$(qdata)"
nemesis_ran
rm -rf $R/data/hmgapi/mods/modc $T/remounts
eng apply
check "remove gone: build.prop stock again" "$STOCK_PROP" "$(md5 $R/system/build.prop)"
has "remove gone: [prop.N] again" "key=persist.ium.support" "$(qdata)"
nemesis_ran

# ---- NEMESIS couldn't do a job: said in status and plan; Apply queues it again
eng apply   # nothing to do
n=$(queued)
printf 'action=install\nid=hmgapi\nstate=failed\nrc=1\n' > $R/data/nemesis/queue/00000009.job
echo "libtest.so: Some package already replaces it" > $R/data/nemesis/queue/00000009.out
f=$R/data/nemesis/apps/hmgapi/NEMESIS.data; sed 's/^package-version=.*/package-version=1.0/' $f > $f.new && mv $f.new $f   # no sed -i: BSD sed's differs
eng status
check "failed job: status exit 4" 4 $rc
has "failed job: status says why" "NEMESIS couldn't apply HMG-API's last file changes: libtest.so: Some package already replaces it" "$out"
eng apply
has "failed job: apply says why" "Some package already replaces it" "$out"
check "failed job: apply with nothing to do queues it again" $((n + 1)) "$(queued)"
nemesis_ran
rm -f $R/data/nemesis/queue/00000009.*
eng status
check "NEMESIS has it: status exit 0" 0 $rc

# ---- restore (uninstall.sh): HMG-API's view stock, nothing queued (its unlisting takes the files)
n=$(queued)
eng restore
check "restore exit" 0 $rc
check "restore queued nothing" $n "$(queued)"
stock_system "restore"
check "restore: HMG-API owns nothing" "{}" "$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))["files"]))' $R/data/hmgapi/state/files.json)"

# ---- NEMESIS gone: HMG-API writes /system again at the next apply
nemesis_off
eng apply
check "NEMESIS gone: apply exit" 0 $rc
has "NEMESIS gone: says so" "NEMESIS LivePatch isn't there any more" "$out"
check "NEMESIS gone: library written to /system" "$(md5 $T/lib.a)" "$(md5 $R/system/lib/libtest.so)"
check "NEMESIS gone: view dropped" absent "$(md5 $LP/system/lib/libtest.so)"

# ---- a trial from before LivePatch, NEMESIS 1.17.1 since: recover rolls /system back, not the view
echo '<permissions/>' > $T/d.xml
mod modd 1.0.0 '{"type": "add", "path": "/system/etc/permissions/d.xml", "src": "d.xml"}'
cp $T/d.xml $R/data/hmgapi/mods/modd/
eng apply --trial
check "old trial: the file on /system" "$(md5 $T/d.xml)" "$(md5 $R/system/etc/permissions/d.xml)"
nemesis_on
n=$(queued)
eng recover   # on the unit this also remounts /system (Engine.remount; off-car it does nothing)
check "old trial: recover exit" 0 $rc
check "old trial: /system rolled back" absent "$(md5 $R/system/etc/permissions/d.xml)"
check "old trial: nothing queued" $n "$(queued)"
nemesis_off
rm -rf $R/data/hmgapi/mods/modd

# ---- migration: what HMG-API wrote to /system (rc.9) goes to NEMESIS, /system stock again
nemesis_on
n=$(queued); : > $T/calls
eng plan
check "migration: plan exit" 0 $rc
has "migration: plan sees its files as they are" "same     /system/lib/libtest.so" "$out"
check "migration: plan writes nothing" "$(md5 $T/lib.a)" "$(md5 $R/system/lib/libtest.so)"
check "migration: plan queues nothing" $n "$(queued)"
rm -f $T/remounts
eng apply
check "migration: apply exit" 0 $rc
has "migration: says what moved" "file(s) HMG-API had written to /system are stock there again" "$out"
# /system/lib can be a NEMESIS farm (read-only): only --bypass-livepatch lets the stock bytes land
check "migration: through nemesis remount rw --bypass-livepatch then ro" "rw --bypass-livepatch ro" "$(tr '\n' ' ' < $T/remounts | sed 's/ $//')"
stock_system "migration"
check "migration: queued" $((n + 1)) "$(queued)"
check "migration: the library in the package is HMG-API's result" "$(md5 $T/lib.a)" "$(qfile /system/lib/libtest.so)"
has "migration: build.prop key a [prop.N]" "key=persist.ium.support" "$(qdata)"
eng status
check "migration: status exit 0" 0 $rc

# ---- engine-test.sh's cases without NEMESIS stay as they were (that test has no /system/xbin/nemesis)
exit $fail
