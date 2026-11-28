#!/bin/sh
# Build hmgapid for the head unit (ARMv7 Thumb, static, no libc).
# OUT: output folder (default ../../out). Extra arguments go to clang (tests use -D...).
set -e
cd "$(dirname "$0")"
. ../../tools/env.sh
OUT=${OUT:-../../out}
mkdir -p "$OUT"
# the version: the release's, from Engine.java (the one place a release sets it)
V=$(sed -n 's/.*HMGAPI_VERSION = "\(.*\)";/\1/p' ../runtime/java/hmgapi/engine/Engine.java)
[ -n "$V" ] || { echo "hmgapid: no HMGAPI_VERSION in Engine.java" >&2; exit 1; }
clang $ARM_STATIC -Wall -Wno-unused-function -DVERSION="\"$V\"" "$@" -o "$OUT/hmgapid" hmgapid.c
md5sum "$OUT/hmgapid"
