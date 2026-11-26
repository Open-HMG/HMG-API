#!/bin/sh
# Off-car test of the engine's HTTPS pieces (Http, NativeFetch.Web, Sha256, DnsLookup); the library
# itself (core/fetch/fetch.c) needs the unit's libssl, or a computer's: catalog-test.sh's notes.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
javac --release 8 -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/FetchTest.java 2>/dev/null
java -cp "$CP" hmgapi.engine.FetchTest
