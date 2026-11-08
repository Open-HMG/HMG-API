#!/bin/sh
# Make a signed HMG-API release: out/releases/hmgapi-<version>.zip (the payload,
# signed with HMG-API's release key). On a USB stick in HMGAPI/updates/, the HMG-API
# app offers it; over adb: hmgapid engine update-hmgapi <zip>.
#   tools/mkrelease.sh [release key]   (default ~/hmg-private/keys/leafia-release.key)
set -e
cd "$(dirname "$0")/.."
KEY=${1:-$HOME/hmg-private/keys/leafia-release.key}
./build.sh payload >/dev/null
V=$(cat out/payload/VERSION)
CP=out/engine-host
mkdir -p "$CP" && javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r out/payload "$W/hmgapi-$V"
java -cp "$CP" hmgapi.engine.Signing sign-hmgapi "$W/hmgapi-$V" "$KEY"
mkdir -p out/releases
Z=$PWD/out/releases/hmgapi-$V.zip
rm -f "$Z"
# fixed order and times: UTC and the C locale for all of it (zip writes local times, sort follows the locale)
(cd "$W" && export TZ=UTC LC_ALL=C && find "hmgapi-$V" -type f | sort | xargs touch -d 2026-01-01T00:00:00 && find "hmgapi-$V" -type f | sort | zip -q -X -D "$Z" -@)
echo "$Z"
