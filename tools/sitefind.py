#!/usr/bin/env python3
"""sitefind.py - relocatable native recipes ("hooks": {"native_sites": ...}): sites found by
what the code looks like, not where it is (hmgapi.engine.SiteFind resolves them on the unit;
docs/mod-reference.md, "Native patches").

A site is a window of the library described by hashes only, so a recipe still holds no
stock bytes:

  site <name> <len> <md5> [mask <hex>] [in <symbol> | at <ref>]

usage:
  sitefind.py syms <lib> [--at OFF]       exported functions (readelf --dyn-syms for these
                                           libraries); --at: the one holding that file offset
  sitefind.py make <lib> <off> <len> [--also <lib>...] [--name N]
                                           a site line for that window: `in <function>` when
                                           it's inside an exported one, made longer until it
                                           is unique; --also: other builds of the library (the
                                           window found by its function), bytes that differ
                                           become wildcards
  sitefind.py find <lib> '<site line>'     every match (file offsets)
  sitefind.py relocate <recipe> [--lib <stock>]
                                           a fixed recipe as a relocatable one. From the text
                                           alone: every verify region a site (its md5 is the
                                           region's "before" md5), every write relative to it.
                                           With the library it was made for, also: sites made
                                           unique and bounded to their function, branches
                                           written as jump/bw/bl, patches widened to whole
                                           instructions, rel32 targets as sym: or new sites.
                                           Anything still a plain offset pins it (built-for).
  sitefind.py fits <recipe> <lib>          "fits" (every verify region's stock bytes there),
                                           "applied" (every region's fixed "after" there), or "no"
  sitefind.py scan <recipe>... --libs <dir|lib>...
                                           every verify region as a site, against every
                                           library of that name: where it matches, how often
  sitefind.py selftest                     the matcher on synthetic libraries

Prints offsets, lengths, md5s and symbol names only, never bytes of a library.
"""
import argparse, glob, hashlib, os, re, struct, sys

MIN_COMPARED = 16


# ---- ELF (32-bit little-endian ARM: these head units' libraries)

def sections(b):
    if b[:4] != b'\x7fELF' or b[4] != 1 or b[5] != 1:
        raise ValueError('not a 32-bit little-endian ELF')
    shoff, = struct.unpack_from('<I', b, 0x20)
    shentsize, shnum = struct.unpack_from('<HH', b, 0x2e)
    out = []
    for k in range(shnum):
        name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from('<10I', b, shoff + k * shentsize)
        out.append(dict(type=typ, off=off, size=size, link=link))
    return out


def cstr(b, at):
    return b[at:b.index(b'\0', at)].decode('latin-1')


def vaddr_to_off(b, va):
    phoff, = struct.unpack_from('<I', b, 0x1c)
    phnum, = struct.unpack_from('<H', b, 0x2c)
    for k in range(phnum):
        t, off, v, pa, fsz, msz, fl, al = struct.unpack_from('<8I', b, phoff + 32 * k)
        if t == 1 and v <= va < v + fsz:
            return va - v + off
    return None


_fcache = {}


def functions(b):
    """exported, defined functions: [(name, file offset, size, thumb)], by offset"""
    key = (len(b), hashlib.md5(b[:4096]).digest(), hashlib.md5(b[-4096:]).digest())
    if key in _fcache:
        return _fcache[key]
    out = []
    try:
        secs = sections(b)
    except ValueError:
        secs = []
    for d in [s for s in secs if s['type'] == 11]:   # SHT_DYNSYM
        strs = secs[d['link']]
        for k in range(d['size'] // 16):
            name, value, size, info, other, shndx = struct.unpack_from('<IIIBBH', b, d['off'] + 16 * k)
            if info & 0xf != 2 or shndx == 0 or value == 0:   # STT_FUNC, defined
                continue
            off = vaddr_to_off(b, value & ~1)
            if off is not None:
                out.append((cstr(b, strs['off'] + name), off, size, value & 1))
    out.sort(key=lambda f: f[1])
    _fcache[key] = out
    return out


def function_at(b, off, n=1):
    for f in functions(b):
        if f[1] <= off and off + n <= f[1] + f[2]:
            return f
    return None


def function_named(b, name):
    for f in functions(b):
        if f[0] == name:
            return f
    return None


# ---- Thumb-2: what depends on where it is (hmgapi.engine.SiteFind.decode)

def u16(b, o):
    return b[o] | b[o + 1] << 8


def sext(v, bits):
    return v - (1 << bits) if v & (1 << (bits - 1)) else v


def cond_target(b, o):
    hw1, hw2 = u16(b, o), u16(b, o + 2)
    if hw1 & 0xf800 != 0xf000 or hw2 & 0xd000 != 0x8000 or (hw1 >> 6) & 0xe == 0xe:
        return None
    s, j1, j2 = (hw1 >> 10) & 1, (hw2 >> 13) & 1, (hw2 >> 11) & 1
    imm = (s << 20) | (j2 << 19) | (j1 << 18) | ((hw1 & 0x3f) << 12) | ((hw2 & 0x7ff) << 1)
    return o + 4 + sext(imm, 21)


def decode(b, o):
    """(length, kind, target): kind None (doesn't depend on its address), 'b.w', 'bl', 'blx', 'b<c>.w', 'other';
    target None if unknown"""
    hw1 = u16(b, o)
    if hw1 >> 11 < 0x1d:
        if hw1 & 0xf000 == 0xd000 and (hw1 >> 8) & 0xf < 0xe:
            return 2, 'other', o + 4 + sext(hw1 & 0xff, 8) * 2
        if hw1 & 0xf800 == 0xe000:
            return 2, 'other', o + 4 + sext(hw1 & 0x7ff, 11) * 2
        if hw1 & 0xf500 == 0xb100:
            return 2, 'other', o + 4 + ((((hw1 >> 9) & 1) << 6) | (((hw1 >> 3) & 0x1f) << 1))
        if hw1 & 0xf800 in (0x4800, 0xa000):
            return 2, 'other', ((o + 4) & ~3) + (hw1 & 0xff) * 4
        if hw1 & 0xfc00 == 0x4400 and (hw1 >> 3) & 0xf == 0xf:
            return 2, 'other', None
        return 2, None, None
    hw2 = u16(b, o + 2)
    if hw1 & 0xf800 == 0xf000 and hw2 & 0x8000:
        op = hw2 & 0xd000
        if op in (0x9000, 0xd000, 0xc000):
            s = (hw1 >> 10) & 1
            i1, i2 = 1 - (((hw2 >> 13) & 1) ^ s), 1 - (((hw2 >> 11) & 1) ^ s)
            imm = sext((s << 24) | (i1 << 23) | (i2 << 22) | ((hw1 & 0x3ff) << 12) | ((hw2 & 0x7ff) << 1), 25)
            kind = {0x9000: 'b.w', 0xd000: 'bl', 0xc000: 'blx'}[op]
            return 4, kind, (((o + 4) & ~3) if op == 0xc000 else o + 4) + imm
        if op == 0x8000 and (hw1 >> 6) & 0xe != 0xe:
            return 4, 'b<c>.w', cond_target(b, o)
        return 4, None, None
    m = hw1 & 0xff7f
    if m in (0xf81f, 0xf83f, 0xf85f, 0xf91f, 0xf93f):
        imm = hw2 & 0xfff
        return 4, 'other', ((o + 4) & ~3) + (imm if (hw1 >> 7) & 1 else -imm)
    if hw1 & 0xfe5f == 0xe85f:
        imm = (hw2 & 0xff) * 4
        return 4, 'other', ((o + 4) & ~3) + (imm if (hw1 >> 7) & 1 else -imm)
    if hw1 & 0xfbff in (0xf20f, 0xf2af):
        imm = (((hw1 >> 10) & 1) << 11) | (((hw2 >> 12) & 7) << 8) | (hw2 & 0xff)
        return 4, 'other', ((o + 4) & ~3) + (imm if hw1 & 0xfbff == 0xf20f else -imm)
    if hw1 == 0xe8df and hw2 & 0xffe0 == 0xf000:
        return 4, 'other', None
    return 4, None, None


def instruction_starts(b, lo, hi):
    """instruction boundaries from lo (a function's first instruction) up to hi"""
    out, o = [], lo
    while o < hi:
        out.append(o)
        o += decode(b, o)[0]
    return out, o


# ---- sites

def bitset(mask, i):
    return mask is None or mask[i >> 3] & (0x80 >> (i & 7))


def masked(win, mask):
    if mask is None:
        return bytes(win)
    return bytes(x if bitset(mask, i) else 0 for i, x in enumerate(win))


def parse_site(line):
    w = line.split('#', 1)[0].split()
    if len(w) < 4 or w[0] != 'site':
        raise ValueError('not a site line: ' + line)
    s = dict(name=w[1], len=int(w[2], 0), md5=w[3], mask=None, within=None, at=None)
    for i in range(4, len(w), 2):
        key = {'mask': 'mask', 'in': 'within', 'at': 'at'}.get(w[i])
        if key is None or i + 1 >= len(w):
            raise ValueError('bad word %r in %s' % (w[i], line))
        s[key] = bytes.fromhex(w[i + 1]) if key == 'mask' else w[i + 1]
    if s['mask'] is not None and len(s['mask']) != (s['len'] + 7) // 8:
        raise ValueError('mask is %d bytes, a %d-byte window needs %d' % (len(s['mask']), s['len'], (s['len'] + 7) // 8))
    return s


def search_range(b, s):
    if s['at']:
        r = s['at']
        base, _, add = r.partition('+')
        if not base.startswith('sym:'):
            return None
        f = function_named(b, base[4:])
        if f is None:
            return None
        p = f[1] + (int(add, 0) if add else 0)
        return p, p + s['len']
    if s['within']:
        f = function_named(b, s['within'])
        return (f[1], f[1] + f[2]) if f else None
    return 0, len(b)


def find(b, s):
    """every file offset where the site's window matches"""
    r = search_range(b, s)
    if r is None:
        return []
    lo, hi = r
    n, want, mask, md5 = s['len'], s['md5'], s['mask'], hashlib.md5
    if mask is None:
        return [i for i in range(lo, min(hi, len(b)) - n + 1) if md5(b[i:i + n]).hexdigest() == want]
    return [i for i in range(lo, min(hi, len(b)) - n + 1) if md5(masked(b[i:i + n], mask)).hexdigest() == want]


def compared(mask, n):
    return n if mask is None else sum(1 for i in range(n) if bitset(mask, i))


def site_line(name, b, off, n, mask=None, within=None):
    line = 'site %s %d %s' % (name, n, hashlib.md5(masked(b[off:off + n], mask)).hexdigest())
    if mask is not None:
        line += ' mask ' + mask.hex()
    if within:
        line += ' in ' + within
    return line


def unique_site(b, off, n, name, mask=None, grow=True, limit=256):
    """a site for b[off:off+n], bounded to its function when it's inside an exported one,
    made longer (forward, then back) until it is unique; (line, start, length) or ValueError"""
    start, ln = off, n
    while True:
        f = function_at(b, start, ln)
        within = f[0] if f else None
        line = site_line(name, b, start, ln, mask if (start, ln) == (off, n) else None, within)
        hits = find(b, parse_site(line))
        if hits == [start]:
            if compared(mask if (start, ln) == (off, n) else None, ln) < MIN_COMPARED:
                raise ValueError('only %d bytes to compare (at least %d)' % (compared(mask, ln), MIN_COMPARED))
            return line, start, ln
        if not grow or ln >= limit:
            raise ValueError('the window at %#x matches %d time(s), not once, even at %d bytes' % (off, len(hits), ln))
        if start + ln + 4 <= len(b):
            ln += 4
        else:
            start, ln = start - 4, ln + 4


def make(b, off, n, also=(), name='s1'):
    mask = None
    if also:
        f0 = function_at(b, off, n)
        if f0 is None:
            raise ValueError('--also needs the window inside an exported function (to find it in the other builds)')
        rel = off - f0[1]
        bits = bytearray(b'\xff' * ((n + 7) // 8))
        for ob in also:
            fo = function_named(ob, f0[0])
            if fo is None:
                raise ValueError('%s is not exported in one of the other builds' % f0[0])
            w2 = ob[fo[1] + rel:fo[1] + rel + n]
            for i in range(n):
                if i >= len(w2) or w2[i] != b[off + i]:
                    bits[i >> 3] &= ~(0x80 >> (i & 7)) & 0xff
        if n % 8:
            bits[-1] &= (0xff << (8 - n % 8)) & 0xff
        full = bytearray(b'\xff' * ((n + 7) // 8))
        if n % 8:
            full[-1] &= (0xff << (8 - n % 8)) & 0xff
        if bits != full:
            mask = bytes(bits)
    return unique_site(b, off, n, name, mask, grow=mask is None)[0]


# ---- recipes

def recipe_lines(text):
    for l in text.split('\n'):
        w = l.split('#', 1)[0].split()
        yield l, w


def built_md5(text):
    m = re.search(r'(?:from stock|stock build|build with md5)\s+([0-9a-f]{32})', text)
    return m.group(1) if m else None


def fits(text, b):
    regions = [(int(w[1], 0), int(w[2], 0), w[3], w[4]) for _, w in recipe_lines(text) if w and w[0] == 'verify']
    md5 = lambda o, n: hashlib.md5(b[o:o + n]).hexdigest()
    if regions and all(o + n <= len(b) and md5(o, n) == before for o, n, before, _ in regions):
        return 'fits'
    if regions and all(after == '-' or (o + n <= len(b) and md5(o, n) == after) for o, n, _, after in regions) \
            and any(after != '-' for *_, after in regions):
        return 'applied'
    return 'no'


def relocate(text, lib=None):
    """(relocatable text, notes); lib: the library the recipe was made for (its stock bytes)"""
    regions = [(int(w[1], 0), int(w[2], 0), w[3]) for _, w in recipe_lines(text) if w and w[0] == 'verify']
    notes, pinned = [], []
    if lib is not None and fits(text, lib) != 'fits':
        raise ValueError("the library isn't the one this recipe was made from (its verify regions' stock bytes aren't there)")
    sites = []   # (name, start, len, line)
    for k, (o, n, before) in enumerate(regions):
        name = 'v%d' % k
        if lib is None:
            sites.append((name, o, n, 'site %s %d %s' % (name, n, before)))
            continue
        line, so, sn = unique_site(lib, o, n, name)
        if (so, sn) != (o, n):
            notes.append('%s: the %d-byte region at %#x matches more than once; its site is %d bytes from %#x' % (name, n, o, sn, so))
        sites.append((name, so, sn, line))

    def site_of(off, n=1):
        for name, so, sn, _ in sites:
            if so <= off and off + n <= so + sn:
                return name, off - so
        return None

    def ref(off, n=1):
        s = site_of(off, n)
        return None if s is None else ('%s+%#x' % s if s[1] else s[0])

    extra = []

    def target_ref(t):
        """a branch or call target: a site it's in, an exported function, or a new site"""
        r = ref(t)
        if r:
            return r
        if lib is None:
            return None
        f = function_at(lib, t)
        if f is not None:
            return 'sym:%s%s' % (f[0], '+%#x' % (t - f[1]) if t != f[1] else '')
        name = 't%d' % len(extra)
        try:
            line, so, sn = unique_site(lib, t, 32, name)
        except ValueError:
            return None
        extra.append((name, so, sn, line))
        sites.append((name, so, sn, line))
        return ref(t)

    # the writes: bytes the recipe changes, each run widened to whole instructions (with the library)
    writes = {}
    for _, w in recipe_lines(text):
        if w and w[0] == 'patch':
            for i, x in enumerate(bytes.fromhex(w[2])):
                writes[int(w[1], 0) + i] = x
    out_writes = []   # lines
    if lib is not None and writes:
        after = bytearray(lib)
        for o, x in writes.items():
            after[o] = x
        done = set()
        for o in sorted(writes):
            if o in done:
                continue
            f = function_at(lib, o)
            starts = None
            if f is not None:
                st, _ = instruction_starts(lib, f[1], o + 1)
                starts = [s for s in st if s <= o]
            first = starts[-1] if starts else o
            # one instruction at a time from first, while it writes something
            p = first
            run_start, run = None, bytearray()
            while True:
                ln, kind, tgt = decode(bytes(after), p)
                touched = [q for q in range(p, p + ln) if q in writes]
                if not touched:
                    break
                for q in range(p, p + ln):
                    done.add(q)
                old = decode(lib, p)
                if kind == 'b.w' and old[1] == 'b<c>.w' and old[2] == tgt:
                    line = 'jump %s' % ref(p, 4)
                elif kind in ('b.w', 'bl') and tgt is not None and site_of(tgt) != site_of(p, 4):
                    tr = target_ref(tgt)
                    line = '%s %s %s' % ('bw' if kind == 'b.w' else 'bl', ref(p, 4), tr) if tr else None
                    if line is None:
                        pinned.append('%s at %#x to %#x' % (kind, p, tgt))
                        line = 'patch %s %s' % (ref(p, ln) or '%#x' % p, bytes(after[p:p + ln]).hex())
                else:
                    if kind is not None and (tgt is None or site_of(tgt) != site_of(p, ln)):
                        pinned.append('%s at %#x' % (kind, p))
                    line = 'patch %s %s' % (ref(p, ln) or '%#x' % p, bytes(after[p:p + ln]).hex())
                if line.startswith('patch') and out_writes and out_writes[-1][0] == 'patch' and out_writes[-1][2] == p:
                    prev = out_writes[-1]
                    out_writes[-1] = ('patch', prev[1], p + ln, prev[3] + bytes(after[p:p + ln]))
                elif line.startswith('patch'):
                    out_writes.append(('patch', ref(p, ln) or '%#x' % p, p + ln, bytes(after[p:p + ln])))
                else:
                    out_writes.append(('op', line, p + ln, None))
                p += ln
        out_writes = [('%s %s %s' % ('patch', w[1], w[3].hex())) if w[0] == 'patch' else w[1] for w in out_writes]
    else:
        for _, w in recipe_lines(text):
            if w and w[0] == 'patch':
                out_writes.append('patch %s %s' % (ref(int(w[1], 0), len(bytes.fromhex(w[2]))), w[2]))

    out = []
    for l, w in recipe_lines(text):
        if not w:
            if l.startswith('#'):
                out.append(l)
            continue
        k = w[0]
        if k == 'verify':
            name, so, sn, line = sites[[r[0] for r in regions].index(int(w[1], 0))]
            out.append(line)
            out.append('verify %s %s' % (ref(int(w[1], 0), int(w[2], 0)), w[2]))
        elif k == 'patch':
            continue
        elif k == 'branch':
            out.append('branch %s %s' % (ref(int(w[1], 0), 4), w[2]))
        elif k == 'rel32':
            t = int(w[3], 0)
            r = target_ref(t & ~1)
            if r is not None and not r.startswith('sym:') and t & 1:
                base, _, add = r.partition('+')
                r = '%s+%#x' % (base, (int(add, 0) if add else 0) + 1)
            if r is None:
                r = w[3]
                pinned.append('rel32 target %s' % w[3])
            out.append('rel32 %s %s %s' % (w[1], w[2], r))
        elif k == 'segment':
            out.append(' '.join(w))
        else:
            out.append(' '.join(w))
    for name, so, sn, line in extra:
        out.append(line + '   # a call target the added code uses')
    out += out_writes
    if pinned:
        m = built_md5(text) or (hashlib.md5(lib).hexdigest() if lib is not None else None)
        if not m:
            raise ValueError('%s stay plain offsets and the recipe does not say its build' % ', '.join(pinned))
        out.insert(0, 'built-for ' + m)
        said = ', '.join(pinned[:3]) + (' and %d more' % (len(pinned) - 3) if len(pinned) > 3 else '')
        out.insert(0, '# pinned to its build: %s depend on where they are, and stay plain' % said)
        notes.append('pinned to its build (built-for): %d instruction(s) or target(s) that depend on where they are stay plain: %s' % (len(pinned), said))
    # writes reference sites: put every site line before the first line that uses it
    sites_first = [l for l in out if l.startswith('site ')]
    rest = [l for l in out if not l.startswith('site ')]
    head = [l for l in rest if l.startswith('#') or l.split()[0] in ('built-for', 'id', 'file', 'native')]
    body = [l for l in rest if l not in head]
    return '\n'.join(head + sites_first + body) + '\n', notes


# ---- scan

def recipe_regions(path):
    rid, file, regions = os.path.basename(path), None, []
    for _, w in recipe_lines(open(path).read()):
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


def same_lib(file, lib):
    return os.path.basename(lib).split('.')[0] == os.path.basename(file or '').split('.')[0]


def scan(recipes, libs):
    rows, cache = [], {}
    for rp in recipes:
        rid, file, regions = recipe_regions(rp)
        for lib in libs:
            if not same_lib(file, lib):
                continue
            if lib not in cache:
                b = open(lib, 'rb').read()
                cache[lib] = (b, hashlib.md5(b).hexdigest())
            b, m = cache[lib]
            for off, n, md5 in regions:
                rows.append((rid, file, off, n, lib, m, find(b, dict(len=n, md5=md5, mask=None, within=None, at=None))))
    return rows


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
    check('two matches: both reported (the site is not unique)', len(find(bytes(lib) + site, parse_site(line))) == 2)
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
        unique_site(bytes(lib), 5000, 8, 's', mask=bytes([0xff]), grow=False)
        check('a window too short to be safe is refused', False)
    except ValueError:
        check('a window too short to be safe is refused', True)
    rep = bytes(lib[:3000]) + bytes(64) + bytes(lib[3000:]) + bytes(64)
    l2, so, sn = unique_site(rep, 3010, 20, 's')
    check('a window that is not unique is made longer until it is', find(rep, parse_site(l2)) == [so] and sn > 20)
    # Thumb: b.w at 0x100 to 0x1000, beq.w at 0x200 to 0x300, bl, ldr literal, cmp
    t = bytearray(0x2000)

    def put(o, hws):
        for i, h in enumerate(hws):
            t[o + 2 * i], t[o + 2 * i + 1] = h & 0xff, h >> 8

    off = 0x1000 - 0x104
    put(0x100, [0xf000 | ((off >> 12) & 0x3ff), 0xb800 | ((off >> 1) & 0x7ff)])   # b.w (S=0: J1=J2=1)
    put(0x200, [0xf000 | (0 << 6) | (((0x300 - 0x204) >> 12) & 0x3f), 0x8000 | (((0x300 - 0x204) >> 1) & 0x7ff)])
    put(0x400, [0x4803])                                                           # ldr r0, [pc, #12]
    put(0x402, [0x2819])                                                           # cmp r0, #25
    check('decode: b.w and its target', decode(bytes(t), 0x100) == (4, 'b.w', 0x1000))
    check('decode: b<c>.w and its target', decode(bytes(t), 0x200)[1:] == ('b<c>.w', 0x300))
    check('decode: ldr literal depends on its address', decode(bytes(t), 0x400) == (2, 'other', 0x410))
    check('decode: cmp does not', decode(bytes(t), 0x402) == (2, None, None))
    return fail


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('syms'); p.add_argument('lib'); p.add_argument('--at', type=lambda x: int(x, 0))
    p = sub.add_parser('make'); p.add_argument('lib'); p.add_argument('off', type=lambda x: int(x, 0)); p.add_argument('len', type=lambda x: int(x, 0))
    p.add_argument('--also', nargs='*', default=[]); p.add_argument('--name', default='s1')
    p = sub.add_parser('find'); p.add_argument('lib'); p.add_argument('site')
    p = sub.add_parser('relocate'); p.add_argument('recipe'); p.add_argument('--lib')
    p = sub.add_parser('fits'); p.add_argument('recipe'); p.add_argument('lib')
    p = sub.add_parser('scan'); p.add_argument('recipes', nargs='+'); p.add_argument('--libs', nargs='+', required=True)
    sub.add_parser('selftest')
    a = ap.parse_args()
    if a.cmd == 'selftest':
        sys.exit(selftest())
    if a.cmd == 'syms':
        b = open(a.lib, 'rb').read()
        if a.at is not None:
            f = function_at(b, a.at)
            print('%s +%#x (function at %#x, %d bytes)' % (f[0], a.at - f[1], f[1], f[2]) if f else 'not inside an exported function')
            return
        for name, off, size, thumb in functions(b):
            print('%#08x %6d %s %s' % (off, size, 'T' if thumb else 'A', name))
    elif a.cmd == 'make':
        try:
            print(make(open(a.lib, 'rb').read(), a.off, a.len, [open(x, 'rb').read() for x in a.also], a.name))
        except ValueError as e:
            sys.exit('no site: %s' % e)
    elif a.cmd == 'find':
        hits = find(open(a.lib, 'rb').read(), parse_site(a.site))
        print(' '.join('%#x' % h for h in hits) if hits else 'no match')
        sys.exit(0 if len(hits) == 1 else 1)
    elif a.cmd == 'relocate':
        try:
            text, notes = relocate(open(a.recipe).read(), open(a.lib, 'rb').read() if a.lib else None)
        except ValueError as e:
            sys.exit('not relocated: %s' % e)
        sys.stdout.write(text)
        for n in notes:
            print('note: ' + n, file=sys.stderr)
    elif a.cmd == 'fits':
        print(fits(open(a.recipe).read(), open(a.lib, 'rb').read()))
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
