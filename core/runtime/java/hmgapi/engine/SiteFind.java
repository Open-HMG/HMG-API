package hmgapi.engine;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A mod's memory recipe whose offsets are found in the library by what the code there
 * looks like, instead of fixed for one build ("hooks": {"native_sites": ...}, see
 * SiteRecipes). resolve() turns it into a plain MemRecipe for one library; MemRecipe.apply
 * then checks those exact bytes in memory before it writes anything, as for any recipe.
 * The recipe still holds no stock bytes: a site is an md5, as a verify line is.
 *
 *   site <name> <len> <md5> [mask <hex>] [in <symbol> | at <ref>]
 *       a window of len bytes whose md5 (wildcard bytes, mask bit 0, counted as 0) is md5.
 *       Found where exactly one window in the range has it; none or more: not found.
 *       mask: one bit per byte, the first byte's is 0x80 of the first mask byte.
 *       in <symbol>: only inside that exported function (.dynsym), at <ref>: only there
 *       (sym:<function>+off: an exported symbol plus an offset, still checked by its md5).
 *   verify <ref> <len>                 the md5s are the library's own bytes there, before
 *                                      and after this recipe's writes ("-" with a branch in it)
 *   patch <ref> <hex>                  as MemRecipe; it must start on an instruction
 *   jump <ref>                         the b<cond>.w there made a b.w to the same target
 *   bw <ref> <target ref>              a b.w there to the target   (bytes computed per build)
 *   bl <ref> <target ref>              a bl there to the target    (the same)
 *   branch <ref> <seg off>, rel32 <seg off> <anchor> <ref>, segment <ref|off> <hex>: as MemRecipe
 *   built-for <md5>                    plain numbers are offsets in that build only
 *   <ref>: <site>[+off], sym:<function>[+off], or a plain number (only on the built-for build)
 *
 * What keeps it from writing in the wrong place:
 *   - a site is used only on a unique match, and compares 16 bytes at least;
 *   - a patch never writes a site's wildcard byte (jump, bw and bl may: they decode or
 *     compute the bytes they write);
 *   - a plain patch holding a PC-relative instruction (a branch, a literal load) whose
 *     target leaves its site is refused: the same bytes would point elsewhere on another
 *     build. Branches between sites are bw/bl/jump;
 *   - a plain number (a call target in added code, say) holds on one build only, and no
 *     verify region covers it: such a recipe resolves only on its built-for build.
 * The code is Thumb-2, as in these head units' libraries.
 */
public final class SiteFind {
    private SiteFind() {}

    public static final int MIN_COMPARED = 16;

    public static final class Site {
        public String name, md5, within, at;
        public int len;
        public byte[] mask;   // null: every byte compared

        public int compared() {
            if (mask == null) return len;
            int n = 0;
            for (int i = 0; i < len; i++) if (bit(mask, i)) n++;
            return n;
        }
    }

    /** resolution's outcome: the plain recipe, or why there's none */
    public static final class Resolved {
        public final String recipe, why;
        public final Map<String, Integer> sites;

        Resolved(String recipe, String why, Map<String, Integer> sites) {
            this.recipe = recipe;
            this.why = why;
            this.sites = sites;
        }
    }

    static boolean bit(byte[] mask, int i) {
        return (mask[i >> 3] & (0x80 >> (i & 7))) != 0;
    }

    public static Site parseSite(String[] w) {
        Site s = new Site();
        s.name = w[1];
        s.len = Integer.decode(w[2]);
        s.md5 = w[3];
        for (int i = 4; i + 1 < w.length; i += 2) {
            if (w[i].equals("mask")) s.mask = MemRecipe.hex(w[i + 1]);
            else if (w[i].equals("in")) s.within = w[i + 1];
            else if (w[i].equals("at")) s.at = w[i + 1];
            else throw new IllegalArgumentException("unknown word " + w[i]);
        }
        if ((w.length - 4) % 2 != 0) throw new IllegalArgumentException("a word without its value");
        if (s.len < 1 || s.len > 4096) throw new IllegalArgumentException("site length " + s.len);
        if (s.md5.length() != 32) throw new IllegalArgumentException("not an md5: " + s.md5);
        if (s.mask != null && s.mask.length != (s.len + 7) / 8) throw new IllegalArgumentException("mask length");
        if (s.within != null && s.at != null) throw new IllegalArgumentException("in and at together");
        return s;
    }

    /** every file offset in [lo, hi) where the site's window matches */
    public static List<Integer> find(byte[] lib, Site s, int lo, int hi) {
        List<Integer> hits = new ArrayList<Integer>();
        MessageDigest d = md5();
        byte[] want = MemRecipe.hex(s.md5), win = new byte[s.len];
        for (int i = Math.max(0, lo); i + s.len <= Math.min(hi, lib.length); i++) {
            System.arraycopy(lib, i, win, 0, s.len);
            if (s.mask != null) for (int k = 0; k < s.len; k++) if (!bit(s.mask, k)) win[k] = 0;
            if (Arrays.equals(d.digest(win), want)) hits.add(i);
        }
        return hits;
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Does every site say where to look (in or at)? Then resolving costs little: an app can do it itself. */
    public static boolean bounded(String text) {
        boolean any = false;
        for (String raw : text.split("\n")) {
            String[] w = words(raw);
            if (w == null || !w[0].equals("site")) continue;
            any = true;
            String l = " " + join(w) + " ";
            if (!l.contains(" in ") && !l.contains(" at ")) return false;
        }
        return any;
    }

    // ---- ELF: exported functions (32-bit little-endian)

    static int u32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    static String cstr(byte[] b, int at) {
        int e = at;
        while (e < b.length && b[e] != 0) e++;
        return new String(b, at, e - at, java.nio.charset.Charset.forName("ISO-8859-1"));
    }

    static int vaddrToOff(byte[] b, long va) {
        int phoff = u32(b, 0x1c), phnum = u16(b, 0x2c);
        for (int k = 0; k < phnum; k++) {
            int p = phoff + 32 * k;
            if (p + 32 > b.length) break;
            long v = u32(b, p + 8) & 0xffffffffL, fsz = u32(b, p + 16) & 0xffffffffL;
            if (u32(b, p) == 1 && va >= v && va < v + fsz) return (int) (va - v + u32(b, p + 4));
        }
        return -1;
    }

    /** exported, defined functions: name -> {file offset, size, Thumb bit}; empty if it isn't an ELF we read */
    public static Map<String, int[]> functions(byte[] b) {
        Map<String, int[]> out = new LinkedHashMap<String, int[]>();
        try {
            if (b.length < 0x34 || b[0] != 0x7f || b[1] != 'E' || b[2] != 'L' || b[3] != 'F' || b[4] != 1 || b[5] != 1) return out;
            int shoff = u32(b, 0x20), shentsize = u16(b, 0x2e), shnum = u16(b, 0x30);
            for (int k = 0; k < shnum; k++) {
                int sh = shoff + k * shentsize;
                if (u32(b, sh + 4) != 11) continue;   // SHT_DYNSYM
                int off = u32(b, sh + 16), size = u32(b, sh + 20), link = u32(b, sh + 24);
                int strs = u32(b, shoff + link * shentsize + 16);
                for (int i = 0; i < size / 16; i++) {
                    int e = off + 16 * i;
                    long value = u32(b, e + 4) & 0xffffffffL;
                    int sz = u32(b, e + 8), info = b[e + 12] & 0xff, shndx = u16(b, e + 14);
                    if ((info & 0xf) != 2 || shndx == 0 || value == 0) continue;   // STT_FUNC, defined
                    int fo = vaddrToOff(b, value & ~1L);
                    if (fo >= 0) out.put(cstr(b, strs + u32(b, e)), new int[] { fo, sz, (int) (value & 1) });
                }
            }
        } catch (RuntimeException e) {
            out.clear();   // a damaged table: no symbols, so "in"/"at"/"sym:" sites aren't found
        }
        return out;
    }

    // ---- Thumb-2: the instructions whose meaning depends on where they are

    /** {length, target}: target -1 if it doesn't depend on its address, -2 if it does in a way we don't follow (TBB, add rX, pc) */
    static long[] decode(byte[] b, int o) {
        int hw1 = u16(b, o);
        if ((hw1 >>> 11) < 0x1d) {
            if ((hw1 & 0xf000) == 0xd000 && ((hw1 >> 8) & 0xf) < 0xe) return new long[] { 2, o + 4 + ((byte) hw1) * 2L };      // b<c> T1
            if ((hw1 & 0xf800) == 0xe000) return new long[] { 2, o + 4 + sext(hw1 & 0x7ff, 11) * 2L };                          // b T2
            if ((hw1 & 0xf500) == 0xb100) return new long[] { 2, o + 4 + ((((hw1 >> 9) & 1) << 6) | (((hw1 >> 3) & 0x1f) << 1)) }; // cbz/cbnz
            if ((hw1 & 0xf800) == 0x4800 || (hw1 & 0xf800) == 0xa000) return new long[] { 2, ((o + 4) & ~3L) + (hw1 & 0xff) * 4L }; // ldr lit, adr
            if ((hw1 & 0xfc00) == 0x4400 && ((hw1 >> 3) & 0xf) == 0xf) return new long[] { 2, -2 };                              // add/mov/cmp rX, pc
            return new long[] { 2, -1 };
        }
        if (o + 4 > b.length) return new long[] { 4, -2 };
        int hw2 = u16(b, o + 2);
        if ((hw1 & 0xf800) == 0xf000 && (hw2 & 0x8000) != 0) {
            int op = hw2 & 0xd000;
            if (op == 0x9000 || op == 0xd000 || op == 0xc000) {   // b.w T4, bl, blx
                long s = (hw1 >> 10) & 1, i1 = ~(((hw2 >> 13) & 1) ^ s) & 1, i2 = ~(((hw2 >> 11) & 1) ^ s) & 1;
                long imm = (s << 24) | (i1 << 23) | (i2 << 22) | ((hw1 & 0x3ffL) << 12) | ((hw2 & 0x7ffL) << 1);
                if (s == 1) imm -= 1L << 25;
                return new long[] { 4, (op == 0xc000 ? ((o + 4) & ~3L) : o + 4) + imm };
            }
            if (op == 0x8000 && ((hw1 >> 6) & 0xe) != 0xe) return new long[] { 4, condBranchTarget(b, o) };   // b<c>.w T3
            return new long[] { 4, -1 };
        }
        int m = hw1 & 0xff7f;
        if (m == 0xf81f || m == 0xf83f || m == 0xf85f || m == 0xf91f || m == 0xf93f) {   // ldr(b,h,sb,sh).w literal
            long imm = hw2 & 0xfff;
            return new long[] { 4, ((o + 4) & ~3L) + (((hw1 >> 7) & 1) == 1 ? imm : -imm) };
        }
        if ((hw1 & 0xfe5f) == 0xe85f) {   // ldrd literal
            long imm = (hw2 & 0xff) * 4L;
            return new long[] { 4, ((o + 4) & ~3L) + (((hw1 >> 7) & 1) == 1 ? imm : -imm) };
        }
        if ((hw1 & 0xfbff) == 0xf20f || (hw1 & 0xfbff) == 0xf2af) {   // adr.w
            long imm = (((hw1 >> 10) & 1L) << 11) | (((hw2 >> 12) & 7L) << 8) | (hw2 & 0xff);
            return new long[] { 4, ((o + 4) & ~3L) + ((hw1 & 0xfbff) == 0xf20f ? imm : -imm) };
        }
        if (hw1 == 0xe8df && (hw2 & 0xffe0) == 0xf000) return new long[] { 4, -2 };   // tbb/tbh
        return new long[] { 4, -1 };
    }

    static long sext(long v, int bits) {
        return (v & (1L << (bits - 1))) != 0 ? v - (1L << bits) : v;
    }

    /** the target (file offset) of a Thumb-2 B<cond>.W (T3) at off, or -1 if it isn't one */
    static long condBranchTarget(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) return -1;
        int hw1 = u16(b, off), hw2 = u16(b, off + 2);
        if ((hw1 & 0xf800) != 0xf000 || (hw2 & 0xd000) != 0x8000 || ((hw1 >> 6) & 0xe) == 0xe) return -1;   // cond 111x: not T3
        long s = (hw1 >> 10) & 1, j1 = (hw2 >> 13) & 1, j2 = (hw2 >> 11) & 1, imm6 = hw1 & 0x3f, imm11 = hw2 & 0x7ff;
        long imm = (s << 20) | (j2 << 19) | (j1 << 18) | (imm6 << 12) | (imm11 << 1);
        if (s == 1) imm -= 1 << 21;
        return off + 4 + imm;
    }

    /** a Thumb-2 BL from from to target, or null if out of range */
    public static byte[] blW(long from, long target) {
        byte[] b = MemRecipe.branchW(from, target);
        if (b == null) return null;
        b[3] |= 0x40;   // hw2 0x9000 (b.w) -> 0xd000 (bl): the same displacement
        return b;
    }

    // ---- resolution

    static String[] words(String raw) {
        int hash = raw.indexOf('#');
        String l = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
        return l.isEmpty() ? null : l.split("\\s+");
    }

    static String hexOf(byte[] b) {
        StringBuilder h = new StringBuilder();
        for (byte x : b) h.append(String.format("%02x", x & 0xff));
        return h.toString();
    }

    static final class Ctx {
        final byte[] lib;
        final boolean pinned;
        final Map<String, Integer> at = new LinkedHashMap<String, Integer>();
        final Map<String, Site> sites = new LinkedHashMap<String, Site>();
        Map<String, int[]> fns;

        Ctx(byte[] lib, boolean pinned) {
            this.lib = lib;
            this.pinned = pinned;
        }

        Map<String, int[]> fns() {
            if (fns == null) fns = functions(lib);
            return fns;
        }

        /** <site>[+off], sym:<name>[+off], or a number (only on the built-for build) */
        long ref(String r) {
            String base = r;
            long add = 0;
            int plus = r.indexOf('+');
            if (plus > 0) {
                base = r.substring(0, plus);
                add = Long.decode(r.substring(plus + 1));
            }
            if (base.startsWith("sym:")) {
                int[] f = fns().get(base.substring(4));
                if (f == null) throw new IllegalArgumentException("no exported function " + base.substring(4));
                return f[0] + add;
            }
            if (at.containsKey(base)) return at.get(base) + add;
            if (sites.containsKey(base)) throw new IllegalArgumentException("site " + base + " is used before it's found");
            if (base.matches("0x[0-9a-fA-F]+|[0-9]+")) {
                if (!pinned) throw new IllegalArgumentException("plain offset " + r + " isn't a site's or a symbol's: it only holds on its built-for build");
                return Long.decode(base) + add;
            }
            throw new IllegalArgumentException("no site " + base);
        }

        /** a ref's target as a call/branch destination: a symbol's Thumb bit must be set */
        long codeRef(String r) {
            if (r.startsWith("sym:")) {
                int[] f = fns().get(r.substring(4).split("\\+")[0]);
                if (f != null && f[2] == 0) throw new IllegalArgumentException(r + " is ARM code: a Thumb b.w/bl can't reach it");
            }
            return ref(r);
        }

        /** the site window holding [off, off+len), or null */
        Site siteOf(long off, int len) {
            for (Site s : sites.values()) {
                Integer a = at.get(s.name);
                if (a != null && off >= a && off + len <= a + s.len) return s;
            }
            return null;
        }
    }

    /** a relocatable recipe on this library (its file bytes): the plain recipe, or why not */
    public static Resolved resolve(String text, byte[] lib) {
        String builtFor = null, libMd5 = MemRecipe.md5(lib);
        List<String[]> lines = new ArrayList<String[]>();
        for (String raw : text.split("\n")) {
            String[] w = words(raw);
            if (w == null) continue;
            if (w[0].equals("built-for")) builtFor = w[1];
            else lines.add(w);
        }
        Ctx c = new Ctx(lib, builtFor != null && builtFor.equals(libMd5));
        StringBuilder head = new StringBuilder("# resolved for " + libMd5 + " by hmgapi.engine.SiteFind\n");
        List<long[]> writes = new ArrayList<long[]>();   // {off, index, plain (1) or computed (0)}
        List<byte[]> bytes = new ArrayList<byte[]>();
        List<Long> branches = new ArrayList<Long>();
        List<long[]> verifies = new ArrayList<long[]>();
        List<String> rest = new ArrayList<String>();
        try {
            for (String[] w : lines) {
                String k = w[0];
                if (k.equals("site")) {
                    Site s = parseSite(w);
                    if (c.sites.containsKey(s.name)) throw new IllegalArgumentException("site " + s.name + " twice");
                    c.sites.put(s.name, s);
                    if (s.compared() < MIN_COMPARED) throw new IllegalArgumentException("site " + s.name + " compares only " + s.compared() + " bytes (" + MIN_COMPARED + " at least)");
                    if (s.at != null) {
                        long p = c.ref(s.at);
                        if (find(lib, s, (int) p, (int) p + s.len).size() != 1) throw new IllegalArgumentException("site " + s.name + " isn't at " + s.at);
                        c.at.put(s.name, (int) p);
                        continue;
                    }
                    int lo = 0, hi = lib.length;
                    if (s.within != null) {
                        int[] f = c.fns().get(s.within);
                        if (f == null) throw new IllegalArgumentException("site " + s.name + ": no exported function " + s.within);
                        lo = f[0];
                        hi = f[0] + f[1];
                    }
                    List<Integer> hits = find(lib, s, lo, hi);
                    if (hits.size() != 1) throw new IllegalArgumentException("site " + s.name + " matches " + hits.size() + " times" + (hits.size() > 1 ? " (not unique)" : ""));
                    c.at.put(s.name, hits.get(0));
                } else if (k.equals("verify")) {
                    verifies.add(new long[] { c.ref(w[1]), Long.decode(w[2]) });
                } else if (k.equals("patch")) {
                    long o = c.ref(w[1]);
                    writes.add(new long[] { o, bytes.size(), 1 });
                    bytes.add(MemRecipe.hex(w[2]));
                } else if (k.equals("jump") || k.equals("bw") || k.equals("bl")) {
                    long o = c.ref(w[1]), target;
                    if (k.equals("jump")) {
                        target = condBranchTarget(lib, (int) o);
                        if (target < 0) throw new IllegalArgumentException("jump at 0x" + Long.toHexString(o) + ": not a conditional b<cond>.w");
                    } else {
                        target = c.codeRef(w[2]) & ~1L;
                    }
                    byte[] b = k.equals("bl") ? blW(o, target) : MemRecipe.branchW(o, target);
                    if (b == null) throw new IllegalArgumentException(k + " at 0x" + Long.toHexString(o) + ": target out of range");
                    writes.add(new long[] { o, bytes.size(), 0 });
                    bytes.add(b);
                } else if (k.equals("branch")) {
                    long o = c.ref(w[1]);
                    branches.add(o);
                    rest.add("branch 0x" + Long.toHexString(o) + " " + w[2]);
                } else if (k.equals("rel32")) {
                    long target = c.ref(w[3]);
                    if (w[3].startsWith("sym:")) target |= c.fns().get(w[3].substring(4).split("\\+")[0])[2];   // a call target keeps its Thumb bit
                    rest.add("rel32 " + w[1] + " " + w[2] + " 0x" + Long.toHexString(target));
                } else if (k.equals("segment")) {
                    long p;
                    try {
                        p = c.ref(w[1]);
                    } catch (IllegalArgumentException e) {
                        if (!w[1].matches("0x[0-9a-fA-F]+|[0-9]+")) throw e;
                        p = Long.decode(w[1]);   // only a placement hint: any number is safe
                    }
                    rest.add("segment 0x" + Long.toHexString(p) + " " + w[2]);
                } else if (k.equals("id") || k.equals("file") || k.equals("native")) {
                    head.append(join(w)).append('\n');
                } else {
                    throw new IllegalArgumentException("unknown line: " + join(w));
                }
            }
            byte[] after = lib.clone();
            for (long[] wr : writes) {
                byte[] b = bytes.get((int) wr[1]);
                if (wr[0] < 0 || wr[0] + b.length > lib.length) throw new IllegalArgumentException("write at 0x" + Long.toHexString(wr[0]) + " outside the library");
                System.arraycopy(b, 0, after, (int) wr[0], b.length);
            }
            for (long[] wr : writes) {
                if (wr[2] != 1) continue;
                String why = wildcardWritten(c, wr[0], bytes.get((int) wr[1]).length);
                if (why == null && !c.pinned) why = pcRelative(c, after, wr[0], bytes.get((int) wr[1]).length);
                if (why != null) throw new IllegalArgumentException(why);
            }
            StringBuilder out = new StringBuilder(head);
            for (long[] v : verifies) {
                int off = (int) v[0], len = (int) v[1];
                if (off < 0 || len < 1 || off + len > lib.length) throw new IllegalArgumentException("verify region 0x" + Integer.toHexString(off) + " outside the library");
                boolean branch = false;
                for (long b : branches) if (b + 4 > off && b < off + len) branch = true;
                out.append("verify 0x").append(Integer.toHexString(off)).append(' ').append(len).append(' ')
                        .append(MemRecipe.md5(Arrays.copyOfRange(lib, off, off + len))).append(' ')
                        .append(branch ? "-" : MemRecipe.md5(Arrays.copyOfRange(after, off, off + len))).append('\n');
            }
            for (long[] wr : writes) out.append("patch 0x").append(Long.toHexString(wr[0])).append(' ').append(hexOf(bytes.get((int) wr[1]))).append('\n');
            for (String r : rest) out.append(r).append('\n');
            MemRecipe.parse(out.toString());   // every write inside a verify region, and MemRecipe's other checks
            return new Resolved(out.toString(), null, c.at);
        } catch (IllegalArgumentException e) {
            return new Resolved(null, e.getMessage(), c.at);
        } catch (RuntimeException e) {
            return new Resolved(null, "can't read the recipe: " + e, c.at);
        }
    }

    /** a plain patch may not write a site's wildcard byte: the site would match a library with another change there too */
    static String wildcardWritten(Ctx c, long off, int len) {
        for (Site s : c.sites.values()) {
            Integer base = c.at.get(s.name);
            if (s.mask == null || base == null) continue;
            for (long a = off; a < off + len; a++) {
                long i = a - base;
                if (i >= 0 && i < s.len && !bit(s.mask, (int) i)) return "patch at 0x" + Long.toHexString(off) + " writes a wildcard byte of site " + s.name;
            }
        }
        return null;
    }

    /**
     * The instructions a plain patch writes (from its first byte, which must start one): any
     * whose target leaves the patch's site would point somewhere else on another build.
     */
    static String pcRelative(Ctx c, byte[] after, long off, int len) {
        Site s = c.siteOf(off, len);
        if (s == null) return "patch at 0x" + Long.toHexString(off) + " isn't inside a site (a relocatable recipe writes only in its sites)";
        int lo = c.at.get(s.name), hi = lo + s.len;
        for (long o = off; o < off + len; ) {
            long[] d = decode(after, (int) o);
            if (d[1] == -2 || (d[1] != -1 && (d[1] < lo || d[1] >= hi))) {
                return "patch at 0x" + Long.toHexString(off) + " writes an instruction at 0x" + Long.toHexString(o) + " that depends on where it is"
                        + (d[1] >= 0 ? " (its target 0x" + Long.toHexString(d[1]) + " is outside site " + s.name + ")" : "")
                        + ": write it as jump, bw or bl, or pin the recipe (built-for)";
            }
            o += d[0];
        }
        return null;
    }

    static String join(String[] w) {
        StringBuilder s = new StringBuilder();
        for (String x : w) s.append(s.length() == 0 ? "" : " ").append(x);
        return s.toString();
    }

    static byte[] bytes(java.io.File f) throws java.io.IOException {
        return Engine.readAll(new java.io.FileInputStream(f));
    }

    /** java -cp <engine> hmgapi.engine.SiteFind <relocatable recipe> <library>: the plain recipe, or why not (exit 1) */
    public static void main(String[] a) throws Exception {
        Resolved r = resolve(Engine.read(new java.io.File(a[0])), bytes(new java.io.File(a[1])));
        if (r.recipe == null) {
            System.err.println("doesn't fit: " + r.why);
            System.exit(1);
        }
        System.out.print(r.recipe);
    }
}
