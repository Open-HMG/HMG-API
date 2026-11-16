#!/bin/sh
# Off-car test of relocatable memory recipes (research prototype, docs/research/self-mapping.md
# idea 4): tools/sitefind.py's matcher and hmgapi.engine.SiteFind on synthetic libraries.
#
# With stock libraries, also every mod recipe on them: no verify region matches more than once
# in any library of that name, and each recipe's relocatable form (sitefind.py relocate)
# resolves on its own stock library to exactly the shipped recipe. Stock libraries:
# $HMGAPI_CARS trees' system/lib (docs/car-trees.md) and the folders in $HMGAPI_STOCK_LIBS;
# recipes: every recipes/*.recipe under the repos in $HMGAPI_MODS (colon-separated).
# Prints offsets, counts and md5s only.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$CP" "$T"' EXIT
sh tools/engine-classes.sh "$CP" && javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/SiteFindTest.java 2>/dev/null
fail=0
python3 tools/sitefind.py selftest || fail=1
java -cp "$CP" hmgapi.engine.SiteFindTest || fail=1

libs=""
for d in ${HMGAPI_CARS:-$HOME/hmg-cars}/*/*/*/*/system/lib $(echo "${HMGAPI_STOCK_LIBS:-}" | tr ':' ' '); do
    [ -d "$d" ] && libs="$libs $d"
done
recipes=""
for r in $(echo "${HMGAPI_MODS:-}" | tr ':' ' '); do
    [ -d "$r" ] && recipes="$recipes $(find "$r" -path '*/recipes/*.recipe' -not -path '*/.git/*' | sort | tr '\n' ' ')"
done
if [ -z "$libs" ] || [ -z "$(echo $recipes)" ]; then
    echo "skip  mod recipes on stock libraries (needs \$HMGAPI_STOCK_LIBS or \$HMGAPI_CARS, and \$HMGAPI_MODS)"
    exit $fail
fi
if python3 tools/sitefind.py scan $recipes --libs $libs > "$T/scan"; then
    echo "ok    no verify region of $(echo $recipes | wc -w | tr -d ' ') recipe(s) matches twice in a stock library ($(grep -c unique "$T/scan") unique, $(grep -c 'no match' "$T/scan") absent)"
else
    grep MULTIPLE "$T/scan" | sed 's/^/FAIL  matches more than once: /'; fail=1
fi
args=""
for r in $recipes; do
    built=$(sed -n 's/.*from stock \([0-9a-f]\{32\}\).*/\1/p' "$r" | head -1)
    name=$(sed -n 's/^file .*\/\([^/]*\)$/\1/p' "$r")
    [ -n "$built" ] && [ -n "$name" ] || continue
    for d in $libs; do
        for l in "$d"/*; do
            [ -f "$l" ] && [ "$(md5sum < "$l" 2>/dev/null | cut -c1-32 || md5 -q "$l")" = "$built" ] || continue
            b=$(basename "$r" .recipe)
            python3 tools/sitefind.py relocate "$r" --lib "$l" > "$T/$b.rel" 2>/dev/null
            args="$args $T/$b.rel $l $r"
            grep '^verify' "$r" > "$T/$b.ship"
            break 2
        done
    done
done
if [ -n "$args" ]; then
    java -cp "$CP" hmgapi.engine.SiteFindTest $args || fail=1
    # the Wi-Fi CarPlay patch written as "jump" (a beq.w made b.w, bytes computed per build) instead of its bytes
    for a in $args; do
        case $a in */carplay-core-wifi.rel) rel=$a ;; esac
    done
    if [ -n "${rel:-}" ]; then
        lib=$(echo $args | tr ' ' '\n' | grep -A1 -x "$rel" | sed -n 2p)
        sed -e '/^patch v0+/d' "$rel" > "$T/jump.rel"; echo 'jump v0+0x10' >> "$T/jump.rel"
        java -cp "$CP" hmgapi.engine.SiteFind "$T/jump.rel" "$lib" > "$T/jump.recipe" || true
        if grep -q '^verify' "$T/jump.recipe" && [ "$(grep '^verify' "$T/jump.recipe")" = "$(grep '^verify' "${rel%.rel}.ship")" ]; then
            echo "ok    carplay-core-wifi as 'jump v0+0x10': the same bytes as the shipped recipe on its stock library"
        else
            echo "FAIL  carplay-core-wifi as 'jump v0+0x10' doesn't come out as the shipped recipe"; fail=1
        fi
    fi
else
    echo "skip  no recipe's own stock library found (the md5 in its 'from stock' line)"
fi
exit $fail
