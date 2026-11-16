#!/bin/sh
# Off-car test of HMG-API's Android Auto library recipe and raw sender: raw sends only
# within a session with the recipe on, and never overlapping its end; which sensor types
# are offered; navigation message lines (AaMessagesTest); core/recipes is what
# CoreRecipes holds (CoreRecipesTest); tools/mkaarecipe.py on synthetic libraries. With
# a car tree that has the US 260507 library ($HMGAPI_CARS, docs/car-trees.md), the
# recipe is made again from it and applied to it.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$CP" "$T"' EXIT
sh tools/engine-classes.sh "$CP"
javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/java/hmgapi/hook/AaMessages.java core/runtime/java/hmgapi/hook/CoreRecipes.java \
    core/runtime/test/hmgapi/hook/AaMessagesTest.java core/runtime/test/hmgapi/hook/CoreRecipesTest.java
fail=0
ok() { echo "ok    $1"; }
bad() { echo "FAIL  $1"; fail=1; }
java -cp "$CP" hmgapi.hook.AaMessagesTest || fail=1

# tools/mkaarecipe.py on synthetic libraries (random bytes, the stock instructions at the sites)
python3 -I - "$T" <<'PY'
import random, sys
T = sys.argv[1]
random.seed(44)
def lib(sites, queue):
    b = bytearray(random.getrandbits(8) for _ in range(0x150000))
    for off, old in zip(sites, (bytes.fromhex("1428"), bytes.fromhex("6ff00807"), bytes.fromhex("2de9f041"))):
        b[off:off + len(old)] = old
    b[queue:queue + 6] = bytes.fromhex("30b504460079")
    return b
open(T + "/other.so", "wb").write(lib((0x145002, 0xBB3A2, 0xBB73C), 0xB1A70))
open(T + "/us-offsets.so", "wb").write(lib((0x144EC2, 0xBB29E, 0xBB638), 0xB1972))
PY
M="python3 -I tools/mkaarecipe.py"
R=core/recipes/libautoreceiver_infobank.so/aa-raw-48ee793a.recipe
if $M "$T/us-offsets.so" "$T/x.recipe" >/dev/null 2>&1; then bad "a library that isn't 48ee793a was taken for it"; else ok "without --sites: only the US 260507 build"; fi
$M "$T/us-offsets.so" "$T/us.recipe" --sites 0x144ec2,0xbb29e,0xbb638 --queue 0xb1972 --id aa-raw-test0000 >/dev/null
[ "$(grep -E '^(patch|verify 0x[0-9a-f]+ [0-9]+)' "$T/us.recipe" | sed 's/ [0-9a-f]\{32\}.*//')" = "$(grep -E '^(patch|verify)' $R | sed 's/ [0-9a-f]\{32\}.*//')" ] \
    && ok "at the US offsets: the shipped recipe's windows and patch bytes" || bad "the tool's windows or patch bytes differ from $R"
$M "$T/other.so" "$T/other.recipe" --sites 0x145002,0xbb3a2,0xbb73c --queue 0xb1a70 --id aa-raw-0ther000 >/dev/null
grep -q '^patch 0xbb73c f6f798b9$' "$T/other.recipe" && ok "another build: its own b.w to its queueOutgoing" || bad "another build's branch: $(grep '^patch 0xbb73c' "$T/other.recipe")"
if $M "$T/other.so" "$T/y.recipe" --sites 0x145000,0xbb3a2,0xbb73c --queue 0xb1a70 --id y >/dev/null 2>&1; then bad "wrong bytes at a site accepted"; else ok "other bytes at a site: refused"; fi
if $M "$T/other.so" "$T/y.recipe" --sites 0x145002,0xbb3a2,0xbb73c --queue 0xb1a72 --id y >/dev/null 2>&1; then bad "a branch target that isn't queueOutgoing accepted"; else ok "a branch target that isn't queueOutgoing: refused"; fi
if $M "$T/other.so" "$T/y.recipe" --sites 0x145002,0xbb3a2,0xbb73c --queue 0xb1a70 --id y --avoid "$T/other.recipe" >/dev/null 2>&1; then bad "overlap with --avoid accepted"; else ok "overlapping an --avoid recipe: refused"; fi
if $M "$T/other.so" "$T/y.recipe" --sites 0x145002,0xbb3a2,0xbb73c --queue 0xb1a70 >/dev/null 2>&1; then bad "--sites without --id accepted"; else ok "--sites needs --queue and --id"; fi

# the real library, from car trees that have it (stock files only: never in the repo)
set --
for t in "${HMGAPI_CARS:-$HOME/hmg-cars}"/*/*/*/*/system/lib/libautoreceiver_infobank.so; do
    [ -f "$t" ] || continue
    md5=$(python3 -I -c 'import hashlib,sys; print(hashlib.md5(open(sys.argv[1],"rb").read()).hexdigest())' "$t")
    for r in core/recipes/libautoreceiver_infobank.so/*.recipe; do
        grep -q "stock build $md5" "$r" || continue
        $M "$t" "$T/again.recipe" >/dev/null && cmp -s "$T/again.recipe" "$r" \
            && ok "$(basename "$r"): made again from ${t%/system/*}" || bad "$(basename "$r") differs when made from $t"
        set -- "$@" libautoreceiver_infobank.so "$t"
    done
done
[ $# -gt 0 ] || echo "skip  the recipe on the real library: no car tree with a library build core/recipes has (\$HMGAPI_CARS)"
java -cp "$CP" hmgapi.hook.CoreRecipesTest core/recipes "$@" || fail=1
exit $fail
