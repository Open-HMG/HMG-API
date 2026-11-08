#!/usr/bin/env python3
"""Check the hook point catalogue and every car's map.

usage: check-maps.py [--rebuild [--only CAR[,CAR]]]

- point ids are unique; a member point's "of" is a class point
- every map (maps/<make>/<model>/<region>/<firmware>/) has every catalogue point,
  with an entry of the right kind, or lists it in car.json "missing" (with why), and
  a valid car.json
- --rebuild: maps with a stock tree on this machine ($HMGAPI_CARS, docs/car-trees.md)
  are made again with map-car.py and must come out identical; trees whose files aren't
  exactly the map's (tools/cars.py list) are skipped
Exit 1 on any problem: a new hook point has to be mapped for every car.
"""
import glob, json, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import cartrees
problems = []
points = {}
for f in sorted(glob.glob(os.path.join(REPO, 'core', 'hookpoints', '*.json'))):
    cat = json.load(open(f))
    for p in cat['points']:
        if p['id'] in points:
            problems.append('point %s is in the catalogue twice' % p['id'])
        points[p['id']] = p
for p in points.values():
    if p['kind'] != 'class':
        o = points.get(p.get('of'))
        if o is None or o['kind'] != 'class':
            problems.append('%s: "of" %s isn\'t a class point' % (p['id'], p.get('of')))
KEY = {'class': 'class', 'method': 'method', 'ctor': 'ctor', 'field': 'field'}
maps = sorted(glob.glob(os.path.join(REPO, 'maps', '*', '*', '*', '*', 'car.json')))
for cj in maps:
    d = os.path.dirname(cj)
    rel = os.path.relpath(d, os.path.join(REPO, 'maps'))
    car = json.load(open(cj))
    for k in ('make', 'model', 'region', 'firmware', 'products', 'status', 'files', 'apps', 'app_files'):
        if k not in car:
            problems.append('%s: car.json has no %s' % (rel, k))
    if car.get('status') not in ('mapped', 'probed', 'verified'):
        problems.append('%s: status %r (mapped, probed or verified)' % (rel, car.get('status')))
    if car.get('status') == 'verified' and not car.get('verified'):
        problems.append('%s: verified, but no verified record (who, when, report)' % rel)
    entries = {}
    for app in car.get('apps', []):
        entries.update(json.load(open(os.path.join(d, app + '.json'))))
    missing = car.get('missing', {})
    for pid, p in points.items():
        e = entries.get(pid)
        if e is None and pid not in missing:
            problems.append('%s: no %s (map it, or run map-car.py again to list it as missing)' % (rel, pid))
        elif e is not None and pid in missing:
            problems.append('%s: %s is both mapped and missing' % (rel, pid))
        elif e is None:
            continue
        elif KEY[p['kind']] not in e:
            problems.append('%s: %s should be a %s' % (rel, pid, p['kind']))
    for pid in list(entries) + list(missing):
        if pid not in points:
            problems.append('%s: %s isn\'t in the catalogue' % (rel, pid))
if '--rebuild' in sys.argv:
    only = sys.argv[sys.argv.index('--only') + 1] if '--only' in sys.argv[:-1] else None
    for t in cartrees.pick(only):
        folder, tree, car = t.id, t.path, t.map
        if car is None:
            continue
        m = cartrees.match(t)[0]
        if m != 'exact':
            print('skip  %s: %s tree (tools/cars.py list)' % (folder, m))
            continue
        with tempfile.TemporaryDirectory() as t:
            mk, mo, rg, fw = folder.split('/')
            r = subprocess.run([sys.executable, os.path.join(HERE, 'map-car.py'), tree, '--make', mk, '--model', mo,
                                '--region', rg, '--firmware', fw, '--products', ','.join(car['products']),
                                '--name', car['name'], '--maps', t, '--work', os.path.join(t, 'work')],
                               capture_output=True, text=True)
            if r.returncode:
                problems.append('%s: map-car.py failed: %s' % (folder, r.stderr.strip()[-200:]))
                continue
            for f in glob.glob(os.path.join(t, folder, '*.json')):
                a, b = json.load(open(f)), json.load(open(os.path.join(REPO, 'maps', folder, os.path.basename(f))))
                if os.path.basename(f) == 'car.json':
                    a = {k: v for k, v in a.items() if k not in ('status', 'verified')}
                    b = {k: v for k, v in b.items() if k not in ('status', 'verified')}
                if a != b:
                    problems.append('%s: %s rebuilt differently from the stock tree' % (folder, os.path.basename(f)))
            print('ok    rebuilt %s from %s' % (folder, tree))
for p in problems:
    print('FAIL  ' + p)
print('%d hook points, %d car map(s)%s' % (len(points), len(maps), '' if problems else ': all complete'))
sys.exit(1 if problems else 0)
