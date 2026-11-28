#!/bin/sh
# Off-car test of the engine's HTTPS pieces (Http, NativeFetch.Web, Sha256, DnsLookup), then the
# library itself (core/fetch/fetch.c) built for this computer (-DHOST: no SO_MARK or wlan0 here)
# against a local TLS server, when there's a C compiler, the JDK's jni.h and an OpenSSL 1.1+/3
# libssl (HMGAPI_TEST_SSL, or Homebrew's or the system's); skipped otherwise.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
javac --release 8 -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/FetchTest.java core/runtime/test/hmgapi/engine/FetchNativeTest.java 2>/dev/null
java -cp "$CP" hmgapi.engine.FetchTest

JH=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')
SSL=${HMGAPI_TEST_SSL:-}
for s in /opt/homebrew/opt/openssl@3/lib/libssl.dylib /usr/local/opt/openssl@3/lib/libssl.dylib /usr/lib/x86_64-linux-gnu/libssl.so.3 /usr/lib/aarch64-linux-gnu/libssl.so.3 /usr/lib64/libssl.so.3; do
    [ -n "$SSL" ] || { [ -f "$s" ] && SSL=$s; }
done
case $(uname) in Darwin) OS=darwin EXT=dylib ;; *) OS=linux EXT=so ;; esac
if ! command -v cc >/dev/null || [ ! -f "$JH/include/jni.h" ] || [ -z "$SSL" ] || ! command -v keytool >/dev/null; then
    echo "skip  the library on this computer: needs cc, the JDK's jni.h, keytool and an OpenSSL 1.1+/3 libssl (HMGAPI_TEST_SSL)"
    exit 0
fi
cc -DHOST -shared -fPIC -Wall -Werror -I"$JH/include" -I"$JH/include/$OS" -o "$CP/libhmgapi_fetch.$EXT" core/fetch/fetch.c
keytool -genkeypair -alias t -keyalg EC -groupname secp256r1 -dname CN=localhost -validity 2 -storetype PKCS12 \
    -keystore "$CP/ks.p12" -storepass fetchtest -keypass fetchtest >/dev/null 2>&1
java -cp "$CP" hmgapi.engine.FetchNativeTest "$CP/libhmgapi_fetch.$EXT" "$SSL" "$CP/ks.p12" fetchtest
