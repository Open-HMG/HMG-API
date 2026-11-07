#!/bin/sh
# Test of tools/catalog.py (the community mod catalogue) in a throwaway git repo:
# add, check (fields, author keys, --base rules) and the wiki page. No network:
# CI's catalogue job runs `check --download` on the real links.
set -e
cd "$(dirname "$0")/.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
R=$T/repo
mkdir -p $R/tools $R/maps $R/core/runtime/java/hmgapi
cp tools/catalog.py $R/tools/
cp -r core/runtime/java/hmgapi/engine $R/core/runtime/java/hmgapi/
printf '{"about": "test", "authors": {}}\n' > $R/maps/authors.json
C() { set +e; out=$(python3 $R/tools/catalog.py "$@" 2>&1); rc=$?; set -e; }
G() { git -C $R "$@"; }
G init -q && G -c user.name=t -c user.email=t@t commit -qm base --allow-empty && G add -A && G -c user.name=t -c user.email=t@t commit -qm tools

CP=$T/engine; mkdir -p $CP
javac --release 8 -Xlint:-options -nowarn -d $CP core/runtime/java/hmgapi/engine/*.java 2>/dev/null
S() { java -cp $CP hmgapi.engine.Signing "$@"; }
S keygen $T/a.key >/dev/null; UA=$(S uuid $T/a.key)
S keygen $T/b.key >/dev/null; UB=$(S uuid $T/b.key)
# pkg <dir name> <version> <key> <zip>: mods/examples/hello as <version>, signed
pkg() {
    rm -rf $T/$1; cp -r mods/examples/hello $T/$1
    python3 - $T/$1/mod.json $2 <<'P'
import json, sys
m = json.load(open(sys.argv[1])); m['version'] = sys.argv[2]; m['authors'] = ['Test Author']; m['repo'] = 'https://example.org/hello'
json.dump(m, open(sys.argv[1], 'w'))
P
    S sign $T/$1 $3 >/dev/null
    (cd $T/$1 && rm -f $4 && zip -qr $4 .)
}
DL=https://example.org/hello/releases/hello.zip

pkg h1 1.0.0 $T/a.key $T/h1.zip
C add $T/h1.zip --download $DL; check "add" 0 $rc; has "says what" "hello 1.0.0, author key $UA (added to maps/authors.json as Test Author)" "$out"
check "entry's sha256" "$(sha256sum $T/h1.zip | cut -d' ' -f1)" "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' $R/catalog/hello.json)"
check "repo from mod.json" "https://example.org/hello" "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["repo"])' $R/catalog/hello.json)"
has "author listed" "\"$UA\"" "$(cat $R/maps/authors.json)"
C check --base HEAD; check "check a new entry" 0 $rc
G add -A && G -c user.name=t -c user.email=t@t commit -qm hello

pkg h2 1.1.0 $T/a.key $T/h2.zip
C add $T/h2.zip --download $DL --repo https://example.org/hello/sub; check "update" 0 $rc
check "author's repo not repeated under its root" 1 "$(grep -c example.org $R/maps/authors.json)"
C check --base HEAD; check "check an update" 0 $rc
G checkout -q -- .

pkg hb 1.2.0 $T/b.key $T/hb.zip
C add $T/hb.zip --download $DL; check "another key refused" 1 $rc; has "says why" "Only the same key updates a mod" "$out"
pkg h0 0.9.0 $T/a.key $T/h0.zip
C add $T/h0.zip --download $DL; check "older version refused" 1 $rc
C add $T/h1.zip --download http://example.org/x.zip; check "plain http refused" 1 $rc
echo x > $T/h1/extra.txt; (cd $T/h1 && zip -qr $T/bad.zip .)
C add $T/bad.zip --download $DL; check "unsigned package refused" 1 $rc; has "says why" "file not in the manifest: extra.txt" "$out"
python3 -c "import zipfile; z = zipfile.ZipFile('$T/evil.zip', 'w'); z.writestr('../x', 'x'); z.close()"
C add $T/evil.zip --download $DL; check "path out of the folder refused" 1 $rc; has "says why" "refused entry '../x'" "$out"

# hand edits a pull request could make
J() { python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); exec(sys.argv[2]); json.dump(d,open(f,'w'))" "$@"; }
J $R/catalog/hello.json "d['uuid']='$UB'"
C check; check "key not listed refused" 1 $rc; has "says why" "author key $UB isn't in maps/authors.json" "$out"
J $R/maps/authors.json "d['authors']['$UB']={'name':'B'}"
C check; check "listed: fields fine" 0 $rc
C check --base HEAD; check "key change refused (base)" 1 $rc; has "says why" "the author key changed" "$out"
G checkout -q -- .
J $R/catalog/hello.json "d['version']='0.1.0'"
C check --base HEAD; check "version back refused (base)" 1 $rc
G checkout -q -- .
J $R/maps/authors.json "d['authors']['$UA']['name']='Someone else'"
C check --base HEAD; check "someone else's author entry refused (base)" 1 $rc; has "says why" "$UA changed, but no catalogue entry" "$out"
G checkout -q -- .
J $R/catalog/hello.json "d['extra']=1; d['repo']='javascript:alert(1)'; d['description']='a\nb'"
C check; check "bad fields refused" 1 $rc; has "unknown field" 'unknown field "extra"' "$out"; has "repo https" '"repo" isn'"'"'t an https:// link' "$out"; has "one line" '"description" is one line' "$out"
G checkout -q -- .
cp $R/catalog/hello.json $R/catalog/other.json
C check; check "file name is the id" 1 $rc
rm $R/catalog/other.json

J $R/catalog/hello.json "d['description']='Says <b>hi</b> [x](https://evil)'"
C wiki $T/page.md; check "wiki" 0 $rc
P=$(cat $T/page.md)
has "page names the mod" "## Hello" "$P"
has "page escapes text" 'Says \<b\>hi\</b\> \[x\]\(https://evil\)' "$P"
has "page links the download" "(<$DL>)" "$P"
has "page shows the key" "\`$UA\`" "$P"

[ $fail = 0 ] && echo "catalog: all ok" || { echo "catalog: FAILED"; exit 1; }
