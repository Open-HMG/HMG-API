#!/bin/sh
# Off-car test of hook plans (hmgapi.engine.HookPlan through `engine plan`): load
# order, declared modes, and conflicts when two mods replace the same hook point.
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
exit $fail
