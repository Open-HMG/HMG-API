#!/bin/sh
# Off-car test of raw Android Auto sends and the sensor list: raw sends only within a
# session, never overlapping its end; which sensor types are offered; navigation message
# lines (AaMessagesTest). Who owns which native recipe, which ModContext.nativePatched
# answers and sendRaw needs (NativeOwnersTest).
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
sh tools/engine-classes.sh "$CP"
javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/java/hmgapi/hook/AaMessages.java core/runtime/java/hmgapi/hook/NativeOwners.java \
    core/runtime/test/hmgapi/hook/AaMessagesTest.java core/runtime/test/hmgapi/hook/NativeOwnersTest.java
fail=0
java -cp "$CP" hmgapi.hook.AaMessagesTest || fail=1
java -cp "$CP" hmgapi.hook.NativeOwnersTest || fail=1
exit $fail
