#!/bin/sh
# Off-car test of the adb mod on the patch engine: each toggle sets its props,
# the live mirror (sys.usb.config) and the adbd restart; restore gives the stock values back.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
prop() { grep "^prop/$1=" $R/settings.txt | cut -d= -f2-; }
check "NEMESIS package refuses adb.tcp (mod.json nemesis_conflicts)" "hmgapi@>=1.0,!adb.tcp" \
    "$(HMGAPI_NEMESIS_CONFLICTS=/dev/null tools/nemesis-meta.py depends hmgapi.adb 'hmgapi@>=1.0' --mod mods/adb/mod.json)"
mkdir -p $R/data/hmgapi/mods $R/data/hmgapi/settings
printf 'ro.board.platform=tcc803x\n' > $R/props.txt
printf 'prop/persist.service.adb.tcp.enable=1\nprop/persist.sys.usb.config=adb\nprop/persist.adb.tcp.port=5555\n' > $R/settings.txt
cp -r mods/adb $R/data/hmgapi/mods/; rm $R/data/hmgapi/mods/adb/test.sh

eng apply; check "defaults apply" 0 $rc
check "Engineering Mode switch off (no eth0 address)" 0 "$(prop persist.service.adb.tcp.enable)"
check "USB adb on" adb "$(prop persist.sys.usb.config)"
check "adb over Wi-Fi off by default: port unset" "" "$(prop persist.adb.tcp.port)"
check "... adbd restarted, nothing else live" "restart adbd" "$(grep -v STATE_CHANGED $R/actions.log 2>/dev/null)"
check "values already right aren't set again" "prop/persist.adb.tcp.port=|prop/persist.service.adb.tcp.enable=0|" "$(tr '\n' '|' < $R/settings.log 2>/dev/null)"

printf '{"usb": false}\n' > $R/data/hmgapi/settings/adb.json
eng apply; check "USB off applies" 0 $rc
check "port back to host mode (stock)" "host,adb" "$(prop persist.sys.usb.config)"
has "switched live" "live/sys.usb.config=host,adb" "$(cat $R/actions.log)"
has "plan line says what runs after" "then    sys.usb.config = host,adb" "$out"

printf '{"usb": false, "tcp": true}\n' > $R/data/hmgapi/settings/adb.json
: > $R/actions.log
eng apply; check "TCP on: port 5555 (eth0 left alone)" 5555 "$(prop persist.adb.tcp.port)"
has "adbd restarted" "restart adbd" "$(cat $R/actions.log)"
check "... the car's switch stays off" 0 "$(prop persist.service.adb.tcp.enable)"

printf '{"usb": true, "tcp": true}\n' > $R/data/hmgapi/settings/adb.json
: > $R/actions.log
eng apply; check "both on again" "adb 5555" "$(prop persist.sys.usb.config) $(prop persist.adb.tcp.port)"
check "USB on again: live switch only (TCP was already on)" "live/sys.usb.config=adb|" "$(grep -v STATE_CHANGED $R/actions.log | tr '\n' '|')"

: > $R/actions.log
eng restore; check "restore" 0 $rc
check "stock values back" "1 adb 5555" "$(prop persist.service.adb.tcp.enable) $(prop persist.sys.usb.config) $(prop persist.adb.tcp.port)"
exit $fail
