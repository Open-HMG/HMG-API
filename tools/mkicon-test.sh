#!/bin/sh
# Test of tools/mkicon.sh: a shape with transparency (an SVG), one without (a black square on white),
# and one picked by colour (HMG-API's logo: green letters on a black plate).
set -e
cd "$(dirname "$0")/.."
M=magick; command -v magick >/dev/null || M=convert
command -v $M >/dev/null || { echo "skip  mkicon: no ImageMagick"; exit 0; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
px() { $M "$1" -format "%[pixel:p{$2}]" info:; }
echo '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" fill="#fff"/></svg>' > $T/c.svg
tools/mkicon.sh $T/c.svg $T/svg >/dev/null
for f in icon icon_night icon_disabled icon_disabled_night; do
    check "svg $f is 99x99 8-bit" "99x99 8" "$($M $T/svg/$f.png -format '%wx%h %z' info:)"
    check "svg $f corner is clear" "0" "$($M $T/svg/$f.png -format '%[fx:p{0,0}.a]' info:)"
done
check "svg shape is 58 high (the default)" "58" "$($M $T/svg/icon.png -alpha extract -format '%@' info: | sed 's/^[0-9]*x\([0-9]*\).*/\1/')"
$M -size 200x100 xc:white -fill black -draw 'rectangle 20,10 179,89' $T/sq.png
tools/mkicon.sh $T/sq.png $T/sq 58 >/dev/null
check "opaque: shape found, centred" "58x29+20+35" "$($M $T/sq/icon.png -alpha extract -threshold 50% -format '%@' info:)"
check "day top left is the stock cyan" "srgba(35,228,197,1)" "$(px $T/sq/icon.png 20,35)"
check "day bottom right is the stock purple" "srgba(135,99,204,1)" "$(px $T/sq/icon.png 77,63)"
check "night top left" "srgba(42,184,170,1)" "$(px $T/sq/icon_night.png 20,35)"
check "disabled is the stock grey at 20%" "srgba(98,121,127,0.2)" "$(px $T/sq/icon_disabled.png 40,50)"
check "disabled at night at 40%" "srgba(98,121,127,0.4)" "$(px $T/sq/icon_disabled_night.png 40,50)"
tools/mkicon.sh docs/img/hmg-api.png $T/logo 66 '#41ae45' >/dev/null
check "by colour: the letters, not the plate" "66x53+16+23" "$($M $T/logo/icon.png -alpha extract -format '%@' info:)"
check "by colour: between the letters is clear" "0" "$($M $T/logo/icon.png -format '%[fx:p{49,49}.a]' info:)"
exit $fail
