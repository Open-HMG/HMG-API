package hmgapi.hook;

import hmgapi.engine.MemRecipe;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HMG-API's own native recipes: core/recipes/<lib>/*.recipe are the ones in CoreRecipes,
 * byte for byte, and each parses. Arguments after the folder: <lib name> <library file>
 * pairs (real libraries from a car tree): one of that library's recipes applies to it,
 * then finds it already applied.
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
        if (used != null) check(used.id + ": a second time, already patched", used.apply(m, base, textEnd, md5).status == MemRecipe.Result.ALREADY);
    }

    static int i32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
