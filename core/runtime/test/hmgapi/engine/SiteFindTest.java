package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * SiteFind (relocatable memory recipes) on synthetic libraries. Arguments: (relocatable
 * recipe, stock library, shipped recipe) triples: the relocatable form must resolve on the
 * stock library to exactly the shipped recipe's lines.
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
     * exporting fnA (0x2000, 0x400 bytes) and fnB (0x3000, 0x100 bytes), both Thumb.
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
        byte[] names = "\0fnA\0fnB\0".getBytes();
        System.arraycopy(names, 0, b, str, names.length);
        int[][] fns = { { 1, 0x2000 + shift, 0x400 }, { 5, 0x3000 + shift, 0x100 } };
        for (int k = 0; k < fns.length; k++) {
            int e = sym + 16 * (k + 1);
            put32(b, e, fns[k][0]);
            put32(b, e + 4, fns[k][1] | 1);
            put32(b, e + 8, fns[k][2]);
            b[e + 12] = 0x12;                 // GLOBAL FUNC
            put16(b, e + 14, 1);
        }
        put32(b, sh + 40 + 4, 11);            // [1] SHT_DYNSYM
        put32(b, sh + 40 + 16, sym);
        put32(b, sh + 40 + 20, 48);
        put32(b, sh + 40 + 24, 2);
        put32(b, sh + 80 + 4, 3);             // [2] SHT_STRTAB
        put32(b, sh + 80 + 16, str);
        return b;
    }

    static String md5(byte[] b, int off, int len) {
        return MemRecipe.md5(Arrays.copyOfRange(b, off, off + len));
    }

    /** a process with the library at base, as MemRecipeTest's, without segments */
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

    static List<String> body(String recipe) {
        List<String> out = new ArrayList<String>();
        for (String l : recipe.split("\n")) {
            int h = l.indexOf('#');
            String x = (h >= 0 ? l.substring(0, h) : l).trim().replaceAll("\\s+", " ");
            if (x.matches("(verify|patch|branch|rel32|segment|native|id|file) .*")) out.add(x.replaceAll("^(segment) 0x[0-9a-f]+", "$1 *"));
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0) {
            for (int i = 0; i + 2 < a.length; i += 3) {
                byte[] lib = SiteFind.bytes(new File(a[i + 1]));
                SiteFind.Resolved r = SiteFind.resolve(Engine.read(new File(a[i])), lib);
                String name = new File(a[i + 2]).getName();
                if (r.recipe == null) {
                    check(name + ": relocatable form resolves on its stock library (" + r.why + ")", false);
                    continue;
                }
                List<String> want = body(Engine.read(new File(a[i + 2]))), got = body(r.recipe);
                if (!want.equals(got)) {
                    List<String> only = new ArrayList<String>(got);
                    only.removeAll(want);
                    System.out.println("      resolved, not shipped: " + only);
                }
                check(name + ": relocatable form resolves on its stock library to exactly the shipped recipe", want.equals(got));
            }
            System.exit(fail);
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
        Mem mem = new Mem(0x40000000L, moved);
        MemRecipe.Result res = MemRecipe.parse(rm.recipe).apply(mem, mem.base, moved.length, null);
        check("and the resolved recipe applies there", res.status == MemRecipe.Result.APPLIED
                && mem.lib[at + 0x64 + 0x10] == 0 && mem.lib[at + 0x64 + 0x11] == (byte) 0xbf);

        byte[] other = moved.clone();
        other[at + 0x64 + 3] ^= 0x55;
        Mem mem2 = new Mem(0x40000000L, other);
        MemRecipe.Result res2 = MemRecipe.parse(rm.recipe).apply(mem2, mem2.base, other.length, null);
        check("resolved on one library, the bytes in memory differ: nothing written (MemRecipe's verify)",
                res2.status == MemRecipe.Result.MISMATCH && Arrays.equals(mem2.lib, other));
        check("... and it doesn't resolve on that library at all", SiteFind.resolve(rel, other).recipe == null);

        byte[] dup = lib.clone();
        System.arraycopy(lib, at, dup, 0x4800, 32);   // the window twice: outside any function, and in fnA
        SiteFind.Resolved rd = SiteFind.resolve(rel, dup);
        check("the window twice in the library: not resolved (not unique)", rd.recipe == null && rd.why.contains("not unique"));
        SiteFind.Resolved ri = SiteFind.resolve(rel.replace(site, site + " in fnA"), dup);
        check("... unless the site says which function: then the one in fnA", ri.recipe != null && ri.sites.get("s") == at);
        check("a site in a function the library doesn't export: not resolved",
                SiteFind.resolve(rel.replace(site, site + " in fnZ"), lib).recipe == null);

        byte[] build2 = lib.clone();
        build2[at + 5] ^= 0x01;   // a byte that differs between builds (a branch offset, say)
        byte[] mask = { (byte) 0xfb, (byte) 0xff, (byte) 0xff, (byte) 0xff };   // byte 5 a wildcard
        byte[] w = Arrays.copyOfRange(lib, at, at + 32);
        w[5] = 0;
        String ms = "site s 32 " + MemRecipe.md5(w) + " mask fbffffff";
        check("a wildcard byte: the site matches the build where it differs", SiteFind.resolve(rel.replace(site, ms), build2).recipe != null);
        SiteFind.Resolved r2 = SiteFind.resolve(rel.replace(site, ms), build2);
        check("... and that build's verify region is its own bytes", r2.recipe != null && r2.recipe.contains(md5(build2, at, 32)));
        String wp = "id t\nfile /system/lib/libt.so\n" + ms + "\nverify s 32\npatch s+0x4 0000\n";   // bytes 4-5: 5 is the wildcard
        SiteFind.Resolved rw = SiteFind.resolve(wp, build2);
        check("a patch writing a wildcard byte: refused", rw.recipe == null && rw.why.contains("wildcard"));
        check("a site comparing under 16 bytes is refused",
                SiteFind.resolve(rel.replace(site, "site s 32 " + MemRecipe.md5(new byte[32]) + " mask 0000ffff"), lib).recipe == null);

        String plain = "id t\nfile /system/lib/libt.so\n" + site + "\nverify s 32\npatch 0x2110 00bf\n";
        check("a plain offset without built-for: refused (it would be wrong on another build)", SiteFind.resolve(plain, lib).recipe == null);
        check("with built-for this library: resolved", SiteFind.resolve("built-for " + MemRecipe.md5(lib) + "\n" + plain, lib).recipe != null);
        check("with built-for another library: refused", SiteFind.resolve("built-for " + MemRecipe.md5(lib) + "\n" + plain, moved).recipe == null);

        String seg = "id t\nfile /system/lib/libt.so\n" + site + "\nverify s 32\nsegment s 0000000000000000\nrel32 0x0 0x0 sym:fnB+0x10\nbranch s+0x8 0x4\n";
        SiteFind.Resolved rs = SiteFind.resolve(seg, moved);
        check("rel32 to sym:fnB+0x10: fnB's offset in this build, Thumb bit kept", rs.recipe != null && rs.recipe.contains("rel32 0x0 0x0 0x3075"));
        check("a verify region with a branch in it has no fixed after", rs.recipe != null && rs.recipe.contains(" 32 " + md5(moved, at + 0x64, 32) + " -"));
        // jump: beq.w (T3) at fnA+0x40, target 0x120 bytes on, made b.w (T4) to the same target, wherever the code is
        byte[] jl = elf(3, 0);
        int jo = 0x2040;
        int off = 0x120 - 4;   // T3 imm: target - (pc + 4)
        int hw1 = 0xf000 | (0 << 6) | ((off >> 12) & 0x3f), hw2 = 0x8000 | (off >> 1 & 0x7ff);   // cond 0 (eq), S J1 J2 0
        put16(jl, jo, hw1);
        put16(jl, jo + 2, hw2);
        String js = "site j 32 " + md5(jl, jo - 8, 32);
        String jr = "id t\nfile /system/lib/libt.so\n" + js + "\nverify j 32\njump j+0x8\n";
        for (int sh : new int[] { 0, 0x2a }) {
            byte[] lj = sh == 0 ? jl : elf(3, 0);
            if (sh != 0) {   // the same code, shifted (another build)
                lj = new byte[jl.length + sh];
                System.arraycopy(jl, 0, lj, 0, 0x1000);
                System.arraycopy(jl, 0x1000, lj, 0x1000 + sh, jl.length - 0x1000);
            }
            SiteFind.Resolved rj = SiteFind.resolve(jr, lj);
            boolean ok = rj.recipe != null;
            if (ok) {
                Mem mj = new Mem(0x40000000L, lj);
                ok = MemRecipe.parse(rj.recipe).apply(mj, mj.base, lj.length, null).status == MemRecipe.Result.APPLIED
                        && Arrays.equals(Arrays.copyOfRange(mj.lib, jo + sh, jo + sh + 4), MemRecipe.branchW(jo + sh, jo + sh + 0x120));
            }
            check("jump: the beq.w becomes a b.w to the same target" + (sh == 0 ? "" : ", code moved by 0x" + Integer.toHexString(sh)), ok);
        }
        byte[] nb = jl.clone();
        put16(nb, jo, 0xbf00);   // two nops where the beq.w was
        put16(nb, jo + 2, 0xbf00);
        String ns = "site j 32 " + md5(nb, jo - 8, 32);
        SiteFind.Resolved rn = SiteFind.resolve(jr.replace(js, ns), nb);
        check("jump on something that isn't a conditional branch: not resolved", rn.recipe == null && rn.why.contains("not a conditional"));
        System.exit(fail);
    }
}
