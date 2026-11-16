package hmgapi.hook;

import hmgapi.engine.MemRecipe;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HMG-API's own native recipes: core/recipes/<lib>/*.recipe are the ones in CoreRecipes,
 * byte for byte, and each parses. HMG-API's recipe and a mod's on the same bytes
 * (NativeOwners, as NativePatch runs it): HMG-API's goes on, the mod's is refused and
 * logged, nativePatched is false for the mod and rawReady true. Arguments after the
 * folder: <lib name> <library file> pairs (real libraries from a car tree): one of that
 * library's recipes applies to it, then finds it already applied, and the same with
 * HMG-API's real recipe and a mod's copy of it.
 */
public final class CoreRecipesTest {
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

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File[] libs = dir.listFiles();
        int n = 0;
        for (File lib : libs == null ? new File[0] : libs) {
            List<String> embedded = new ArrayList<String>(Arrays.asList(CoreRecipes.of(lib.getName())));
            File[] files = lib.listFiles();
            for (File f : files == null ? new File[0] : files) {
                if (!f.getName().endsWith(".recipe")) continue;
                n++;
                String text = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                check(lib.getName() + "/" + f.getName() + ": in CoreRecipes as it is in the file", embedded.remove(text));
                MemRecipe r = MemRecipe.parse(text);
                check(f.getName() + ": parses, its id is its name", (r.id + ".recipe").equals(f.getName()));
            }
            check(lib.getName() + ": no recipe in CoreRecipes without its file", embedded.isEmpty());
        }
        check("core/recipes has recipes", n > 0);
        synthetic();
        for (int i = 1; i + 1 < a.length; i += 2) real(a[i], a[i + 1]);
        System.exit(fail);
    }

    static void real(String lib, String path) throws Exception {
        byte[] file = Files.readAllBytes(new File(path).toPath());
        int ph = i32(file, 0x1c), textEnd = 0;
        for (int k = 0; textEnd == 0; k++) if (i32(file, ph + 32 * k) == 1) textEnd = i32(file, ph + 32 * k + 16);   // the first PT_LOAD
        long base = 0x48000000L;
        Mem m = new Mem(base, Arrays.copyOf(file, textEnd));
        String md5 = MemRecipe.md5(file);
        MemRecipe used = null;
        for (String text : CoreRecipes.of(lib)) {
            MemRecipe r = MemRecipe.parse(text);
            if (r.apply(m, base, textEnd, md5).status == MemRecipe.Result.APPLIED) {
                used = r;
                break;
            }
        }
        boolean bytes = used != null;
        for (int i = 0; used != null && i < used.patchAt.size(); i++) {
            int off = (int) used.patchAt.get(i)[0];
            bytes &= Arrays.equals(Arrays.copyOfRange(m.b, off, off + used.patchBytes.get(i).length), used.patchBytes.get(i));
        }
        check(lib + " " + md5 + ": " + (used == null ? "no recipe applies" : used.id + " applies, its bytes in place"), bytes);
        if (used == null) return;
        check(used.id + ": a second time, already patched", used.apply(m, base, textEnd, md5).status == MemRecipe.Result.ALREADY);
        // a mod carrying the same recipe (egmp-aa-ev's ev-sensor-<build>: same windows and bytes)
        List<MemRecipe> core = new ArrayList<MemRecipe>(), mod = new ArrayList<MemRecipe>();
        for (String text : CoreRecipes.of(lib)) {
            core.add(MemRecipe.parse(text));
            mod.add(MemRecipe.parse(text.replace("id aa-raw-", "id ev-sensor-")));
        }
        coexist(used.id + " and a mod's copy", new Mem(base, Arrays.copyOf(file, textEnd)), textEnd, md5, lib, core, mod);
    }

    /** what a mod's log or HMG-API's would get */
    static final class Captured implements NativeOwners.Log {
        final StringBuilder out = new StringBuilder();

        public void info(String msg) {
            out.append("I ").append(msg).append('\n');
        }

        public void error(String msg, Throwable t) {
            out.append("E ").append(msg).append('\n');
        }
    }

    /**
     * HMG-API's recipe, then a mod's on the same bytes asked for after it (what NativePatch
     * does in GPM: ProjectionHooks asks before the mods' appLoaded), on the library in m.
     * Prints both logs; returns the mod's.
     */
    static String coexist(String what, Mem m, int textEnd, String md5, String lib, List<MemRecipe> core, List<MemRecipe> mod) {
        NativeOwners.clear();
        Captured coreLog = new Captured(), modLog = new Captured();
        NativeOwners.Want c = new NativeOwners.Want(NativeOwners.CORE, lib, core, coreLog);
        NativeOwners.Want e = new NativeOwners.Want("egmp-aa-ev", lib, mod, modLog);
        NativeOwners.add(c);
        NativeOwners.add(e);
        for (NativeOwners.Want w : NativeOwners.waiting()) NativeOwners.apply(w, "/system/lib/" + lib, m.base, textEnd, md5, m);
        System.out.print("      HMG-API: " + coreLog.out + "      egmp-aa-ev: " + modLog.out);
        AaMessages raw = new AaMessages();
        raw.sender = new AaMessages.Sender() {
            public void send(Object source, byte[] message) {}
        };
        raw.start(new Object(), NativeOwners.patched(NativeOwners.CORE, lib));   // as ProjectionHooks: rawReady()
        check(what + ": HMG-API's applied, the mod's refused as a conflict",
                coreLog.out.toString().contains(": applied") && modLog.out.toString().startsWith("E native patch " + lib + " not applied")
                        && modLog.out.toString().contains("conflict with HMG-API's " + core.get(0).id.substring(0, 7)));
        check(what + ": nativePatched false for the mod, rawReady true",
                !NativeOwners.patched("egmp-aa-ev", lib) && NativeOwners.patched(NativeOwners.CORE, lib) && raw.ready());
        return modLog.out.toString();
    }

    static void synthetic() {
        byte[] lib = new byte[0x4000];
        for (int i = 0; i < lib.length; i++) lib[i] = (byte) (i * 13 + 5);
        byte[] after = lib.clone();
        after[0x210] = 0x19;
        String text = "verify 0x208 16 " + MemRecipe.md5(Arrays.copyOfRange(lib, 0x208, 0x218)) + " "
                + MemRecipe.md5(Arrays.copyOfRange(after, 0x208, 0x218)) + "\npatch 0x210 19\n";
        List<MemRecipe> core = Arrays.asList(MemRecipe.parse("id aa-raw-test0000\n" + text));
        List<MemRecipe> mod = Arrays.asList(MemRecipe.parse("id ev-sensor-test0000\n" + text));
        coexist("synthetic", new Mem(0x40000000L, lib.clone()), lib.length, "x", "libt.so", core, mod);
        NativeOwners.clear();
        Captured alone = new Captured();
        NativeOwners.Want w = new NativeOwners.Want("egmp-aa-ev", "libt.so", mod, alone);
        NativeOwners.add(w);
        NativeOwners.apply(w, "/system/lib/libt.so", 0x40000000L, lib.length, "x", new Mem(0x40000000L, lib.clone()));
        check("synthetic: without HMG-API's, the mod's own applies (nativePatched true)", NativeOwners.patched("egmp-aa-ev", "libt.so"));
        NativeOwners.clear();
    }

    static int i32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
