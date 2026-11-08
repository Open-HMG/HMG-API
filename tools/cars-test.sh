#!/bin/sh
# Test of tools/cars.py and tools/cartrees.py on made-up trees and maps (a few bytes each,
# no car files): list (exact, partial, different, no map), where, the old .local files,
# and test (skips, a mod's test on every car with HMGAPI_CAR, FAIL's exit code, --report).
set -e
cd "$(dirname "$0")/.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
export HMGAPI_CARS=$T/cars HMGAPI_TEST_MAPS=$T/maps HMGAPI_TEST_LOCAL_DIR=$T/local BAKSMALI=$T/none
C() { set +e; out=$(python3 tools/cars.py "$@" 2>&1); rc=$?; set -e; }
md5() { md5sum "$1" | cut -c1-32; }
mkdir -p $T/local $T/cars

C list; has "no trees: says so" "no car trees in $T/cars" "$out"; check "... exit 0" 0 $rc
C ids; check "no trees: no ids" "" "$out"

# a made-up stock tree, and maps for two cars made from it
mkdir -p $T/stock/app $T/stock/framework   # no bin/app_process: the installer suites skip, built or not
echo app > $T/stock/app/A.odex; echo fw > $T/stock/framework/f.odex
for m in kia/one/usa/260507 kia/two/usa/260507; do
    mkdir -p $T/maps/$m
    printf '{"products": ["ONE.USA."], "files": {"/system/app/A.odex": "%s", "/system/framework/f.odex": "%s"}}\n' \
        "$(md5 $T/stock/app/A.odex)" "$(md5 $T/stock/framework/f.odex)" > $T/maps/$m/car.json
done
printf '{"products": ["TWO.USA."], "files": {"/system/app/A.odex": "%s", "/system/framework/f.odex": "%s", "/system/app/B.odex": "00"}}\n' \
    "$(md5 $T/stock/app/A.odex)" "$(md5 $T/stock/framework/f.odex)" > $T/maps/kia/two/usa/260507/car.json
mkdir -p $T/cars/kia/one/usa/260507 $T/cars/kia/two/usa/260507 $T/cars/kia/one/can/260206/system/app
ln -s $T/stock $T/cars/kia/one/usa/260507/system
cp -r $T/stock/app $T/stock/framework $T/cars/kia/two/usa/260507/   # no system/: the folder itself
echo changed > $T/cars/kia/one/can/260206/system/app/A.odex
mkdir -p $T/maps/kia/one/can/260206
printf '{"products": ["ONE.CAN."], "files": {"/system/app/A.odex": "%s", "/system/framework/f.odex": "11"}}\n' "$(md5 $T/stock/app/A.odex)" > $T/maps/kia/one/can/260206/car.json
mkdir -p $T/cars/kia/three/usa/260903/system/app; echo app > $T/cars/kia/three/usa/260903/system/app/A.odex

C list
has "exact" "kia/one/usa/260507         exact (2/2 files)" "$out"
has "partial: the files it hasn't" "kia/two/usa/260507         partial (2/3 files; not in the tree: B.odex)" "$out"
has "different: the files that differ" "kia/one/can/260206         different (1/2 files differ: A.odex; not stock, or another firmware)" "$out"
has "no map" "kia/three/usa/260903       no map (maps/kia/three/usa/260903)" "$out"
C ids; check "ids" "kia/one/can/260206 kia/one/usa/260507 kia/three/usa/260903 kia/two/usa/260507" "$(echo $out)"

# where: a tree laid out some other way
C where $T/stock
has "where: the map it matches" "# $T/stock: kia/one/usa/260507, exact" "$out"
has "... ln" "ln -s $T/stock ~/hmg-cars/kia/one/usa/260507/system" "$(HMGAPI_CARS= python3 tools/cars.py where $T/stock)"
mkdir -p $T/other/app; echo x > $T/other/app/A.odex
C where $T/other; has "where: no match" "fits no map" "$out"
mkdir -p $T/part/framework $T/partA/app; cp $T/stock/framework/f.odex $T/part/framework/; cp $T/stock/app/A.odex $T/partA/app/
C where $T/part $T/stock
has "where: two trees for a car: the better one linked" 'ln -s '$T'/stock $HMGAPI_CARS/kia/one/usa/260507/system' "$out"
has "... the other not" "# $T/part: kia/one/usa/260507, but $T/stock fits it better: not linked" "$out"
C where $T/partA
has "where: fits two maps equally" "fits kia/one/usa/260507 and kia/one/can/260206 equally" "$out"

# the old .local files: a label is the car its tree matches; a map folder replaces that car's tree
rm $T/cars/kia/one/usa/260507/system
printf '# old\nev6-old %s\n' $T/stock > $T/local/hook-trees.local
C list
has "hook-trees.local: deprecated" "note  tools/hook-trees.local is deprecated" "$out"
has "... its label is the map it matches" "kia/one/usa/260507         exact (2/2 files)" "$out"
has "... where from" "from tools/hook-trees.local: $T/stock" "$out"
printf 'kia/one/can/260206 %s\n' $T/stock > $T/local/map-trees.local
C list; has "map-trees.local replaces a car's tree" "kia/one/can/260206         different (1/2 files differ: f.odex; not stock, or another firmware)
                             from tools/map-trees.local: $T/stock" "$out"
hasnt "... and the same tree isn't listed twice" "ev6-old" "$out"
rm $T/local/*.local; ln -s $T/stock $T/cars/kia/one/usa/260507/system

# test: what can't run is a skip; a mod's tests on every car, with that car
mkdir -p $T/sdk/test $T/sdk/bin $T/modrepo/m
: > $T/sdk/test/lib.sh; printf 'mkdir -p "$2"\n' > $T/sdk/bin/build-mod.sh
printf '{"id": "m", "system": ["ONE."]}\n' > $T/modrepo/m/mod.json
printf 'echo "car $HMGAPI_CAR_ID $HMGAPI_CAR_PRODUCTS"\n[ -f "$HMGAPI_CAR/app/A.odex" ] && [ "$(cat $HMGAPI_CAR/app/A.odex)" = app ]\n' > $T/modrepo/m/test.sh
export HMGAPI_SDK=$T/sdk
C test --mods $T/modrepo --logs $T/logs --only kia/one/usa/260507,kia/two/usa/260507,kia/three/usa/260903
check "all ok or skip: exit 0" 0 $rc
has "maps: skip without baksmali" "skip  kia/one/usa/260507 maps: no baksmali" "$out"
has "install: skip without bin/app_process" "skip  kia/one/usa/260507 install: no bin/app_process" "$out"
has "mod: ok on a car in its system" "ok    kia/one/usa/260507 mod:m" "$out"
has "mod: skip on one that isn't" "skip  kia/two/usa/260507 mod:m: not in its \"system\"" "$out"
has "mod: a car with no map: runs (no products to rule it out)" "kia/three/usa/260903 mod:m" "$out"
has "mod: got the car" "car kia/one/usa/260507 ONE.USA." "$(cat $T/logs/kia-one-usa-260507/mod-m.log)"
C test --mods $T/modrepo --logs $T/logs --suites mod:m --report
check "a FAIL: exit 1" 1 $rc
has "... the car that failed" "| kia/one/can/260206 | different (1/2 files differ: A.odex; not stock, or another firmware) | " "$out"
has "report: Markdown table" "| car | stock files (vs car.json) | digest | mod:m |
|---|---|---|---|
" "$out"
has "report: the exact car" "| kia/one/usa/260507 | exact (2/2 files) | " "$out"
hasnt "report: no paths from this machine" "$T" "$out"
has "report: the count" "4 car(s), 1 suite(s): 1 FAIL" "$out"
d1=$(echo "$out" | sed -n 's/^| kia\/one\/usa\/260507 | [^|]* | \([0-9a-f]*\) .*/\1/p')
check "digest: 12 hex digits" 12 ${#d1}
C test --suites nope; has "unknown suite" "no suite nope" "$out"
exit $fail
