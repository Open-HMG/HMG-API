package hmgapi.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * SiteFind (relocatable memory recipes) on synthetic libraries.
 *   SiteFindTest                                     the synthetic checks
 *   SiteFindTest --same <relocatable> <lib> <recipe>...   triples: the relocatable form resolves on
 *                                                    the library to the same recipe (same verify
 *                                                    regions, the same bytes changed, the same
 *                                                    branches and call targets)
 *   SiteFindTest --fuzz <n> <lib>...                 random sites in each library: found only where
 *                                                    they are, or not unique; never once somewhere else
 *   SiteFindTest --elf <file> <seed> <shift>         writes a synthetic library (for the engine test)
 */
public final class SiteFindTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static void put32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    static void put16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    /**
     * A small ARM ELF: random code at 0x1000..0x5000 (plus shift bytes in front of it),
     * exporting fnA (0x2000, 0x400 bytes, Thumb), fnB (0x3000, 0x100 bytes, Thumb) and
     * fnC (0x3800, 0x100 bytes, ARM).
     */
    static byte[] elf(long seed, int shift) {
        Random r = new Random(seed);
        byte[] code = new byte[0x4000];
        r.nextBytes(code);
        byte[] b = new byte[0x7000 + shift];
        System.arraycopy(code, 0, b, 0x1000 + shift, code.length);
        b[0] = 0x7f; b[1] = 'E'; b[2] = 'L'; b[3] = 'F'; b[4] = 1; b[5] = 1; b[6] = 1;
        put32(b, 0x1c, 0x34);                 // e_phoff
        put32(b, 0x20, 0x6200 + shift);       // e_shoff
        put16(b, 0x2c, 1);                    // e_phnum
        put16(b, 0x2e, 40);                   // e_shentsize
        put16(b, 0x30, 3);                    // e_shnum
        put32(b, 0x34, 1);                    // PT_LOAD
        put32(b, 0x34 + 16, b.length);        // p_filesz
        int str = 0x6000 + shift, sym = 0x6100 + shift, sh = 0x6200 + shift;
        byte[] names = "\0fnA\0fnB\0fnC\0".getBytes();
        System.arraycopy(names, 0, b, str, names.length);
        int[][] fns = { { 1, 0x2000 + shift, 0x400, 1 }, { 5, 0x3000 + shift, 0x100, 1 }, { 9, 0x3800 + shift, 0x100, 0 } };
        for (int k = 0; k < fns.length; k++) {
            int e = sym + 16 * (k + 1);
            put32(b, e, fns[k][0]);
            put32(b, e + 4, fns[k][1] | fns[k][3]);
            put32(b, e + 8, fns[k][2]);
            b[e + 12] = 0x12;                 // GLOBAL FUNC
            put16(b, e + 14, 1);
        }
        put32(b, sh + 40 + 4, 11);            // [1] SHT_DYNSYM
        put32(b, sh + 40 + 16, sym);
        put32(b, sh + 40 + 20, 64);
        put32(b, sh + 40 + 24, 2);
        put32(b, sh + 80 + 4, 3);             // [2] SHT_STRTAB
        put32(b, sh + 80 + 16, str);
        return b;
    }

    static String md5(byte[] b, int off, int len) {
        return MemRecipe.md5(Arrays.copyOfRange(b, off, off + len));
    }

    /** a process with the library at base (MemRecipeTest's, without segments) */
    static final class Mem implements MemRecipe.Memory {
        final long base;
        final byte[] lib;

        Mem(long base, byte[] lib) {
            this.base = base;
            this.lib = lib.clone();
        }

        public byte[] peek(long addr, int len) {
            return Arrays.copyOfRange(lib, (int) (addr - base), (int) (addr - base) + len);
        }

        public boolean poke(long addr, byte[] data) {
            System.arraycopy(data, 0, lib, (int) (addr - base), data.length);
            return true;
        }

        public long mapCode(long hint, int len) {
            return 0;
        }
    }

    static MemRecipe.Result apply(String recipe, byte[] lib, Mem[] out) {
        Mem m = new Mem(0x40000000L, lib);
        if (out != null) out[0] = m;
        return MemRecipe.parse(recipe).apply(m, m.base, lib.length, null);
    }

    /** b.w (T4) bytes at from to target */
    static void bw(byte[] b, int from, int target) {
        byte[] x = MemRecipe.branchW(from, target);
        System.arraycopy(x, 0, b, from, 4);
    }

    // ---- --same: a relocatable form against a shipped recipe

    /** what a recipe does to a library: verify lines, the bytes it changes, branch/rel32 lines */
    static List<String> effect(String recipe, byte[] lib) {
        List<String> out = new ArrayList<String>();
        Map<Long, Integer> changed = new TreeMap<Long, Integer>();
        for (String raw : recipe.split("\n")) {
            String[] w = SiteFind.words(raw);
            if (w == null) continue;
            if (w[0].equals("native") || w[0].equals("file")) {
                out.add(SiteFind.join(w));
            } else if (w[0].equals("verify") || w[0].equals("branch") || w[0].equals("rel32")) {
                out.add(w[0] + " " + Long.decode(w[1]) + (w.length > 2 ? " " + SiteFind.join(Arrays.copyOfRange(w, 2, w.length)) : ""));
            } else if (w[0].equals("patch")) {
                byte[] b = MemRecipe.hex(w[2]);
                long o = Long.decode(w[1]);
                for (int i = 0; i < b.length; i++) if (lib[(int) o + i] != b[i]) changed.put(o + i, b[i] & 0xff);
            } else if (w[0].equals("segment")) {
                out.add("segment " + w[2]);
            }
        }
        out.add("changes " + changed);
        java.util.Collections.sort(out);
        return out;
    }

    static void same(String[] a) throws Exception {
        for (int i = 1; i + 2 < a.length; i += 3) {
            byte[] lib = SiteFind.bytes(new File(a[i + 1]));
            String name = new File(a[i + 2]).getName();
            SiteFind.Resolved r = SiteFind.resolve(Engine.read(new File(a[i])), lib);
            if (r.recipe == null) {
                check(name + ": its relocatable form resolves on " + new File(a[i + 1]).getName() + " (" + r.why + ")", false);
                continue;
            }
            List<String> want = effect(Engine.read(new File(a[i + 2])), lib), got = effect(r.recipe, lib);
            if (!want.equals(got)) {
                List<String> only = new ArrayList<String>(got);
                only.removeAll(want);
                System.out.println("      resolved, not shipped: " + only);
            }
            check(name + ": its relocatable form resolves on " + new File(a[i + 1]).getName() + " (" + MemRecipe.md5(lib).substring(0, 8)
                    + ") to the same recipe: the same verify regions, the same bytes changed", want.equals(got));
        }
    }

    // ---- --offsets: on a library that carries the recipe's change already, where everything lands

    static List<String> places(String recipe) {
        List<String> out = new ArrayList<String>();
        for (String raw : recipe.split("\n")) {
            String[] w = SiteFind.words(raw);
            if (w == null) continue;
            if (w[0].equals("verify")) out.add("verify " + Long.decode(w[1]) + " " + w[2]);
            else if (w[0].equals("patch") || w[0].equals("branch")) out.add(w[0] + " " + Long.decode(w[1]));
            else if (w[0].equals("rel32")) out.add("rel32 " + w[1] + " " + Long.decode(w[3]));
        }
        java.util.Collections.sort(out);
        return out;
    }

    static void offsets(String[] a) throws Exception {
        for (int i = 1; i + 2 < a.length; i += 3) {
            byte[] lib = SiteFind.bytes(new File(a[i + 1]));
            String name = new File(a[i + 2]).getName();
            SiteFind.Resolved r = SiteFind.resolve(Engine.read(new File(a[i])), lib);
            List<String> want = places(Engine.read(new File(a[i + 2])));
            boolean ok = r.recipe != null && places(r.recipe).equals(want);
            if (r.recipe != null && !ok) System.out.println("      resolved " + places(r.recipe) + ", shipped " + want);
            check(name + " on " + new File(a[i + 1]).getName() + " (" + MemRecipe.md5(lib).substring(0, 8) + ", which has its change already): "
                    + "its sites, writes and call targets land at the shipped offsets" + (r.recipe == null ? " (" + r.why + ")" : ""), ok);
        }
    }

    // ---- --fuzz

    static void fuzz(String[] a) throws Exception {
        int n = Integer.parseInt(a[1]);
        Random r = new Random(23);
        List<byte[]> libs = new ArrayList<byte[]>();
        List<String> names = new ArrayList<String>();
        for (int i = 2; i < a.length; i++) {
            libs.add(SiteFind.bytes(new File(a[i])));
            names.add(new File(a[i]).getName());
        }
        for (int li = 0; li < libs.size(); li++) {
            byte[] lib = libs.get(li);
            String name = names.get(li);
            Map<String, int[]> fns = SiteFind.functions(lib);
            List<int[]> big = new ArrayList<int[]>();
            List<String> bigNames = new ArrayList<String>();
            for (Map.Entry<String, int[]> e : fns.entrySet()) {
                if (e.getValue()[1] >= 96) {
                    big.add(e.getValue());
                    bigNames.add(e.getKey());
                }
            }
            int wrong = 0, unique = 0, multiple = 0, moved = 0, gone = 0, maskedOk = 0, cross = 0, bounded = 0;
            // whole-library sites (n of them), each also against a build with bytes inserted before or after it,
            // with a compared byte changed, with a wildcard byte changed, and against every other library
            for (int k = 0; k < n; k++) {
                int len = 16 + r.nextInt(33), off = 0x1000 + r.nextInt(lib.length - 0x2000 - len);
                SiteFind.Site s = site(lib, off, len, r);
                List<Integer> hits = SiteFind.find(lib, s, 0, lib.length);
                if (!hits.contains(off)) wrong++;
                if (hits.size() == 1) unique++;
                else multiple++;
                int at = r.nextInt(lib.length), ins = 1 + r.nextInt(64);
                if (at > off - 64 && at < off + len + 64) at = Math.max(0, off - 200);
                byte[] lib2 = new byte[lib.length + ins];
                System.arraycopy(lib, 0, lib2, 0, at);
                byte[] junk = new byte[ins];
                r.nextBytes(junk);
                System.arraycopy(junk, 0, lib2, at, ins);
                System.arraycopy(lib, at, lib2, at + ins, lib.length - at);
                int expect = off + (at <= off ? ins : 0);
                List<Integer> h2 = SiteFind.find(lib2, s, 0, lib2.length);
                if (h2.size() == 1 && h2.get(0) != expect) wrong++;
                if (h2.size() == 1 && h2.get(0) == expect) moved++;
                byte[] lib3 = lib.clone();
                int c = firstCompared(s, r);
                lib3[off + c] ^= 0x5a;
                List<Integer> h3 = SiteFind.find(lib3, s, 0, lib3.length);
                if (h3.contains(off)) wrong++;
                if (h3.isEmpty() || (hits.size() > 1 && !h3.contains(off))) gone++;
                if (s.mask != null) {
                    byte[] lib4 = lib.clone();
                    for (int i = 0; i < len; i++) if (!SiteFind.bit(s.mask, i)) lib4[off + i] ^= 0x33;
                    if (SiteFind.find(lib4, s, 0, lib4.length).contains(off)) maskedOk++;
                    else wrong++;
                }
                for (int lj = 0; lj < libs.size(); lj++) if (lj != li && !SiteFind.find(libs.get(lj), s, 0, libs.get(lj).length).isEmpty()) cross++;
            }
            // sites bounded to an exported function: many, they're cheap
            for (int k = 0; k < Math.min(n * 25, big.size()); k++) {
                int fi = r.nextInt(big.size());
                int[] f = big.get(fi);
                int len = 16 + r.nextInt(33), off = f[0] + r.nextInt(f[1] - len);
                SiteFind.Site s = site(lib, off, len, r);
                s.within = bigNames.get(fi);
                List<Integer> hits = SiteFind.find(lib, s, f[0], f[0] + f[1]);
                if (!hits.contains(off)) wrong++;
                bounded++;
            }
            check(name + ": " + n + " whole-library sites (" + unique + " unique, " + multiple + " not unique), " + bounded
                    + " in-function sites: each found where it is; with code inserted, " + moved + " found again where it moved; "
                    + "a compared byte changed, " + gone + " not found; " + maskedOk + " with their wildcard bytes changed still found; "
                    + cross + " in another library (the same bytes); never once in a wrong place", wrong == 0);
        }
    }

    static SiteFind.Site site(byte[] lib, int off, int len, Random r) {
        SiteFind.Site s = new SiteFind.Site();
        s.name = "f";
        s.len = len;
        if (r.nextBoolean() && len > SiteFind.MIN_COMPARED + 2) {
            s.mask = new byte[(len + 7) / 8];
            Arrays.fill(s.mask, (byte) 0xff);
            int holes = 1 + r.nextInt(len - SiteFind.MIN_COMPARED);
            for (int i = 0; i < holes; i++) {
                int p = r.nextInt(len);
                s.mask[p >> 3] &= ~(0x80 >> (p & 7));
            }
            for (int i = len; i < s.mask.length * 8; i++) s.mask[i >> 3] &= ~(0x80 >> (i & 7));
        }
        byte[] w = Arrays.copyOfRange(lib, off, off + len);
        if (s.mask != null) for (int i = 0; i < len; i++) if (!SiteFind.bit(s.mask, i)) w[i] = 0;
        s.md5 = MemRecipe.md5(w);
        return s;
    }

    static int firstCompared(SiteFind.Site s, Random r) {
        for (int t = 0; t < 100; t++) {
            int p = r.nextInt(s.len);
            if (s.mask == null || SiteFind.bit(s.mask, p)) return p;
        }
        return 0;
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("--same")) {
            same(a);
            System.exit(fail);
        }
        if (a.length > 0 && a[0].equals("--offsets")) {
            offsets(a);
            System.exit(fail);
        }
        if (a.length > 0 && a[0].equals("--fuzz")) {
            fuzz(a);
            System.exit(fail);
        }
        if (a.length > 0 && a[0].equals("--app")) {   // <data dir> <mod> <name> <recipe file> <lib>: what an app gets
            String[] why = new String[1];
            MemRecipe r = SiteRecipes.forLibrary(new File(a[1]), a[2], a[3], Engine.read(new File(a[4])), a[5], CarMap.md5(new File(a[5])), why);
            System.out.println(r != null ? "recipe " + r.verifies.get(0).off : "none: " + why[0]);
            return;
        }
        if (a.length > 0 && a[0].equals("--elf")) {
            FileOutputStream o = new FileOutputStream(a[1]);
            o.write(elf(Long.parseLong(a[2]), Integer.parseInt(a[3])));
            o.close();
            return;
        }

        byte[] lib = elf(1, 0);
        int at = 0x2100;   // inside fnA
        String site = "site s 32 " + md5(lib, at, 32);
        String rel = "id t\nfile /system/lib/libt.so\n" + site + "\nverify s 32\npatch s+0x10 00bf\n";
        SiteFind.Resolved r = SiteFind.resolve(rel, lib);
        check("a site resolves where its window is", r.recipe != null && r.sites.get("s") == at);
        byte[] after = Arrays.copyOfRange(lib, at, at + 32);
        after[0x10] = 0x00;
        after[0x11] = (byte) 0xbf;
        check("its verify line: the library's own bytes before, with the patch after",
                r.recipe != null && r.recipe.contains("verify 0x2100 32 " + md5(lib, at, 32) + " " + MemRecipe.md5(after)));

        byte[] moved = elf(1, 0x64);
        SiteFind.Resolved rm = SiteFind.resolve(rel, moved);
        check("the same code 0x64 bytes later (another build): found there", rm.recipe != null && rm.sites.get("s") == at + 0x64);
        Mem[] m = new Mem[1];
        check("and the resolved recipe applies there", rm.recipe != null && apply(rm.recipe, moved, m).status == MemRecipe.Result.APPLIED
                && m[0].lib[at + 0x64 + 0x10] == 0 && m[0].lib[at + 0x64 + 0x11] == (byte) 0xbf);

        byte[] other = moved.clone();
        other[at + 0x64 + 3] ^= 0x55;
        check("resolved on one library, other bytes in memory: nothing written (MemRecipe's verify)",
                rm.recipe != null && apply(rm.recipe, other, m).status == MemRecipe.Result.MISMATCH && Arrays.equals(m[0].lib, other));
        check("... and it doesn't resolve on that library at all", SiteFind.resolve(rel, other).recipe == null);

        byte[] dup = lib.clone();
        System.arraycopy(lib, at, dup, 0x4800, 32);   // the window twice: outside every function, and in fnA
        SiteFind.Resolved rd = SiteFind.resolve(rel, dup);
        check("the window twice in the library: not resolved (not unique)", rd.recipe == null && rd.why.contains("not unique"));
        SiteFind.Resolved ri = SiteFind.resolve(rel.replace(site, site + " in fnA"), dup);
        check("... unless the site says which function: then the one in fnA", ri.recipe != null && ri.sites.get("s") == at);
        check("a site in a function the library doesn't export: not resolved", SiteFind.resolve(rel.replace(site, site + " in fnZ"), lib).recipe == null);
        check("a site at sym:fnA+0x100 (an exported symbol plus an offset): resolved there",
                SiteFind.resolve(rel.replace(site, site + " at sym:fnA+0x100"), moved).sites.get("s") == at + 0x64);
        check("... but only if its md5 is right there: sym:fnA+0x104 isn't",
                SiteFind.resolve(rel.replace(site, site + " at sym:fnA+0x104"), moved).recipe == null);
        check("in or at on every site: an app may resolve it itself (bounded)", SiteFind.bounded(rel.replace(site, site + " in fnA")) && !SiteFind.bounded(rel));

        byte[] build2 = lib.clone();
        build2[at + 5] ^= 0x01;   // a byte that differs between builds
        byte[] w = Arrays.copyOfRange(lib, at, at + 32);
        w[5] = 0;
        String ms = "site s 32 " + MemRecipe.md5(w) + " mask fbffffff";   // byte 5 a wildcard
        SiteFind.Resolved r2 = SiteFind.resolve(rel.replace(site, ms), build2);
        check("a wildcard byte: the site matches the build where it differs, with that build's own bytes in verify",
                r2.recipe != null && r2.recipe.contains(md5(build2, at, 32)));
        SiteFind.Resolved rw = SiteFind.resolve("id t\nfile /system/lib/libt.so\n" + ms + "\nverify s 32\npatch s+0x4 0000\n", build2);
        check("a patch writing a wildcard byte: refused", rw.recipe == null && rw.why.contains("wildcard"));
        check("a site comparing under 16 bytes: refused",
                SiteFind.resolve(rel.replace(site, "site s 32 " + MemRecipe.md5(new byte[32]) + " mask 0000ffff"), lib).recipe == null);

        String plain = "id t\nfile /system/lib/libt.so\n" + site + "\nverify s 32\npatch 0x2110 00bf\n";
        check("a plain offset without built-for: refused (it would be wrong on another build)", SiteFind.resolve(plain, lib).recipe == null);
        check("with built-for this library: resolved", SiteFind.resolve("built-for " + MemRecipe.md5(lib) + "\n" + plain, lib).recipe != null);
        check("with built-for another library: refused", SiteFind.resolve("built-for " + MemRecipe.md5(lib) + "\n" + plain, moved).recipe == null);

        // PC-relative instructions in a plain patch
        byte[] pc = elf(5, 0);
        int s0 = 0x2200;
        String ps = "site p 32 " + md5(pc, s0, 32);
        byte[] far = MemRecipe.branchW(s0 + 8, 0x3000), near = MemRecipe.branchW(s0 + 8, s0 + 0x18);
        SiteFind.Resolved rfar = SiteFind.resolve("id t\nfile /system/lib/libt.so\n" + ps + "\nverify p 32\npatch p+0x8 " + SiteFind.hexOf(far) + "\n", pc);
        check("a plain patch with a b.w out of its site: refused (on another build it would land elsewhere)",
                rfar.recipe == null && rfar.why.contains("depends on where it is"));
        check("a plain patch with a b.w inside its site: resolved (it moves with the site)",
                SiteFind.resolve("id t\nfile /system/lib/libt.so\n" + ps + "\nverify p 32\npatch p+0x8 " + SiteFind.hexOf(near) + "\n", pc).recipe != null);
        check("a plain patch with a literal load out of its site (ldr r0, [pc, #1020]): refused",
                SiteFind.resolve("id t\nfile /system/lib/libt.so\n" + ps + "\nverify p 32\npatch p+0x8 ff48\n", pc).recipe == null);
        check("a plain patch with cmp r0, #25: resolved",
                SiteFind.resolve("id t\nfile /system/lib/libt.so\n" + ps + "\nverify p 32\npatch p+0x8 1928\n", pc).recipe != null);

        // bw / bl / jump: bytes computed for each build
        String tgt = "site q 24 " + md5(pc, 0x2800, 24);
        String ops = "id t\nfile /system/lib/libt.so\n" + ps + "\n" + tgt + "\nverify p 32\nverify q 24\nbw p+0x8 q+0x4\nbl p+0x10 sym:fnB+0x20\n";
        for (int sh : new int[] { 0, 0x46 }) {
            byte[] l = elf(5, sh);
            SiteFind.Resolved ro = SiteFind.resolve(ops, l);
            boolean ok = ro.recipe != null && apply(ro.recipe, l, m).status == MemRecipe.Result.APPLIED
                    && SiteFind.decode(m[0].lib, s0 + sh + 8)[1] == 0x2804 + sh && SiteFind.decode(m[0].lib, s0 + sh + 0x10)[1] == 0x3020 + sh;
            check("bw to a site, bl to sym:fnB+0x20: the right targets" + (sh == 0 ? "" : ", code moved by 0x" + Integer.toHexString(sh)), ok);
        }
        check("bl to ARM code (fnC): refused", SiteFind.resolve(ops.replace("sym:fnB+0x20", "sym:fnC"), pc).recipe == null);

        byte[] jl = elf(3, 0);
        int jo = 0x2040, off = 0x120 - 4;   // beq.w (T3) at fnA+0x40, target 0x120 bytes on
        put16(jl, jo, 0xf000 | ((off >> 12) & 0x3f));
        put16(jl, jo + 2, 0x8000 | (off >> 1 & 0x7ff));
        String js = "site j 32 " + md5(jl, jo - 8, 32);
        String jr = "id t\nfile /system/lib/libt.so\n" + js + "\nverify j 32\njump j+0x8\n";
        for (int sh : new int[] { 0, 0x2a }) {
            byte[] lj = jl;
            if (sh != 0) {   // the same code, shifted (another build)
                lj = new byte[jl.length + sh];
                System.arraycopy(jl, 0, lj, 0, 0x1000);
                System.arraycopy(jl, 0x1000, lj, 0x1000 + sh, jl.length - 0x1000);
            }
            SiteFind.Resolved rj = SiteFind.resolve(jr, lj);
            boolean ok = rj.recipe != null && apply(rj.recipe, lj, m).status == MemRecipe.Result.APPLIED
                    && Arrays.equals(Arrays.copyOfRange(m[0].lib, jo + sh, jo + sh + 4), MemRecipe.branchW(jo + sh, jo + sh + 0x120));
            check("jump: the beq.w becomes a b.w to the same target" + (sh == 0 ? "" : ", code moved by 0x" + Integer.toHexString(sh)), ok);
        }
        byte[] nb = jl.clone();
        put16(nb, jo, 0xbf00);   // two nops where the beq.w was
        put16(nb, jo + 2, 0xbf00);
        SiteFind.Resolved rn = SiteFind.resolve(jr.replace(js, "site j 32 " + md5(nb, jo - 8, 32)), nb);
        check("jump on something that isn't a conditional branch: refused", rn.recipe == null && rn.why.contains("not a conditional"));

        check("a damaged symbol table: no symbols, nothing thrown", SiteFind.functions(Arrays.copyOf(lib, 0x40)).isEmpty());
        check("a recipe line SiteFind doesn't know: refused", SiteFind.resolve(rel + "poke s 00\n", lib).recipe == null);
        System.exit(fail);
    }
}
