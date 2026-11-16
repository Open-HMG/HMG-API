#!/usr/bin/env python3
"""sitefind.py - find a native patch's sites by what they look like, not where they are
(research prototype: docs/research/self-mapping.md, ideas 4 and 5).

A site is a window of the library's code described by hashes only, so a recipe that uses
it still holds no stock bytes:

  site <name> <len> <md5> [mask <hex>] [in <symbol>]

  md5   of the window's bytes with every wildcard byte set to 0
  mask  one bit per byte, byte 0 = the first byte's bit 0x80 ... (1: compared, 0: wildcard);
        no mask: every byte is compared (an ordinary verify region is such a site)
  in    search only inside that exported function (.dynsym st_value .. + st_size)

It matches where exactly one window in the search range has that md5. None, or more than
one, and the site isn't found: a recipe using it doesn't fit that library.

usage:
  sitefind.py syms <lib> [--at OFF]           exported functions (names, offsets, sizes);
                                               --at: the one holding that file offset
  sitefind.py make <lib> <off> <len> [--also <lib>...] [--in SYM] [--name N]
                                               a site line for that window; --also: other
                                               builds of the library, the window found in each
                                               by its symbol, bytes that differ become wildcards
  sitefind.py find <lib> '<site line>'         every match (file offsets)
  sitefind.py scan <recipe>... --libs <dir|lib>...
                                               every verify region of the recipes as a site
                                               (no mask), against every library: where it
                                               matches, how many times. Prints offsets and
                                               counts only, never bytes.
  sitefind.py relocate <recipe> [--lib <stock>]
                                               the recipe with every verify region as a site
                                               (from the recipe text alone: a full-mask site's
                                               md5 is the verify region's "before" md5) and
                                               every patch and branch relative to its site.
                                               --lib: rel32 call targets inside an exported
                                               function become sym:<name>+off. Offsets that
                                               stay plain numbers pin the recipe to its build
                                               (built-for); hmgapi.engine.SiteFind resolves it
  sitefind.py selftest                         the matcher on synthetic libraries

Prints nothing from the library but offsets, lengths, md5s and symbol names.
"""
import argparse, glob, hashlib, os, struct, sys


# ---- ELF (32-bit little-endian ARM: these head units' libraries)

def sections(b):
    if b[:4] != b'\x7fELF' or b[4] != 1 or b[5] != 1:
        raise ValueError('not a 32-bit little-endian ELF')
    shoff, = struct.unpack_from('<I', b, 0x20)
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', b, 0x2e)
    out = []
    for k in range(shnum):
        name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from('<10I', b, shoff + k * shentsize)
        out.append(dict(name=name, type=typ, addr=addr, off=off, size=size, link=link, entsize=entsize))
    if shstrndx < len(out):
        st = out[shstrndx]
        for s in out:
            s['name'] = cstr(b, st['off'] + s['name'])
    return out


def cstr(b, at):
    end = b.index(b'\0', at)
    return b[at:end].decode('latin-1')


def vaddr_to_off(b, va):
    phoff, = struct.unpack_from('<I', b, 0x1c)
    phnum, = struct.unpack_from('<H', b, 0x2c)
    for k in range(phnum):
        t, off, v, pa, fsz, msz, fl, al = struct.unpack_from('<8I', b, phoff + 32 * k)
        if t == 1 and v <= va < v + fsz:
            return va - v + off
    return None


def functions(b):
    """exported (defined) functions from .dynsym: [(name, file offset, size)], Thumb bit dropped"""
    secs = sections(b)
    dyn = [s for s in secs if s['type'] == 11]   # SHT_DYNSYM
    if not dyn:
        return []
    d = dyn[0]
    strs = secs[d['link']]
    out = []
    for k in range(d['size'] // 16):
        name, value, size, info, other, shndx = struct.unpack_from('<IIIBBH', b, d['off'] + 16 * k)
        if info & 0xf != 2 or shndx == 0 or value == 0:   # STT_FUNC, defined
            continue
        off = vaddr_to_off(b, value & ~1)
        if off is not None:
            out.append((cstr(b, strs['off'] + name), off, size))
    return sorted(out, key=lambda f: f[1])


def function_at(b, off):
    for name, at, size in functions(b):
        if at <= off < at + size:
            return name, at, size
    return None


# ---- sites

def masked(win, mask):
    if mask is None:
        return win
    return bytes(x if mask[i >> 3] & (0x80 >> (i & 7)) else 0 for i, x in enumerate(win))


def parse_site(line):
    w = line.split()
    if len(w) < 4 or w[0] != 'site':
        raise ValueError('not a site line: ' + line)
    s = dict(name=w[1], len=int(w[2], 0), md5=w[3], mask=None, within=None)
    i = 4
    while i < len(w):
        if w[i] == 'mask':
            s['mask'] = bytes.fromhex(w[i + 1])
        elif w[i] == 'in':
            s['within'] = w[i + 1]
        else:
            raise ValueError('unknown word %r in %s' % (w[i], line))
        i += 2
    if s['mask'] is not None and len(s['mask']) != (s['len'] + 7) // 8:
        raise ValueError('mask is %d bytes, a %d-byte window needs %d' % (len(s['mask']), s['len'], (s['len'] + 7) // 8))
    return s


def search_range(b, s):
    if not s['within']:
        return 0, len(b)
    for name, at, size in functions(b):
        if name == s['within']:
            return at, at + size
    return None


def find(b, s):
    """every file offset where the site's window matches"""
    r = search_range(b, s)
    if r is None:
        return []
    lo, hi = r
    n, want, mask = s['len'], s['md5'], s['mask']
    hits = []
    md5 = hashlib.md5
    if mask is None:
        for i in range(lo, hi - n + 1):
            if md5(b[i:i + n]).hexdigest() == want:
                hits.append(i)
    else:
        for i in range(lo, hi - n + 1):
            if md5(masked(b[i:i + n], mask)).hexdigest() == want:
                hits.append(i)
    return hits


def compared(mask, n):
    return n if mask is None else sum(1 for i in range(n) if mask[i >> 3] & (0x80 >> (i & 7)))


def make(b, off, n, also=(), within=None, name='s1', min_compared=16):
    """a site line for b[off:off+n]; wildcards where the same window differs in the other builds"""
    if within is None:
        f = function_at(b, off)
        within = f[0] if f else None
    mask = None
    if also:
        if within is None:
            raise ValueError('--also needs the window inside an exported function (to find it in the other builds)')
        f0 = [f for f in functions(b) if f[0] == within][0]
        rel = off - f0[1]
        bits = bytearray(b'\xff' * ((n + 7) // 8))
        for ob in also:
            fo = [f for f in functions(ob) if f[0] == within]
            if not fo:
                raise ValueError('%s is not exported in one of the other builds' % within)
            w2 = ob[fo[0][1] + rel:fo[0][1] + rel + n]
            for i in range(n):
                if i >= len(w2) or w2[i] != b[off + i]:
                    bits[i >> 3] &= ~(0x80 >> (i & 7)) & 0xff
        if bits != b'\xff' * len(bits):
            mask = bytes(bits)
        if n % 8:   # unused bits of the last byte stay 0
            bits[-1] &= (0xff << (8 - n % 8)) & 0xff
            mask = bytes(bits) if mask is not None else None
    if compared(mask, n) < min_compared:
        raise ValueError('only %d bytes left to compare (at least %d): a longer window, or builds that differ less'
                         % (compared(mask, n), min_compared))
    line = 'site %s %d %s' % (name, n, hashlib.md5(masked(b[off:off + n], mask)).hexdigest())
    if mask is not None:
        line += ' mask ' + mask.hex()
    if within:
        line += ' in ' + within
    hits = find(b, parse_site(line))
    if hits != [off]:
        raise ValueError('the window matches %d time(s) at %s, not once: make it longer'
                         % (len(hits), ', '.join('%#x' % h for h in hits[:5])))
    return line


# ---- scan: every verify region of every recipe, against every library

def recipe_regions(path):
    rid, file, regions = os.path.basename(path), None, []
    for l in open(path):
        w = l.split('#', 1)[0].split()
        if not w:
            continue
        if w[0] == 'id':
            rid = w[1]
        elif w[0] == 'file':
            file = w[1]
        elif w[0] == 'verify':
            regions.append((int(w[1], 0), int(w[2], 0), w[3]))
    return rid, file, regions


def libs_in(args):
    out = []
    for a in args:
        if os.path.isdir(a):
            out += sorted(glob.glob(os.path.join(a, '**', '*.so'), recursive=True))
        elif os.path.isfile(a):
            out.append(a)
    return out


def scan(recipes, libs):
    """[(recipe id, file, region off, len, lib, md5 of lib, [hits])]; same-named libraries only"""
    rows = []
    cache = {}
    for rp in recipes:
        rid, file, regions = recipe_regions(rp)
        base = os.path.basename(file or '')
        for lib in libs:
            if base and os.path.basename(lib).split('.')[0] != base.split('.')[0]:
                continue
            if lib not in cache:
                b = open(lib, 'rb').read()
                cache[lib] = (b, hashlib.md5(b).hexdigest())
            b, m = cache[lib]
            for off, n, md5 in regions:
                hits = find(b, dict(len=n, md5=md5, mask=None, within=None))
                rows.append((rid, file, off, n, lib, m, hits))
    return rows


# ---- relocate: a fixed-offset recipe as a relocatable one

def relocate(text, lib=None):
    import re
    regions, out, pinned = [], [], []
    m = re.search(r'from stock ([0-9a-f]{32})', text)
    built = m.group(1) if m else None
    fns = functions(lib) if lib else []
    for l in text.split('\n'):
        w = l.split('#', 1)[0].split()
        if w and w[0] == 'verify':
            regions.append((int(w[1], 0), int(w[2], 0), w[3]))

    def site_of(off, n=1):
        for k, (o, ln, _) in enumerate(regions):
            if o <= off and off + n <= o + ln:
                return 'v%d+%#x' % (k, off - o)
        return None

    for l in text.split('\n'):
        w = l.split('#', 1)[0].split()
        if not w:
            if l.startswith('#'):
                out.append(l)
            continue
        if w[0] == 'verify':
            k = [r[0] for r in regions].index(int(w[1], 0))
            out.append('site v%d %s %s' % (k, w[2], w[3]))
            out.append('verify v%d %s' % (k, w[2]))
        elif w[0] in ('patch', 'branch'):
            n = len(bytes.fromhex(w[2])) if w[0] == 'patch' else 4
            out.append('%s %s %s' % (w[0], site_of(int(w[1], 0), n), w[2]))
        elif w[0] == 'rel32':
            t = int(w[3], 0)
            ref = None
            for name, at, size in fns:
                if at <= (t & ~1) < at + size:
                    ref = 'sym:%s' % name + ('+%#x' % ((t & ~1) - at) if (t & ~1) != at else '')
            if ref is None:
                ref = site_of(t & ~1)
                if ref is not None and t & 1:
                    ref = ref.split('+')[0] + '+%#x' % (int(ref.split('+')[1], 16) + 1)
            if ref is None:
                ref = w[3]
                pinned.append(w[3])
            out.append('rel32 %s %s %s' % (w[1], w[2], ref))
        else:
            out.append(' '.join(w))
    if pinned:
        if not built:
            raise ValueError('rel32 targets %s are plain offsets and the recipe does not say its stock md5' % ', '.join(pinned))
        out.insert(0, '# pinned to its build: rel32 target(s) %s are neither in a site nor in an exported function' % ', '.join(pinned))
        out.insert(1, 'built-for ' + built)
    return '\n'.join(out) + '\n', pinned


# ---- selftest: synthetic libraries, no stock bytes needed

def selftest():
    import random
    fail = 0

    def check(what, ok):
        nonlocal fail
        print(('ok    ' if ok else 'FAIL  ') + what)
        fail |= not ok

    rnd = random.Random(7)
    lib = bytearray(rnd.getrandbits(8) for _ in range(20000))
    site = bytes(lib[5000:5032])
    line = 'site s 32 ' + hashlib.md5(site).hexdigest()
    check('a window is found where it is', find(bytes(lib), parse_site(line)) == [5000])
    moved = bytes(lib[:4000]) + bytes(64) + bytes(lib[4000:])
    check('found again after the code moved (64 bytes inserted before it)', find(moved, parse_site(line)) == [5064])
    dup = bytes(lib) + site
    check('two matches: the site is not found (not unique)', len(find(dup, parse_site(line))) == 2)
    other = bytearray(lib)
    other[5010] ^= 0xff
    check('one byte different: no match', find(bytes(other), parse_site(line)) == [])
    mask = bytearray(b'\xff' * 4)
    mask[1] &= ~(0x80 >> 2) & 0xff                 # byte 10 is a wildcard
    ml = 'site s 32 %s mask %s' % (hashlib.md5(masked(site, mask)).hexdigest(), mask.hex())
    check('with byte 10 a wildcard: matches the build where it differs', find(bytes(other), parse_site(ml)) == [5000])
    check('... and the build where it does not', find(bytes(lib), parse_site(ml)) == [5000])
    other[5011] ^= 0xff
    check('a compared byte different: still no match', find(bytes(other), parse_site(ml)) == [])
    try:
        make(bytes(lib), 5000, 8, min_compared=16)
        check('a window too short to be safe is refused', False)
    except ValueError:
        check('a window too short to be safe is refused', True)
    rep = bytes(lib[:3000]) + bytes(64) + bytes(lib[3000:])   # 64 zero bytes: a window inside isn't unique
    try:
        make(rep + bytes(64), 3010, 20)
        check('a window that is not unique in its own library is refused', False)
    except ValueError:
        check('a window that is not unique in its own library is refused', True)
    return fail


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('syms'); p.add_argument('lib'); p.add_argument('--at', type=lambda x: int(x, 0))
    p = sub.add_parser('make'); p.add_argument('lib'); p.add_argument('off', type=lambda x: int(x, 0)); p.add_argument('len', type=lambda x: int(x, 0))
    p.add_argument('--also', nargs='*', default=[]); p.add_argument('--in', dest='within'); p.add_argument('--name', default='s1')
    p = sub.add_parser('find'); p.add_argument('lib'); p.add_argument('site')
    p = sub.add_parser('scan'); p.add_argument('recipes', nargs='+'); p.add_argument('--libs', nargs='+', required=True)
    p = sub.add_parser('relocate'); p.add_argument('recipe'); p.add_argument('--lib')
    sub.add_parser('selftest')
    a = ap.parse_args()
    if a.cmd == 'selftest':
        sys.exit(selftest())
    if a.cmd == 'relocate':
        text, pinned = relocate(open(a.recipe).read(), open(a.lib, 'rb').read() if a.lib else None)
        sys.stdout.write(text)
        if pinned:
            print('note: pinned to its build (rel32 %s)' % ', '.join(pinned), file=sys.stderr)
        return
    if a.cmd == 'syms':
        b = open(a.lib, 'rb').read()
        if a.at is not None:
            f = function_at(b, a.at)
            print('%s +%#x (function at %#x, %d bytes)' % (f[0], a.at - f[1], f[1], f[2]) if f else 'not inside an exported function')
            return
        fs = functions(b)
        for name, off, size in fs:
            print('%#08x %6d %s' % (off, size, name))
        print('# %d exported functions' % len(fs), file=sys.stderr)
    elif a.cmd == 'make':
        b = open(a.lib, 'rb').read()
        try:
            print(make(b, a.off, a.len, [open(x, 'rb').read() for x in a.also], a.within, a.name))
        except ValueError as e:
            sys.exit('no site: %s' % e)
    elif a.cmd == 'find':
        hits = find(open(a.lib, 'rb').read(), parse_site(a.site))
        print(' '.join('%#x' % h for h in hits) if hits else 'no match')
        sys.exit(0 if len(hits) == 1 else 1)
    elif a.cmd == 'scan':
        libs = libs_in(a.libs)
        if not libs:
            print('skip  no libraries found in %s' % ' '.join(a.libs))
            return
        bad = 0
        for rid, file, off, n, lib, m, hits in scan(a.recipes, libs):
            where = 'none' if not hits else ' '.join('%#x' % h for h in hits[:4]) + (' ...' if len(hits) > 4 else '')
            verdict = 'unique' if len(hits) == 1 else ('no match' if not hits else 'MULTIPLE')
            moved = '' if len(hits) != 1 or hits[0] == off else ' (moved %+d)' % (hits[0] - off)
            print('%-26s %#08x %3d  %-28s %s  %-8s %s%s' % (rid, off, n, os.path.basename(lib), m[:8], verdict, where, moved))
            bad |= len(hits) > 1
        sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
