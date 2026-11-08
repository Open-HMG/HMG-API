#!/usr/bin/env python3
"""The stock car trees on this machine, and every test that needs them (docs/car-trees.md).

usage: cars.py list
       cars.py test [--only ID[,ID]] [--suites S[,S]] [--mods REPO]... [--report] [--no-coverage]
       cars.py where DIR...
       cars.py ids

  list    every tree in $HMGAPI_CARS (default ~/hmg-cars), against its map's car.json md5s:
          exact, partial (the files it doesn't have), different (the files whose md5 isn't the
          map's: not stock, or another firmware), or no map
  test    every suite that needs stock files, on every tree, one at a time, at low priority
          (nice): maps (check-maps.py --rebuild), hooks (test-hooks.py), probe (probe-test.py),
          install and usb (the installer tests with the car's own app_process), and mod:<id>
          for each mod in the mod repos --mods (or $HMGAPI_MODS, space-separated) names: its
          test.sh and tools/*/test.sh through the mod SDK ($HMGAPI_SDK, or out/sdk), with the
          car in HMGAPI_CAR. Prints car x suite (ok, FAIL, skip), each suite's output in
          out/cars/<car>/<suite>.log. --report: the table as Markdown, with each tree's match
          and digest and nothing else from the files, for a PR. The hooks suite keeps each
          exact or partial car's rows in maps/coverage.json when it passes (other cars' rows
          stay); --no-coverage leaves the file alone. Exit 1 on any FAIL.
  where   for stock trees laid out some other way: the map each one matches, and the commands
          that would link it into $HMGAPI_CARS (nothing is moved)
  ids     the trees' ids, one per line (for scripts)
"""
import argparse, glob, json, os, shutil, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import cartrees as ct

SUITES = ['maps', 'hooks', 'probe', 'install', 'usb']
BAKSMALI = os.environ.get('BAKSMALI', os.path.expanduser('~/.local/share/smali/baksmali-2.5.2.jar'))


def describe(t):
    m, absent, diff = ct.match(t)
    if m == 'no map':
        return 'no map (maps/%s)' % t.id if t.id.count('/') == 3 else 'no map (not laid out as <make>/<model>/<region>/<firmware>)'
    n = len(t.map['files'])
    if m == 'exact':
        return 'exact (%d/%d files)' % (n, n)
    if m == 'partial':
        return 'partial (%d/%d files; not in the tree: %s)' % (n - len(absent), n, ', '.join(os.path.basename(p) for p in absent))
    return 'different (%d/%d files differ: %s; not stock, or another firmware)' % (len(diff), n, ', '.join(os.path.basename(p) for p in diff))


def cmd_list(a):
    trees = ct.found()
    if not trees:
        print('no car trees in %s (docs/car-trees.md)' % ct.cars_dir())
        return 0
    for t in trees:
        print('%-26s %s' % (t.id, describe(t)))
        if t.source != '$HMGAPI_CARS':
            print('%-26s   from %s: %s' % ('', t.source, t.path))
    return 0


def cmd_ids(a):
    for t in ct.found(quiet=True):
        print(t.id)
    return 0


def cmd_where(a):
    root = os.environ.get('HMGAPI_CARS') and '$HMGAPI_CARS' or '~/hmg-cars'
    best = {}   # map: (score, dir) of the tree that fits it best
    notes = []
    for d in a.dirs:
        d = os.path.abspath(os.path.expanduser(d)).rstrip('/')
        s = ct.system_dir(d) or d
        f = ct.fits(s)
        if not f:
            notes.append('# %s: fits no map (not stock, another firmware, or a car with no map yet)' % d)
        elif len(f) > 1 and f[1][0] == f[0][0]:
            notes.append('# %s: fits %s equally (the files it has are the same on each): not linked' % (d, ' and '.join(m for _, m in f if _ == f[0][0])))
        else:
            (score, m) = f[0]
            if m in best and best[m][0] >= score:
                notes.append('# %s: %s, but %s fits it better: not linked' % (d, m, best[m][1]))
                continue
            if m in best:
                notes.append('# %s: %s, but %s fits it better: not linked' % (best[m][1], m, d))
            best[m] = (score, d, s)
    for m, (score, d, s) in sorted(best.items()):
        n = len(json.load(open(os.path.join(ct.MAPS, m, 'car.json')))['files'])
        print('# %s: %s, %s' % (d, m, 'exact' if score[0] == n else 'partial (%d of %d files)' % (score[0], n)))
        print('mkdir -p %s/%s' % (root, m))
        print('ln -s %s %s/%s/system' % (s, root, m))
    for n in notes:
        print(n)
    return 0


# ---- test

def run(log, argv, env=None, cwd=REPO):
    os.makedirs(os.path.dirname(log), exist_ok=True)
    with open(log, 'w') as f:
        r = subprocess.run(argv, stdout=f, stderr=subprocess.STDOUT, cwd=cwd, env=dict(os.environ, **(env or {})))
    return 'ok' if r.returncode == 0 else 'FAIL'


def mod_folders(repos):
    out = []
    for r in repos:
        r = os.path.abspath(os.path.expanduser(r))
        for mj in sorted(glob.glob(os.path.join(r, '*', 'mod.json'))):
            out.append((json.load(open(mj)), os.path.dirname(mj)))
    return out


def sdk():
    s = os.environ.get('HMGAPI_SDK') or os.path.join(REPO, 'out', 'sdk')
    return s if os.path.isfile(os.path.join(s, 'test', 'lib.sh')) else None


def car_env(t):
    return {'HMGAPI_CAR': t.path, 'HMGAPI_CAR_ID': t.id, 'HMGAPI_CAR_PRODUCTS': ' '.join(t.products())}


def suite(name, t, logs, a):
    """(result, why): result ok / FAIL / skip"""
    log = os.path.join(logs, t.id.replace('/', '-'), name.replace(':', '-') + '.log')
    m = ct.match(t)[0]
    py = sys.executable
    if name in ('maps', 'hooks', 'probe') and not os.path.isfile(BAKSMALI):
        return 'skip', 'no baksmali'
    if name == 'maps':
        if m != 'exact':
            return 'skip', 'no map' if m == 'no map' else m + ' tree'
        return run(log, [py, os.path.join(HERE, 'check-maps.py'), '--rebuild', '--only', t.id]), ''
    if name == 'hooks':
        save = not a.no_coverage and m in ('exact', 'partial')
        return run(log, [py, os.path.join(HERE, 'test-hooks.py'), '--only', t.id] + (['--save-passing'] if save else [])), ''
    if name == 'probe':
        if not shutil.which('javac'):
            return 'skip', 'no javac'
        return run(log, [py, os.path.join(HERE, 'probe-test.py'), '--only', t.id]), ''
    if name in ('install', 'usb'):
        if not os.path.isfile(t.file('/system/bin/app_process')):
            return 'skip', 'no bin/app_process'
        if not os.path.isfile(os.path.join(REPO, 'out', 'payload', 'MANIFEST')):
            return 'skip', 'no build (./build.sh payload)'
        sh = 'install/common/install-test.sh' if name == 'install' else 'install/usb/test.sh'
        return run(log, ['sh', sh], car_env(t)), ''
    # mod:<id>
    mod, d = a.mod_by_id[name[4:]]
    s = sdk()
    if not s:
        return 'skip', 'no SDK (./build.sh sdk)'
    want = mod.get('system') or []
    if want and t.products() and not any(p.startswith(w) or w.startswith(p) for p in t.products() for w in want):
        return 'skip', 'not in its "system"'
    if mod['id'] not in a.built:
        a.built[mod['id']] = run(os.path.join(logs, 'mod-' + mod['id'] + '-build.log'),
                                 ['sh', os.path.join(s, 'bin', 'build-mod.sh'), d, os.path.join(logs, 'build', mod['id']),
                                  os.path.join(logs, 'work', mod['id'])], {'HMGAPI_SDK': s})
    if a.built[mod['id']] != 'ok':
        return 'FAIL', 'build (mod-%s-build.log)' % mod['id']
    res = 'ok'
    os.makedirs(os.path.dirname(log), exist_ok=True)
    with open(log, 'w'):
        pass
    for i, test in enumerate([os.path.join(d, 'test.sh')] + sorted(glob.glob(os.path.join(d, 'tools', '*', 'test.sh')))):
        if os.path.isfile(test):
            env = dict(car_env(t), HMGAPI_SDK=s, HMGAPI_MOD_BUILD=os.path.join(logs, 'build', mod['id']))
            if run(log + '.%d' % i, ['sh', test], env, cwd=os.path.dirname(d)) != 'ok':
                res = 'FAIL'
            with open(log, 'a') as f, open(log + '.%d' % i) as g:
                f.write('== %s\n%s' % (os.path.relpath(test, os.path.dirname(d)), g.read()))
            os.remove(log + '.%d' % i)
    return res, ''


def git_rev():
    r = subprocess.run(['git', 'describe', '--always', '--dirty'], cwd=REPO, capture_output=True, text=True)
    return r.stdout.strip() or 'unknown'


def cmd_test(a):
    trees = ct.pick(a.only)
    if not trees:
        print('no car trees%s in %s (docs/car-trees.md)' % (' named ' + a.only if a.only else '', ct.cars_dir()))
        return 0
    os.nice(19)
    repos = a.mods if a.mods else (os.environ.get('HMGAPI_MODS') or '').split()
    a.mod_by_id = {m['id']: (m, d) for m, d in mod_folders(repos)}
    a.built = {}
    names = (a.suites.split(',') if a.suites else SUITES) + (['mod:' + i for i in a.mod_by_id] if not a.suites else [])
    for n in names:
        if n not in SUITES and not (n.startswith('mod:') and n[4:] in a.mod_by_id):
            sys.exit('no suite %s (%s, or mod:<id> of a mod in --mods)' % (n, ', '.join(SUITES)))
    logs = os.path.abspath(a.logs)
    table = {}
    for t in trees:
        for n in names:
            r = table[t.id, n] = suite(n, t, logs, a)
            print('%-5s %s %s%s' % (r[0], t.id, n, ': ' + r[1] if r[1] else ''), flush=True)
    cell = lambda r: r[0] + (' (%s)' % r[1] if r[1] else '')
    print()
    if a.report:
        print('#### Tested on cars (`tools/cars.py test`, HMG-API %s)\n' % git_rev())
        print('| car | stock files (vs car.json) | digest | %s |' % ' | '.join(names))
        print('|---|---|---|%s' % ('---|' * len(names)))
        for t in trees:
            print('| %s | %s | %s | %s |' % (t.id, describe(t), ct.digest(t) or '-', ' | '.join(cell(table[t.id, n]) for n in names)))
    else:
        w = max(len(t.id) for t in trees)
        print('%-*s  %s' % (w, 'car', '  '.join('%-8s' % n for n in names)))
        for t in trees:
            print('%-*s  %s' % (w, t.id, '  '.join('%-8s' % table[t.id, n][0] for n in names)))
        print('\nlogs: %s/<car>/<suite>.log' % os.path.relpath(logs))
    fails = sum(r[0] == 'FAIL' for r in table.values())
    print('%s%d car(s), %d suite(s): %s' % ('\n' if a.report else '', len(trees), len(names), '%d FAIL' % fails if fails else 'no FAIL'))
    return 1 if fails else 0


def main():
    ap = argparse.ArgumentParser(description='stock car trees and the tests that need them (docs/car-trees.md)')
    sub = ap.add_subparsers(dest='cmd', required=True)
    sub.add_parser('list')
    sub.add_parser('ids')
    w = sub.add_parser('where')
    w.add_argument('dirs', nargs='+')
    t = sub.add_parser('test')
    t.add_argument('--only', help='car ids, comma-separated')
    t.add_argument('--suites', help='%s, mod:<id> (default: all)' % ', '.join(SUITES))
    t.add_argument('--mods', action='append', default=[], help='a mod repo (a folder of mod folders); again for more')
    t.add_argument('--report', action='store_true')
    t.add_argument('--no-coverage', action='store_true')
    t.add_argument('--logs', default=os.path.join(REPO, 'out', 'cars'), help='where each suite\'s output goes (out/cars)')
    a = ap.parse_args()
    sys.exit({'list': cmd_list, 'ids': cmd_ids, 'where': cmd_where, 'test': cmd_test}[a.cmd](a))


if __name__ == '__main__':
    main()
