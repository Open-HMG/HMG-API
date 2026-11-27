#!/bin/sh
# Off-car test of the catalogue and updates (Catalog): the catalogue fetched (index.json, or GitHub's
# folder listing; a bad index, an entry that doesn't come), what each mod would do here, dependencies
# (requires installed first, a required mod that's off turned on, requires only the package names and
# a second OK for them, loops, conflicts either way, the result as a whole, unreadable ranges, what
# isn't in the catalogue), trust (a first install's key must be on the unit's list, or the owner's yes
# per mod), every download checked before the first install (SHA-256, the package being what the entry
# says, the author key), the owner's any-car choice per mod, updates (mods and HMG-API's own release,
# its pre-releases, mods it would leave behind), the automatic check (every 6 hours, staging only pure
# updates aside until apply), and a removal's warning.
# GitHub is played by files (HMGAPI_TEST_WEB: NativeFetch.Web).
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root D=$T/root/data/hmgapi W=$T/web
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_TEST_WEB=$W HMGAPI_TEST_CHECK_GAP=${GAP-0} java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
# without the harness's HMGAPI_TEST_ANY_CAR (it only turns off the hook map here, but the owner's any-car path must not need it)
eng0() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_WEB=$W HMGAPI_TEST_CHECK_GAP=${GAP-0} java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
offline() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
sig() { java -cp "$CP" hmgapi.engine.Signing "$@"; }
js() { python3 -c "import json,sys; d=json.loads(sys.stdin.read()[sys.stdin.read and 0:]); print(eval(sys.argv[1]))" "$1"; }
j() { printf '%s' "$out" | python3 -c "import json,sys; t=sys.stdin.read(); d=json.loads(t[t.index('{'):]); print(eval(sys.argv[1]))" "$1"; }
installed() { [ -f "$D/mods/$1/mod.json" ] && python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['version'])" "$D/mods/$1/mod.json" || echo none; }

mkdir -p "$D/mods" "$D/settings" "$D/maps" "$W"
sig keygen $T/author.key > /dev/null
sig keygen $T/other.key > /dev/null
U=$(sig uuid $T/author.key); O=$(sig uuid $T/other.key)
printf '{"authors": {"%s": {"name": "Test Author"}}}\n' "$U" > "$D/maps/authors.json"
CAT=$W/raw.githubusercontent.com/Open-HMG/HMG-API/main/catalog
DL=$W/github.com/test/releases/download
mkdir -p "$CAT" "$DL" "$W/objects.githubusercontent.com/r"

# pkg <id> <version> <mod.json extras> [key]: a signed package, served from a release (through GitHub's
# redirect to its asset host), and its catalogue entry (extras for the entry: $ENTRY; its author key: $EUUID, else the trusted one)
pkg() {
    rm -rf $T/m; mkdir -p $T/m
    printf '{"id": "%s", "name": "%s mod", "version": "%s", "hmgapi": ">=1.0.0"%s}\n' "$1" "$1" "$2" "$3" > $T/m/mod.json
    sig sign $T/m ${4:-$T/author.key} > /dev/null
    z=$W/objects.githubusercontent.com/r/$1-$2.zip
    (cd $T/m && rm -f "$z" && zip -q -r -X "$z" .)
    echo "https://objects.githubusercontent.com/r/$1-$2.zip" > "$DL/$1-$2.zip.redirect"
    sha=$(python3 -c "import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$z")
    printf '{"id": "%s", "name": "%s mod", "description": "the %s mod", "authors": ["tester"], "uuid": "%s", "version": "%s", "repo": "https://github.com/test/%s", "download": "https://github.com/test/releases/download/%s-%s.zip", "sha256": "%s", "hmgapi": ">=1.0.0"%s}\n' \
        "$1" "$1" "$1" "${EUUID:-$U}" "$2" "$1" "$1" "$2" "$sha" "$ENTRY" > "$CAT/$1.json"
    ENTRY= EUUID=
}
listing() {   # GitHub's folder listing, from the entries there
    mkdir -p $W/api.github.com/repos/Open-HMG/HMG-API/contents
    (cd "$CAT" && ls *.json | python3 -c "import json,sys; print(json.dumps([{'name': n.strip(), 'type': 'file'} for n in sys.stdin]))") \
        > "$W/api.github.com/repos/Open-HMG/HMG-API/contents/catalog_ref=main"
}
mkdir -p $W/api.github.com/repos/Open-HMG/HMG-API
echo '[]' > "$W/api.github.com/repos/Open-HMG/HMG-API/releases_per_page=15"

ENTRY=', "requires": ["base >=1.0.0"]'; pkg app 1.0.0 ', "requires": ["base >=1.0.0"]'
pkg base 1.0.0
ENTRY=', "hmgapi": ">=9.0.0"'; pkg future 1.0.0 ''
listing

# ---- before any check
eng catalog
check "no catalogue yet: an empty list" 0 "$(j 'len(d["mods"])')"
check "... online (here: the test web)" True "$(j 'd["online"]')"

# ---- home Wi-Fi off: nothing fetched
offline catalog --refresh
has "home Wi-Fi off: not fetched, said why" "Home Wi-Fi is off" "$out"
check "... nothing asked of GitHub" no "$([ -f $W/requests.log ] && echo yes || echo no)"
mkdir -p "$D/public"; echo '{"state": "searching", "rssi": 0, "freq": 0}' > "$D/public/wifi.json"
offline catalog --refresh
has "home Wi-Fi not joined yet: said so" "home Wi-Fi isn't connected (searching)" "$out"

# ---- the catalogue, from GitHub's listing (no index.json)
eng catalog --refresh
check "fetched" 0 $rc
hasnt "... no error" '"error"' "$out"
check "three entries" "app base future" "$(j '" ".join(m["id"] for m in d["mods"])')"
has "... the listing came from GitHub's API" "api.github.com/repos/Open-HMG/HMG-API/contents/catalog?ref=main" "$(cat $W/requests.log)"
check "base: new" new "$(j '[m for m in d["mods"] if m["id"]=="base"][0]["status"]')"
check "app: its requires" "['base >=1.0.0']" "$(j '[m for m in d["mods"] if m["id"]=="app"][0]["requires"]')"
check "the author on the trusted list" True "$(j '[m for m in d["mods"] if m["id"]=="app"][0]["trusted"]')"
check "future: not for this HMG-API" "needs HMG-API >=9.0.0 (this is $(j 'd["hmgapi"]'))" "$(j '[m for m in d["mods"] if m["id"]=="future"][0]["unfit"]')"
check "cached for the app" yes "$([ -s $D/catalog/catalog.json ] && echo yes)"

# ---- installing a mod takes what it requires, first
eng catalog-plan app
check "plan: base first, for app, then app" "base install app|app install None" "$(j '"|".join(s["id"]+" "+s["action"]+" "+str(s.get("for")) for s in d["steps"])')"
check "plan: ok" True "$(j 'd["ok"]')"
check "plan: nothing installed by it" none "$(installed base)"
eng catalog-install app
check "install app" 0 $rc
has "... base downloaded through the redirect, checked" "downloaded base 1.0.0" "$out"
has "... base installed first" "installing base 1.0.0 (app needs it)" "$out"
check "... both in place" "1.0.0 1.0.0" "$(installed base) $(installed app)"
has "... said to apply" "Apply changes (engine apply)" "$out"
check "... base before app" "base app" "$(printf '%s\n' "$out" | sed -n 's/^installing \([a-z]*\).*/\1/p' | tr '\n' ' ' | sed 's/ $//')"
eng catalog
check "both now the same as the catalogue" "same same" "$(j '" ".join(m["status"] for m in d["mods"] if m["id"] in ("app","base"))')"
eng catalog-install app
has "installed already: nothing to do" "nothing to install" "$out"
eng catalog-install future
check "a mod for a newer HMG-API: refused" 1 $rc
has "... why" "can't: future 1.0.0, which needs HMG-API >=9.0.0" "$out"
eng catalog-install nosuch
has "not in the catalogue" "nosuch isn't in the catalogue" "$out"

# ---- a required mod that's turned off is turned on
: > "$D/mods/base/.disabled"
ENTRY=', "requires": ["base"]'; pkg tool 1.0.0 ', "requires": ["base"]'; listing
eng catalog --refresh
eng catalog-install tool
check "install tool" 0 $rc
has "... base turned on for it" "turned on base 1.0.0 (tool needs it)" "$out"
check "... base on" no "$([ -f $D/mods/base/.disabled ] && echo yes || echo no)"

# ---- the package requires more than its entry says (an older entry): learned, installed first
pkg helper 1.0.0
pkg extra 1.0.0 ', "requires": ["helper"]'; listing
eng catalog --refresh
eng catalog-install extra
check "install extra: the plan the owner saw missed a step, so stopped for a second OK" 5 $rc
has "... said why" "the packages require more than the catalogue says" "$out"
check "... the new plan handed back" "helper install extra|extra install None" "$(j '"|".join(s["id"]+" "+s["action"]+" "+str(s.get("for")) for s in d["steps"])')"
check "... nothing installed" "none none" "$(installed helper) $(installed extra)"
eng catalog-plan extra
check "... the next plan has it (learned from the package)" "helper extra" "$(j '" ".join(s["id"] for s in d["steps"])')"
eng catalog-install extra
check "install extra again" 0 $rc
has "... helper, which only the package names, installed" "installing helper 1.0.0 (extra needs it)" "$out"
check "... both" "1.0.0 1.0.0" "$(installed helper) $(installed extra)"

# ---- what can't be met: nothing installed
ENTRY=', "requires": ["missing >=1.0.0"]'; pkg lonely 1.0.0 ', "requires": ["missing >=1.0.0"]'
ENTRY=', "requires": ["base >=5.0.0"]'; pkg greedy 1.0.0 ', "requires": ["base >=5.0.0"]'
ENTRY=', "requires": ["loopb"]'; pkg loopa 1.0.0 ', "requires": ["loopb"]'
ENTRY=', "requires": ["loopa"]'; pkg loopb 1.0.0 ', "requires": ["loopa"]'
ENTRY=', "conflicts": ["app"]'; pkg rival 1.0.0 ', "conflicts": ["app"]'
ENTRY=', "requires": [{"id": "base"}, {"id": "nowhere", "optional": true}]'; pkg relaxed 1.0.0 ''
listing; eng catalog --refresh
eng catalog-install lonely
has "a requirement not in the catalogue" "lonely needs missing >=1.0.0, which isn't in the catalogue" "$out"
eng catalog-install greedy
has "a requirement newer than the catalogue's" "greedy needs base >=5.0.0, but the catalogue has base 1.0.0 (1.0.0 is installed)" "$out"
eng catalog-install loopa
has "a requires loop" "a requires loop" "$out"
check "... none of them installed" "none none none" "$(installed lonely) $(installed greedy) $(installed loopa)"
eng catalog-install rival
has "a conflict with an installed mod" "rival 1.0.0 conflicts with app 1.0.0 (installed)" "$out"
check "... not installed" none "$(installed rival)"
eng catalog-install relaxed
check "an optional requirement (NEMESIS's): not needed here" 0 $rc

# ---- downloads that aren't what the entry says
pkg tampered 1.0.0
python3 - "$CAT/tampered.json" <<'P'
import json,sys; d=json.load(open(sys.argv[1])); d["sha256"]="0"*64; json.dump(d,open(sys.argv[1],"w"))
P
pkg wrongkey 1.0.0 '' $T/other.key
listing; eng catalog --refresh
eng catalog-install tampered
has "another SHA-256: refused" "refused tampered 1.0.0: the download's SHA-256 is" "$out"
check "... not installed" none "$(installed tampered)"
check "... nothing left behind" no "$([ -d $D/catalog/downloads ] && echo yes || echo no)"
eng catalog-install wrongkey
has "signed by another key than the entry's: refused" "refused wrongkey 1.0.0: wrongkey is signed by author key $O, not the expected $U" "$out"
check "... not installed" none "$(installed wrongkey)"

# ---- updates: base 2.0.0 and an app 2.0.0 that needs it
ENTRY=', "requires": ["base >=2.0.0"]'; pkg app 2.0.0 ', "requires": ["base >=2.0.0"]'
pkg base 2.0.0; listing
eng updates --refresh
check "updates: app and base" "app base" "$(j '" ".join(m["id"] for m in d["mods"])')"
check "... in public/ for the app" "$(j 'd["count"]')" "$(python3 -c "import json; print(json.load(open('$D/public/updates.json'))['count'])")"
eng update app
check "update app" 0 $rc
has "... base updated first, for it" "updating base 1.0.0 -> 2.0.0 (app needs it)" "$out"
check "... both 2.0.0" "2.0.0 2.0.0" "$(installed base) $(installed app)"
eng updates
check "... none left" 0 "$(j 'd["count"]')"
check "... public/ says so too" 0 "$(python3 -c "import json; print(json.load(open('$D/public/updates.json'))['count'])")"
eng update base
has "update of an up-to-date mod: nothing" "nothing to install" "$out"
eng update rival
has "update of a mod that isn't installed: install it instead" "rival isn't installed" "$out"

# ---- the automatic check (wifi.sh, when home Wi-Fi comes up), and staging if the owner allowed it
pkg helper 1.1.0; listing
eng update-check
check "update-check" 0 $rc
has "... logged" "checked: 1 update(s)" "$(cat $D/logs/hmgapi/updates.log)"
check "... nothing staged without the owner's switch" 1.0.0 "$(installed helper)"
mkdir -p "$D/settings/allow"; : > "$D/settings/allow/auto_stage_updates"
eng update-check
check "update-check, staging allowed" 0 $rc
has "... logged" "staged: helper 1.0.0 -> 1.1.0 (it goes in when you apply)" "$(cat $D/logs/hmgapi/updates.log)"
check "... staged aside, not installed: nothing loads it before an apply" "1.0.0 1.1.0" "$(installed helper) $(python3 -c "import json; print(json.load(open('$D/pending/helper/mod.json'))['version'])")"
eng updates
check "... the update says it's staged" True "$(j '[m for m in d["mods"] if m["id"]=="helper"][0].get("staged")')"
eng plan
has "... the plan names it" "update   helper 1.0.0 -> 1.1.0 (staged by the automatic check" "$out"
check "... still not installed" 1.0.0 "$(installed helper)"
eng update-check
hasnt "... checked again: not staged twice" "not staged: helper" "$(cat $D/logs/hmgapi/updates.log)"
# an apply that's refused leaves it staged
mkdir -p "$D/mods/broken"; echo '{"id": "notbroken", "version": "1.0.0"}' > "$D/mods/broken/mod.json"; echo '{"changes": []}' > "$D/mods/broken/patches.json"
eng apply
check "an apply with a problem" 3 $rc
check "... the staged update back to staged" "1.0.0 yes" "$(installed helper) $([ -d $D/pending/helper ] && echo yes)"
has "... said so" "the staged updates (helper) stay staged" "$out"
rm -r "$D/mods/broken"
eng apply
check "apply" 0 $rc
has "... the staged update put in place first" "update   helper 1.0.0 -> 1.1.0 (staged by the automatic check; 1.0.0 kept in /data/hmgapi/previous/helper)" "$out"
check "... installed, the previous version kept" "1.1.0 1.0.0" "$(installed helper) $(python3 -c "import json; print(json.load(open('$D/previous/helper/mod.json'))['version'])")"
check "... nothing left staged" no "$([ -d $D/pending/helper ] && echo yes || echo no)"
rm "$D/settings/allow/auto_stage_updates"

# ---- HMG-API's own release
cat > "$W/api.github.com/repos/Open-HMG/HMG-API/releases_per_page=15" <<'J'
[{"tag_name": "v9.0.0", "draft": true, "prerelease": false, "assets": []},
 {"tag_name": "v1.1.0", "draft": false, "prerelease": false, "html_url": "https://github.com/Open-HMG/HMG-API/releases/tag/v1.1.0", "body": "What's new in 1.1.0",
  "assets": [{"name": "hmgapi-1.1.0-update.zip", "browser_download_url": "https://github.com/Open-HMG/HMG-API/releases/download/v1.1.0/hmgapi-1.1.0-update.zip"},
             {"name": "SHA256SUMS", "browser_download_url": "https://github.com/Open-HMG/HMG-API/releases/download/v1.1.0/SHA256SUMS"}]},
 {"tag_name": "v1.0.2", "draft": false, "prerelease": false, "assets": []}]
J
mkdir -p $W/github.com/Open-HMG/HMG-API/releases/download/v1.1.0
echo "not a release" > $W/github.com/Open-HMG/HMG-API/releases/download/v1.1.0/hmgapi-1.1.0-update.zip
printf '%s  hmgapi-1.1.0-update.zip\n' "$(printf '%064d' 0)" > $W/github.com/Open-HMG/HMG-API/releases/download/v1.1.0/SHA256SUMS
eng updates --refresh
check "HMG-API's newer release offered (not the draft)" 1.1.0 "$(j 'd["hmgapi"]["version"]')"
check "... counted" 1 "$(j 'd["count"]')"
check "... with its release notes, for the app" "What's new in 1.1.0" "$(j 'd["hmgapi"]["notes"]')"
eng update hmgapi
has "a release that isn't its SHA256SUMS: refused" "refused HMG-API 1.1.0: the download's SHA-256 is" "$out"
echo '[{"tag_name": "v1.0.2", "draft": false, "prerelease": false, "assets": []}]' > "$W/api.github.com/repos/Open-HMG/HMG-API/releases_per_page=15"
eng updates --refresh
check "only older releases: nothing offered" None "$(j 'd.get("hmgapi")')"

# ---- HMG-API's pre-releases: a unit on 1.1.0-rc.10 is offered 1.1.0 and 1.1.0's next rcs, never 1.2.0's
REL=$W/api.github.com/repos/Open-HMG/HMG-API/releases_per_page=15
rel() {   # rel <tag> <prerelease>: a release JSON object with its assets, and its SHA256SUMS served
    v=${1#v}; d=$W/github.com/Open-HMG/HMG-API/releases/download/$1; mkdir -p $d
    echo "not a release" > $d/hmgapi-$v-update.zip
    printf '%s  hmgapi-%s-update.zip\n' "$(printf '%064d' 0)" "$v" > $d/SHA256SUMS
    printf '{"tag_name": "%s", "draft": false, "prerelease": %s, "html_url": "https://github.com/Open-HMG/HMG-API/releases/tag/%s", "assets": [{"name": "hmgapi-%s-update.zip", "browser_download_url": "https://github.com/Open-HMG/HMG-API/releases/download/%s/hmgapi-%s-update.zip"}, {"name": "SHA256SUMS", "browser_download_url": "https://github.com/Open-HMG/HMG-API/releases/download/%s/SHA256SUMS"}]}' \
        "$1" "$2" "$1" "$v" "$1" "$v" "$1"
}
echo "version 1.1.0-rc.10" > "$D/installed.txt"
echo "[$(rel v1.2.0-alpha.1 true), $(rel v1.1.0 false), $(rel v1.1.0-rc.11 true)]" > "$REL"
eng updates --refresh
check "on rc.10: 1.1.0 offered, not 1.2.0-alpha.1" 1.1.0 "$(j 'd["hmgapi"]["version"]')"
echo "[$(rel v1.2.0-alpha.1 true), $(rel v1.1.0-rc.11 true)]" > "$REL"
eng updates --refresh
check "... with no 1.1.0 yet: the next rc" 1.1.0-rc.11 "$(j 'd["hmgapi"]["version"]')"
python3 - "$D/settings/hmgapi.json" <<'P'
import json, os, sys
f = sys.argv[1]; d = json.load(open(f)) if os.path.exists(f) else {}; d["catalog"] = {"prereleases": True}; json.dump(d, open(f, "w"))
P
eng updates --refresh
check "... the owner's opt-in: every pre-release" 1.2.0-alpha.1 "$(j 'd["hmgapi"]["version"]')"
python3 - "$D/settings/hmgapi.json" <<'P'
import json, sys
f = sys.argv[1]; d = json.load(open(f)); del d["catalog"]; json.dump(d, open(f, "w"))
P
# ---- updating HMG-API when an installed mod's "hmgapi" range wouldn't take the new version
echo "[$(rel v1.1.0 false)]" > "$REL"
eng updates --refresh
mkdir -p "$D/mods/oldapi"; echo '{"id": "oldapi", "version": "1.0.0", "hmgapi": "<=1.1.0-rc.10"}' > "$D/mods/oldapi/mod.json"
eng update hmgapi
check "a mod that wouldn't take the new HMG-API: not yet" 1 $rc
has "... names it" "these mods wouldn't load, and applying stops until they're updated or turned off: oldapi 1.0.0 (needs HMG-API <=1.1.0-rc.10)" "$out"
hasnt "... nothing downloaded" "downloaded HMG-API" "$out"
eng update hmgapi --force
has "... the owner's override: a warning" "WARNING: with HMG-API 1.1.0 these mods won't load" "$out"
has "... then the usual checks" "refused HMG-API 1.1.0: the download's SHA-256 is" "$out"
: > "$D/mods/oldapi/.disabled"
eng update hmgapi
hasnt "... a mod that's off doesn't count" "not yet" "$out"
rm -r "$D/mods/oldapi" "$D/installed.txt"
echo '[]' > "$REL"

# ---- trust: a first install's author key must already be on this unit's list (the catalogue isn't signed)
cp "$D/maps/authors.json" $T/authors.json
EUUID=$O; pkg stranger 1.0.0 '' $T/other.key
EUUID=$O; pkg strangedep 1.0.0 '' $T/other.key
ENTRY=', "requires": ["strangedep"]'; pkg needsstranger 1.0.0 ', "requires": ["strangedep"]'
listing; eng catalog --refresh
check "an unknown author: not trusted" False "$(j '[m for m in d["mods"] if m["id"]=="stranger"][0]["trusted"]')"
eng catalog-plan stranger
check "... the plan isn't ok" False "$(j 'd["ok"]')"
has "... why" "stranger 1.0.0 is by author key $O, which isn't on this head unit's trusted author list" "$out"
check "... the owner may say yes, for this mod" "stranger author" "$(j '" ".join(o["id"]+" "+o["kind"] for o in d["overrides"])')"
eng catalog-install stranger
check "... refused" 1 $rc
check "... not installed" none "$(installed stranger)"
eng catalog-install needsstranger
has "... a dependency by an unknown author too" "strangedep 1.0.0 is by author key $O" "$out"
check "... nothing installed" "none none" "$(installed strangedep) $(installed needsstranger)"
eng catalog-install needsstranger --any-author=needsstranger
check "... a yes for one mod isn't one for another" 1 $rc
eng catalog-install stranger --any-author=stranger
check "... the owner's yes, for that mod" 0 $rc
has "... with a warning" "WARNING: stranger is by author key $O" "$out"
check "... installed" 1.0.0 "$(installed stranger)"
EUUID=$O; pkg stranger 1.1.0 '' $T/other.key; listing; eng catalog --refresh
eng update stranger
check "... its update: the installed key, no yes needed" 0 $rc
check "... updated" 1.1.0 "$(installed stranger)"
check "... maps/authors.json never changed by the catalogue" "$(cat $T/authors.json)" "$(cat $D/maps/authors.json)"

# ---- the result as a whole: what everything that will be on requires, and conflicts either way
pkg lib 1.0.0
ENTRY=', "requires": ["lib ^1.0.0"]'; pkg user 1.0.0 ', "requires": ["lib ^1.0.0"]'
listing; eng catalog --refresh; eng catalog-install user
check "user and lib 1.0.0" "1.0.0 1.0.0" "$(installed user) $(installed lib)"
pkg lib 2.0.0; listing; eng catalog --refresh
eng update lib
check "an update that breaks what an installed mod requires: refused" 1 $rc
has "... why" "the installed user 1.0.0 needs lib ^1.0.0, but lib 2.0.0 would be the one installed" "$out"
eng update --all
has "... update --all leaves it out, says why" "not updated: lib: the installed user 1.0.0 needs lib ^1.0.0" "$out"
check "... lib unchanged" 1.0.0 "$(installed lib)"
pkg c 1.0.0
ENTRY=', "requires": ["c ^1.0.0"]'; pkg a 1.0.0 ', "requires": ["c ^1.0.0"]'
listing; eng catalog --refresh; eng catalog-install a
pkg c 2.0.0
ENTRY=', "requires": ["c >=2.0.0"]'; pkg b 1.0.0 ', "requires": ["c >=2.0.0"]'
listing; eng catalog --refresh
eng catalog-plan b
check "diamond: b needs c >=2, the installed a needs c ^1: not ok" False "$(j 'd["ok"]')"
has "... why" "the installed a 1.0.0 needs c ^1.0.0, but c 2.0.0 would be the one installed" "$out"
eng catalog-install b
check "... nothing installed" "none 1.0.0" "$(installed b) $(installed c)"
ENTRY=', "conflicts": ["peace"]'; pkg hawk 1.0.0 ', "conflicts": ["peace"]'
pkg peace 1.0.0
listing; eng catalog --refresh; eng catalog-install hawk
eng catalog-plan peace
check "an installed mod's conflicts naming the new one: not ok" False "$(j 'd["ok"]')"
has "... why" "the installed hawk 1.0.0 conflicts with peace 1.0.0 (its conflicts list: peace): remove or update hawk first" "$out"
: > "$D/mods/hawk/.disabled"
eng catalog-plan peace
check "... with hawk off: ok" True "$(j 'd["ok"]')"
ENTRY=', "conflicts": ["peace"]'; pkg dove 1.0.0 ''
listing; eng catalog --refresh
eng catalog-plan dove peace
has "... and between two mods being installed" "dove 1.0.0 conflicts with peace 1.0.0 (being installed)" "$out"
eng remove hawk

# ---- a range that can't be read: a plan problem, never a crash
ENTRY=', "requires": ["lib ^x.y"]'; pkg badrange 1.0.0 ''
listing; eng catalog --refresh
eng catalog-plan badrange
check "a bad range in an entry: a plan" 0 $rc
has "... with the problem" 'badrange 1.0.0: its requires \"lib ^x.y\" can'"'"'t be read' "$out"
hasnt "... no exception" "Exception" "$out"
eng catalog-install badrange
check "... not installed" "1 none" "$rc $(installed badrange)"
mkdir -p "$D/mods/oddrange"; echo '{"id": "oddrange", "version": "1.0.0", "requires": ["lib ^x.y"], "conflicts": ["c ~q"]}' > "$D/mods/oddrange/mod.json"
eng catalog-plan peace
check "... an installed mod's bad range doesn't stop other installs" True "$(j 'd["ok"]')"
rm -r "$D/mods/oddrange"

# ---- every package checked before the first one goes in
pkg dep 1.0.0
ENTRY=', "requires": ["dep"]'; pkg top 1.0.0 ', "requires": ["dep"]' $T/other.key
listing; eng catalog --refresh
eng catalog-install top
check "top signed with another key than its entry's: refused" 3 $rc
has "... before anything went in" "refused top 1.0.0: top is signed by author key $O, not the expected $U (nothing was installed)" "$out"
check "... its dependency not installed on its own" none "$(installed dep)"

# ---- the owner's any-car choice: per mod, and kept (without the harness's HMGAPI_TEST_ANY_CAR)
ENTRY=', "system": {"model": ["ZZEV"]}'; pkg othercar 1.0.0 ', "system": {"model": ["ZZEV"]}'
pkg plain 1.0.0
listing; eng0 catalog --refresh
has "othercar: not for this head unit" "car: " "$(j '[m for m in d["mods"] if m["id"]=="othercar"][0]["unfit"]')"
eng0 catalog-plan othercar plain
check "... the plan isn't ok" False "$(j 'd["ok"]')"
check "... the owner may say yes, for that mod" "othercar car" "$(j '" ".join(o["id"]+" "+o["kind"] for o in d["overrides"])')"
eng0 catalog-install othercar plain --any-car=othercar
check "... installed with a yes for othercar only" 0 $rc
check "... both" "1.0.0 1.0.0" "$(installed othercar) $(installed plain)"
has "... the choice noted" "noted: othercar runs on this head unit at your own risk" "$out"
eng0 catalog
check "... and shown" True "$(j '[m for m in d["mods"] if m["id"]=="othercar"][0].get("any_car")')"
ENTRY=', "system": {"model": ["ZZEV"]}'; pkg othercar 1.1.0 ', "system": {"model": ["ZZEV"]}'
pkg plain 1.1.0; listing; eng0 catalog --refresh
eng0 update --all
check "update --all with an any-car mod installed: the others aren't held back" "1.1.0 1.1.0" "$(installed othercar) $(installed plain)"
hasnt "... nothing refused for the car" "isn't for this head unit:" "$out"

# ---- a hotspot mod on a unit whose Wi-Fi chip is never powered: for this head unit (SystemCheck's unpowered chip)
mkdir -p "$R/system/wifi" "$R/system/etc/permissions"; : > "$R/system/wifi/bcmdhd.ko"; : > "$R/system/wifi/fw_bcmdhd.bin"
printf '<permissions><feature name="com.hkmc.software.wifi.module"/></permissions>\n' > "$R/system/etc/permissions/wifi.xml"
ENTRY=', "system": {"features": ["hmgapi.wifi"]}, "wifi": {"hotspot": true}'; pkg hotspot 1.0.0 ', "system": {"features": ["hmgapi.wifi"]}, "wifi": {"hotspot": true}'
ENTRY=', "system": {"features": ["hmgapi.wifi"]}'; pkg wifionly 1.0.0 ', "system": {"features": ["hmgapi.wifi"]}'
listing; eng0 catalog --refresh
check "a hotspot mod, unpowered chip: for this head unit" None "$(j '[m for m in d["mods"] if m["id"]=="hotspot"][0].get("unfit")')"
has "... a mod that doesn't power it: not" "never powers it" "$(j '[m for m in d["mods"] if m["id"]=="wifionly"][0].get("unfit")')"
rm -r "$R/system/wifi" "$R/system/etc/permissions/wifi.xml"

# ---- the automatic check stages only pure updates: nothing new installed, nothing turned on
: > "$D/settings/allow/auto_stage_updates"
pkg newdep 1.0.0
ENTRY=', "requires": ["newdep"]'; pkg plain 1.2.0 ', "requires": ["newdep"]'
: > "$D/mods/helper/.disabled"
ENTRY=', "requires": ["helper"]'; pkg extra 1.1.0 ', "requires": ["helper"]'
listing
: > $T/before.log; [ -f $D/logs/hmgapi/updates.log ] && cp $D/logs/hmgapi/updates.log $T/before.log
eng update-check
L=$(diff $T/before.log $D/logs/hmgapi/updates.log || true)
has "an update that needs a new mod: not staged" "not staged: plain: it takes installing newdep 1.0.0 too" "$L"
has "an update that needs a mod turned on: not staged" "not staged: extra: it takes turning on helper too" "$L"
check "... neither staged, newdep not installed, helper still off" "no no none yes" "$([ -d $D/pending/plain ] && echo yes || echo no) $([ -d $D/pending/extra ] && echo yes || echo no) $(installed newdep) $([ -f $D/mods/helper/.disabled ] && echo yes)"
rm "$D/mods/helper/.disabled" "$D/settings/allow/auto_stage_updates"

# ---- update-check at most every 6 hours (2 GitHub API calls of 60 an hour each)
: > $W/requests.log
GAP=21600 eng update-check
has "a check within 6 hours of the last: not made" "not checked: the last check was 0 min ago" "$(cat $D/logs/hmgapi/updates.log)"
check "... nothing asked of GitHub" "" "$(cat $W/requests.log)"

# ---- a plan refreshes a catalogue that's old, when it can
pkg fresh 1.0.0; listing; eng catalog --refresh
pkg fresh 1.1.0; listing
eng catalog-plan fresh
check "a fresh catalogue: as checked" 1.0.0 "$(j 'd["steps"][0]["version"]')"
python3 - "$D/catalog/catalog.json" <<'P'
import json, sys
f = sys.argv[1]; d = json.load(open(f)); d["checked"] = 0; json.dump(d, open(f, "w"))
P
eng catalog-plan fresh
check "... an old one: checked again first" 1.1.0 "$(j 'd["steps"][0]["version"]')"

# ---- one entry that doesn't come: the last check's stays
mv "$CAT/fresh.json" $T/fresh.json
eng catalog --refresh
has "an entry that didn't come: still there" "fresh" "$(j '" ".join(m["id"] for m in d["mods"])')"
has "... noted" "fresh.json: HTTP 404 (the last check's entry kept)" "$(j '" ".join(d.get("notes", []))')"
mv $T/fresh.json "$CAT/fresh.json"
check "no temporary files left" "" "$(ls -a $D/catalog $D/public | grep '\.tmp$' || true)"

# ---- an index.json, when the repo has one, instead of the listing
python3 - "$CAT" <<'P'
import json,os,sys; c=sys.argv[1]; json.dump({"mods": [json.load(open(os.path.join(c,"base.json")))]}, open(os.path.join(c,"index.json"),"w"))
P
: > $W/requests.log
eng catalog --refresh
check "index.json: its mods" base "$(j '" ".join(m["id"] for m in d["mods"])')"
hasnt "... no API call" "api.github.com/repos/Open-HMG/HMG-API/contents" "$(cat $W/requests.log)"
echo '{"mods": [{"id": "Bad Id"}, {"id": "nohttps", "version": "1.0.0", "download": "http://x/y.zip", "sha256": "'"$(printf '%064d' 0)"'", "uuid": "x"}]}' > "$CAT/index.json"
eng catalog --refresh
check "bad entries left out" 0 "$(j 'len(d["mods"])')"
has "... with a note" "nohttps: left out (download isn't https)" "$out"
echo '{"mods": [' > "$CAT/index.json"
eng catalog --refresh
has "an index.json that isn't JSON: said" "the catalogue's index.json isn't JSON" "$out"
check "... the old catalogue kept" 0 "$(j 'len(d["mods"])')"
touch "$W/raw.githubusercontent.com.down"
eng catalog --refresh
has "GitHub not answering: said, the old catalogue kept" "no connection" "$out"
rm "$W/raw.githubusercontent.com.down"

# ---- removing a mod others require: warned
eng remove base
has "a removal others need: warned" "app requires base: it won't load until base is installed again" "$out"

[ $fail = 0 ] && echo "all catalogue checks passed"
exit $fail
