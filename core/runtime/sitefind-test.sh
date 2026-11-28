#!/bin/sh
# Off-car test of relocatable native recipes (hooks.native_sites: hmgapi.engine.SiteFind,
# SiteRecipes, tools/sitefind.py): the matcher and the resolver on synthetic libraries, the
# engine's `sites` on a fake root, and what an app gets.
#
# With stock libraries, also every mod recipe on them, and the matcher fuzzed on them. Stock
# libraries: $HMGAPI_CARS trees' system/lib (docs/car-trees.md) and the folders in
# $HMGAPI_STOCK_LIBS (colon-separated); recipes: every recipes/*.recipe under the repos in
# $HMGAPI_MODS (colon-separated). Per recipe and library of its name:
#   - made from it (its stock bytes are there): relocated (tools/sitefind.py relocate --lib),
#     resolved, it must be the same recipe: the same verify regions, the same bytes changed;
#   - it has the recipe's change already (another package's build): the sites made from its
#     bytes, every site, write and call target must land at the recipe's offsets;
#   - another build: relocated from the text alone, tried, and said (found, or not).
# Prints offsets, counts and md5s only, never bytes of a library.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$CP" "$T"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null && javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/SiteFindTest.java 2>/dev/null
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
python3 tools/sitefind.py selftest || fail=1
java -cp "$CP" hmgapi.engine.SiteFindTest || fail=1

# ---- the engine: engine sites on a fake root, and what an app then gets
R=$T/root
D=$R/data/hmgapi
mkdir -p $R/system/lib $D/mods/m/recipes
java -cp "$CP" hmgapi.engine.SiteFindTest --elf $R/system/lib/libt.so 1 0
md5at() { python3 -c "import hashlib,sys; b=open(sys.argv[1],'rb').read(); print(hashlib.md5(b[int(sys.argv[2],0):int(sys.argv[2],0)+int(sys.argv[3])]).hexdigest())" "$@"; }
S=$(md5at $R/system/lib/libt.so 0x2100 32)
printf 'id t\nfile /system/lib/libt.so\nsite s 32 %s\nverify s 32\npatch s+0x10 00bf\n' "$S" > $D/mods/m/recipes/t.recipe
printf 'id t\nfile /system/lib/libt.so\nsite s 32 %s in fnA\nverify s 32\npatch s+0x10 00bf\n' "$S" > $D/mods/m/recipes/b.recipe
printf 'id t\nfile /system/lib/libt.so\nsite s 32 %s\nverify s 32\npatch s+0x10 00bf\n' 0123456789abcdef0123456789abcdef > $D/mods/m/recipes/n.recipe
printf '{"id": "m", "name": "M", "version": "1.0.0", "hmgapi": ">=1.0.0", "hooks": {"jar": "h.jar", "entry": "x.X", "native_sites": {"libt.so": ["recipes/t.recipe", "recipes/n.recipe"]}}}\n' > $D/mods/m/mod.json
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
app() { java -cp "$CP" hmgapi.engine.SiteFindTest --app $D m "$1" $D/mods/m/"$1" $R/system/lib/libt.so; }
eng sites
check "engine sites" 0 $rc
has "a recipe whose site is there: resolved, and where" "recipes/t.recipe on /system/lib/libt.so ($(md5at $R/system/lib/libt.so 0 999999 | cut -c1-8)): resolved (s at 0x2100)" "$out"
has "one whose site isn't: doesn't fit, and why" "recipes/n.recipe on /system/lib/libt.so" "$out"
has "... (its site matches 0 times)" "doesn't fit: site s matches 0 times" "$out"
check "the result kept by the recipe's and the library's md5" 2 "$(ls $D/public/sites/m | grep -c "\.$(md5at $R/system/lib/libt.so 0 999999)\.")"
check "readable by the apps" "-rw-r--r--" "$(ls -l $D/public/sites/m | grep 'recipes_t' | cut -c1-10)"
check "an app gets the plain recipe from it" "recipe 8448" "$(app recipes/t.recipe)"
has "... and the reason for one that doesn't fit" "none: recipes/n.recipe: site s matches 0 times" "$(app recipes/n.recipe)"
eng sites
has "again: the kept result, not resolved again" "resolved (kept)" "$out"
java -cp "$CP" hmgapi.engine.SiteFindTest --elf $R/system/lib/libt.so 1 100   # another build: the code 100 bytes later
has "a new build of the library: no result for it yet, the app waits for the engine" "not resolved for this build of the library yet" "$(app recipes/t.recipe)"
check "... unless every site says where to look: then the app resolves it itself" "recipe 8548" "$(app recipes/b.recipe)"
eng sites
has "engine sites on the new build: found where the code moved" "resolved (s at 0x2164)" "$out"
check "the old build's result is gone" 2 "$(ls $D/public/sites/m | wc -l | tr -d ' ')"
check "and the app gets the new one" "recipe 8548" "$(app recipes/t.recipe)"
touch $D/mods/m/.disabled
eng sites
check "a mod turned off: its results go" no "$([ -d $D/public/sites/m ] && echo yes || echo no)"
rm $D/mods/m/.disabled
printf '{"id": "m", "name": "M", "version": "1.0.0", "hmgapi": ">=1.0.0", "hooks": {"jar": "h.jar", "entry": "x.X", "native_sites": {"libt.so": ["../../etc/x.recipe", "recipes/none.recipe"]}}}\n' > $D/mods/m/mod.json
eng sites
has "a path out of the mod's folder: refused" "../../etc/x.recipe isn't a file in the mod" "$out"
has "a missing file: said" "recipes/none.recipe isn't a file in the mod" "$out"

# ---- mod recipes on stock libraries
libs=""
for d in ${HMGAPI_CARS:-$HOME/hmg-cars}/*/*/*/*/system/lib $(echo "${HMGAPI_STOCK_LIBS:-}" | tr ':' ' '); do
    [ -d "$d" ] && libs="$libs $d"
done
recipes=""
for r in $(echo "${HMGAPI_MODS:-}" | tr ':' ' '); do
    [ -d "$r" ] && recipes="$recipes $(find "$r" -path '*/recipes/*.recipe' -not -path '*/.git/*' -not -path '*/build/*' | sort | tr '\n' ' ')"
done
if [ -z "$libs" ] || [ -z "$(echo $recipes)" ]; then
    echo "skip  mod recipes on stock libraries (needs \$HMGAPI_STOCK_LIBS or \$HMGAPI_CARS, and \$HMGAPI_MODS)"
    exit $fail
fi
same=""; offs=""; n=0
for r in $recipes; do
    name=$(sed -n 's/^file .*\/\([^/.]*\)\.so$/\1/p' "$r")
    [ -n "$name" ] || continue
    for d in $libs; do
        for l in "$d"/$name*.so; do
            [ -f "$l" ] || continue
            n=$((n + 1)); b=$T/rel$n
            case $(python3 tools/sitefind.py fits "$r" "$l") in
                fits)
                    if python3 tools/sitefind.py relocate "$r" --lib "$l" > $b 2> $b.notes; then
                        same="$same $b $l $r"
                        sed "s|^note: |info  $(basename "$r"): |" $b.notes
                    else
                        echo "FAIL  $(basename "$r"): not relocated on $(basename "$l"): $(cat $b.notes)"; fail=1
                    fi ;;
                applied)
                    python3 - "$r" "$l" > $b <<'PY' && offs="$offs $b $l $r"
# the recipe's form with its sites made from this library's bytes (it has the change already):
# only to check where its sites, writes and call targets land, never to apply
import importlib.util, sys
spec = importlib.util.spec_from_file_location('sitefind', 'tools/sitefind.py'); sf = importlib.util.module_from_spec(spec); spec.loader.exec_module(sf)
text, b = open(sys.argv[1]).read(), open(sys.argv[2], 'rb').read()
regions = [(int(w[1], 0), int(w[2], 0)) for _, w in sf.recipe_lines(text) if w and w[0] == 'verify']
sites, out, extra = [], [], []
for k, (o, n) in enumerate(regions):
    line, so, sn = sf.unique_site(b, o, n, 'v%d' % k)
    sites.append(('v%d' % k, so, sn)); out.append(line)
def ref(off, n=1):
    for name, so, sn in sites:
        if so <= off and off + n <= so + sn:
            return name + ('+%#x' % (off - so) if off != so else '')
def target(t):
    r = ref(t & ~1)
    if r:
        return r if not t & 1 else (r.split('+')[0] + '+%#x' % ((int(r.split('+')[1], 0) if '+' in r else 0) + 1))
    f = sf.function_at(b, t & ~1)
    if f:
        return 'sym:%s%s' % (f[0], '+%#x' % ((t & ~1) - f[1]) if (t & ~1) != f[1] else '')
    line, so, sn = sf.unique_site(b, t & ~1, 32, 't%d' % len(extra)); extra.append(line); sites.append(('t%d' % (len(extra) - 1), so, sn))
    return target(t)
for _, w in sf.recipe_lines(text):
    if not w: continue
    if w[0] in ('id', 'file', 'native'): print(' '.join(w))
    elif w[0] == 'verify': out.append('verify %s %s' % (ref(int(w[1], 0), int(w[2], 0)), w[2]))
    elif w[0] == 'patch': out.append('patch %s %s' % (ref(int(w[1], 0)), w[2]))
    elif w[0] == 'branch': out.append('branch %s %s' % (ref(int(w[1], 0), 4), w[2]))
    elif w[0] == 'rel32': out.append('rel32 %s %s %s' % (w[1], w[2], target(int(w[3], 0))))
    elif w[0] == 'segment': out.append(' '.join(w))
print('\n'.join(out[:len(regions)] + extra + out[len(regions):]))
PY
                    ;;
                *)
                    if python3 tools/sitefind.py relocate "$r" > $b 2>/dev/null; then
                        why=$(java -cp "$CP" hmgapi.engine.SiteFind $b "$l" 2>&1 >/dev/null || true)
                        if [ -z "$why" ]; then
                            echo "info  $(basename "$r") (another build) resolves on $(basename "$l") ($(md5at "$l" 0 99999999 | cut -c1-8)): its code is there unchanged"
                        else
                            echo "info  $(basename "$r") (another build) on $(basename "$l"): $(echo "$why" | sed 's/^doesn.t fit: //'), nothing written"
                        fi
                    fi ;;
            esac
        done
    done
done
[ -n "$same" ] && { java -cp "$CP" hmgapi.engine.SiteFindTest --same $same || fail=1; }
[ -n "$offs" ] && { java -cp "$CP" hmgapi.engine.SiteFindTest --offsets $offs || fail=1; }
java -cp "$CP" hmgapi.engine.SiteFindTest --fuzz ${HMGAPI_FUZZ:-8} $(for d in $libs; do ls "$d"/*.so; done | sort -u) || fail=1
exit $fail
