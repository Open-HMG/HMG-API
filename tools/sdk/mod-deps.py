#!/usr/bin/env python3
"""The mods a mod requires ("requires" in mod.json), from HMG-API's mod catalogue (in the
mod SDK as bin/mod-deps.py; `hmgapi-mod deps` runs it).

    mod-deps.py <mod dir> [--catalog <dir or URL>] [--cache <dir>] [--pins <file>]

For each required mod, and each mod those require: its catalogue entry (catalog/<id>.json
in HMG-API: the download, its SHA-256 and the author key), the version checked against the
range (as the engine reads it), the download fetched, its SHA-256 checked, unpacked, and
its signature checked against the entry's author key (hmgapi.engine.Signing, as the head
unit does), which must be on HMG-API's author list (maps/authors.json). Each lands in the
cache, <id>-<version>.zip and its mod folder <id>-<version>/ (as signed), and is used from
there next time (the zip's SHA-256 checked again, no download).

Prints the mod folders, one per line (what test/lib.sh's plan_test takes); what it did
goes to stderr. Exit 1 with the reasons if any can't be had.

  --catalog  a catalog/ folder (an HMG-API checkout's), or its URL. Default:
             $HMGAPI_CATALOG, else Open-HMG/HMG-API's main branch on GitHub
             (https://raw.githubusercontent.com/Open-HMG/HMG-API/main/catalog).
             The author list is maps/authors.json beside it.
  --cache    default .hmgapi/deps (in the folder it's run from)
  --pins     default hmgapi-deps.txt, if there: "<id> <version> <url> <sha256>" lines,
             for a pre-release the catalogue doesn't list yet (it lists final releases).
             A pin keeps the catalogue entry's author key, and its download must be a
             release of the entry's own repo (<repo>/releases/download/...), as
             tools/release-mods.txt pins in HMG-API.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, urllib.error, urllib.parse, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.dirname(HERE)
CP = os.pathsep.join([os.path.join(SDK, 'lib', 'hmgapi-engine.jar'), os.path.join(SDK, 'test', 'hmgapi-engine-test.jar')])
DEFAULT_CATALOG = 'https://raw.githubusercontent.com/Open-HMG/HMG-API/main/catalog'
ID = re.compile(r'^[a-z0-9][a-z0-9-]{0,63}$')
VERSION = re.compile(r'^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$')
SHA = re.compile(r'^[0-9a-f]{64}$')
MAX_FILES, MAX_BYTES = 2000, 64 << 20   # the engine's limits (Packages.java)


class Fail(Exception):
    pass


def say(msg):
    print(msg, file=sys.stderr)


def java(*args):
    return subprocess.run(['java', '-cp', CP] + list(args), capture_output=True, text=True)


def is_url(s):
    return re.match(r'^[a-z][a-z0-9+.-]*://', s) is not None


def get(src, what):
    """the bytes of a file in a folder or at a URL; None if it isn't there"""
    if not is_url(src):
        try:
            with open(src, 'rb') as f:
                return f.read()
        except FileNotFoundError:
            return None
    req = urllib.request.Request(src, headers={'User-Agent': 'hmgapi-mod deps'})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            out, n = bytearray(), 0
            for b in iter(lambda: r.read(1 << 20), b''):
                n += len(b)
                if n > MAX_BYTES:
                    raise Fail(f'{what}: bigger than {MAX_BYTES >> 20} MB')
                out += b
            return bytes(out)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise Fail(f'{what}: {src}: {e}')
    except (urllib.error.URLError, OSError) as e:
        raise Fail(f'{what}: can\'t fetch {src}: {e}')


def join(base, name):
    return base.rstrip('/') + '/' + name if is_url(base) else os.path.join(base, name)


def authors_of(catalog):
    """maps/authors.json beside the catalog folder (or URL)"""
    if is_url(catalog):
        u = urllib.parse.urlsplit(catalog)
        path = u.path.rstrip('/')
        src = urllib.parse.urlunsplit(u._replace(path=path.rsplit('/', 1)[0] + '/maps/authors.json'))
    else:
        src = os.path.join(os.path.dirname(os.path.abspath(catalog)), 'maps', 'authors.json')
    b = get(src, 'authors')
    if b is None:
        raise Fail(f'no author list at {src} (HMG-API\'s maps/authors.json beside the catalogue)')
    return json.loads(b).get('authors', {})


def pins(path):
    out = {}
    if not path or not os.path.isfile(path):
        return out
    for i, line in enumerate(open(path).read().split('\n'), 1):
        w = line.split('#', 1)[0].split()
        if not w:
            continue
        if len(w) != 4 or not ID.match(w[0]) or not VERSION.match(w[1]) or not SHA.match(w[3]):
            raise Fail(f'{path}:{i}: wanted "<id> <version> <url> <sha256>" (sha256: 64 lowercase hex digits)')
        out[w[0]] = {'version': w[1], 'download': w[2], 'sha256': w[3]}
    return out


def unpack(zpath, dest):
    """the package's files under dest (refusing paths out of it, links and oversize), then its
    mod folder: dest itself or its one subfolder, as the engine takes it"""
    with zipfile.ZipFile(zpath) as z:
        infos = [i for i in z.infolist() if not i.is_dir()]
        if len(infos) > MAX_FILES or sum(i.file_size for i in infos) > MAX_BYTES:
            raise Fail(f'{zpath}: more than {MAX_FILES} files or {MAX_BYTES >> 20} MB')
        for i in infos:
            n = i.filename
            if n.startswith('/') or '\\' in n or '..' in n.split('/') or (i.external_attr >> 16) & 0o170000 == 0o120000:
                raise Fail(f'{zpath}: refused entry {n!r}')
            out = os.path.join(dest, n)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with z.open(i) as src, open(out, 'wb') as dst:
                shutil.copyfileobj(src, dst)
    if os.path.isfile(os.path.join(dest, 'mod.json')):
        return dest
    subs = [d for d in os.listdir(dest) if os.path.isdir(os.path.join(dest, d))]
    if len(subs) == 1 and os.path.isfile(os.path.join(dest, subs[0], 'mod.json')):
        return os.path.join(dest, subs[0])
    raise Fail(f'{zpath}: no mod.json')


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def requires(moddir):
    r = java('hmgapi.engine.Requires', 'list', moddir)
    if r.returncode:
        raise Fail(f'{moddir}/mod.json: {(r.stdout + r.stderr).strip()}')
    return [(l.split(' ', 1) + [''])[:2] for l in r.stdout.splitlines() if l.strip()]


def satisfies(v, rng):
    if not rng:
        return True
    r = java('hmgapi.engine.Requires', 'satisfies', v, rng)
    if r.returncode == 2:
        raise Fail(f'"{rng}": {r.stderr.strip()}')
    return r.returncode == 0


def one(mid, rng, who, catalog, authors, pinned, cache):
    """the mod folder of a required mod, from the cache or downloaded and checked"""
    if not ID.match(mid):
        raise Fail(f'{who} requires "{mid}": not a mod id')
    b = get(join(catalog, mid + '.json'), mid)
    if b is None:
        raise Fail(f'{who} requires {mid}, which isn\'t in HMG-API\'s catalogue ({join(catalog, mid + ".json")}): '
                   'a required mod comes from the catalogue (its author adds it: tools/catalog.py add)')
    e = json.loads(b)
    if e.get('id') != mid or not SHA.match(str(e.get('sha256', ''))) or not e.get('uuid') or not e.get('download'):
        raise Fail(f'catalog/{mid}.json: no id, download, sha256 or uuid')
    uuid = str(e['uuid']).lower()
    if uuid not in authors:
        raise Fail(f'catalog/{mid}.json: author key {uuid} isn\'t on HMG-API\'s author list (maps/authors.json)')
    src = 'the catalogue'
    if mid in pinned:
        p = pinned[mid]
        base = str(e.get('repo', '')).rstrip('/') + '/releases/download/'
        if not e.get('repo') or not p['download'].startswith(base):
            raise Fail(f'pin {mid} {p["version"]}: the download must be a release of the mod\'s own repo ({base}...)')
        e = dict(e, **p)
        src = 'a pin'
    v = str(e.get('version', ''))
    if not satisfies(v, rng):
        raise Fail(f'{who} requires {mid} {rng}, but {src} has {v}'
                   + ('' if src == 'a pin' else ' (a pre-release can be pinned: hmgapi-deps.txt)'))
    z = os.path.join(cache, f'{mid}-{v}.zip')
    d = os.path.join(cache, f'{mid}-{v}')
    ok = d + '.checked'   # beside the mod folder: in it, the signature check would count it
    if os.path.isfile(z) and os.path.isfile(ok) and sha256(z) == e['sha256'] and open(ok).read().strip() == e['sha256'] + ' ' + uuid:
        say(f'ok    {mid} {v}: cached ({z})')
        return d
    tmp = tempfile.mkdtemp(prefix='hmgapi-mod-deps-', dir=cache)
    try:
        tz = os.path.join(tmp, 'pkg.zip')
        b = get(e['download'], mid)
        if b is None:
            raise Fail(f'{mid} {v}: no such download: {e["download"]}')
        with open(tz, 'wb') as f:
            f.write(b)
        got = sha256(tz)
        if got != e['sha256']:
            raise Fail(f'{mid} {v}: the download\'s SHA-256 is {got}, {src} says {e["sha256"]}')
        md = unpack(tz, os.path.join(tmp, 'mod'))
        r = java('hmgapi.engine.Signing', 'verify', md, uuid)
        if r.returncode:
            raise Fail(f'{mid} {v}: {(r.stdout + r.stderr).strip()}')
        man = json.load(open(os.path.join(md, 'META-HMGAPI', 'manifest.json')))
        if (man.get('id'), man.get('version')) != (mid, v):
            raise Fail(f'{mid} {v}: the download is {man.get("id")} {man.get("version")}')
        shutil.rmtree(d, ignore_errors=True)
        os.replace(md, d)
        os.replace(tz, z)
        with open(ok, 'w') as f:
            f.write(e['sha256'] + ' ' + uuid + '\n')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    say(f'ok    {mid} {v}: downloaded from {src}, SHA-256 and signature match (author key {uuid})')
    return d


def main():
    ap = argparse.ArgumentParser(description='the mods a mod requires, from HMG-API\'s catalogue')
    ap.add_argument('mod')
    ap.add_argument('--catalog', default=os.environ.get('HMGAPI_CATALOG') or DEFAULT_CATALOG)
    ap.add_argument('--cache', default=os.path.join('.hmgapi', 'deps'))
    ap.add_argument('--pins', default='hmgapi-deps.txt')
    a = ap.parse_args()
    if not os.path.isfile(os.path.join(a.mod, 'mod.json')):
        say(f'FAIL  no mod.json in {a.mod}')
        return 1
    try:
        todo = [(d, r, os.path.basename(os.path.abspath(a.mod))) for d, r in requires(a.mod)]
        if not todo:
            say('ok    no "requires" in mod.json')
            return 0
        os.makedirs(a.cache, exist_ok=True)
        authors, pinned = authors_of(a.catalog), pins(a.pins)
        done, out, fails = {}, [], []
        while todo:
            mid, rng, who = todo.pop(0)
            if mid in done:
                if done[mid] and not satisfies(done[mid][0], rng):
                    fails.append(f'{who} requires {mid} {rng}, but {done[mid][0]} is the one the others need')
                continue
            try:
                d = one(mid, rng, who, a.catalog, authors, pinned, os.path.abspath(a.cache))
            except Fail as x:
                fails.append(str(x))
                done[mid] = None
                continue
            v = json.load(open(os.path.join(d, 'mod.json'))).get('version', '')
            done[mid] = (v, d)
            out.append(d)
            todo += [(x, r, mid) for x, r in requires(d)]
    except Fail as x:
        fails = [str(x)]
        out = []
    for f in fails:
        say('FAIL  ' + f)
    if fails:
        return 1
    for d in out:
        print(d)
    return 0


if __name__ == '__main__':
    sys.exit(main())
