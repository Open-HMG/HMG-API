#!/bin/sh
# HMG-API's icon (its app-menu tile and the app's own icon) from the logo's green
# letters (docs/img/hmg-api.png), in the stock tile style (tools/mkicon.sh): day and
# night, normal and greyed out, for hdpi (the head units' density, as the launcher's own tiles).
set -e
cd "$(dirname "$0")/.."
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
tools/mkicon.sh docs/img/hmg-api.png "$W" 66 '#41ae45' >/dev/null
R=core/app/res
mkdir -p $R/drawable-hdpi $R/drawable-night-hdpi
cp "$W/icon.png" $R/drawable-hdpi/ic_hmgapi.png
cp "$W/icon_disabled.png" $R/drawable-hdpi/ic_hmgapi_disabled.png
cp "$W/icon_night.png" $R/drawable-night-hdpi/ic_hmgapi.png
cp "$W/icon_disabled_night.png" $R/drawable-night-hdpi/ic_hmgapi_disabled.png
echo "$R/drawable-hdpi, $R/drawable-night-hdpi: ic_hmgapi.png ic_hmgapi_disabled.png"
