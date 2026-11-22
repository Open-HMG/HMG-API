package hmgapi.hook;

import hmgapi.engine.MemRecipe;

import java.util.Arrays;
import java.util.List;

/**
 * Who owns which native recipe in a process (NativeOwners, as NativePatch runs it), and
 * what ModContext.nativePatched answers: a mod's recipe applied; a second mod's on the
 * same bytes refused, with both named in its log; a recipe for another build skipped
 * for the one that fits; nothing for a library that doesn't match.
 */
public final class NativeOwnersTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    /** the library in a byte array, as MemRecipe sees a process */
    static final class Mem implements MemRecipe.Memory {
        final long base;
        final byte[] b;

        Mem(long base, byte[] b) {
            this.base = base;
            this.b = b;
        }

        public byte[] peek(long addr, int len) {
            return Arrays.copyOfRange(b, (int) (addr - base), (int) (addr - base) + len);
        }

        public boolean poke(long addr, byte[] d) {
            System.arraycopy(d, 0, b, (int) (addr - base), d.length);
            return true;
        }

        public long mapCode(long hint, int len) {
            return 0;
        }
    }

    /** what a mod's log would get */
    static final class Captured implements NativeOwners.Log {
        final StringBuilder out = new StringBuilder();

        public void info(String msg) {
            out.append("I ").append(msg).append('\n');
        }

        public void error(String msg, Throwable t) {
            out.append("E ").append(msg).append('\n');
        }
    }

    static final String LIB = "libautoreceiver_infobank.so", PATH = "/system/lib/" + LIB;
    static final long BASE = 0x40000000L;

    static byte[] lib(int seed) {
        byte[] b = new byte[0x4000];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i * seed + 5);
        return b;
    }

    /** a recipe writing 19 at 0x210 of lib, its window 0x208-0x218 */
    static MemRecipe recipe(String id, byte[] lib) {
        byte[] after = lib.clone();
        after[0x210] = 0x19;
        return MemRecipe.parse("id " + id + "\nverify 0x208 16 " + MemRecipe.md5(Arrays.copyOfRange(lib, 0x208, 0x218)) + " "
                + MemRecipe.md5(Arrays.copyOfRange(after, 0x208, 0x218)) + "\npatch 0x210 19\n");
    }

    /** the wants, in the order asked for, put on one library as NativePatch.tryAll does */
    static void run(Mem m, NativeOwners.Want... wants) {
        NativeOwners.clear();
        for (NativeOwners.Want w : wants) NativeOwners.add(w);
        for (NativeOwners.Want w : NativeOwners.waiting()) NativeOwners.apply(w, PATH, m.base, m.b.length, "x", m);
    }

    public static void main(String[] a) {
        byte[] lib = lib(13), other = lib(7);

        Captured log = new Captured();
        run(new Mem(BASE, lib.clone()), new NativeOwners.Want("egmp-aa-ev", LIB, Arrays.asList(recipe("ev-sensor-other0000", other), recipe("ev-sensor-test0000", lib)), log));
        check("a mod's recipe for another build is passed over for the one that fits", log.out.toString().startsWith("I native patch " + LIB + ": ev-sensor-test0000: applied"));
        check("nativePatched: true for that mod", NativeOwners.patched("egmp-aa-ev", LIB) && !NativeOwners.patched("egmp-aa-ev", "libother.so"));

        Captured first = new Captured(), second = new Captured();
        run(new Mem(BASE, lib.clone()), new NativeOwners.Want("egmp-aa-ev", LIB, Arrays.asList(recipe("ev-sensor-test0000", lib)), first),
                new NativeOwners.Want("other-ev", LIB, Arrays.asList(recipe("ev-other-test0000", lib)), second));
        System.out.print("      egmp-aa-ev: " + first.out + "      other-ev: " + second.out);
        check("two mods' recipes on the same bytes: the first applied", first.out.toString().contains(": applied") && NativeOwners.patched("egmp-aa-ev", LIB));
        check("... the second refused, and its log names the first",
                second.out.toString().equals("E native patch " + LIB + " not applied; ev-other-test0000: conflict with egmp-aa-ev's ev-sensor-test0000\n"));
        check("... nativePatched false for the second (its sendRaw stays off)", !NativeOwners.patched("other-ev", LIB));

        Captured none = new Captured();
        List<MemRecipe> wrong = Arrays.asList(recipe("ev-sensor-other0000", other));
        run(new Mem(BASE, lib.clone()), new NativeOwners.Want("egmp-aa-ev", LIB, wrong, none));
        check("a library no recipe of the mod fits: not applied, nativePatched false",
                none.out.toString().startsWith("E native patch " + LIB + " not applied; ev-sensor-other0000: not this library's stock bytes")
                        && !NativeOwners.patched("egmp-aa-ev", LIB));
        NativeOwners.clear();
        System.exit(fail);
    }
}
