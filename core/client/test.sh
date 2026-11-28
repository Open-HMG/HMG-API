#!/bin/sh
# Off-car test of hmgapi-client's settings calls (HmgApi.settings, saveSettings) against a fake
# hmgapid (core/client/test: stand-ins for the few Android and org.json classes it uses), as the
# daemon sees them: a service hmgapid runs as root names its mod (HMGAPI_MOD), nothing else does.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -nowarn -d "$CP" $(find core/client/test -name '*.java') $(find core/client/java -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -f "$CP/hmgapi/client/ClientTest.class" ] || { echo "FAIL  the client and its test didn't build"; exit 1; }
fail=0
t() { uid=$1 mod=$2; shift 2
    if [ "$mod" = "(unset)" ]; then env -u HMGAPI_MOD java -Dtest.uid=$uid -cp "$CP" hmgapi.client.ClientTest "$@" || fail=1
    else HMGAPI_MOD=$mod java -Dtest.uid=$uid -cp "$CP" hmgapi.client.ClientTest "$@" || fail=1; fi; }
t 0 dashcam "a root service (hmgapid's): names its mod in setting-get and setting-set" dashcam
t 0 side-panel-2 "... an id with a digit and dashes" side-panel-2
t 10071 dashcam "a mod's app (not root), HMGAPI_MOD in its environment anyway: no id (the daemon knows the app)" -
t 0 "(unset)" "root with no HMGAPI_MOD: no id (the daemon refuses, as before)" - refused
t 0 "Dash/../x" "root with an id that isn't one (case, slashes): not passed on (the daemon refuses, as before)" - refused
t 0 "" "root with an empty HMGAPI_MOD: no id (the daemon refuses, as before)" - refused
t 0 "-dashcam" "root with an id starting with a dash: not passed on (the daemon refuses, as before)" - refused
t 0 "$(printf 'a%.0s' $(seq 1 65))" "root with an id over 64 characters: not passed on (the daemon refuses, as before)" - refused
[ $fail = 0 ] && echo "all client checks passed"
exit $fail
