#!/bin/sh
# Off-car test of the patch engine (hmgapi.engine) on a desktop JVM, against a
# fake head unit in a temp folder: real recipes/deltas (tools/mkrecipe.py,
# mkdelta.py) applied by lpatch-host. Needs a JDK, python3 with bsdiff4, and
# out/lpatch-host (./build.sh lpatch).
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
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
md5() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo absent; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 HMGAPI_ONEBIN=cvev_us \
        HMGAPI_LPATCH=$REPO/out/lpatch-host java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }

# ---- a fake head unit
mkdir -p $R/system/lib $R/system/app $R/system/onebin/cvev_us $R/system/etc/permissions $R/data/hmgapi/mods $R/data/hmgapi/settings
printf 'ro.build.product=CVEV.USA.0000.V015\npersist.ium.support=none\nro.other=1\n' > $R/system/build.prop
printf '%s\n' "-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml" "-/system/etc/permissions/x.xml" > $R/system/onebin/cvev_us/featurexml.cfg
python3 - "$T" "$R" <<'PY'
import os, sys, random, zipfile
T, R = sys.argv[1], sys.argv[2]
random.seed(7)
stock = bytearray(random.getrandbits(8) for _ in range(65536))
open(R + "/system/lib/libtest.so", "wb").write(stock)
a = bytearray(stock); a[0x1000:0x1004] = b"\x01\x02\x03\x04"; open(T + "/lib.a", "wb").write(a)
b = bytearray(stock); b[0x3000:0x3004] = b"\x05\x06\x07\x08"; open(T + "/lib.b", "wb").write(b)
c = bytearray(stock); c[0x1002:0x1006] = b"\x09\x09\x09\x09"; open(T + "/lib.c", "wb").write(c)
ab = bytearray(a); ab[0x3000:0x3004] = b"\x05\x06\x07\x08"; open(T + "/lib.ab", "wb").write(ab)
with zipfile.ZipFile(R + "/system/app/Test.apk", "w") as z:
    z.writestr("AndroidManifest.xml", b"manifest"); z.writestr("resources.arsc", b"res")
open(R + "/system/app/Test.odex", "wb").write(bytes(random.getrandbits(8) for _ in range(8192)))
open(T + "/classes.dex", "wb").write(b"dex\n035\0" + bytes(random.getrandbits(8) for _ in range(4096)))
PY
STOCK_PROP=$(md5 $R/system/build.prop); STOCK_CFG=$(md5 $R/system/onebin/cvev_us/featurexml.cfg)
STOCK_LIB=$(md5 $R/system/lib/libtest.so); STOCK_APK=$(md5 $R/system/app/Test.apk); STOCK_ODEX=$(md5 $R/system/app/Test.odex)

mod() {   # mod <id> <version> <mod.json extra> <patches.json changes>
    d=$R/data/hmgapi/mods/$1; mkdir -p $d/patches/ev6
    printf '{"id": "%s", "version": "%s"%s}\n' "$1" "$2" "$3" > $d/mod.json
    printf '{"cars": {"CVEV.USA.": "ev6"}, "changes": [%s]}\n' "$4" > $d/patches.json
    printf 'libtest.so %s\nTest.odex %s\nTest.apk %s\n' $STOCK_LIB $STOCK_ODEX $STOCK_APK > $d/patches/ev6/stock
}
mkdir -p $T/m
python3 tools/mkrecipe.py ra /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.a > $T/m/ra.recipe
python3 tools/mkrecipe.py rb /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.b > $T/m/rb.recipe
python3 tools/mkrecipe.py rc /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.c > $T/m/rc.recipe
python3 tools/mkdelta.py $R/system/app/Test.odex $T/classes.dex $T/m/Test.lpd > /dev/null
echo '<permissions/>' > $T/m/a.xml

mod moda 1.0.0 ', "parts": [{"id": "main"}, {"id": "extra", "when": "settings.extra"}]' '
  {"type": "props", "path": "/system/build.prop", "set": {"persist.ium.support": "carplay"}, "stock": "any"},
  {"type": "lines", "path": "/system/onebin/{onebin}/featurexml.cfg", "comment": ["-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml"], "stock": "any"},
  {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "ra.recipe"},
  {"type": "add", "path": "/system/etc/permissions/a.xml", "src": "a.xml"},
  {"type": "app", "path": "/system/app/Test.apk", "delta": "Test.lpd"},
  {"type": "setting", "namespace": "global", "key": "wifi_on", "value": "1"},
  {"type": "props", "path": "/system/build.prop", "set": {"persist.wifi.state": "3"}, "stock": "any", "part": "extra"}'
cp $T/m/ra.recipe $T/m/a.xml $T/m/Test.lpd $R/data/hmgapi/mods/moda/
printf '{"settings": [{"key": "extra", "type": "toggle", "default": false}]}\n' > $R/data/hmgapi/mods/moda/settings.json
mod modb 2.1.0 ', "requires": ["moda >=1.0 <2"]' '
  {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rb.recipe"},
  {"type": "props", "path": "/system/build.prop", "set": {"persist.modb": "yes"}, "stock": "any"}'
cp $T/m/rb.recipe $R/data/hmgapi/mods/modb/

# ---- plan and apply
eng plan
check "plan exit" 0 $rc
has "plan lists the library" "change   /system/lib/libtest.so [moda, modb]" "$out"
has "plan says restart" "restart the head unit" "$out"
check "plan writes nothing" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
eng apply
check "apply exit" 0 $rc
check "two recipes combined" "$(md5 $T/lib.ab)" "$(md5 $R/system/lib/libtest.so)"
has "props set" "persist.ium.support=carplay" "$(cat $R/system/build.prop)"
has "props from second mod" "persist.modb=yes" "$(cat $R/system/build.prop)"
has "other props kept" "ro.other=1" "$(cat $R/system/build.prop)"
check "part off by default" "" "$(grep persist.wifi.state $R/system/build.prop || true)"
has "line commented" "#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
has "other line kept" "-/system/etc/permissions/x.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
check "new file added" "<permissions/>" "$(cat $R/system/etc/permissions/a.xml)"
check "app rebuilt with classes.dex" "$(md5 $T/classes.dex)" "$(unzip -p $R/system/app/Test.apk classes.dex | md5sum | cut -d' ' -f1)"
check "setting written" "global/wifi_on=1" "$(cat $R/settings.txt)"
check "stock backup taken" "$STOCK_LIB" "$(md5 $R/data/hmgapi/stock/system/lib/libtest.so)"
check "odex backed up" "$STOCK_ODEX" "$(md5 $R/data/hmgapi/stock/system/app/Test.odex)"
check "no journal left" "no" "$([ -e $R/data/hmgapi/state/journal.json ] && echo yes || echo no)"
eng status
check "status ok" 0 $rc
eng apply
has "second apply has nothing to do" "nothing to do" "$out"

# ---- refusals: nothing changes
LIB_NOW=$(md5 $R/system/lib/libtest.so)
mod modc 1.0.0 '' '{"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rc.recipe"}'
cp $T/m/rc.recipe $R/data/hmgapi/mods/modc/
eng apply
check "overlapping recipe refused" 3 $rc
has "conflict named" "conflict: /system/lib/libtest.so" "$out"
check "refused apply wrote nothing" "$LIB_NOW" "$(md5 $R/system/lib/libtest.so)"
rm -r $R/data/hmgapi/mods/modc
mod modd 1.0.0 ', "requires": ["moda >=2"]' ''
eng plan
has "version requirement" "modd: requires moda >=2, but moda 1.0.0 is installed" "$out"
rm -r $R/data/hmgapi/mods/modd
mod mode 1.0.0 ', "conflicts": ["moda <1.5"]' ''
eng plan
has "conflicts list" "conflict: mode 1.0.0 can't be used with moda 1.0.0" "$out"
rm -r $R/data/hmgapi/mods/mode
mod modf 1.0.0 ', "hmgapi": ">=3.0"' ''
eng plan
has "needs newer HMG-API" "modf 1.0.0: needs HMG-API >=3.0" "$out"
rm -r $R/data/hmgapi/mods/modf
mod modg 1.0.0 ', "requires": [{"id": "modb", "version": "^2.0"}]' ''
eng plan
check "object-form requires met" 0 $rc
rm -r $R/data/hmgapi/mods/modg

# ---- a file changed outside HMG-API
cp $R/system/build.prop $T/bp.keep; echo "x=1" >> $R/system/build.prop
eng apply
check "outside change stops apply" 3 $rc
has "outside change reported" "/system/build.prop: changed outside HMG-API" "$out"
cp $T/bp.keep $R/system/build.prop

# ---- mod off: rebuilt from stock without it
touch $R/data/hmgapi/mods/modb/.disabled
eng apply
check "disable modb" 0 $rc
check "library without modb" "$(md5 $T/lib.a)" "$(md5 $R/system/lib/libtest.so)"
check "modb's prop gone" "" "$(grep persist.modb $R/system/build.prop || true)"

# ---- only in P (Gear): a change to /system starts only with the car in P; no reading: the owner's go-ahead
printf '#!/bin/sh\n[ "$*" = "vehicle gear.park" ] || exit 0\ncat "%s/gear.out"\ncase "$(cat "%s/gear.out")" in gear.park*) exit 0 ;; esac\nexit 69\n' "$T" "$T" > $T/hmgapid
chmod +x $T/hmgapid
export HMGAPI_HMGAPID=$T/hmgapid
rm $R/data/hmgapi/mods/modb/.disabled
LIB_NOW=$(md5 $R/system/lib/libtest.so)
echo "gear.park 0" > $T/gear.out
eng apply
check "not in P: apply refused" 75 $rc
has "... saying why" "refused: the car isn't in P, and the apply only starts in P" "$out"
check "... nothing changed" "$LIB_NOW" "$(md5 $R/system/lib/libtest.so)"
check "... no journal" no "$([ -e $R/data/hmgapi/state/journal.json ] && echo yes || echo no)"
eng apply --gear-unknown-ok
check "not in P: refused even with the override" 75 $rc
eng plan
check "a plan needs no P" 0 $rc
echo "the vehicle service isn't running (yet)" > $T/gear.out
eng apply
check "no gear reading: apply refused" 76 $rc
has "... naming the owner's go-ahead" "then go on anyway (--gear-unknown-ok)" "$out"
check "... nothing changed" "$LIB_NOW" "$(md5 $R/system/lib/libtest.so)"
echo "gear.park -" > $T/gear.out
eng apply
check "the car didn't answer: refused the same" 76 $rc
eng apply --gear-unknown-ok
check "no gear reading, with the owner's go-ahead: applied" 0 $rc
has "... with the warning" "warning: the gear can't be read on this car" "$out"
check "... modb back in" "$(md5 $T/lib.ab)" "$(md5 $R/system/lib/libtest.so)"
touch $R/data/hmgapi/mods/modb/.disabled
echo "gear.park 1" > $T/gear.out
eng apply
check "in P: applied" 0 $rc
has "... the owner told to stay in P" "keep the car in P until the apply is done" "$out"
check "... modb out again" "$(md5 $T/lib.a)" "$(md5 $R/system/lib/libtest.so)"
echo "gear.park 0" > $T/gear.out
eng apply
check "nothing to change on /system: no gear needed" "0" "$rc"
# apps an apply stopped while their files changed (pm disable lasts across restarts): an apply
# cut off before it let them go leaves the list, and recover (the next start) turns them on
printf 'dev.example.gpm\ndev.example.dipo\n' > $R/data/hmgapi/state/held.txt
eng recover
has "recover: the apps a cut-off apply stopped turned on again" "enabled  dev.example.gpm (stopped by an apply that was cut off)" "$out"
has "... each" "enabled  dev.example.dipo" "$out"
check "... the list gone" no "$([ -e $R/data/hmgapi/state/held.txt ] && echo yes || echo no)"
unset HMGAPI_HMGAPID

# ---- trial: undone by recover, or kept
echo '{"extra": true}' > $R/data/hmgapi/settings/moda.json
BP_BEFORE=$(md5 $R/system/build.prop)
eng apply --trial
check "trial apply" 0 $rc
has "trial applied the part" "persist.wifi.state=3" "$(cat $R/system/build.prop)"
eng apply
check "another apply waits for keep/recover" 1 $rc
eng recover
check "recover" 0 $rc
check "trial undone" "$BP_BEFORE" "$(md5 $R/system/build.prop)"
eng apply --trial
eng keep
check "keep" 0 $rc
has "kept trial stays" "persist.wifi.state=3" "$(cat $R/system/build.prop)"
eng status
check "status after keep" 0 $rc

# ---- a firmware update put /system back to stock under HMG-API (install.sh runs engine firmware-reset)
echo '{"extra": false}' > $R/data/hmgapi/settings/moda.json
eng apply --trial
check "a trial waiting when the update comes" yes "$([ -f $R/data/hmgapi/state/journal.json ] && echo yes)"
S=$R/data/hmgapi/stock/system
for f in lib/libtest.so app/Test.apk app/Test.odex onebin/cvev_us/featurexml.cfg; do cp $S/$f $R/system/$f; done
printf 'ro.build.product=CVEV.USA.0000.V015\npersist.ium.support=none\nro.other=1\nro.newer=1\n' > $R/system/build.prop
rm -f $R/system/etc/permissions/a.xml
NEWPROP=$(md5 $R/system/build.prop)
eng firmware-reset
check "firmware-reset" 0 $rc
has "the trial dropped, not rolled back (that would write the old firmware's files)" "dropped an unfinished apply or trial" "$out"
check "... no journal" no "$([ -f $R/data/hmgapi/state/journal.json ] && echo yes || echo no)"
check "... the new build.prop untouched" "$NEWPROP" "$(md5 $R/system/build.prop)"
has "each replaced file said" "firmware  /system/build.prop: replaced by the update" "$out"
hasnt "... no file record left" "/system/" "$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["files"]))' $R/data/hmgapi/state/files.json)"
check "... their backups gone" "" "$(find $R/data/hmgapi/stock -type f)"
has "... settings still recorded (an update keeps them)" "global/wifi_on" "$(cat $R/data/hmgapi/state/files.json)"
eng apply
check "the next apply builds on the new files" 0 $rc
has "... the mods' props on the new build.prop" "ro.newer=1" "$(cat $R/system/build.prop)"
has "... and their change" "persist.ium.support=carplay" "$(cat $R/system/build.prop)"
check "... the added file back" yes "$([ -f $R/system/etc/permissions/a.xml ] && echo yes)"
eng firmware-reset
has "firmware-reset with nothing replaced: nothing dropped" "no file HMG-API changed needed dropping" "$out"
STOCK_PROP=$NEWPROP   # the update's build.prop is stock from here on

# ---- restore everything
rm -f $R/data/hmgapi/mods/modb/.disabled
eng restore
check "restore exit" 0 $rc
check "build.prop stock" "$STOCK_PROP" "$(md5 $R/system/build.prop)"
check "featurexml stock" "$STOCK_CFG" "$(md5 $R/system/onebin/cvev_us/featurexml.cfg)"
check "library stock" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
check "apk stock" "$STOCK_APK" "$(md5 $R/system/app/Test.apk)"
check "odex stock" "$STOCK_ODEX" "$(md5 $R/system/app/Test.odex)"
check "added file removed" "absent" "$(md5 $R/system/etc/permissions/a.xml)"
check "setting back to unset" "" "$(cat $R/settings.txt)"

# ---- baselines: not stock, override, NEMESIS backup
rm -r $R/data/hmgapi/mods/moda $R/data/hmgapi/state
mod modh 1.0.0 '' '{"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "ra.recipe"}'
cp $T/m/ra.recipe $R/data/hmgapi/mods/modh/
rm -r $R/data/hmgapi/mods/modb
cp $T/lib.b $R/system/lib/libtest.so
eng plan
has "non-stock refused" "not the stock file [modh] expect" "$out"
mkdir -p $R/data/nemesis/backup/oldpkg/lib && cp $R/data/hmgapi/stock/system/lib/libtest.so $R/data/nemesis/backup/oldpkg/lib/libtest.so
eng apply
check "NEMESIS stock copy used" 0 $rc
has "NEMESIS note" "using the stock copy NEMESIS kept (lib)" "$out"
check "built from the NEMESIS copy" "$(md5 $T/lib.a)" "$(md5 $R/system/lib/libtest.so)"
eng restore
check "restore to stock bytes from the NEMESIS copy" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
rm -r $R/data/nemesis $R/data/hmgapi/state $R/data/hmgapi/stock
cp $T/lib.b $R/system/lib/libtest.so
printf '{"overrides": {"accept_baseline": [{"path": "/system/lib/libtest.so", "md5": "%s"}]}}\n' "$(md5 $T/lib.b)" > $R/data/hmgapi/settings/hmgapi.json
eng apply
check "override accepts it" 0 $rc
has "override reported" "override /system/lib/libtest.so" "$out"
check "override: both changes" "$(md5 $T/lib.ab)" "$(md5 $R/system/lib/libtest.so)"
eng restore
check "restore to the owner's file" "$(md5 $T/lib.b)" "$(md5 $R/system/lib/libtest.so)"

# ---- feature flags: shared between mods, kept while any mod wants them
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock $R/data/hmgapi/settings/hmgapi.json
CFG=$(md5 $R/system/onebin/cvev_us/featurexml.cfg)
for m in fa fb; do
    mkdir -p $R/data/hmgapi/mods/$m
    printf '{"id": "%s", "version": "1.0.0", "feature_flags": ["com.hkmc.software.connectivity.carplay_wireless"]}\n' $m > $R/data/hmgapi/mods/$m/mod.json
done
eng apply
check "two mods, one feature: applies" 0 $rc
has "the feature file" '<feature name="com.hkmc.software.connectivity.carplay_wireless" />' "$(cat $R/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml 2>/dev/null)"
has "its featurexml line commented" "#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
export HMGAPI_PROPS="persist.service.adb.tcp.enable=1"; eng plan
has "the car's ADB TCP switch on with wireless projection: warned" "WARNING: the car's ADB TCP switch is on" "$out"
export HMGAPI_PROPS="persist.service.adb.tcp.enable=0"; eng plan
hasnt "... off: no warning" "ADB TCP switch" "$out"
unset HMGAPI_PROPS
touch $R/data/hmgapi/mods/fa/.disabled
eng apply
check "one mod off: the other still has it" yes "$([ -f $R/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml ] && echo yes || echo no)"
touch $R/data/hmgapi/mods/fb/.disabled
eng apply
check "both off: the file goes" no "$([ -f $R/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml ] && echo yes || echo no)"
check "... and featurexml.cfg is stock again" "$CFG" "$(md5 $R/system/onebin/cvev_us/featurexml.cfg)"
rm -r $R/data/hmgapi/mods/fa $R/data/hmgapi/mods/fb
mkdir -p $R/data/hmgapi/mods/fc
printf '{"id": "fc", "version": "1.0.0", "feature_flags": ["rm -rf"]}\n' > $R/data/hmgapi/mods/fc/mod.json
eng plan
has "odd feature names refused" 'feature "rm -rf": a com.hkmc.*' "$out"

# ---- media_codecs.xml: edits by codec name, merged between mods
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
cat > $R/system/etc/media_codecs.xml <<'X'
<MediaCodecs>
    <Include href="media_codecs_google_audio.xml" />
    <Decoders>
        <MediaCodec name="OMX.TCC.avcdec" type="video/avc" />
        <MediaCodec name="OMX.TCC.hevcdec" type="video/hevc">
            <Quirk name="requires-allocate-on-input-ports" />
        </MediaCodec>
        <!-- <MediaCodec name="OMX.google.h264.decoder" type="video/avc" /> -->
        <MediaCodec name="OMX.google.h264.decoder" type="video/avc" />
    </Decoders>
    <Encoders>
        <MediaCodec name="OMX.TCC.avcenc" type="video/avc" />
    </Encoders>
</MediaCodecs>
X
CODECS=$(md5 $R/system/etc/media_codecs.xml)
mkdir -p $R/data/hmgapi/mods/ca $R/data/hmgapi/mods/cb
printf '{"id": "ca", "version": "1.0.0"}\n' > $R/data/hmgapi/mods/ca/mod.json
printf '{"id": "cb", "version": "1.0.0"}\n' > $R/data/hmgapi/mods/cb/mod.json
printf '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "remove": ["OMX.google.h264.decoder"], "add": [{"name": "OMX.google.vp9.decoder", "type": "video/x-vnd.on2.vp9"}]}]}\n' > $R/data/hmgapi/mods/ca/patches.json
printf '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "remove": ["OMX.TCC.hevcdec"]}]}\n' > $R/data/hmgapi/mods/cb/patches.json
eng apply
check "two mods' codec edits: applied" 0 $rc
x=$(cat $R/system/etc/media_codecs.xml)
hasnt "a removed codec is gone" '    <MediaCodec name="OMX.google.h264.decoder" type="video/avc" />
    </Decoders>' "$x"
has "... the commented-out one is untouched" '<!-- <MediaCodec name="OMX.google.h264.decoder" type="video/avc" /> -->' "$x"
hasnt "the other mod's removal, with its quirks" "OMX.TCC.hevcdec" "$x"
has "an added codec goes last in Decoders" '        <MediaCodec name="OMX.google.vp9.decoder" type="video/x-vnd.on2.vp9" />
    </Decoders>' "$x"
has "encoders untouched" '<MediaCodec name="OMX.TCC.avcenc" type="video/avc" />' "$x"
mkdir -p $R/data/hmgapi/mods/cc
printf '{"id": "cc", "version": "1.0.0"}\n' > $R/data/hmgapi/mods/cc/mod.json
printf '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "add": [{"name": "OMX.google.h264.decoder", "type": "video/avc"}]}]}\n' > $R/data/hmgapi/mods/cc/patches.json
eng plan
has "adding what another mod removes: conflict" "conflict: /system/etc/media_codecs.xml: codec OMX.google.h264.decoder: ca removes it, cc adds it" "$out"
rm -r $R/data/hmgapi/mods/cc
eng restore
check "restore: stock bytes" "$CODECS" "$(md5 $R/system/etc/media_codecs.xml)"

# ---- a mod's install-time probe (Codec Patch: decided on the unit)
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
mkdir -p $R/data/hmgapi/mods/probe/lib
printf '{"id": "probe", "version": "1.0.0", "probe": {"script": "probe.sh"}}\n' > $R/data/hmgapi/mods/probe/mod.json
echo soft > $R/data/hmgapi/mods/probe/lib/libsoft.so
cat > $R/data/hmgapi/mods/probe/probe.sh <<'P'
echo "probe ran" >> "$HMGAPI_MOD_DIR/runs"
echo "looking at codecs" >&2
echo '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "remove": ["OMX.TCC.hevcdec"]},'
echo '             {"type": "add", "path": "/system/lib/libsoft.so", "src": "lib/libsoft.so", "mode": "644", "stock": "absent"}]}'
P
eng apply
check "probe's changes applied" 0 $rc
has "probe reported in the plan" "probe    probe: 2 change(s) from its look at this unit" "$out"
hasnt "... codec removed" "OMX.TCC.hevcdec" "$(cat $R/system/etc/media_codecs.xml)"
check "... file added" soft "$(cat $R/system/lib/libsoft.so 2>/dev/null)"
has "its stderr in its log" "looking at codecs" "$(cat $R/data/hmgapi/logs/probe/probe.log 2>/dev/null)"
eng plan
check "the answer is kept: not run again" 1 "$(grep -c ran $R/data/hmgapi/mods/probe/runs)"
check "... and the plan stays the same" 0 $rc
eng probe probe
eng plan
check "engine probe <mod>: runs again" 2 "$(grep -c ran $R/data/hmgapi/mods/probe/runs)"
eng restore
cat > $R/data/hmgapi/mods/probe/probe.sh <<'P'
echo '{"changes": [{"type": "add", "path": "/system/bin/sh", "src": "../../../../system/bin/x"}]}'
P
eng probe probe
eng plan
has "probe: only codecs and the mod's own files" "probe: only" "$out"
printf 'exit 3\n' > $R/data/hmgapi/mods/probe/probe.sh
eng probe probe
eng plan
has "a failing probe: reported, nothing from it" "probe failed (exit 3" "$out"

# ---- list settings (KeyBORD: enabled keyboards), merged per entry
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
echo "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME" > $R/settings.txt
for m in ka kb; do mkdir -p $R/data/hmgapi/mods/$m; printf '{"id": "%s", "version": "1.0.0"}\n' $m > $R/data/hmgapi/mods/$m/mod.json; done
printf '{"changes": [{"type": "setting", "namespace": "secure", "key": "enabled_input_methods", "add": ["org.pocketworkstation.pckeyboard/.LatinIME"]},
  {"type": "setting", "namespace": "secure", "key": "default_input_method", "value": "org.pocketworkstation.pckeyboard/.LatinIME"}]}\n' > $R/data/hmgapi/mods/ka/patches.json
printf '{"changes": [{"type": "setting", "namespace": "secure", "key": "enabled_input_methods", "add": ["dev.example/.Ime", "com.android.inputmethod.latin/.LatinIME"]}]}\n' > $R/data/hmgapi/mods/kb/patches.json
eng apply
check "list setting: applies" 0 $rc
has "stock entry kept, both mods' added once each" "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME:org.pocketworkstation.pckeyboard/.LatinIME:dev.example/.Ime" "$(cat $R/settings.txt)"
touch $R/data/hmgapi/mods/kb/.disabled
eng apply
check "one mod off: its entry goes" "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME:org.pocketworkstation.pckeyboard/.LatinIME" "$(grep enabled_input $R/settings.txt)"
touch $R/data/hmgapi/mods/ka/.disabled
eng apply
check "both off: the stock list" "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME" "$(grep enabled_input $R/settings.txt)"
check "... and the default keyboard back to stock (unset)" "" "$(grep default_input $R/settings.txt)"
rm $R/data/hmgapi/mods/ka/.disabled $R/data/hmgapi/mods/kb/.disabled
printf '{"changes": [{"type": "setting", "namespace": "secure", "key": "enabled_input_methods", "value": "only/.This"}]}\n' > $R/data/hmgapi/mods/kb/patches.json
eng plan
has "a value and a list edit on one key: conflict" "conflict: setting secure/enabled_input_methods: ka adds to it, kb sets it to only/.This" "$out"

# ---- Wi-Fi client: the same two changes for every mod that asks
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
for m in wa wb; do mkdir -p $R/data/hmgapi/mods/$m; printf '{"id": "%s", "version": "1.0.0", "wifi": {"client": true}}\n' $m > $R/data/hmgapi/mods/$m/mod.json; done
eng apply
check "two mods ask for the Wi-Fi client: applies" 0 $rc
has "the client feature" '<feature name="android.hardware.wifi" />' "$(cat $R/system/etc/permissions/android.hardware.wifi.xml 2>/dev/null)"
check "persist.wifi.state=3" "prop/persist.wifi.state=3" "$(grep persist.wifi.state $R/settings.txt)"
touch $R/data/hmgapi/mods/wa/.disabled $R/data/hmgapi/mods/wb/.disabled
eng apply
check "both off: feature gone" no "$([ -f $R/system/etc/permissions/android.hardware.wifi.xml ] && echo yes || echo no)"
check "... state back" "" "$(grep persist.wifi.state $R/settings.txt)"

# ---- Wi-Fi hotspot: the stock hotspot feature, which makes the unit power its Wi-Fi chip
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
HS=$R/system/etc/permissions/com.hkmc.software.wifihotspot.xml
printf '%s\n' "-/system/etc/permissions/com.hkmc.software.wifihotspot.xml" "-/system/etc/permissions/x.xml" > $R/system/onebin/cvev_us/featurexml.cfg
CFG=$(md5 $R/system/onebin/cvev_us/featurexml.cfg)
for m in ha hb; do mkdir -p $R/data/hmgapi/mods/$m; printf '{"id": "%s", "version": "1.0.0", "wifi": {"hotspot": true}}\n' $m > $R/data/hmgapi/mods/$m/mod.json; done
eng apply
check "no hotspot file (Korean units): two mods ask, applies" 0 $rc
check "... HMG-API's feature file" '<permissions>
    <feature name="com.hkmc.software.wifihotspot" />
</permissions>' "$(cat $HS 2>/dev/null)"
has "... its featurexml line commented" "#-/system/etc/permissions/com.hkmc.software.wifihotspot.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
eng apply
has "... applied again: nothing to do" "nothing to do" "$out"
touch $R/data/hmgapi/mods/ha/.disabled $R/data/hmgapi/mods/hb/.disabled
eng apply
check "both off: the file goes" absent "$(md5 $HS)"
check "... featurexml.cfg stock again" "$CFG" "$(md5 $R/system/onebin/cvev_us/featurexml.cfg)"
# a car whose stock software has the file (deleted at boot by its featurexml line, or not)
printf '<?xml version="1.0" encoding="utf-8"?>\n<permissions>\n<feature name="com.hkmc.software.wifihotspot"/>\n</permissions>\n' > $HS
HSM=$(md5 $HS)
rm $R/data/hmgapi/mods/ha/.disabled
eng apply
check "stock hotspot file: applies" 0 $rc
check "... the stock file stays as it is" "$HSM" "$(md5 $HS)"
has "... only its featurexml line commented" "#-/system/etc/permissions/com.hkmc.software.wifihotspot.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
touch $R/data/hmgapi/mods/ha/.disabled
eng apply
check "... off: featurexml.cfg stock again" "$CFG" "$(md5 $R/system/onebin/cvev_us/featurexml.cfg)"
check "... and the stock file still there" "$HSM" "$(md5 $HS)"
rm -f $HS
rm -rf $R/data/hmgapi/mods/*
mkdir -p $R/data/hmgapi/mods/hc; printf '{"id": "hc", "version": "1.0.0", "wifi": {"hotspot": "yes"}}\n' > $R/data/hmgapi/mods/hc/mod.json
eng plan
has "\"wifi\" with another value: refused, with the form" '"wifi": {"client": true}, {"hotspot": true}, {"projection": true} and/or {"home": true}' "$out"
has "bad Wi-Fi declaration reported against its mod" 'hc: "wifi":' "$out"
hasnt "bad Wi-Fi declaration doesn't crash plan" 'engine error:' "$out"
rm -rf $R/data/hmgapi/mods/hc

# ---- one engine run at a time (rc.5: the app's two plans after a USB install raced on .features/*.tmp)
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
mkdir -p $R/data/hmgapi/mods/wa $R/data/hmgapi/state
printf '{"id": "wa", "version": "1.0.0", "wifi": {"client": true}}\n' > $R/data/hmgapi/mods/wa/mod.json
python3 -c '
import fcntl, os, sys, time
f = open(sys.argv[1], "a+"); fcntl.lockf(f, fcntl.LOCK_EX); f.truncate(0); f.write("apply 999\n"); f.flush()
open(sys.argv[2], "w").close(); time.sleep(60)' $R/data/hmgapi/state/engine.lock $T/held &
holder=$!
while [ ! -f $T/held ]; do sleep 0.1; done
export HMGAPI_LOCK_WAIT=1
eng plan
check "lock held elsewhere: the run gives up" 4 $rc
has "... saying who holds it" "busy: another engine run (apply 999)" "$out"
eng status
hasnt "status doesn't wait for the lock" "busy:" "$out"
export HMGAPI_ENGINE_LOCKED=1
eng plan
check "a child of the holder's run passes through" 0 $rc
unset HMGAPI_ENGINE_LOCKED HMGAPI_LOCK_WAIT
kill $holder; wait $holder 2>/dev/null || true
for i in 1 2 3; do
    rm -rf $R/data/hmgapi/mods/wa/.features
    for j in 1 2 3; do
        (HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 HMGAPI_ONEBIN=cvev_us \
            HMGAPI_LPATCH=$REPO/out/lpatch-host java -cp "$CP" hmgapi.engine.Engine plan > $T/race.$j 2>&1; echo $? >> $T/race.$j) &
    done
    wait
    for j in 1 2 3; do
        r=$(cat $T/race.$j)
        case "$r" in *"engine error"*|*busy:*) echo "FAIL  three plans at once on a fresh mod (round $i): $r"; fail=1 ;; esac
    done
done
echo "ok    three plans at once on a fresh mod, three rounds"
check "... the feature file written once, no .tmp left" "android.hardware.wifi.xml" "$(ls -A $R/data/hmgapi/mods/wa/.features)"

# ---- a failed apply: rolled back in the same run; a cut-off one: by recover. Apps held while
# their files change are journaled first and always started again; modes, owners, labels kept.
# Off the unit, owners and labels live in $R/meta.txt (FileMeta), the mode is the file's own.
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
check "app stock before" "$STOCK_APK $STOCK_ODEX" "$(md5 $R/system/app/Test.apk) $(md5 $R/system/app/Test.odex)"
mkdir -p $R/system/bin $R/system/framework $R/data/system
mode() { python3 -c 'import os, sys; print(oct(os.stat(sys.argv[1]).st_mode & 0o7777)[2:])' "$1"; }
own() { m=$(mode $R$1); o=$(grep "^$1 " $R/meta.txt 2>/dev/null | sed "s#^$1 ##"); echo "$m ${o:-0 0 -}"; }
for f in lib/a.so lib/z.so bin/netd framework/framework-res.apk app/Pers.apk app/Off.apk; do
    head -c 4096 /dev/urandom > $R/system/$f; head -c 4096 /dev/urandom > $T/m/$(basename $f).new
done
chmod 640 $R/system/lib/a.so; chmod 644 $R/system/lib/z.so; chmod 755 $R/system/bin/netd
printf '%s\n' "/system/lib/a.so 1000 1000 u:object_r:system_file:s0" "/system/bin/netd 0 2000 u:object_r:netd_exec:s0" > $R/meta.txt
cp -p $R/meta.txt $T/meta.stock
OWN_A=$(own /system/lib/a.so); OWN_Z=$(own /system/lib/z.so); OWN_N=$(own /system/bin/netd)
check "stock modes and owners set up" "640 1000 1000 u:object_r:system_file:s0|644 0 0 -|755 0 2000 u:object_r:netd_exec:s0" "$OWN_A|$OWN_Z|$OWN_N"
sums() { for f in lib/a.so lib/z.so bin/netd app/Test.apk app/Test.odex framework/framework-res.apk app/Pers.apk app/Off.apk; do md5 $R/system/$f; done | tr '\n' ' '; }
STOCK_ALL=$(sums)
# a package manager: Test.apk's app runs, Off.apk's is off already, Pers.apk's is persistent, framework-res is android
cat > $T/pm <<EOF
#!/bin/sh
echo "\$*" >> $T/pm.calls
case "\$1 \$2" in
    "list packages") if [ "\$3" = -f ]; then cat $T/pm.pkgs; else sed 's/^/package:/' $T/pm.off; fi ;;
    "disable "*) echo "\$2" >> $T/pm.off; echo "Package \$2 new state: disabled" ;;
    "enable "*) grep -vx "\$2" $T/pm.off > $T/pm.off.n || true; mv $T/pm.off.n $T/pm.off; echo "Package \$2 new state: enabled" ;;
esac
EOF
chmod +x $T/pm
printf 'package:%s\n' /system/app/Test.apk=com.test.app /system/app/Off.apk=com.test.off /system/app/Pers.apk=com.test.pers \
    /system/framework/framework-res.apk=android > $T/pm.pkgs
cat > $R/data/system/packages.xml <<'X'
<packages>
<package name="android" codePath="/system/framework/framework-res.apk" nativeLibraryPath="/data/app-lib/framework-res" flags="573005" ft="1" version="19" sharedUserId="1000">
<package name="com.test.app" codePath="/system/app/Test.apk" nativeLibraryPath="/data/app-lib/Test" flags="572997" ft="1" version="1" userId="10010">
<package name="com.test.off" codePath="/system/app/Off.apk" flags="572997" ft="1" version="1" userId="10011">
<package name="com.test.pers" codePath="/system/app/Pers.apk" flags="573005" ft="1" version="1" userId="10012">
<updated-package name="com.test.app" codePath="/system/app/Test.apk" flags="8" ft="1" version="1" />
</packages>
X
pmreset() { echo com.test.off > $T/pm.off; rm -f $T/pm.calls; }
# engf "<step>:<path> ...": eng with those steps failing (Engine.testFail); exported and unset again, since
# sh keeps a variable put before a function call
engf() { HMGAPI_TEST_FAIL=$1; export HMGAPI_TEST_FAIL; shift; eng "$@"; unset HMGAPI_TEST_FAIL; }
pmreset
export HMGAPI_PM=$T/pm HMGAPI_PROPS="sys.boot_completed=1"
mod rb 1.0.0 '' '
  {"type": "add", "path": "/system/lib/a.so", "src": "a.so.new", "stock": "any"},
  {"type": "add", "path": "/system/lib/z.so", "src": "z.so.new", "stock": "any"},
  {"type": "add", "path": "/system/bin/netd", "src": "netd.new", "stock": "any", "mode": "755"},
  {"type": "app", "path": "/system/app/Test.apk", "delta": "Test.lpd"},
  {"type": "add", "path": "/system/framework/framework-res.apk", "src": "framework-res.apk.new", "stock": "any"},
  {"type": "add", "path": "/system/app/Pers.apk", "src": "Pers.apk.new", "stock": "any"},
  {"type": "add", "path": "/system/app/Off.apk", "src": "Off.apk.new", "stock": "any"}'
cp $T/m/*.new $T/m/Test.lpd $R/data/hmgapi/mods/rb/
J=$R/data/hmgapi/state/journal.json; HELD=$R/data/hmgapi/state/held.txt
left() { find $R/system -name '*.hmgapi-new' | tr '\n' ' '; }
allstock() {   # allstock <what>: every file, mode, owner and label as before, nothing left over, apps as before
    check "$1: every file as it was" "$STOCK_ALL" "$(sums)"
    check "$1: modes, owners, labels as they were" "$OWN_A|$OWN_Z|$OWN_N" "$(own /system/lib/a.so)|$(own /system/lib/z.so)|$(own /system/bin/netd)"
    check "$1: no journal, no prev/, no held list, no temp files" "no no no " "$([ -f $J ] && echo yes || echo no) $([ -d $R/data/hmgapi/state/prev ] && echo yes || echo no) $([ -f $HELD ] && echo yes || echo no) $(left)"
    check "$1: the app it held started again; the one off before still off" "com.test.off" "$(cat $T/pm.off)"
}

engf "swap:/system/lib/z.so" apply
check "a swap failing part-way (a.so written, z.so not): the apply fails" 1 $rc
has "... saying why" "apply failed: java.io.IOException: test: swap fails at /system/lib/z.so" "$out"
has "... rolled back in the same run, last written first" "rolled back /system/lib/a.so" "$out"
has "... all of it" "rolled back: every file is as it was before this apply" "$out"
allstock "swap failure"
has "... the app was stopped first, then started again" "disable com.test.app|" "$(tr '\n' '|' < $T/pm.calls)"
hasnt "... Android itself (framework-res.apk) never stopped" "disable android" "$(cat $T/pm.calls)"
hasnt "... nor a persistent app" "disable com.test.pers" "$(cat $T/pm.calls)"
hasnt "... nor one that was off already (or started)" "com.test.off" "$(grep -v '^list' $T/pm.calls)"
has "... said" "note     com.test.pers keeps running while /system/app/Pers.apk changes (persistent)" "$out"
check "... no record of the failed apply" "" "$(grep -o 'a\.so' $R/data/hmgapi/state/files.json 2>/dev/null || true)"
eng plan
check "... other commands aren't refused after it" 0 $rc

pmreset
engf "dexopt:/system/app/Test.apk" apply
check "dexopt failing (the odex deleted already): the apply fails" 1 $rc
allstock "dexopt failure"
check "... the odex back" "$STOCK_ODEX" "$(md5 $R/system/app/Test.odex)"

pmreset
engf "put:/system/lib/z.so" apply
check "a copy failing: the apply fails" 1 $rc
allstock "copy failure (its temp file deleted)"

pmreset
engf "remount:rw" apply
check "remount rw failing: the apply fails" 1 $rc
allstock "remount failure"
hasnt "... nothing had changed, so nothing rolled back" "rolled back /system" "$out"

# a power cut (or a killed engine) in the middle: a.so new, z.so stock, the app off
pmreset
engf "halt:/system/lib/z.so" apply
check "cut off mid-swap: the journal stays" yes "$([ -f $J ] && echo yes)"
check "... a.so new, z.so stock" "$(md5 $T/m/a.so.new) $(md5 $R/data/hmgapi/state/prev/_system_lib_z.so)" "$(md5 $R/system/lib/a.so) $(md5 $R/system/lib/z.so)"
check "... the held app journaled before it was stopped, and still off" "com.test.app|com.test.app" "$(cat $HELD)|$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["held"]))' $J)"
has "... (off in the package manager)" "com.test.app" "$(cat $T/pm.off)"
eng plan
check "... other commands wait for recover" 1 $rc
eng status
has "... status names the held app" "held     [com.test.app]" "$out"
export HMGAPI_PROPS=""   # recover at boot runs before Android is up
eng recover
check "recover before Android is up" 0 $rc
has "... rolled back" "unfinished apply rolled back" "$out"
check "... files, modes, owners back" "$STOCK_ALL|$OWN_A|$OWN_Z|$OWN_N" "$(sums)|$(own /system/lib/a.so)|$(own /system/lib/z.so)|$(own /system/bin/netd)"
has "... the app left for boot.sh" "start again once Android is up (boot.sh: engine release-held)" "$out"
check "... still in held.txt" "com.test.app" "$(cat $HELD)"
eng release-held
check "release-held with Android not up: waits" 1 $rc
export HMGAPI_PROPS="sys.boot_completed=1"
eng release-held
check "release-held once Android is up" 0 $rc
allstock "power cut, then recover and release-held"

# the rollback fails too: the journal stays, the apps stay off with their files half-changed, recover finishes later
pmreset
engf "swap:/system/lib/z.so rollback:/system/lib/a.so" apply
check "a rollback that fails too: the apply fails" 1 $rc
has "... said" "NOT rolled back: [/system/lib/a.so (test: rollback fails at /system/lib/a.so)]" "$out"
check "... the journal kept" yes "$([ -f $J ] && echo yes)"
has "... the app stays off" "com.test.app" "$(cat $T/pm.off)"
eng release-held
check "... release-held won't start it over half-changed files" 1 $rc
eng recover
check "... recover finishes it" 0 $rc
allstock "failed rollback, then recover"

# a good apply: modes and owners kept, a mod's mode used, stock ones recorded; restore puts them back
pmreset
eng apply
check "apply" 0 $rc
check "a file with no mode keeps its stock mode, owner and label" "$OWN_A" "$(own /system/lib/a.so)"
check "netd: the mod's mode, the stock owner and label" "$OWN_N" "$(own /system/bin/netd)"
check "the held app started again" "com.test.off" "$(cat $T/pm.off)"
check "no held list left" no "$([ -f $HELD ] && echo yes || echo no)"
check "stock modes recorded" "640 1000 1000 u:object_r:system_file:s0" "$(python3 -c 'import json, sys; m = json.load(open(sys.argv[1]))["files"]["/system/lib/a.so"]["stockMeta"]["/system/lib/a.so"]; print(m["mode"], m["uid"], m["gid"], m["context"])' $R/data/hmgapi/state/files.json)"
eng restore
check "restore" 0 $rc
check "restore: files, modes, owners, labels stock" "$STOCK_ALL|$OWN_A|$OWN_Z|$OWN_N" "$(sums)|$(own /system/lib/a.so)|$(own /system/lib/z.so)|$(own /system/bin/netd)"
# a record from before modes were kept, and netd as that HMG-API wrote it (644 0:0): restore uses /system/bin's 755 0:2000
eng apply
python3 - $R/data/hmgapi/state/files.json <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
for f in s["files"].values(): f.pop("stockMeta", None)
json.dump(s, open(sys.argv[1], "w"))
PY
chmod 644 $R/system/bin/netd; sed -i.bak '/^\/system\/bin\/netd /d' $R/meta.txt
eng restore
check "restore of an old record: /system/bin's defaults" "755 0 2000 -" "$(own /system/bin/netd)"
unset HMGAPI_PM HMGAPI_PROPS
exit $fail
