#!/usr/bin/env python3
"""Does any order of events leave a head unit that can't start Android?

Runs the real install.sh and uninstall.sh (this tree's, and older releases' from their git
tags) on a made-up head unit, through every order of events up to a depth, and after each
one follows what the zygote start would run: app_process, NEMESIS 1.17's LivePatch script to
app_process.real, an HMG-API wrapper to app_process.hmgapi-stock, and so on to a stock binary.
An order is dead when that ends anywhere else (a loop, a missing file, a script, an empty or
short file). Found #397's K5 (NEMESIS, a forced older install, then 1.1.0-rc.9's update) at
depth 3, and the same mistake by eleven other roads.

Events (--order takes them by these names):
  install, force-install, uninstall, force-uninstall, purge     this tree's scripts (the
                                    uninstall is whichever release's the last install saved)
  install@TAG, force-install@TAG    an older release's install.sh
  restart                           NEMESIS's watchdog runs (svc.c -> lp_wrapper_install): its
                                    script goes to app_process; the binary found there is kept
                                    as app_process.real, once, whatever it is
  nemesis                           NEMESIS 1.17 installed (its watchdog runs at once)
  nemesis-uninstall                 NEMESIS Manager's (pkg.c wipe -> lp_wrapper_remove):
                                    app_process.real back to app_process, if its script is there
  nemesis-usb-uninstall             usb_bundle/uninstall_nemesis.sh: the same, script or not
  firmware, firmware-new            the firmware installed again (same build, a newer one):
                                    /system as shipped, /data kept
  factory-reset                     /data wiped, /system kept

Two passes, each on a build stock.txt knows and on one it doesn't (where only forced installs go on):
  orders   every order up to --depth; fails when one ends dead with this tree's install or
           uninstall as its last step. Older releases and NEMESIS end many dead by themselves
           (#397's table); those are counted, not failed: these scripts can't change them.
  cut-off  from a few starting points, this tree's install with its Nth copy failing part-way
           (a short file left, as a full disk does) or the installer killed there (a reset),
           then the install again, then each uninstall; fails when any of that ends dead.

usage: tools/zygote-orders.py [--depth N] [--scripts DIR] [--releases TAG,TAG] [--stock known|unknown|both]
                              [--pass orders|cut-off|both] [--cut fail|kill|both] [--quick] [-j N] [--list]
                              [--order "event event ..."]
Exit 0: nothing these scripts do ends dead. 1: something does (listed). 3: a release's tag isn't in this clone.
"""
import argparse, hashlib, os, shutil, subprocess, sys, tempfile
from multiprocessing import Pool

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES = ['v1.0.2', 'v1.1.0-rc.8', 'v1.1.0-rc.9']
# NEMESIS 1.17's script, byte for byte (native/livepatch.c WRAPPER_TEXT; md5 f0fa894f6898c28850bfcebfb8db69d0)
NLP = (b'#!/system/bin/sh\n# NEMESIS LivePatch: apply patches before the zygote starts, then run the real binary.\n'
       b'case " $* " in\n*" --zygote "*) [ -x /system/xbin/nemesis ] && /system/xbin/nemesis boot-apply ;;\nesac\n'
       b'exec /system/bin/app_process.real "$@"\n')
AP, REAL, KEEP = 'system/bin/app_process', 'system/bin/app_process.real', 'system/bin/app_process.hmgapi-stock'
BAK = 'data/hmgapi/stock/system/bin/app_process'
NEM = 'system/xbin/nemesis'


def blob(tag, n=3000):
    out, h = b'', tag.encode()
    while len(out) < n:
        h = hashlib.sha256(h).digest()
        out += h
    return out


ELF = b'\x7fELF\x01\x01\x01\x00'
STOCK, STOCK2 = ELF + blob('stock'), ELF + blob('stock, a newer firmware')
# every HMG-API wrapper names the stock copy it runs; 1.0.x's bytes differ from 1.1.0-rc.7's on
WRAP_OLD = ELF + blob('wrapper 1.0') + b'/system/bin/app_process.hmgapi-stock\0'
WRAP = ELF + blob('wrapper') + b'/system/bin/app_process.hmgapi-stock\0'
KINDS = {hashlib.md5(b).hexdigest(): k for b, k in
         ((STOCK, 'stock'), (STOCK2, 'stock'), (WRAP_OLD, 'wrapper'), (WRAP, 'wrapper'), (NLP, 'nemesis-script'))}
CFG = {}


def rd(p):
    try:
        with open(p, 'rb') as f:
            return f.read()
    except OSError:
        return None


def wr(p, b, mode=0o755):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(b)
    os.chmod(p, mode)


def kind(b):
    if b is None:
        return 'none'
    if not b:
        return 'empty'
    k = KINDS.get(hashlib.md5(b).hexdigest())
    if k:
        return k
    return 'short' if b[:4] == ELF[:4] else 'other'


def after_watchdog(R):
    """app_process and app_process.real as NEMESIS's watchdog leaves them (lp_wrapper_install)"""
    ap, real = rd(os.path.join(R, AP)), rd(os.path.join(R, REAL))
    if not os.path.exists(os.path.join(R, NEM)) or ap is None or ap == NLP:
        return ap, real
    if not ap.startswith(b'#!/system/bin/sh\n# NEMESIS LivePatch') and real is None:
        real = ap
    return (NLP, real) if real is not None else (ap, real)


def starts(R):
    """(True, the chain) when the zygote start ends at a stock binary once the watchdog has run"""
    ap, real = after_watchdog(R)
    files = {AP: ap, REAL: real, KEEP: rd(os.path.join(R, KEEP))}
    f, seen, path = AP, [], []
    while True:
        k = kind(files[f])
        path.append('%s (%s)' % (f.split('/')[-1], k))
        if k == 'stock':
            return True, ' -> '.join(path)
        if f in seen:
            return False, ' -> '.join(path) + ': a loop'
        seen.append(f)
        if k == 'nemesis-script':
            f = REAL
        elif k == 'wrapper':
            f = KEEP
        else:
            return False, ' -> '.join(path) + ": can't run"


def payload(work, name, scripts, wrapper, known):
    """a payload folder for install.sh: its scripts, stand-ins for the binaries"""
    P = os.path.join(work, 'payload-' + name)
    os.makedirs(P)
    for f, b in scripts.items():
        wr(os.path.join(P, f), b)
    for f in ('hmgapid', 'lpatch', 'libhmgapi_hook.so', 'libhmgapi_fetch.so', 'hmgapi-hook.jar', 'HmgApi.apk'):
        wr(os.path.join(P, f), blob(name + f, 500))
    wr(os.path.join(P, 'app_process'), wrapper)
    for f, b in (('VERSION', name.encode()), ('BUILD', b'test'), ('MAPS.md5', b'')):
        wr(os.path.join(P, f), b)
    lines = ['%s test stock\n' % hashlib.md5(b).hexdigest() for b in (STOCK, STOCK2)] if known else ['0 no build\n']
    wr(os.path.join(P, 'stock.txt'), ''.join(lines).encode())
    names = ['hmgapid', 'lpatch', 'app_process', 'libhmgapi_hook.so', 'libhmgapi_fetch.so', 'hmgapi-hook.jar', 'HmgApi.apk', 'MAPS.md5'] + sorted(scripts) + ['stock.txt']
    wr(os.path.join(P, 'MANIFEST'), ''.join('%s  %s\n' % (hashlib.md5(rd(os.path.join(P, n))).hexdigest(), n) for n in names).encode())
    return P


def release_scripts(tag):
    """install/common's scripts at a tag, or None when the clone hasn't got it"""
    r = subprocess.run(['git', '-C', REPO, 'ls-tree', '--name-only', tag, 'install/common/'], capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout.strip():
        return None
    out = {}
    for path in r.stdout.split():
        if path.endswith('.sh') and not path.endswith('test.sh'):
            out[os.path.basename(path)] = subprocess.run(['git', '-C', REPO, 'show', '%s:%s' % (tag, path)], capture_output=True, check=True).stdout
    return out


def tree_scripts(d):
    return {f: rd(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f.endswith('.sh') and not f.endswith('test.sh')}


def setup(work, scripts_dir, releases, known):
    """the payloads and the stand-ins for the unit's tools; -> the config the workers need"""
    payloads = {'': payload(work, 'this', tree_scripts(scripts_dir), WRAP, known)}
    for tag in releases:
        payloads[tag] = payload(work, tag, release_scripts(tag), WRAP_OLD if tag.startswith('v1.0.') else WRAP, known)
    B = os.path.join(work, 'bin')
    wr(os.path.join(B, 'md5'), b'#!/bin/sh\n[ -f "$1" ] || exit 1\nset -- $(md5sum "$1"); echo "$1 $2"\n')
    for t in ('chown', 'pm', 'am', 'setprop', 'settings', 'service', 'sync'):
        wr(os.path.join(B, t), b'#!/bin/sh\nexit 0\n')
    wr(os.path.join(B, 'getprop'), b'#!/bin/sh\necho 0\n')
    # cp that fails at its Nth call of one run (CUT_AT): a short file left, exit 1; CUT_KILL: the
    # installer killed there too, so nothing it would do about the failure runs
    wr(os.path.join(B, 'cut', 'cp'), b'''#!/bin/sh
n=$(($(cat "$CUT_COUNT" 2>/dev/null || echo 0) + 1)); echo $n > "$CUT_COUNT"
if [ "$n" = "$CUT_AT" ]; then
    for a; do s=$d; d=$a; done
    case "$1" in -*) ;; *) [ -f "$s" ] && head -c 16 "$s" > "$d" ;; esac
    [ -z "$CUT_KILL" ] || kill -9 $PPID
    exit 1
fi
exec /bin/cp "$@"
''')
    return {'work': work, 'payloads': payloads, 'this_uninstall': rd(os.path.join(payloads[''], 'uninstall.sh')),
            'sh': os.environ.get('UNIT_SH', 'sh')}


def sh(R, args, force=False, cut=None):
    path = os.path.join(CFG['work'], 'bin') + os.pathsep + os.environ['PATH']
    env = dict(os.environ, ROOT=R, CAPCHECK='0', HMGAPI_FROM_NEMESIS='1')
    env.pop('HMGAPI_FORCE', None)
    if force:
        env['HMGAPI_FORCE'] = '1'
    if cut:
        path = os.path.join(CFG['work'], 'bin', 'cut') + os.pathsep + path
        env.update(CUT_AT=str(cut[0]), CUT_COUNT=R + '.cp-count')
        if cut[1]:
            env['CUT_KILL'] = '1'
        if os.path.exists(R + '.cp-count'):
            os.remove(R + '.cp-count')
    env['PATH'] = path
    r = subprocess.run([CFG['sh']] + args, env=env, cwd=CFG['work'], capture_output=True, text=True, errors='replace', timeout=120)
    lines = [l for l in (r.stdout + r.stderr).splitlines() if l.strip()]
    return r.returncode, (lines[-1] if lines else '')


def shipped(R, stock):
    """/system as the firmware ships it"""
    shutil.rmtree(os.path.join(R, 'system'), ignore_errors=True)
    for d in ('system/bin', 'system/lib', 'system/framework', 'system/etc', 'system/xbin', 'data/dalvik-cache', 'dev'):
        os.makedirs(os.path.join(R, d), exist_ok=True)
    wr(os.path.join(R, AP), stock)
    wr(os.path.join(R, 'system/etc/install-recovery.sh'), b'#!/system/bin/sh\nexit 0\n')
    wr(os.path.join(R, 'system/etc/qb_runtime.sh'), b'echo qb stock\n')


def events(releases):
    old = [e + '@' + t for t in releases for e in ('install', 'force-install')]
    return ['restart'] + old + ['install', 'force-install', 'uninstall', 'force-uninstall', 'purge', 'nemesis', 'nemesis-uninstall',
                                'nemesis-usb-uninstall', 'firmware', 'firmware-new', 'factory-reset']


def apply(R, e, cut=None):
    """-> (it applies here, what the script said when it refused)"""
    j = os.path.join
    if e == 'restart':
        ap, real = after_watchdog(R)
        if real is not None:
            wr(j(R, REAL), real)
        if ap is not None:
            wr(j(R, AP), ap)
        return True, ''
    if e.split('@')[0] in ('install', 'force-install'):
        P = CFG['payloads'][e.partition('@')[2]]
        rc, last = sh(R, [j(P, 'install.sh'), P], e.startswith('force'), cut)
        return True, '' if rc == 0 else 'refused: ' + last
    if e in ('uninstall', 'force-uninstall', 'purge'):
        u = j(R, 'data/hmgapi/uninstall.sh')
        if not os.path.exists(u):
            return False, ''
        rc, last = sh(R, [u] + (['--purge'] if e == 'purge' else []), e == 'force-uninstall')
        return True, '' if rc == 0 else 'refused: ' + last
    if e == 'nemesis':
        if os.path.exists(j(R, NEM)):
            return False, ''
        wr(j(R, NEM), b'#!/bin/sh\nexit 0\n')
        return apply(R, 'restart')
    if e in ('nemesis-uninstall', 'nemesis-usb-uninstall'):
        if not os.path.exists(j(R, NEM)):
            return False, ''
        theirs = (rd(j(R, AP)) or b'').startswith(b'#!/system/bin/sh\n# NEMESIS LivePatch')
        if os.path.exists(j(R, REAL)) and (theirs or e == 'nemesis-usb-uninstall'):
            os.replace(j(R, REAL), j(R, AP))
        os.remove(j(R, NEM))
        return True, ''
    if e in ('firmware', 'firmware-new'):
        shipped(R, STOCK if e == 'firmware' else STOCK2)
        return True, ''
    if e == 'factory-reset':
        shutil.rmtree(j(R, 'data'), ignore_errors=True)
        os.makedirs(j(R, 'data/dalvik-cache'))
        return True, ''
    raise SystemExit('no such event: ' + e)


def ours(R):
    """the uninstall saved on the unit is this tree's"""
    return rd(os.path.join(R, 'data/hmgapi/uninstall.sh')) == CFG['this_uninstall']


def key(R):
    """what tells two units apart, as far as these scripts and the zygote start can see"""
    j = os.path.join
    parts = [hashlib.md5(rd(j(R, p)) or b'-').hexdigest()[:8] for p in (AP, REAL, KEEP, BAK)]
    parts.append(hashlib.md5(rd(j(R, 'data/hmgapi/uninstall.sh')) or b'-').hexdigest()[:8])
    inst = (rd(j(R, 'data/hmgapi/installed.txt')) or b'').decode(errors='replace')
    parts.append(' '.join(l.split()[1] for l in inst.splitlines() if l.endswith('/system/bin/app_process')) or '-')
    parts += ['y' if os.path.exists(j(R, p)) else 'n' for p in (NEM, 'data/hmgapi/firmware-reset', 'system/bin/hmgapid')]
    return '|'.join(parts)


def files(R):
    return 'app_process %s, .real %s, stock copy %s, backup %s%s' % (
        tuple(kind(rd(os.path.join(R, p))) for p in (AP, REAL, KEEP, BAK)) + ('' if not os.path.exists(os.path.join(R, NEM)) else ', NEMESIS',))


def init(cfg):
    CFG.update(cfg)


def step(job):
    src, e, n = job
    dst = os.path.join(CFG['work'], 'tmp', '%d-%s' % (n, e))
    shutil.copytree(src, dst, symlinks=True)
    before, was_ours = files(dst), ours(dst)
    ran, note = apply(dst, e)
    if not ran:
        shutil.rmtree(dst)
        return None
    ok, chain = starts(dst)
    mine = e in ('install', 'force-install') or (e in ('uninstall', 'force-uninstall', 'purge') and was_ours)
    return n, e, note, key(dst), ok, chain, dst, before, mine


def orders(pool, work, depth):
    """-> (units seen, [dead orders: {order, before, chain, mine}])"""
    R0 = os.path.join(work, 'units', '0')
    shipped(R0, STOCK)
    seen, dirs, hist, frontier, dead = {key(R0): 0}, [R0], [[]], [0], []
    for _ in range(depth):
        jobs = [(dirs[n], e, n) for n in frontier for e in CFG['events']]
        frontier = []
        for res in pool.imap(step, jobs, chunksize=4):
            if res is None:
                continue
            n, e, note, k, ok, chain, dst, before, mine = res
            h = hist[n] + [e + (' [%s]' % note[:60] if note else '')]
            if not ok:
                dead.append({'order': h, 'before': before, 'chain': chain, 'mine': mine})
            if not ok or k in seen:
                shutil.rmtree(dst)
                continue
            seen[k] = len(dirs)
            nd = os.path.join(work, 'units', str(len(dirs)))
            os.replace(dst, nd)
            dirs.append(nd)
            hist.append(h)
            frontier.append(seen[k])
        if not frontier:
            break
    return len(dirs), dead


def play(R, order, notes):
    for e in order:
        ran, note = apply(R, e)
        notes.append(e + (' [%s]' % note[:70] if note else '') + ('' if ran else ' (nothing to do)'))


def cut_job(job):
    """one starting point, one copy cut off, then the ways on from there; -> the dead ones"""
    base, src, inst, n, kill, i = job
    R = os.path.join(CFG['work'], 'tmp', 'cut-%d' % i)
    shutil.copytree(src, R, symlinks=True)
    was_ok = starts(R)[0]      # a unit an older release already doomed: only the install that goes through must save it
    sh(R, [os.path.join(CFG['payloads'][''], 'install.sh'), CFG['payloads']['']], inst == 'force-install', (n, kill))
    notes = base + ['%s, copy %d %s' % (inst, n, 'cut off and the installer killed' if kill else 'fails part-way')]
    dead = []

    def check(R, more):
        ok, chain = starts(R)
        if not ok:
            dead.append({'order': notes + more, 'before': '', 'chain': chain, 'mine': True})
        return ok
    if not was_ok or check(R, []):
        ran, note = apply(R, inst)
        again = [inst + ' again' + (' [%s]' % note[:70] if note else '')]
        if (was_ok or not note) and check(R, again):
            for u in ('uninstall', 'force-uninstall'):
                U = R + '.' + u
                shutil.copytree(R, U, symlinks=True)
                ran, note = apply(U, u)
                if ran:
                    check(U, again + [u + (' [%s]' % note[:70] if note else '')])
                shutil.rmtree(U)
    shutil.rmtree(R)
    return dead


def cut_off(pool, work, known, old, kinds, quick):
    """this tree's install with each of its copies cut off, from the starting points that differ"""
    force_old = 'force-install@' + old
    bases = [[], ['nemesis'], ['install'], ['nemesis', 'install', 'restart'], ['install', 'nemesis'],
             ['nemesis', force_old], ['nemesis', force_old, 'restart'], ['nemesis', force_old, 'restart', 'nemesis-uninstall'],
             ['install@' + old, 'nemesis', 'uninstall']]
    if quick:          # the starting points that differ most: an update, #397's two units, NEMESIS uninstalled since
        bases = [bases[i] for i in (3, 6, 7, 8)]
    if not known:      # nothing installs unforced on a build stock.txt doesn't know
        bases = [[('force-' + e if e.startswith('install') else e) for e in b] for b in bases]
    jobs = []
    for bi, b in enumerate(bases):
        src = os.path.join(work, 'units', 'cut-base-%d' % bi)
        shipped(src, STOCK)
        play(src, b, [])
        for inst in (['install', 'force-install'] if known and not quick else ['install'] if known else ['force-install']):
            # how many copies this install makes from here (a run that counts them and cuts none)
            C = src + '.count'
            shutil.copytree(src, C, symlinks=True)
            sh(C, [os.path.join(CFG['payloads'][''], 'install.sh'), CFG['payloads']['']], inst == 'force-install', (0, False))
            count = int((rd(C + '.cp-count') or b'0').split()[0])
            shutil.rmtree(C)
            for kill in kinds:
                for n in range(1, count + 1):
                    jobs.append((b, src, inst, n, kill, len(jobs)))
    dead = []
    for d in pool.imap(cut_job, jobs, chunksize=2):
        dead += d
    return len(jobs), dead


def report(dead, limit):
    groups = {}
    for d in dead:
        groups.setdefault((d['order'][-1].split(' [')[0], d['before'], d['chain']), []).append(d['order'])
    for (last, before, chain), os_ in sorted(groups.items(), key=lambda kv: len(min(kv[1], key=len)))[:limit]:
        print('  ' + ' > '.join(min(os_, key=len)) + ('   (%d orders)' % len(os_) if len(os_) > 1 else ''))
        if before:
            print('      before the last step: ' + before)
        print('      zygote start: ' + chain)
    if len(groups) > limit:
        print('  ... and %d more (--list)' % (len(groups) - limit))


def main():
    a = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    a.add_argument('--depth', type=int, default=int(os.environ.get('ORDERS_DEPTH', 5)))
    a.add_argument('--scripts', default=os.path.join(REPO, 'install', 'common'), help="another tree's install/common")
    a.add_argument('--releases', default=','.join(RELEASES))
    a.add_argument('--stock', choices=['known', 'unknown', 'both'], default='both')
    a.add_argument('--pass', dest='passes', choices=['orders', 'cut-off', 'both'], default='both')
    a.add_argument('--cut', choices=['fail', 'kill', 'both'], default='both', help='cut-off pass: the copy fails, the installer is killed there, or each')
    a.add_argument('--quick', action='store_true', help='cut-off pass: four starting points, one kind of install')
    a.add_argument('-j', type=int, default=os.cpu_count() or 2)
    a.add_argument('--list', action='store_true', help='every dead order, the ones older releases and NEMESIS cause too')
    a.add_argument('--order', help='play one order of events and say how it ends')
    o = a.parse_args()
    releases = [t for t in o.releases.split(',') if t]
    missing = [t for t in releases if release_scripts(t) is None]
    if missing:
        print("no tag %s in this clone (git fetch --tags)" % ', '.join(missing))
        return 3
    rc = 0
    for known in {'known': [True], 'unknown': [False], 'both': [True, False]}[o.stock]:
        work = tempfile.mkdtemp(prefix='zygote-orders-')
        try:
            os.makedirs(os.path.join(work, 'tmp'))
            os.makedirs(os.path.join(work, 'units'))
            cfg = setup(work, o.scripts, releases, known)
            cfg['events'] = events(releases)
            init(cfg)
            build = 'a build stock.txt %s' % ('knows' if known else "doesn't know")
            if o.order:
                R = os.path.join(work, 'units', 'one')
                shipped(R, STOCK)
                notes = []
                play(R, o.order.split(), notes)
                ok, chain = starts(R)
                print('%s  %s: %s' % ('starts' if ok else 'DEAD  ', build, ' > '.join(notes)))
                print('        %s; zygote start: %s' % (files(R), chain))
                continue
            with Pool(o.j, init, (cfg,)) as pool:
                if o.passes in ('orders', 'both'):
                    units, dead = orders(pool, work, o.depth)
                    mine = [d for d in dead if d['mine']]
                    print('orders, %s, depth %d: %d units, %d orders end dead, %d of them by these scripts' % (build, o.depth, units, len(dead), len(mine)))
                    report(dead if o.list else mine, 10 ** 6 if o.list else 12)
                    rc |= 1 if mine else 0
                if o.passes in ('cut-off', 'both'):
                    n, dead = cut_off(pool, work, known, releases[0], {'fail': [False], 'kill': [True], 'both': [False, True]}[o.cut], o.quick)
                    print('cut-off, %s: %d runs, %d end dead' % (build, n, len(dead)))
                    report(dead, 10 ** 6 if o.list else 12)
                    rc |= 1 if dead else 0
        finally:
            shutil.rmtree(work, ignore_errors=True)
    return rc


if __name__ == '__main__':
    sys.exit(main())
