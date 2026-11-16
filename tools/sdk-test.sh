#!/bin/sh
# Off-car test of the mod SDK (./build.sh sdk): it has what docs/mod-repos.md section 3
# lists, and a mod repo outside HMG-API builds, tests, checks and packages the example
# mods with it alone, to the same bytes as this repo's own build and tools.
# Needs the build (./build.sh) and the Android SDK.
set -e
cd "$(dirname "$0")/.."
REPO=$PWD
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
md5() { md5sum < "$1" | cut -c1-32; }
# a mod package's files, all but its signature (ECDSA: a new one each time it's signed)
pkg() { (rm -rf $T/u && mkdir $T/u && cd $T/u && unzip -q "$1" && find . -type f ! -path ./META-HMGAPI/signature | sort | xargs md5sum); }

sh build.sh sdk > $T/build.log 2>&1 || { cat $T/build.log; exit 1; }
V=$(sed -n 's/.*HMGAPI_VERSION = "\(.*\)";/\1/p' core/runtime/java/hmgapi/engine/Engine.java)
Z=out/dist/hmgapi-mod-sdk-$V.zip
check "zip named by the version" yes "$([ -f $Z ] && echo yes)"
L=$(unzip -Z1 $Z | sort)
missing=""
for f in lib/hmgapi-runtime.jar lib/hmgapi-runtime-stubs.jar lib/hmgapi-client.jar lib/hmgapi-engine.jar bin/lpatch-host \
    src/lpatch/lpatch.c src/lpatch/build.sh bin/build-mod.sh bin/mkmodpkg.sh bin/mknemesismod.sh bin/nemesis-meta.py \
    bin/nemesis-version.py nemesis/mod/NEMESIS.data.in nemesis/mod/README.txt nemesis/mod/scripts/preinstall.sh \
    nemesis/mod/scripts/postinstall.sh nemesis/mod/scripts/preuninstall.sh nemesis/bin/open.sh bin/check-scripts.sh \
    unit-commands.txt bin/mkrecipe.py bin/mkdelta.py bin/mkmemrecipe.py bin/mkicon.sh bin/mod-draft.py test/lib.sh \
    points.txt VERSION bin/check-points.py bin/env.sh test/hmgapi-engine-test.jar; do
    echo "$L" | grep -qx "$f" || missing="$missing $f"
done
check "every part the doc lists" "" "$missing"
for f in core/hookpoints/*.json; do echo "$L" | grep -qx "catalogue/${f##*/}" || missing="$missing $f"; done
check "the hook point catalogue" "" "$missing"
check "no Android SDK or manufacturer files (android.jar, d8, apk, odex, so)" "" "$(echo "$L" | grep -E 'android\.jar|/d8$|\.apk$|\.odex$|\.so$' || true)"
check "no keys" "" "$(echo "$L" | grep -E '\.(key|p12|pass)$' || true)"
mkdir -p $T/sdk && unzip -q $Z -d $T/sdk
check "VERSION is HMG-API's" "$V" "$(cat $T/sdk/VERSION)"
check "points.txt: every point in the catalogue" \
    "$(python3 -c 'import glob,json; print(len([p for f in glob.glob("core/hookpoints/*.json") for p in json.load(open(f))["points"]]))')" \
    "$(grep -c . $T/sdk/points.txt)"
check "the scripts are executable" yes "$([ -x $T/sdk/bin/build-mod.sh ] && [ -x $T/sdk/bin/mkmodpkg.sh ] && [ -x $T/sdk/bin/lpatch-host ] && echo yes)"
check "MemRecipeTest runs from the SDK (synthetic libraries)" 0 "$(java -cp $T/sdk/lib/hmgapi-engine.jar:$T/sdk/test/hmgapi-engine-test.jar hmgapi.engine.MemRecipeTest > /dev/null; echo $?)"
check "the engine jar runs on its own" yes "$(java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing keygen $T/author.key > /dev/null && [ -s $T/author.key ] && echo yes)"

# ---- a mod repo outside HMG-API (mods in <id>/ folders), the SDK alone, run from its root
M=$T/mods; mkdir -p $M
cp -r mods/examples/hello mods/examples/hello-api mods/examples/hello-user mods/examples/side-panel $M/
cd $M
S=$T/sdk/bin
o=$(sh $S/build-mod.sh hello $T/b/hello $T/w/hello 2>&1) || echo "$o"
check "hello builds" yes "$([ -f $T/b/hello/hooks.jar ] && echo yes)"
check "... the same hooks.jar as build.sh mods" "$(md5 $REPO/out/mods/hello/hooks.jar)" "$(md5 $T/b/hello/hooks.jar)"
o=$(sh $S/build-mod.sh hello-api $T/b/hello-api $T/w/hello-api 2>&1) || echo "$o"
o=$(HMGAPI_MOD_CP=$T/w/hello-api/classes sh $S/build-mod.sh hello-user $T/b/hello-user $T/w/hello-user 2>&1) || echo "$o"
check "a mod that requires another builds against it (HMGAPI_MOD_CP)" "$(md5 $REPO/out/mods/hello-user/hooks.jar)" "$(md5 $T/b/hello-user/hooks.jar 2>/dev/null)"
o=$(sh $S/build-mod.sh side-panel $T/b/side-panel 2>&1) || echo "$o"
APK=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apps"][0]["apk"])' side-panel/mod.json)
check "an app part builds, hmgapi-client inside" yes "$(unzip -p $T/b/side-panel/$APK classes.dex 2>/dev/null | grep -q 'Lhmgapi/' && echo yes)"
check "... the mod's json beside it" yes "$([ -f $T/b/side-panel/mod.json ] && echo yes)"

# hello's own test.sh, as the mod workflow runs it
o=$(HMGAPI_SDK=$T/sdk HMGAPI_MOD_BUILD=$T/b/hello sh hello/test.sh 2>&1) && r=0 || r=$?
check "hello's test.sh passes on the SDK" 0 $r
[ $r = 0 ] || echo "$o"
check "... with its checks" yes "$([ "$(echo "$o" | grep -c '^ok')" -ge 5 ] && echo yes)"

# ---- packaging: the same package as tools/mkmodpkg.sh in HMG-API, with the same key
o=$(HMGAPI_MOD_BUILD=$T/b/hello sh $S/mkmodpkg.sh hello $T/author.key 2>&1) || echo "$o"
check "mkmodpkg.sh: ./out/packages of the mod repo" yes "$([ -f $M/out/packages/hello-1.0.0.zip ] && echo yes)"
check "... test.sh and src left out" "" "$(unzip -Z1 $M/out/packages/hello-1.0.0.zip | grep -E '^(test\.sh|src/)' || true)"
cp $M/out/packages/hello-1.0.0.zip $T/prebuilt.zip
check "... its signature verifies" yes "$(rm -rf $T/v && mkdir $T/v && unzip -q $T/prebuilt.zip -d $T/v && java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing verify $T/v > /dev/null && echo yes)"
o=$(sh $S/mkmodpkg.sh hello $T/author.key 2>&1) || echo "$o"
check "... building it itself (no HMGAPI_MOD_BUILD) gives the same files" "$(pkg $T/prebuilt.zip)" "$(pkg $M/out/packages/hello-1.0.0.zip)"
(cd $REPO && tools/mkmodpkg.sh mods/examples/hello $T/author.key > /dev/null)
check "... and the same files as HMG-API's tools/mkmodpkg.sh" "$(pkg $REPO/out/packages/hello-1.0.0.zip)" "$(pkg $M/out/packages/hello-1.0.0.zip)"
# a signing key left in the mod's folder never goes into the (public) package
cp -r hello leaky; cp $T/author.key leaky/notes.txt; mkdir leaky/app2; echo x > leaky/app2/release.p12
before=$(md5sum < $M/out/packages/hello-1.0.0.zip)
o=$(HMGAPI_MOD_BUILD=$T/b/hello sh $S/mkmodpkg.sh leaky $T/author.key 2>&1) && r=0 || r=$?
check "mkmodpkg.sh: a mod folder with keys in it isn't packaged" 1 $r
check "... it names the author key's copy (whatever its name) and the keystore" "  app2/release.p12|  notes.txt|" "$(echo "$o" | grep '^  ' | tr '\n' '|')"
check "... and no package was written" "$before" "$(md5sum < $M/out/packages/hello-1.0.0.zip)"
rm -rf leaky
cp $M/out/packages/hello-1.0.0.zip $REPO/out/packages/hello-1.0.0.zip   # one package for both NEMESIS builds below
o=$(sh $S/mknemesismod.sh out/packages/hello-1.0.0.zip 2>&1) || echo "$o"
check "mknemesismod.sh: ./out/dist" yes "$([ -f $M/out/dist/hello-1.0.0-nemesis-package.zip ] && [ -f $M/out/dist/hello-1.0.0-nemesis.zip ] && echo yes)"
(cd $REPO && tools/mknemesismod.sh out/packages/hello-1.0.0.zip $T/core-dist > /dev/null)
check "... the same NEMESIS package as HMG-API's" "$(md5 $T/core-dist/hello-1.0.0-nemesis-package.zip)" "$(md5 $M/out/dist/hello-1.0.0-nemesis-package.zip)"

# zipdir (tools/mkinstallers.sh, tools/mknemesismod.sh) and the other zip steps: the same zip bytes in any time zone and locale
ZD=$(grep '^zipdir()' $REPO/tools/mkinstallers.sh)
check "zipdir: the same line in mkinstallers.sh and mknemesismod.sh" "$ZD" "$(grep '^zipdir()' $REPO/tools/mknemesismod.sh)"
if locale -a 2>/dev/null | grep -qiE '^en_US\.utf-?8$' && [ -f /usr/share/zoneinfo/America/Los_Angeles ]; then
    for e in utc:"TZ=UTC LC_ALL=C" la:"TZ=America/Los_Angeles LC_ALL=en_US.UTF-8"; do
        d=$T/zd-${e%%:*}; e=${e#*:}
        (cd $REPO && env $e tools/mknemesismod.sh out/packages/hello-1.0.0.zip $d > /dev/null)
        # mkinstallers.sh's line, on names that C and en_US sort apart (case, punctuation)
        mkdir -p $d/tree/sub && for f in B.txt a.txt _x a-b ab sub/Z sub/y; do echo $f > $d/tree/$f; done
        env $e sh -c "$ZD; zipdir $d/tree $d/tree.zip"
        # the same names through build.sh's SDK jars and zip, tools/mkmodpkg.sh's and tools/mkrelease.sh's zip lines
        env $e sh -c "$(grep '^    jarof()' $REPO/build.sh); jarof $d/tree $d/jar.zip"
        env $e S=$d/tree Z=$d/sdk.zip sh -c "$(grep '^    (cd "\$S" && ' $REPO/build.sh)"
        env $e W=$d ID=tree Z=$d/pkg.zip sh -c "$(grep '^(cd "\$W/\$ID" && ' $REPO/tools/mkmodpkg.sh)"
        mkdir -p $d/rel && cp -R $d/tree $d/rel/hmgapi-0
        env $e W=$d/rel V=0 Z=$d/rel.zip sh -c "$(grep '^(cd "\$W" && ' $REPO/tools/mkrelease.sh)"
    done
    for z in hello-1.0.0-nemesis-package.zip hello-1.0.0-nemesis.zip tree.zip jar.zip sdk.zip pkg.zip rel.zip; do
        check "zipdir: $z the same in UTC/C and Los Angeles/en_US.UTF-8" "$(md5 $T/zd-utc/$z)" "$(md5 $T/zd-la/$z)"
    done
else
    echo "skip  zipdir across time zones and locales (needs the en_US.UTF-8 locale and America/Los_Angeles)"
fi
rm -f $REPO/out/packages/hello-1.0.0.zip

# "nemesis_conflicts" in mod.json: depends= refuses them (no conflicts.txt in the SDK)
mkdir -p $T/c/conf && printf '{"id": "conf", "version": "1.0.0", "hmgapi": ">=1.0.0", "nemesis_conflicts": ["other.pkg", "legacy.pkg"]}\n' > $T/c/conf/mod.json
(cd $T/c && sh $S/mkmodpkg.sh conf $T/author.key > /dev/null && sh $S/mknemesismod.sh out/packages/conf-1.0.0.zip > /dev/null)
check "nemesis_conflicts go in depends as !id" "depends=hmgapi@>=1.0,!other.pkg,!legacy.pkg" \
    "$(unzip -p $T/c/out/dist/conf-1.0.0-nemesis-package.zip NEMESIS.data | grep '^depends=' | tr -d '"')"
printf '{"id": "conf", "version": "1.0.0", "nemesis_conflicts": ["hmgapi"]}\n' > $T/c/conf/mod.json
(cd $T/c && sh $S/mkmodpkg.sh conf $T/author.key > /dev/null)
set +e; o=$(cd $T/c && sh $S/mknemesismod.sh out/packages/conf-1.0.0.zip 2>&1); r=$?; set -e
check "... refusing hmgapi itself: refused" 1 "$([ $r -ne 0 ] && echo 1)"
has "... says why" "both needs and refuses hmgapi" "$o"

# ---- the plan test (test/lib.sh plan_test): HMG-API's maps in the SDK, fake units built from them
check "the maps, without the repo's coverage baseline" "$(cd $REPO/maps && find . -type f -name '*.json' ! -name coverage.json | sort)" "$(cd $T/sdk/maps && find . -type f | sort)"
P=$T/plan; mkdir -p $P/base/recipes $P/user/recipes
H=0123456789abcdef0123456789abcdef
rcp() {   # rcp <file> <id> <stock md5> <verify offset>: a one-region recipe for libx.so
    printf '# generated by mkmemrecipe.py from stock %s -> patched %s\nid %s\nfile /system/lib/libx.so\nverify %s 16 %s %s\npatch %s 00\n' \
        "$3" "$H" "$2" "$4" "$H" "$H" "$4" > "$1"
}
rcp $P/base/recipes/b.recipe base-x 11111111$H 0x100
rcp $P/user/recipes/same.recipe user-same 11111111$H 0x108
rcp $P/user/recipes/other.recipe user-other 22222222$H 0x100
printf '{"id": "base", "version": "1.0.0", "system": {"product": "CVEV.USA."}, "hooks": {"jar": "hooks.jar", "entry": "b.B",
  "points": {"gpm.navigation_status.next_turn": "observe"}, "native": {"libx.so": ["recipes/b.recipe"]}}}\n' > $P/base/mod.json
printf '{"id": "user", "version": "1.0.0", "requires": ["base ^1.0"], "system": {"product": "CVEV.USA."}, "hooks": {"jar": "hooks.jar", "entry": "u.U",
  "points": {"gpm.navigation_status.next_turn": "observe", "gpm.navigation_status.status": "change"},
  "native": {"libx.so": ["recipes/other.recipe", "recipes/same.recipe"]}}}\n' > $P/user/mod.json
cat > $P/t.sh <<'EOF'
set -e
. "$HMGAPI_SDK/test/lib.sh"
plan_test "$@"
exit $fail
EOF
pt() { set +e; o=$(HMGAPI_SDK=$T/sdk sh $P/t.sh "$@" 2>&1); r=$?; set -e; }
pt $P/user $P/base
check "plan_test: an overlap with a required mod's recipe fails" 1 $r
has "... the car its \"system\" names, with the map's product" "ok    CVEV.USA.0000.V015.012.260507 (kia/ev6/usa/260507): no problems in the plan" "$o"
check "... only that car" 1 "$(echo "$o" | grep -c 'no problems in the plan')"
has "... HMG-API uses the car's map" "(kia/ev6/usa/260507): HMG-API uses the car's map" "$o"
has "... every point mapped" "ok    CVEV.USA.0000.V015.012.260507 (kia/ev6/usa/260507): every hook point user uses is mapped" "$o"
has "... its hooks load" "ok    CVEV.USA.0000.V015.012.260507 (kia/ev6/usa/260507): user's hooks load" "$o"
has "... the point it shares, and with whom" "info  CVEV.USA.0000.V015.012.260507 (kia/ev6/usa/260507): shares gpm.navigation_status.next_turn: base (observe)" "$o"
has "... its recipes parse" "ok    user recipes/same.recipe parses" "$o"
has "... a recipe for another build isn't compared" "info  user user-other and base base-x (libx.so): other library builds" "$o"
has "... the overlap, named" "FAIL  user user-same and base base-x (libx.so): overlap at 0x108-0x110" "$o"
rcp $P/user/recipes/same.recipe user-same 11111111$H 0x110
pt $P/user $P/base
check "... clear of it: passes" 0 $r
[ $r = 0 ] || echo "$o"
has "... says so" "ok    user user-same and base base-x (libx.so): no region overlaps" "$o"
pt $P/user
check "plan_test without a required mod's folder: fails" 1 $r
has "... names it" "FAIL  user requires base: give the folder of each" "$o"
cp $P/user/mod.json $P/user.json
sed 's/"product": "CVEV.USA."/"product": "CVEV.USA.", "features": ["hmgapi.wifi", "com.example.x"]/' $P/user.json > $P/user/mod.json
pt $P/user $P/base
check "... the features the mods ask for: the fake unit has them" 0 $r
has "... and the mod's \"system\" is checked there" "ok    CVEV.USA.0000.V015.012.260507 (kia/ev6/usa/260507): user's \"system\" takes this unit" "$o"
sed 's/"product": "CVEV.USA."/"product": "CVEV.USA.", "android": "4.4.3"/' $P/user.json > $P/user/mod.json
pt $P/user $P/base
check "... a \"system\" that refuses the unit otherwise: fails" 1 $r
has "... says why" "takes this unit: wanted [], got [user: needs android 4.4.3" "$o"
cp $P/user.json $P/user/mod.json
python3 - $P/user/mod.json <<'PY2'
import json, sys
m = json.load(open(sys.argv[1])); m["hooks"]["points"]["gpm.no_such_point"] = "observe"
m["system"] = {"model": "NEEV23", "region": "USA"}
json.dump(m, open(sys.argv[1], "w"))
PY2
o=$(HMGAPI_SDK=$T/sdk sh -c '. "$HMGAPI_SDK/test/lib.sh"; plan_cars "$1"' - $P/user)
check "plan_cars: each map and product the \"system\" names" "hyundai/neev/usa/260507 NEEV23.USA.0000.V000.000.260507|hyundai/neev23/usa/260903 NEEV23.USA.0000.V000.000.260903|" "$(echo "$o" | tr '\n' '|')"
printf '{"id": "base", "version": "1.0.0", "patches": "patches.json"}\n' > $P/base/mod.json
printf '{"changes": [{"path": "/system/lib/libx.so", "type": "delta", "delta": "x.delta", "stock": ["%s"], "result": "%s"}]}\n' $H $H > $P/base/patches.json
printf '{"changes": [{"type": "prop", "key": "ro.x", "value": "1"}]}\n' > $P/user/patches.json
pt $P/user $P/base
check "... a mod.json mistake, a point HMG-API hasn't: fails" 1 $r
has "... the mistake is a problem" "got [user: prop ro.x: only persist.* properties" "$o"
check "... the fake unit's stand-in files aren't" "" "$(echo "$o" | grep 'libx.so: not on this head unit' || true)"
has "... names the point" "gpm.no_such_point: not in this car's map" "$o"

# ---- bin/hmgapi-mod: a mod repo's loop, from its root, with the SDK alone
HM="sh $T/sdk/bin/hmgapi-mod"
mkdir -p $T/repo && cd $T/repo
o=$($HM new my-mod --name "My mod" --author Tester 2>&1) || echo "$o"
check "new: the mod, its test, the SDK pin, CI and .gitignore" \
    "./.github/workflows/ci.yml ./.gitignore ./hmgapi-sdk.json ./my-mod/mod.json ./my-mod/src/mod/my_mod/MyMod.java ./my-mod/test.sh" \
    "$(find . -type f | sort | tr '\n' ' ' | sed 's/ $//')"
check "... mod.json: id, name, author, the SDK's release as the floor, its entry class" "my-mod|My mod|Tester|>=${V%%-*}|mod.my_mod.MyMod" \
    "$(python3 -c 'import json; m=json.load(open("my-mod/mod.json")); print("|".join([m["id"], m["name"], m["authors"][0], m["hmgapi"], m["hooks"]["entry"]]))')"
check "... CI calls HMG-API's mod workflow at the SDK's release" "    uses: Open-HMG/HMG-API/.github/workflows/mod.yml@v$V" "$(grep 'uses:' .github/workflows/ci.yml)"
set +e; o=$($HM new my-mod --author Tester 2>&1); r=$?; set -e
check "... an existing folder: refused" "2|hmgapi-mod: my-mod already exists" "$r|$o"
set +e; o=$($HM new My_Mod --author Tester 2>&1); r=$?; set -e
check "... not an id: refused" 2 $r
o=$($HM build 2>&1) && r=0 || r=$?
check "build: the one mod here, into out/build/<id>" "0|yes" "$r|$([ -f out/build/my-mod/hooks.jar ] && echo yes)"
check "... the same hooks.jar as bin/build-mod.sh" "$(sh $S/build-mod.sh my-mod $T/bm >/dev/null 2>&1; md5 $T/bm/hooks.jar)" "$(md5 out/build/my-mod/hooks.jar)"
o=$($HM check 2>&1) && r=0 || r=$?
check "check: check-points.py and check-scripts.sh pass" 0 $r
has "... check-points.py ran" 'my-mod: "hmgapi"' "$o"
printf '#!/system/bin/sh\necho x | tr x y\n' > my-mod/s.sh
python3 -c 'import json; m=json.load(open("my-mod/mod.json")); m["services"]=[{"name": "s", "script": "s.sh"}]; json.dump(m, open("my-mod/mod.json", "w"))'
set +e; o=$($HM check 2>&1); r=$?; set -e
check "... a script calling tr: fails" 1 $r
has "... names it" "tr" "$o"
rm my-mod/s.sh; python3 -c 'import json; m=json.load(open("my-mod/mod.json")); del m["services"]; json.dump(m, open("my-mod/mod.json", "w"))'
python3 -c 'import json; m=json.load(open("my-mod/mod.json")); m["system"]={"product": "CVEV.USA."}; json.dump(m, open("my-mod/mod.json", "w"))'
o=$($HM test 2>&1) && r=0 || r=$?
check "test: builds, then its test.sh (the plan test)" 0 $r
[ $r = 0 ] || echo "$o"
has "... on the cars its \"system\" names" "ok    CVEV.USA.0000.V015.012.260507 (kia/ev6/usa/260507): my-mod's hooks load" "$o"
has "... no mods required" 'no "requires" in mod.json' "$o"
mkdir -p $T/nt && cp -r my-mod $T/nt/ && rm $T/nt/my-mod/test.sh
o=$(cd $T/nt && $HM test 2>&1) && r=0 || r=$?
check "... without a test.sh: the plan test" "0|yes" "$r|$(echo "$o" | grep -q "my-mod's hooks load" && echo yes)"
mkdir $T/empty; set +e; o=$(cd $T/empty && $HM build 2>&1); r=$?; set -e
check "no mod here: says so" "2" "$r"
has "... and what to do" "name the mod's folder" "$o"

# deps: a catalogue (catalog/ and maps/authors.json, as HMG-API has them) with a signed base mod in it
C=$T/core; mkdir -p $C/catalog $C/maps $C/dl $T/repo/base
java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing keygen $T/other.key > /dev/null
UUID=$(java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing uuid $T/author.key)
printf '{"id": "base", "version": "1.2.0", "hmgapi": ">=1.0.0", "system": {"product": "CVEV.USA."}}\n' > base/mod.json
(OUT=$T/bo sh $S/mkmodpkg.sh base $T/author.key > /dev/null) && cp $T/bo/packages/base-1.2.0.zip $C/dl/
cat > $T/entry.py <<'EOF'
import hashlib, json, sys
d, uuid, url, ver = sys.argv[1:5]
json.dump({"id": "base", "name": "Base", "description": "x", "authors": ["Tester"], "uuid": uuid, "version": ver,
           "repo": "https://example.invalid/base", "download": url,
           "sha256": hashlib.sha256(open(sys.argv[5], "rb").read()).hexdigest()}, open(d + "/catalog/base.json", "w"))
json.dump({"authors": {uuid: {"name": "Tester"}}}, open(d + "/maps/authors.json", "w"))
EOF
python3 $T/entry.py $C $UUID file://$C/dl/base-1.2.0.zip 1.2.0 $C/dl/base-1.2.0.zip
rm -rf base
python3 -c 'import json; m=json.load(open("my-mod/mod.json")); m["requires"]=["base ^1.1"]; json.dump(m, open("my-mod/mod.json", "w"))'
export HMGAPI_CATALOG=$C/catalog
o=$($HM deps 2>$T/err) && r=0 || r=$?
TP=$(cd $T && pwd -P)   # mktemp's folder, as the tools see it (macOS: /private/var/...)
check "deps: the required mod from the catalogue, its folder printed" "0|$TP/repo/.hmgapi/deps/base-1.2.0" "$r|$o"
has "... SHA-256 and signature checked" "ok    base 1.2.0: downloaded from the catalogue, SHA-256 and signature match (author key $UUID)" "$(cat $T/err)"
check "... the mod folder, as signed" yes "$(java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing verify $o $UUID > /dev/null && echo yes)"
mv $C/dl/base-1.2.0.zip $C/dl/away.zip
o=$($HM deps 2>$T/err) && r=0 || r=$?
check "... next time from the cache (nothing to download)" "0|ok    base 1.2.0: cached ($TP/repo/.hmgapi/deps/base-1.2.0.zip)" "$r|$(cat $T/err)"
mv $C/dl/away.zip $C/dl/base-1.2.0.zip
o=$($HM test 2>&1) && r=0 || r=$?
check "test: with the required mod beside it" 0 $r
has "... in the plan" "ok    CVEV.USA.0000.V015.012.260507 (kia/ev6/usa/260507): my-mod's hooks load" "$o"
rm -rf .hmgapi
dep() { set +e; o=$($HM deps 2>&1); r=$?; set -e; }
printf 'tampered' >> $C/dl/base-1.2.0.zip
dep; check "... a download that isn't the catalogue's SHA-256: fails" 1 $r
has "... says so" "base 1.2.0: the download's SHA-256 is" "$o"
check "... nothing cached" "" "$(ls .hmgapi/deps)"
cp $T/bo/packages/base-1.2.0.zip $C/dl/
OTHER=$(java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing uuid $T/other.key)
python3 $T/entry.py $C $OTHER file://$C/dl/base-1.2.0.zip 1.2.0 $C/dl/base-1.2.0.zip
dep; check "... signed with another key than the entry's: fails" 1 $r
has "... names the key" "$OTHER" "$o"
python3 $T/entry.py $C $UUID file://$C/dl/base-1.2.0.zip 1.2.0 $C/dl/base-1.2.0.zip
printf '{"authors": {}}\n' > $C/maps/authors.json
dep; check "... an author key HMG-API doesn't list: fails" 1 $r
has "... says so" "isn't on HMG-API's author list" "$o"
python3 $T/entry.py $C $UUID file://$C/dl/base-1.2.0.zip 1.2.0 $C/dl/base-1.2.0.zip
python3 -c 'import json; m=json.load(open("my-mod/mod.json")); m["requires"]=["base >=2.0.0-rc.1"]; json.dump(m, open("my-mod/mod.json", "w"))'
dep; check "... a range the catalogue's version isn't in: fails" 1 $r
has "... says which, and how to pin a pre-release" "my-mod requires base >=2.0.0-rc.1, but the catalogue has 1.2.0 (a pre-release can be pinned: hmgapi-deps.txt)" "$o"
echo "base 2.0.0-rc.1 file://$C/dl/base-1.2.0.zip $(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' $C/dl/base-1.2.0.zip)" > hmgapi-deps.txt
dep; check "... a pin outside the mod's own repo: refused" 1 $r
has "... says where it must be" "must be a release of the mod's own repo (https://example.invalid/base/releases/download/...)" "$o"
python3 -c 'import json; m=json.load(open("my-mod/mod.json")); m["requires"]=["nothere"]; json.dump(m, open("my-mod/mod.json", "w"))'
rm hmgapi-deps.txt
dep; check "... a mod not in the catalogue: fails" 1 $r
has "... says so" "my-mod requires nothere, which isn't in HMG-API's catalogue" "$o"
python3 -c 'import json; m=json.load(open("my-mod/mod.json")); m["requires"]=["base ^1.1"]; json.dump(m, open("my-mod/mod.json", "w"))'
unset HMGAPI_CATALOG

# package: the signed package and the NEMESIS one
o=$($HM package --key $T/author.key 2>&1) && r=0 || r=$?
check "package: out/packages and out/dist" "0|yes" "$r|$([ -f out/packages/my-mod-0.1.0.zip ] && [ -f out/dist/my-mod-0.1.0-nemesis-package.zip ] && echo yes)"
set +e; o=$($HM package 2>&1); r=$?; set -e
has "... without --key: says how to make one" "Signing keygen" "$o"

# the adb commands, against a fake adb: it runs each "adb shell" line here with a fake hmgapid,
# /data/ under $FK, and drops the link once during apply, as adbd's restart does
FK=$T/fake; mkdir -p $FK/bin $FK/data/local/tmp $FK/data/hmgapi/settings $FK/data/hmgapi/logs/hmgapi
cat > $FK/bin/adb <<EOF
#!/bin/sh
FK=$FK
EOF
cat >> $FK/bin/adb <<'EOF'
echo "adb $*" >> $FK/adb.log
case $1 in
get-state) echo device ;;
push) cp "$2" "$FK$3" ;;
shell)
    shift; c=$(printf '%s' "$*" | sed "s#/data/#$FK/data/#g")   # adb joins its arguments, the unit's shell splits them
    if [ -f $FK/down ]; then rm $FK/down; echo "error: device offline" >&2; exit 1; fi
    case $c in
    *"engine apply"*)
        [ -f $FK/drop ] || { PATH=$FK/bin:$PATH sh -c "$c"; exit 0; }
        rm $FK/drop; PATH=$FK/bin:$PATH hmgapid engine apply; touch $FK/down; exit 0 ;;   # no exit code: the link dropped
    esac
    PATH=$FK/bin:$PATH sh -c "$c" | sed 's/$/\r/' ;;   # adb shell's line ends
esac
EOF
cat > $FK/bin/hmgapid <<'EOF'
#!/bin/sh
FK=${0%/bin/hmgapid}
echo "hmgapid $*" >> $FK/hmgapid.log
case $1 in
version) echo "hmgapid 1.1.0" ;;
engine) case $2 in install) echo "installed $3" ;; apply) echo "change ..."; echo "applied" ;; status) echo "HMG-API owns no files" ;; esac ;;
reboot) echo restarting ;;
setting-set) printf '%s' "$3" > $FK/data/hmgapi/settings/$2.json; echo saved ;;
setting-get) cat $FK/data/hmgapi/settings/$2.json ;;
service-log) echo "log of $2/$3" ;;
sh) sh -c "$2" ;;
*) exit 2 ;;
esac
EOF
chmod 755 $FK/bin/adb $FK/bin/hmgapid
export ADB=$FK/bin/adb HMGAPI_ADB_WAIT=10
o=$($HM install --adb --dry-run 2>&1) && r=0 || r=$?
check "install --adb --dry-run: runs no adb command" "0|" "$r|$(cat $FK/adb.log 2>/dev/null)"
has "... prints them: push, install, apply, wait, restart" "+ $ADB push out/packages/my-mod-0.1.0.zip /data/local/tmp/my-mod-0.1.0.zip|+ $ADB shell 'hmgapid engine install /data/local/tmp/my-mod-0.1.0.zip'|+ $ADB shell 'rm -f /data/local/tmp/my-mod-0.1.0.zip'|+ $ADB shell 'hmgapid engine apply'|+ $ADB wait-for-device, then $ADB shell echo until it answers|+ $ADB shell 'hmgapid reboot'|(dry run: nothing was sent)|" "$(echo "$o" | tr '\n' '|')"
o=$($HM install --adb 2>&1) && r=0 || r=$?
check "install --adb: push, install, apply, restart" 0 $r
[ $r = 0 ] || echo "$o"
check "... the head unit's commands, in order" "hmgapid version|hmgapid engine install $FK/data/local/tmp/my-mod-0.1.0.zip|hmgapid engine apply|hmgapid reboot|" "$(tr '\n' '|' < $FK/hmgapid.log)"
check "... the package pushed, then removed" "" "$(ls $FK/data/local/tmp)"
rm $FK/hmgapid.log; touch $FK/drop
o=$($HM install --adb --no-reboot 2>&1) && r=0 || r=$?
check "... adb dropped during the apply: waits, asks the engine" 0 $r
[ $r = 0 ] || echo "$o"
has "... says so" "adb dropped during the apply (it restarts adbd): asking the engine how it went" "$o"
check "... then status, no restart (--no-reboot)" "hmgapid version|hmgapid engine install $FK/data/local/tmp/my-mod-0.1.0.zip|hmgapid engine apply|hmgapid engine status|" "$(tr '\n' '|' < $FK/hmgapid.log)"
J='{"name": "it'"'"'s \"quoted\"", "list": [1, 2], "path": "a b;c $HOME"}'
printf '%s\n' "$J" > $T/s.json
o=$($HM settings set my-mod $T/s.json 2>&1) && r=0 || r=$?
check "settings set: a JSON file reaches the unit as it is (quotes, spaces, \$)" "0|$J" "$r|$(cat $FK/data/hmgapi/settings/my-mod.json)"
check "... pushed as a file, then removed" "" "$(ls $FK/data/local/tmp)"
o=$($HM settings get my-mod 2>&1) && r=0 || r=$?
check "settings get: the settings, indented" "0|$(python3 -c 'import json,sys; print(json.dumps(json.loads(sys.argv[1]), indent=2))' "$J")" "$r|$o"
printf '{"a":' > $T/bad.json
set +e; o=$($HM settings set my-mod $T/bad.json 2>&1); r=$?; set -e
check "... not JSON: refused before adb" "2|yes|" "$r|$(echo "$o" | grep -q "isn't a JSON object" && echo yes)|$(grep -c bad.json $FK/adb.log | grep -v '^0$' || true)"
printf '11-15 10:00:00.000 I/HMG-API( 1): [my-mod] old\n' > $FK/data/hmgapi/logs/hmgapi/log.log.1
printf '11-15 10:00:01.000 I/HMG-API( 1): [other] x\n11-15 10:00:02.000 I/HMG-API( 1): [my-mod] zygote: version 0.1.0\n' > $FK/data/hmgapi/logs/hmgapi/log.log
o=$($HM logs my-mod 2>&1) && r=0 || r=$?
check "logs: the mod's lines in HMG-API's log, older file first" "0|11-15 10:00:00.000 I/HMG-API( 1): [my-mod] old|11-15 10:00:02.000 I/HMG-API( 1): [my-mod] zygote: version 0.1.0" "$r|$(echo "$o" | tr '\n' '|' | sed 's/|$//')"
o=$($HM logs my-mod relay 2>&1) && r=0 || r=$?
check "... a service's log" "0|log of my-mod/relay" "$r|$o"
sed 's/^version) echo "hmgapid 1.1.0" ;;/version) echo "hmgapid: daemon not running" ;;/' $FK/bin/hmgapid > $FK/h && mv $FK/h $FK/bin/hmgapid && chmod 755 $FK/bin/hmgapid
set +e; o=$($HM settings get my-mod 2>&1); r=$?; set -e
check "... HMG-API not running on the unit: says so" "2|hmgapi-mod: HMG-API isn't running on the unit (hmgapid version: hmgapid: daemon not running)" "$r|$o"
unset ADB HMGAPI_ADB_WAIT
cd $M

# ---- check-scripts.sh and check-points.py
o=$(sh $S/check-scripts.sh hello side-panel 2>&1) && r=0 || r=$?
check "check-scripts.sh: the examples pass" 0 $r
mkdir -p $T/bad/bad && printf '{"id": "bad", "version": "1.0.0", "services": [{"name": "s", "script": "s.sh"}]}\n' > $T/bad/bad/mod.json
printf '#!/system/bin/sh\necho x | sed s/x/y/\n' > $T/bad/bad/s.sh
set +e; o=$(sh $S/check-scripts.sh $T/bad/bad 2>&1); r=$?; set -e
check "... a script calling sed: fails" 1 "$([ $r -ne 0 ] && echo 1)"
has "... names it" "sed" "$o"
o=$(python3 $S/check-points.py hello hello-user side-panel 2>&1) && r=0 || r=$?
check "check-points.py: the examples pass (no points to fetch)" 0 $r
mkdir -p $T/p/p
pts() { printf '{"id": "p", "version": "1.0.0", "hmgapi": "%s", "hooks": {"jar": "hooks.jar", "entry": "x.X", "points": {%s}}}\n' "$1" "$2" > $T/p/p/mod.json; }
head -2 $T/sdk/points.txt > $T/floor.txt
P1=$(sed -n 1p $T/floor.txt) P3=$(sed -n 3p $T/sdk/points.txt)
pts ">=1.0.0" "\"$P1\": \"observe\""
o=$(HMGAPI_FLOOR_POINTS=$T/floor.txt python3 $S/check-points.py $T/p/p 2>&1) && r=0 || r=$?
check "... a point the floor release has: ok" 0 $r
pts ">=1.0.0" "\"$P1\": \"observe\", \"$P3\": \"observe\""
set +e; o=$(HMGAPI_FLOOR_POINTS=$T/floor.txt python3 $S/check-points.py $T/p/p 2>&1); r=$?; set -e
check "... a point only a later release has: fails" 1 $r
has "... names it" "$P3" "$o"
pts ">=99.0.0" ""
set +e; o=$(python3 $S/check-points.py $T/p/p 2>&1); r=$?; set -e
check "... a floor above the SDK: fails" 1 $r
has "... says to pin a newer SDK" "but the SDK is $V" "$o"
pts "<3" "\"$P1\": \"observe\""
set +e; o=$(HMGAPI_FLOOR_POINTS=$T/floor.txt python3 $S/check-points.py $T/p/p 2>&1); r=$?; set -e
check "... points but no floor: fails" 1 $r
exit $fail
