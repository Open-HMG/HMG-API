"""The stock car trees on this machine (docs/car-trees.md), for the tools and tests that need them.

$HMGAPI_CARS (default ~/hmg-cars) is laid out like maps/: <make>/<model>/<region>/<firmware>/system/
is that car's stock /system (or part of it). Each tree's id is its folder, e.g. kia/ev6/usa/260507,
the same as its map's. tools/hook-trees.local and tools/map-trees.local ("<label or map folder>
<tree>" lines) still add or replace trees, with a note that they're going away.

Nothing here reads more of a file than its md5.
"""
import glob, hashlib, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
MAPS = os.environ.get('HMGAPI_TEST_MAPS') or os.path.join(REPO, 'maps')            # tools/cars-test.sh's own
LOCAL_DIR = os.environ.get('HMGAPI_TEST_LOCAL_DIR') or HERE
LOCAL = ('map-trees.local', 'hook-trees.local')   # the old lists; the first one a car is in wins


def cars_dir():
    return os.path.expanduser(os.environ.get('HMGAPI_CARS') or '~/hmg-cars')


class Tree:
    def __init__(self, id, path, source):
        self.id = id            # make/model/region/firmware, or an old .local label
        self.path = path        # the /system tree
        self.source = source    # where it was found: $HMGAPI_CARS or the .local file
        cj = os.path.join(MAPS, id, 'car.json')
        self.map = json.load(open(cj)) if '/' in id and os.path.isfile(cj) else None

    def file(self, p):
        """a /system/... path in this tree"""
        return os.path.join(self.path, os.path.relpath(p, '/system'))

    def products(self):
        return self.map['products'] if self.map else []


_md5 = {}


def md5(p):
    st = os.stat(p)
    k = (os.path.realpath(p), st.st_size, st.st_mtime_ns)
    if k not in _md5:
        h = hashlib.md5()
        with open(p, 'rb') as f:
            for b in iter(lambda: f.read(1 << 20), b''):
                h.update(b)
        _md5[k] = h.hexdigest()
    return _md5[k]


def compare(path, car):
    """a tree against a map's car.json md5s: (same, absent, different), lists of /system paths"""
    same, absent, diff = [], [], []
    for p, want in sorted(car['files'].items()):
        f = os.path.join(path, os.path.relpath(p, '/system'))
        (absent if not os.path.isfile(f) else same if md5(f) == want else diff).append(p)
    return same, absent, diff


def match(tree):
    """('exact' | 'partial' | 'different' | 'no map', absent files, different files)"""
    if not tree.map:
        return 'no map', [], []
    same, absent, diff = compare(tree.path, tree.map)
    return ('different' if diff else 'partial' if absent else 'exact'), absent, diff


def digest(tree):
    """one md5 over the (path, md5) pairs of the map's files this tree has: two people with the
    same digest tested the same files, and it says nothing a car.json doesn't"""
    if not tree.map:
        return ''
    h = hashlib.md5()
    for p in sorted(tree.map['files']):
        f = tree.file(p)
        if os.path.isfile(f):
            h.update(('%s %s\n' % (p, md5(f))).encode())
    return h.hexdigest()[:12]


def all_maps():
    return sorted(os.path.relpath(os.path.dirname(c), MAPS) for c in glob.glob(os.path.join(MAPS, '*', '*', '*', '*', 'car.json')))


def fits(path):
    """[((files the same, -files absent), map)] for every map none of whose files differ, best first"""
    out = []
    for m in all_maps():
        same, absent, diff = compare(path, json.load(open(os.path.join(MAPS, m, 'car.json'))))
        if same and not diff:
            out.append(((len(same), -len(absent)), m))
    return sorted(out, reverse=True)


def which_map(path):
    """the one map a tree fits best (fits()), or None: none, or two as well (apps the same on both cars)"""
    f = fits(path)
    return f[0][1] if f and (len(f) == 1 or f[1][0] != f[0][0]) else None


def system_dir(d):
    """the /system tree in a car folder: system/, or the folder itself if it holds app/ or framework/"""
    if os.path.isdir(os.path.join(d, 'system')):
        return os.path.join(d, 'system')
    if os.path.isdir(os.path.join(d, 'framework')) or os.path.isdir(os.path.join(d, 'app')):
        return d
    return None


_noted = set()


def found(quiet=False):
    """every tree on this machine, $HMGAPI_CARS first, then the .local files' (by id; a .local
    line with a map folder, or a label whose tree matches a map, replaces that car's tree)"""
    out = {}
    root = cars_dir()
    for d in sorted(glob.glob(os.path.join(root, '*', '*', '*', '*'))):
        s = system_dir(d)
        if s:
            out[os.path.relpath(d, root)] = Tree(os.path.relpath(d, root), s, '$HMGAPI_CARS')
    seen = set()
    for name in LOCAL:
        p = os.path.join(LOCAL_DIR, name)
        if not os.path.isfile(p):
            continue
        if name not in _noted and not quiet:
            _noted.add(name)
            print('note  tools/%s is deprecated: lay the trees out in $HMGAPI_CARS (docs/car-trees.md)' % name, file=sys.stderr)
        for line in open(p).read().split('\n'):
            if not line.strip() or line.startswith('#'):
                continue
            label, path = line.split()[:2]
            path = os.path.expanduser(path)
            if not os.path.isdir(path):
                continue
            id = label if label.count('/') == 3 else (which_map(path) or label)
            if id in seen or os.path.realpath(path) in {os.path.realpath(t.path) for t in out.values()}:
                continue   # map-trees.local's tree for a car beats hook-trees.local's
            seen.add(id)
            out[id] = Tree(id, path, 'tools/' + name)
    return [out[k] for k in sorted(out)]


def pick(only):
    """found(), only the ids (or old labels) in a comma-separated list, if one is given"""
    trees = found()
    if only:
        want = only.split(',')
        trees = [t for t in trees if t.id in want]
    return trees
