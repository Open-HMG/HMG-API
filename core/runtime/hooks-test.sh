#!/bin/sh
# Off-car test of hook plans (hmgapi.engine.HookPlan through `engine plan`): load
# order, declared modes, and conflicts when two mods replace the same hook point; the
# safety functions no mod touches (hmgapi.engine.Protected) and the Car Report's account of them.
# (The runtime guard and ordering are in the on-car self-test.)
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
mkdir -p $R/data/hmgapi/mods $R/data/hmgapi/settings
mod() {   # mod <id> <points json> [requires json]
    mkdir -p $R/data/hmgapi/mods/$1
    printf '{"id": "%s", "version": "1.0.0", "hooks": {"jar": "hooks.jar", "entry": "x.X", "points": %s}%s}\n' "$1" "$2" "${3:+, \"requires\": $3}" > $R/data/hmgapi/mods/$1/mod.json
    printf '{"changes": []}\n' > $R/data/hmgapi/mods/$1/patches.json
}
mod a '{"launcher.menu_list.load": "change"}'
mod b '{"launcher.menu_list.load": "observe"}'
eng plan; check "plan" 0 $rc
has "two mods on one point: listed" "hooks    launcher.menu_list.load: a (change), b (observe) share it" "$out"
hasnt "no conflict between change and observe" "won't load" "$out"

mod c '{"framework.context_impl.start_service": "replace"}'
mod d '{"framework.context_impl.start_service": "replace"}'
eng plan; has "two replace hooks on one point: the later one refused" "hooks    d won't load: conflict: d and c both replace framework.context_impl.start_service" "$out"
hasnt "the first keeps loading" "c won't load" "$out"
: > $R/data/hmgapi/mods/c/.disabled
eng plan; hasnt "the other one off: no conflict" "won't load" "$out"
rm $R/data/hmgapi/mods/c/.disabled

mod e '{"framework.context_impl.start_service": "observe"}' '["d"]'
eng plan; has "a mod needing a refused mod is refused too" "e won't load: requires d, which isn't loaded" "$out"
rm -r $R/data/hmgapi/mods/c $R/data/hmgapi/mods/d $R/data/hmgapi/mods/e

mod z '{"launcher.menu_list.load": "observe"}'
mod y '{"launcher.menu_list.load": "replace"}' '["z"]'
eng plan; has "required mods load first, then by id" "launcher.menu_list.load: a (change), b (observe), z (observe), y (replace)" "$out"
mod f '{"launcher.menu_list.load": "rewrite"}'
eng plan; has "unknown mode refused" "mode \"rewrite\" (observe, change or replace)" "$out"
rm -r $R/data/hmgapi/mods/*

# "conflicts" between mods that only hook (no patches.json: the patch engine never sees them)
hookonly() {   # hookonly <id> <extra mod.json>
    mkdir -p $R/data/hmgapi/mods/$1
    printf '{"id": "%s", "version": "1.0.0", "hooks": {"jar": "hooks.jar", "entry": "x.X", "points": {"launcher.menu_list.load": "observe"}}%s}\n' "$1" "$2" > $R/data/hmgapi/mods/$1/mod.json
}
hookonly full ', "requires": ["wire"], "conflicts": ["lite"]'
hookonly lite ', "conflicts": ["full"]'
hookonly wire ''
eng plan; check "plan with two mods that list each other" 0 $rc
has "the second in load order won't load, and why" "hooks    lite won't load: conflict: lite 1.0.0 and full 1.0.0 can't run together (each lists the other in \"conflicts\"); full loads, turn it off to use lite instead" "$out"
hasnt "the first loads" "full won't load" "$out"
has "only the first's hooks are planned" "launcher.menu_list.load: wire (observe), full (observe) share it" "$out"
: > $R/data/hmgapi/mods/wire/.disabled
eng plan; has "a mod whose requirement is off won't load" "hooks    full won't load: requires wire, which isn't installed and turned on" "$out"
hasnt "... and the other loads in its place" "lite won't load" "$out"
rm $R/data/hmgapi/mods/wire/.disabled
: > $R/data/hmgapi/mods/full/.disabled
eng plan; hasnt "the first turned off: the other loads" "won't load" "$out"
rm -r $R/data/hmgapi/mods/*

# safety functions (engine.Protected): the cameras, guide lines and telematics, no mod, built in
eng plan; has "plan lists the protected processes" "safety   process com.hkmc.system.app.rearcamera: the rear camera, 360 view (AVM/SVM) and parking guide lines (no mod, built in)" "$out"
has "... and classes" "safety   class com.hkmc.micom.PgsService: the camera and guide line service (system_server) (no mod, built in)" "$out"
mod lines '{"framework.pgs_service.request_guide_lines": "observe"}'
mod fine '{"launcher.menu_list.load": "observe"}'
eng plan; has "a mod declaring the guide line point won't load" "hooks    lines won't load: hook point framework.pgs_service.request_guide_lines reaches the parking guide lines and cameras (use the vehicle commands guide_lines and rear_camera): a safety function, no mod hooks it (built in, no override)" "$out"
hasnt "... the other one loads" "fine won't load" "$out"
printf '{"overrides": {"ignore_system": ["lines"]}}\n' > $R/data/hmgapi/settings/hmgapi.json
eng plan; has "... no owner override changes that" "hooks    lines won't load: hook point framework.pgs_service.request_guide_lines" "$out"
rm $R/data/hmgapi/settings/hmgapi.json
rm -r $R/data/hmgapi/mods/*
# the Car Report: what the runtime logged (a protected process started, a refused hook, an undeclared one)
mkdir -p $R/data/hmgapi/logs/hmgapi
cat > $R/data/hmgapi/logs/hmgapi/log.log <<'L'
11-27 10:00:01.000 I/HMG-API( 1163): protected: com.hkmc.system.app.rearcamera runs the rear camera, 360 view (AVM/SVM) and parking guide lines: no mod loads here, mods' hooks pass through, HMG-API adds nothing (built in, no override)
11-27 10:00:02.000 W/HMG-API(  624): protected: refused cam's hook on com.hkmc.micom.PgsService.requestGuideLineState in system_server: the camera and guide line service (system_server) is a safety function; no mod hooks it (built in, no override)
11-27 10:00:03.000 W/HMG-API( 2900): undeclared hook: egmp-aa-ev hooked X.shutdown in com.google.android.projection.sink directly, not through a hook point it declared in mod.json
11-27 10:05:03.000 W/HMG-API( 3100): undeclared hook: egmp-aa-ev hooked X.shutdown in com.google.android.projection.sink directly, not through a hook point it declared in mod.json
L
eng report; check "report" 0 $rc
has "report: the safety summary" "safety: 1 protected process(es) started with no mod, 1 mod hook(s) refused, 1 direct hook(s) outside declared points" "$out"
md=$(cat $R/data/hmgapi/public/reports/*.md)
has "report: protected processes seen" "Protected processes that started here, with no mod loaded: \`com.hkmc.system.app.rearcamera\`" "$md"
has "report: the refused hook" "  - refused cam's hook on com.hkmc.micom.PgsService.requestGuideLineState in system_server" "$md"
has "report: the undeclared hook, once" "Direct hooks outside a mod's declared points: 1
  - egmp-aa-ev hooked X.shutdown in com.google.android.projection.sink directly" "$md"
has "report json: the rules" '"process com.hkmc.system.app.rearcamera: the rear camera' "$(cat $R/data/hmgapi/public/reports/*.json)"
rm -r $R/data/hmgapi/logs $R/data/hmgapi/public
javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/ProtectedTest.java
java -cp "$CP" hmgapi.engine.ProtectedTest core/hookpoints || fail=1

# the mod loader's own decisions (hook.Mods: boot and live loads), on a mods folder
javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/HookPlanTest.java
mkdir -p $T/loader; java -cp "$CP" hmgapi.engine.HookPlanTest $T/loader || fail=1
exit $fail
