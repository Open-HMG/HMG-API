package hmgapi.hook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import hmgapi.engine.MemRecipe;

/**
 * Native patches in memory (ModContext.nativePatch): a mod's recipes for a library are
 * put on it in this process once it's loaded (now, or right after the Runtime.load /
 * loadLibrary that loads it, its dependencies included), never on the file.
 *
 * Mixing: each recipe owns its verify regions in the library; a recipe overlapping
 * another mod's on the same library isn't applied, and the log names both. The first
 * of a mod's recipes that fits the library (its stock bytes) is the one used, so one
 * mod can carry a recipe per build of a library. HMG-API itself ships no recipe: native
 * patches stay in the mods. Who owns what: NativeOwners.
 */
final class NativePatch {
    private NativePatch() {}

    static final MemRecipe.Memory MEMORY = new MemRecipe.Memory() {
        @Override
        public byte[] peek(long addr, int len) {
            return Bridge.peekNative(addr, len);
        }

        @Override
        public boolean poke(long addr, byte[] data) {
            return Bridge.pokeNative(addr, data) == 0;
        }

        @Override
        public long mapCode(long hint, int len) {
            return Bridge.mapCodeNative(hint, len);
        }
    };

    static boolean hooked;

    static synchronized void request(final ModContext mod, String lib, List<MemRecipe> recipes) {
        hookLoads();
        NativeOwners.Log log = new NativeOwners.Log() {
            @Override
            public void info(String msg) {
                mod.log(msg);
            }

            @Override
            public void error(String msg, Throwable t) {
                mod.error(msg, t);
            }
        };
        NativeOwners.add(new NativeOwners.Want(mod.id, lib, recipes, log));
        tryAll();
    }

    /** whether one of a mod's recipes for lib is in place in this process */
    static boolean patched(String mod, String lib) {
        return NativeOwners.patched(mod, lib);
    }

    /** after every library load in this process, the patches still waiting are tried */
    private static void hookLoads() {
        if (hooked) return;
        hooked = true;
        MethodHook after = new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (p.getThrowable() == null) tryAll();
            }
        };
        if (!Points.needAll("native patches", "framework.runtime.load_library", "framework.runtime.load")) return;
        Points.hook(null, "framework.runtime.load_library", after);
        Points.hook(null, "framework.runtime.load", after);
    }

    static synchronized void tryAll() {
        Map<String, long[]> maps = null;
        for (NativeOwners.Want w : NativeOwners.waiting()) {
            if (maps == null) maps = maps();
            long[] m = null;
            String path = null;
            for (Map.Entry<String, long[]> e : maps.entrySet()) {
                if (e.getKey().endsWith("/" + w.lib)) {
                    path = e.getKey();
                    m = e.getValue();
                }
            }
            if (m == null) continue;
            NativeOwners.apply(w, path, m[0], m[1], ModContext.md5(path), MEMORY);
        }
    }

    /** loaded libraries: path -> {address of file offset 0 (its code mapping), the code mapping's length} */
    static Map<String, long[]> maps() {
        Map<String, long[]> r = new LinkedHashMap<String, long[]>();
        try {
            BufferedReader in = new BufferedReader(new FileReader("/proc/self/maps"));
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    // 4011c000-402e2000 r-xp 00000000 b3:02 1234   /system/lib/libX.so
                    String[] w = l.trim().split("\\s+");
                    if (w.length < 6 || !w[5].endsWith(".so") || w[1].indexOf('x') < 0 || Long.parseLong(w[2], 16) != 0) continue;
                    int dash = w[0].indexOf('-');
                    long start = Long.parseLong(w[0].substring(0, dash), 16), end = Long.parseLong(w[0].substring(dash + 1), 16);
                    if (!r.containsKey(w[5])) r.put(w[5], new long[] { start, end - start });
                }
            } finally {
                in.close();
            }
        } catch (Exception e) {
            HmgApi.i("native patch: can't read /proc/self/maps (" + e + ")");
        }
        return r;
    }

    /** a mod's recipe files (paths in its folder) */
    static List<MemRecipe> read(ModContext mod, List<String> files) throws Exception {
        List<MemRecipe> r = new ArrayList<MemRecipe>();
        for (String f : files) r.add(MemRecipe.parse(Mods.read(new File(mod.dir, f))));
        return r;
    }
}
