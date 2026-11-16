# Helpers for a mod's test.sh, in the mod SDK as test/lib.sh (the same check/has/eng the
# mods' tests in HMG-API used). A test sources it after setting R, the fake head unit's root:
#   . "$HMGAPI_SDK/test/lib.sh"
# then calls eng <engine args> (sets $out and $rc), check/has, and ends with exit $fail.
# HMGAPI_SDK is the unpacked SDK (the mod workflow sets it). Set HMGAPI_PRODUCT,
# HMGAPI_ONEBIN etc. in the environment for the engine; HMGAPI_TEST_ANY_CAR defaults to 1.
HMGAPI_SDK=${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked HMG-API mod SDK}
HMGAPI_SDK=$(cd "$HMGAPI_SDK" && pwd)
ENGINE=$HMGAPI_SDK/lib/hmgapi-engine.jar
LPATCH=$HMGAPI_SDK/bin/lpatch-host
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
# the engine on this machine (host mode), on the head unit at $R
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=${HMGAPI_TEST_ANY_CAR-1} HMGAPI_LPATCH=$LPATCH \
        java -cp "$ENGINE" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
# md5 and path of every file under $R/system (compare before and after a restore)
tree() { (cd $R/system && find . -type f | sort | while read f; do echo "$(md5sum < "$f" | cut -c1-32) $f"; done) }
# put the mod (its folder, without test.sh) on the head unit: install_mod <mod dir>
install_mod() { mkdir -p $R/data/hmgapi/mods && rm -rf "$R/data/hmgapi/mods/$(basename "$1")" && cp -r "$1" $R/data/hmgapi/mods/ && rm -f "$R/data/hmgapi/mods/$(basename "$1")/test.sh"; }
# a car's stock files, when tools/cars.py test runs this test on them (docs/car-trees.md in HMG-API):
# HMGAPI_CAR is that car's stock /system (maybe partial), HMGAPI_CAR_ID its map folder (kia/ev6/usa/260507),
# HMGAPI_CAR_PRODUCTS its map's products (CVEV.USA.). car_tree prints the tree, or fails without one:
#   S=$(car_tree) || { echo "no stock tree (skipped)"; exit 0; }
car_tree() { [ -n "${HMGAPI_CAR:-}" ] && [ -d "$HMGAPI_CAR" ] && echo "$HMGAPI_CAR"; }
# a value from that tree's build.prop, e.g. car_prop ro.build.product
car_prop() { [ -f "$HMGAPI_CAR/build.prop" ] && sed -n "s/^$1=//p" "$HMGAPI_CAR/build.prop" | sed -n 1p; }

# ---- the plan test: the mod through engine plan on a fake head unit for each car HMG-API has a
# map for (the SDK's maps/, or HMGAPI_MAPS), as the car would plan it. A unit has the car's
# maps, a stand-in for each stock file they were made from (no car files), and the mods.
#   plan_test <mod dir> [<required mod dir>...]
# runs it all per car the mod's "system" names, as ok/FAIL/info lines (fail=1 on a FAIL):
#   no problems in the plan (mod.json, a required mod, conflicts; not the stand-ins' files),
#   the mod's "system" takes the unit (the unit has the features the mods ask for), HMG-API uses the car's map, every hook point the mod declares (and those in PLAN_POINTS,
#   points the mod reads by name) is mapped, its hooks load, the points it shares with other
#   mods (info), and its native recipes (hooks.native) parse and stay clear of the required
#   mods' recipes for the same library build. A required mod's folder needs only its
#   mod.json and what that names (its built folder, or its repo's).
# The parts, for checks of your own (R: the unit's root; out, rc: the last plan):
#   plan_cars <mod dir>                    "<map folder> <product>" per car the mod is for
#   plan_unit <map folder> <product> <mod dir>...   R becomes that car's unit; engine plan
#   plan_eng <engine args>                 the engine on that unit, as that car
#   plan_problems, plan_shared <mod id>    from $out; plan_unmapped <mod dir> [<point>...]
#   plan_system <mod dir>                  why the mod's "system" refuses the unit (if it does)
#   recipes_clear <mod dir> [<other mod dir>...]
MAPS=${HMGAPI_MAPS:-$HMGAPI_SDK/maps}
PLAN=$HMGAPI_SDK/test/plan.py
plan_cars() { python3 "$PLAN" cars "$MAPS" "$1"; }
plan_unit() {
    python3 "$PLAN" unit "$R" "$MAPS" "$@" || { echo "FAIL  plan_unit $*: no fake unit"; fail=1; return 1; }
    read PLAN_MAP PLAN_PRODUCT PLAN_ONEBIN < "$R/.plan-car"
    plan_eng plan
}
plan_eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR= HMGAPI_PRODUCT=$PLAN_PRODUCT HMGAPI_ONEBIN=$PLAN_ONEBIN \
        HMGAPI_LPATCH=$LPATCH java -cp "$ENGINE" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
# plan_system <mod dir>: nothing if the mod's "system" takes the unit, else why (and exit 1)
plan_system() { HMGAPI_ROOT=$R HMGAPI_PRODUCT=$PLAN_PRODUCT HMGAPI_ONEBIN=$PLAN_ONEBIN java -cp "$ENGINE" hmgapi.engine.SystemCheck "$1" 2>&1 | grep -v '^ok: '; [ $? = 1 ]; }
plan_problems() { printf '%s\n' "$out" | python3 "$PLAN" problems; }
plan_shared() { printf '%s\n' "$out" | python3 "$PLAN" shared "$1"; }
plan_unmapped() { python3 "$PLAN" unmapped "$R" "$@"; }
recipes_clear() { java -cp "$ENGINE:$HMGAPI_SDK/test/hmgapi-engine-test.jar" hmgapi.engine.RecipeCheck "$@"; }
plan_test() {
    _pm=$1; shift
    _pid=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$_pm/mod.json")
    _pneed=$(python3 "$PLAN" requires "$_pm" "$@" | sed -n 'H; ${x; s/\n/ /g; s/^ //; p; }')
    if [ -n "$_pneed" ]; then
        echo "FAIL  $_pid requires $_pneed: give the folder of each (plan_test <mod> <required mod>...)"; fail=1; return 0
    fi
    _pcars=$(plan_cars "$_pm") || { echo "FAIL  plan_cars $_pm"; fail=1; return 0; }
    [ -n "$_pcars" ] || echo "skip  $_pid: no car its \"system\" names has a hook map in $MAPS"
    _pR=${R-}; _pT=$(mktemp -d); R=$_pT/unit
    while read -r _pmap _pprod <&3; do
        [ -n "$_pmap" ] || continue
        _pl="$_pprod ($_pmap)"
        plan_unit "$_pmap" "$_pprod" "$_pm" "$@" || continue
        check "$_pl: no problems in the plan" "" "$(plan_problems)"
        # engine plan checks "system" only for mods with file changes; the mod loader, every mod's
        check "$_pl: $_pid's \"system\" takes this unit" "" "$(plan_system "$_pm" || true)"
        check "$_pl: HMG-API uses the car's map" "" "$(printf '%s\n' "$out" | grep '^hooks are off' || true)"
        check "$_pl: every hook point $_pid uses is mapped" "" "$(plan_unmapped "$_pm" ${PLAN_POINTS:-})"
        check "$_pl: $_pid's hooks load" "" "$(printf '%s\n' "$out" | grep "^hooks *$_pid won't load" || true)"
        plan_shared "$_pid" | sed "s#^#info  $_pl: shares #"
    done 3<<EOF_CARS
$_pcars
EOF_CARS
    rm -rf "$_pT"; R=$_pR
    set +e; _pr=$(recipes_clear "$_pm" "$@" 2>&1); _prc=$?; set -e
    [ -z "$_pr" ] || printf '%s\n' "$_pr"
    [ $_prc = 0 ] || fail=1
}
