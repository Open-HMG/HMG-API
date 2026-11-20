package hmgapi.engine;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Research prototype (docs/research/self-mapping.md, idea 4): a memory recipe whose
 * offsets are found in the library by what the code there looks like, instead of fixed
 * per build. Not used by the engine or the hook runtime yet.
 *
 * A relocatable recipe is MemRecipe's format with sites:
 *
 *   site <name> <len> <md5> [mask <hex>] [in <symbol>]
 *       a window found where exactly one window in the range has this md5, wildcard
 *       bytes (mask bit 0) counted as 0. mask: one bit per byte, the first byte's is 0x80
 *       of the first mask byte. in: only inside that exported function (.dynsym).
 *   verify <ref> <len>                      md5s taken from the library at resolution
 *   patch <ref> <hex>, branch <ref> <seg off>, rel32 <seg off> <anchor> <ref>
 *       <ref>: <site>[+off], sym:<symbol>[+off], or a plain number (only with built-for)
 *   jump <ref>                              the conditional b<cond>.w there made a b.w to the same
 *                                           target (patch bytes computed for each build)
 *   built-for <md5>                         plain numbers are this build's offsets
 *
 * resolve() turns it into a plain MemRecipe for one library: every site found exactly
 * once (else nothing: the recipe doesn't fit that library), every verify region's "before"
 * md5 the library's own bytes there, its "after" those bytes with the recipe's patches on
 * ("-" with a branch in it). MemRecipe.apply then checks those exact bytes in memory
 * before it writes anything, as for any recipe. A patch never writes a site's wildcard byte
 * (jump may: it decodes them). A plain number that isn't a site's or a
 * symbol's (a call target in the added code, say) would be wrong on another build, and no
 * verify region covers it: such a recipe resolves only on its built-for library.
 */
public final class SiteFind {
    private SiteFind() {}

    public static final class Site {
        public String name, md5, within;
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
            else throw new IllegalArgumentException("unknown word " + w[i]);
        }
        if (s.len < 1) throw new IllegalArgumentException("empty site");
        if (s.mask != null && s.mask.length != (s.len + 7) / 8) throw new IllegalArgumentException("mask length");
        return s;
    }

    /** every file offset in [lo, hi) where the site's window matches */
    public static List<Integer> find(byte[] lib, Site s, int lo, int hi) {
        List<Integer> hits = new ArrayList<Integer>();
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] want = MemRecipe.hex(s.md5), win = new byte[s.len];
            for (int i = Math.max(0, lo); i + s.len <= Math.min(hi, lib.length); i++) {
                System.arraycopy(lib, i, win, 0, s.len);
                if (s.mask != null) for (int k = 0; k < s.len; k++) if (!bit(s.mask, k)) win[k] = 0;
                if (java.util.Arrays.equals(d.digest(win), want)) hits.add(i);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return hits;
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
            long v = u32(b, p + 8) & 0xffffffffL, fsz = u32(b, p + 16) & 0xffffffffL;
            if (u32(b, p) == 1 && va >= v && va < v + fsz) return (int) (va - v + u32(b, p + 4));
        }
        return -1;
    }

    /** exported, defined functions: name -> {file offset, size, Thumb bit} */
    public static Map<String, int[]> functions(byte[] b) {
        Map<String, int[]> out = new LinkedHashMap<String, int[]>();
        if (b.length < 0x34 || b[0] != 0x7f || b[1] != 'E' || b[4] != 1 || b[5] != 1) return out;
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
        return out;
    }

    // ---- resolution

    /** a relocatable recipe on this library (its file bytes): the plain recipe, or why not */
    public static Resolved resolve(String text, byte[] lib) {
        Map<String, Integer> at = new LinkedHashMap<String, Integer>();
        List<Site> found = new ArrayList<Site>();
        Map<String, int[]> fns = null;
        String builtFor = null, libMd5 = MemRecipe.md5(lib);
        List<String[]> lines = new ArrayList<String[]>();
        for (String raw : text.split("\n")) {
            int hash = raw.indexOf('#');
            String l = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (l.isEmpty()) continue;
            String[] w = l.split("\\s+");
            if (w[0].equals("built-for")) {
                builtFor = w[1];
            } else if (w[0].equals("site")) {
                Site s = parseSite(w);
                if (s.compared() < 16) return new Resolved(null, "site " + s.name + " compares only " + s.compared() + " bytes (16 at least)", at);
                int lo = 0, hi = lib.length;
                if (s.within != null) {
                    if (fns == null) fns = functions(lib);
                    int[] f = fns.get(s.within);
                    if (f == null) return new Resolved(null, "site " + s.name + ": no exported function " + s.within, at);
                    lo = f[0];
                    hi = f[0] + f[1];
                }
                List<Integer> hits = find(lib, s, lo, hi);
                if (hits.size() != 1) return new Resolved(null, "site " + s.name + " matches " + hits.size() + " times" + (hits.size() > 1 ? " (not unique)" : ""), at);
                at.put(s.name, hits.get(0));
                found.add(s);
            } else {
                lines.add(w);
            }
        }
        boolean pinned = builtFor != null && builtFor.equals(libMd5);
        StringBuilder out = new StringBuilder("# resolved for " + libMd5 + " by hmgapi.engine.SiteFind\n");
        List<long[]> patches = new ArrayList<long[]>();   // {off, index into patchBytes}
        List<byte[]> patchBytes = new ArrayList<byte[]>();
        List<Long> branches = new ArrayList<Long>();
        List<long[]> verifies = new ArrayList<long[]>();
        List<String> rest = new ArrayList<String>();
        try {
            for (String[] w : lines) {
                if (w[0].equals("verify")) {
                    verifies.add(new long[] { ref(w[1], at, lib, pinned), Long.decode(w[2]) });
                } else if (w[0].equals("patch")) {
                    patches.add(new long[] { ref(w[1], at, lib, pinned), patchBytes.size() });
                    patchBytes.add(MemRecipe.hex(w[2]));
                    String wild = wildcardWritten(found, at, patches.get(patches.size() - 1)[0], patchBytes.get(patchBytes.size() - 1).length);
                    if (wild != null) return new Resolved(null, wild, at);
                    rest.add("patch 0x" + Long.toHexString(patches.get(patches.size() - 1)[0]) + " " + w[2]);
                } else if (w[0].equals("jump")) {
                    // a Thumb-2 conditional b<cond>.w made unconditional, same target: the bytes depend on the build
                    long o = ref(w[1], at, lib, pinned);
                    long target = condBranchTarget(lib, (int) o);
                    if (target < 0) return new Resolved(null, "jump at 0x" + Long.toHexString(o) + ": not a conditional b<cond>.w", at);
                    byte[] bw = MemRecipe.branchW(o, target);
                    StringBuilder h = new StringBuilder();
                    for (byte x : bw) h.append(String.format("%02x", x & 0xff));
                    patches.add(new long[] { o, patchBytes.size() });
                    patchBytes.add(bw);
                    rest.add("patch 0x" + Long.toHexString(o) + " " + h);
                } else if (w[0].equals("branch")) {
                    long o = ref(w[1], at, lib, pinned);
                    branches.add(o);
                    rest.add("branch 0x" + Long.toHexString(o) + " " + w[2]);
                } else if (w[0].equals("rel32")) {
                    long target = ref(w[3], at, lib, pinned);
                    if (w[3].startsWith("sym:")) target |= functions(lib).get(w[3].substring(4).split("\\+")[0])[2];   // a call target keeps its Thumb bit
                    rest.add("rel32 " + w[1] + " " + w[2] + " 0x" + Long.toHexString(target));
                } else if (w[0].equals("segment")) {
                    String pref = w[1];
                    long p;
                    try {
                        p = ref(pref, at, lib, true);   // only a placement hint: any number is safe
                    } catch (IllegalArgumentException e) {
                        p = -1;
                    }
                    rest.add("segment 0x" + Long.toHexString(Math.max(p, 0)) + " " + w[2]);
                } else if (w[0].equals("id") || w[0].equals("file") || w[0].equals("native")) {
                    out.append(join(w)).append('\n');
                } else {
                    return new Resolved(null, "unknown line: " + join(w), at);
                }
            }
        } catch (IllegalArgumentException e) {
            return new Resolved(null, e.getMessage(), at);
        }
        for (long[] v : verifies) {
            int off = (int) v[0], len = (int) v[1];
            if (off < 0 || off + len > lib.length) return new Resolved(null, "verify region 0x" + Integer.toHexString(off) + " outside the library", at);
            byte[] before = java.util.Arrays.copyOfRange(lib, off, off + len), after = before.clone();
            boolean branch = false;
            for (long[] p : patches) {
                byte[] pb = patchBytes.get((int) p[1]);
                for (int k = 0; k < pb.length; k++) {
                    long a = p[0] + k - off;
                    if (a >= 0 && a < len) after[(int) a] = pb[k];
                }
            }
            for (long b : branches) if (b + 4 > off && b < off + len) branch = true;
            out.append("verify 0x").append(Integer.toHexString(off)).append(' ').append(len).append(' ')
                    .append(MemRecipe.md5(before)).append(' ').append(branch ? "-" : MemRecipe.md5(after)).append('\n');
        }
        for (String r : rest) out.append(r).append('\n');
        try {
            MemRecipe.parse(out.toString());   // patches inside verify regions, and the rest of MemRecipe's checks
        } catch (IllegalArgumentException e) {
            return new Resolved(null, "resolved recipe isn't valid: " + e.getMessage(), at);
        }
        return new Resolved(out.toString(), null, at);
    }

    /**
     * A patch may not write a site's wildcard byte: the site would match a library with some
     * other change there too. (jump decodes the bytes it rewrites, so it may.)
     */
    static String wildcardWritten(List<Site> sites, Map<String, Integer> at, long off, int len) {
        for (Site s : sites) {
            if (s.mask == null) continue;
            int base = at.get(s.name);
            for (long a = off; a < off + len; a++) {
                long i = a - base;
                if (i >= 0 && i < s.len && !bit(s.mask, (int) i)) return "patch at 0x" + Long.toHexString(off) + " writes a wildcard byte of site " + s.name;
            }
        }
        return null;
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

    static String join(String[] w) {
        StringBuilder s = new StringBuilder();
        for (String x : w) s.append(s.length() == 0 ? "" : " ").append(x);
        return s.toString();
    }

    /** <site>[+off], sym:<name>[+off], or a number (only on the built-for library) */
    static long ref(String r, Map<String, Integer> at, byte[] lib, boolean pinned) {
        String base = r;
        long add = 0;
        int plus = r.indexOf('+');
        if (plus > 0) {
            base = r.substring(0, plus);
            add = Long.decode(r.substring(plus + 1));
        }
        if (base.startsWith("sym:")) {
            int[] f = functions(lib).get(base.substring(4));
            if (f == null) throw new IllegalArgumentException("no exported function " + base.substring(4));
            return f[0] + add;
        }
        if (at.containsKey(base)) return at.get(base) + add;
        if (base.matches("0x[0-9a-fA-F]+|[0-9]+")) {
            if (!pinned) throw new IllegalArgumentException("plain offset " + r + " isn't a site's or a symbol's: it only holds on the built-for library");
            return Long.decode(base) + add;
        }
        throw new IllegalArgumentException("no site " + base);
    }

    static byte[] bytes(java.io.File f) throws java.io.IOException {
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
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
