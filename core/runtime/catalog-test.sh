#!/bin/sh
# Off-car test of the catalogue and updates (Catalog): the catalogue fetched (index.json, or GitHub's
# folder listing), what each mod would do here, dependencies (requires installed first, a required
# mod that's off turned on, requires only the package names, loops, conflicts, what isn't in the
# catalogue), downloads checked (SHA-256, the package being what the entry says, the author key),
# updates (mods and HMG-API's own release), the automatic check, and a removal's warning.
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
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_TEST_WEB=$W java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
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
# redirect to its asset host), and its catalogue entry (extras for the entry: $ENTRY)
pkg() {
    rm -rf $T/m; mkdir -p $T/m
    printf '{"id": "%s", "name": "%s mod", "version": "%s", "hmgapi": ">=1.0.0"%s}\n' "$1" "$1" "$2" "$3" > $T/m/mod.json
    sig sign $T/m ${4:-$T/author.key} > /dev/null
    z=$W/objects.githubusercontent.com/r/$1-$2.zip
    (cd $T/m && rm -f "$z" && zip -q -r -X "$z" .)
    echo "https://objects.githubusercontent.com/r/$1-$2.zip" > "$DL/$1-$2.zip.redirect"
    sha=$(python3 -c "import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$z")
    printf '{"id": "%s", "name": "%s mod", "description": "the %s mod", "authors": ["tester"], "uuid": "%s", "version": "%s", "repo": "https://github.com/test/%s", "download": "https://github.com/test/releases/download/%s-%s.zip", "sha256": "%s", "hmgapi": ">=1.0.0"%s}\n' \
        "$1" "$1" "$1" "$U" "$2" "$1" "$1" "$2" "$sha" "$ENTRY" > "$CAT/$1.json"
    ENTRY=
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
check "install extra" 0 $rc
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
has "signed by another key than the entry's: refused" "is signed by author key $O, not the expected $U" "$out"
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
check "... staged" 1.1.0 "$(installed helper)"
has "... logged" "staged: [helper] (the owner applies them)" "$(cat $D/logs/hmgapi/updates.log)"
rm "$D/settings/allow/auto_stage_updates"

# ---- HMG-API's own release
cat > "$W/api.github.com/repos/Open-HMG/HMG-API/releases_per_page=15" <<'J'
[{"tag_name": "v9.0.0", "draft": true, "prerelease": false, "assets": []},
 {"tag_name": "v1.1.0", "draft": false, "prerelease": false, "html_url": "https://github.com/Open-HMG/HMG-API/releases/tag/v1.1.0",
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
eng update hmgapi
has "a release that isn't its SHA256SUMS: refused" "refused HMG-API 1.1.0: the download's SHA-256 is" "$out"
echo '[{"tag_name": "v1.0.2", "draft": false, "prerelease": false, "assets": []}]' > "$W/api.github.com/repos/Open-HMG/HMG-API/releases_per_page=15"
eng updates --refresh
check "only older releases: nothing offered" None "$(j 'd.get("hmgapi")')"

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
touch "$W/raw.githubusercontent.com.down"
eng catalog --refresh
has "GitHub not answering: said, the old catalogue kept" "no connection" "$out"
rm "$W/raw.githubusercontent.com.down"

# ---- removing a mod others require: warned
eng remove base
has "a removal others need: warned" "app requires base: it won't load until base is installed again" "$out"

[ $fail = 0 ] && echo "all catalogue checks passed"
exit $fail
