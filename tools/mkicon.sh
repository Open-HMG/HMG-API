#!/bin/sh
# Make app-menu tile pictures in the stock style from any picture (PNG, JPEG, SVG):
# the shape, sized and centred on a 99x99 transparent tile like the launcher's own
# hom_*_66 icons, filled with the stock gradient (cyan top left to purple bottom
# right, darker at night), plus the stock "disabled" look (flat grey, faint).
#   tools/mkicon.sh <picture> <out dir> [glyph size, default 58] [shape colour]
# Writes icon.png, icon_night.png, icon_disabled.png, icon_disabled_night.png:
#   "launcher": [{..., "icon": "icon.png", "icon_night": "icon_night.png",
#                 "icon_disabled": "icon_disabled.png", "icon_disabled_night": "icon_disabled_night.png"}]
# The shape is the picture's transparency; a picture with no transparency is
# read as a dark or light shape on a plain background (the top left pixel's colour).
# With a shape colour (e.g. '#41ae45'), the shape is whatever is that colour: for a
# logo drawn on its own plate (tools/mkicon.sh docs/img/hmg-api.png out 66 '#41ae45').
# Colours measured from the US EV6's Launcher_WP3 (260507, drawable-hdpi and
# drawable-night-hdpi). Needs ImageMagick (magick, or convert from 6.x).
set -e
IN=${1:?usage: mkicon.sh <picture> <out dir> [glyph size] [shape colour]}; OUT=${2:?usage: mkicon.sh <picture> <out dir> [glyph size] [shape colour]}
SIZE=${3:-58} COLOUR=$4
M=magick; command -v magick >/dev/null || M=convert
command -v $M >/dev/null || { echo "mkicon: needs ImageMagick"; exit 1; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$OUT"

# the shape as a grey mask (white = shape), trimmed to its box
SRC=$IN
case "$IN" in *.svg|*.SVG) $M -list format 2>/dev/null | grep -q '^ *RSVG' && SRC="RSVG:$IN" ;; esac   # librsvg, not a desktop app
$M -background none -density 384 "$SRC" -alpha on "$W/in.png"
OPAQUE=$($M "$W/in.png" -alpha extract -format '%[fx:minima==1?1:0]' info:)
if [ -n "$COLOUR" ]; then   # near the colour: shape (soft edges kept), far from it: not
    $M "$W/in.png" -alpha off \( +clone -fill "$COLOUR" -colorize 100 \) -compose difference -composite \
        -colorspace gray -negate -level 65%,100% \( "$W/in.png" -alpha extract \) -compose multiply -composite "$W/mask.png"
elif [ "$OPAQUE" = 1 ]; then
    BG=$($M "$W/in.png" -format '%[pixel:p{0,0}]' info:)
    $M "$W/in.png" -alpha off \( +clone -fill "$BG" -colorize 100 \) -compose difference -composite \
        -colorspace gray -auto-level "$W/mask.png"
else
    $M "$W/in.png" -alpha extract "$W/mask.png"
fi
$M "$W/mask.png" -trim +repage -resize "${SIZE}x${SIZE}" -background black -gravity center -extent 99x99 +repage "$W/mask.png"

# the gradient runs across the shape's own box, as on the stock tiles
set -- $($M "$W/mask.png" -threshold 50% -format '%@' info: | tr 'x+' '  ')
BW=$1 BH=$2 X0=$3 Y0=$4
X1=$((X0 + BW - 1)) Y1=$((Y0 + BH - 1))
tile() {   # <out> <top left> <top right> <bottom left> <bottom right>
    $M -size 99x99 xc: -sparse-color Bilinear "$X0,$Y0 $2 $X1,$Y0 $3 $X0,$Y1 $4 $X1,$Y1 $5" \
        "$W/mask.png" -alpha off -compose CopyOpacity -composite -depth 8 -strip "$OUT/$1"
}
flat() {   # <out> <opacity>
    $M -size 99x99 xc:'#62797f' \( "$W/mask.png" -evaluate multiply "$2" \) -alpha off -compose CopyOpacity -composite \
        -depth 8 -strip "$OUT/$1"
}
tile icon.png '#23e4c5' '#32b6ba' '#758cd7' '#8763cc'
tile icon_night.png '#2ab8aa' '#2d9aa3' '#4c61b4' '#4d3fae'
flat icon_disabled.png 0.2
flat icon_disabled_night.png 0.4
echo "$OUT: icon.png icon_night.png icon_disabled.png icon_disabled_night.png"
