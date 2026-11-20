#!/bin/sh
# Off-car test of the readings self-test (research prototype, docs/research/self-mapping.md
# idea 3): hmgapi.engine.SelfTest replayed on recorded runs (core/runtime/test/selftest/*.json).
# Each run says what it expects ("expect": check -> pass, fail or skip; "result": overall).
# A run recorded on a car (`hmgapid vehicle ...` every few seconds, as JSON) goes in the same
# folder: readings only, never anything per car.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
fail=0
for f in core/runtime/test/selftest/*.json; do
    out=$(java -cp "$CP" hmgapi.engine.SelfTest "$f") || true
    python3 - "$f" "$out" <<'PY' || fail=1
import json, os, sys
run = json.load(open(sys.argv[1]))
got = json.loads(sys.argv[2])
seen = {c['check']: c for c in got['checks']}
bad = []
for k, want in run['expect'].items():
    have = got['result'] if k == 'result' else seen.get(k, {}).get('result', 'absent')
    if have != want:
        bad.append('%s: wanted %s, got %s (%s)' % (k, want, have, seen.get(k, {}).get('detail', '')))
name = os.path.basename(sys.argv[1])
if bad:
    print('FAIL  %s (%s): %s' % (name, run['about'], '; '.join(bad)))
    sys.exit(1)
print('ok    %s: %s' % (name, run['about']))
PY
done
exit $fail
