package hmgapi.engine;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mod's native recipes (mod.json hooks.native) beside other mods' (the ones it requires),
 * without the libraries: each recipe parses (MemRecipe, as on the car), and none of its
 * verify regions overlaps a region of another mod's recipe for the same library build.
 * On the car, an overlapping recipe isn't applied (hmgapi.hook.NativePatch: "conflict
 * with ..."), so one of the two mods loses its patch.
 *
 * A library's recipes in mod.json are alternatives (one per library build; the first that
 * fits is applied), so only recipes for the same build are compared. The build is the
 * stock md5 the recipe was made from, when the recipe says it ("from stock <md5>", as
 * mkrecipe.py and mkmemrecipe.py write it), else the 8 hex digits its id ends with
 * (aa-tbt-48ee793a); a recipe with neither is compared with every recipe of that library.
 *
 *   java -cp hmgapi-engine.jar:hmgapi-engine-test.jar hmgapi.engine.RecipeCheck <mod dir> [<other mod dir>...]
 * prints "ok"/"FAIL" lines (and "info" for pairs of different builds); exit 1 on a FAIL.
 */
public final class RecipeCheck {
    static final Pattern STOCK = Pattern.compile("from stock ([0-9a-f]{32})");
    static final Pattern ID_BUILD = Pattern.compile("-([0-9a-f]{8})$");

    static final class R {
        String mod, path, build;
        MemRecipe recipe;
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 1) {
            System.err.println("usage: RecipeCheck <mod dir> [<other mod dir>...]");
            System.exit(2);
        }
        int fail = 0;
        List<List<Map.Entry<String, List<R>>>> mods = new ArrayList<List<Map.Entry<String, List<R>>>>();
        String self = null;
        for (String dir : a) {
            List<Map.Entry<String, List<R>>> libs = new ArrayList<Map.Entry<String, List<R>>>();
            Map<String, Object> mj = Json.object(new String(Files.readAllBytes(new File(dir, "mod.json").toPath()), "UTF-8"));
            String id = String.valueOf(mj.get("id"));
            if (self == null) self = id;
            Object hooks = mj.get("hooks");
            Object nat = hooks instanceof Map ? ((Map<?, ?>) hooks).get("native") : null;
            if (nat != null && !(nat instanceof Map)) {
                System.out.println("FAIL  " + id + ": hooks.native must be an object {\"<library>\": [\"<recipe>\", ...]}");
                fail = 1;
                nat = null;
            }
            if (nat != null) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) nat).entrySet()) {
                    List<R> rs = new ArrayList<R>();
                    Object v = e.getValue();
                    List<?> paths = v instanceof List ? (List<?>) v : java.util.Collections.singletonList(v);
                    for (Object p : paths) {
                        R r = new R();
                        r.mod = id;
                        r.path = String.valueOf(p);
                        File f = new File(dir, r.path);
                        try {
                            String text = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                            r.recipe = MemRecipe.parse(text);
                            Matcher m = STOCK.matcher(text);
                            Matcher i = ID_BUILD.matcher(r.recipe.id);
                            r.build = m.find() ? m.group(1) : i.find() ? i.group(1) : null;
                            if (mods.isEmpty()) {
                                System.out.println("ok    " + id + " " + r.path + " parses (recipe " + r.recipe.id + ", "
                                        + r.recipe.verifies.size() + " verify, " + r.recipe.patchAt.size() + " patch)");
                            }
                            rs.add(r);
                        } catch (Exception x) {
                            String why = x instanceof java.nio.file.NoSuchFileException ? "no such file in the mod" : x.getMessage();
                            if (mods.isEmpty()) {
                                System.out.println("FAIL  " + id + " " + r.path + ": " + why);
                                fail = 1;
                            } else {   // another mod's: not this mod's to fix, and not compared
                                System.out.println("info  " + id + " " + r.path + ": " + why);
                            }
                        }
                    }
                    libs.add(new java.util.AbstractMap.SimpleEntry<String, List<R>>(String.valueOf(e.getKey()), rs));
                }
            }
            mods.add(libs);
        }
        for (int k = 1; k < mods.size(); k++) {
            for (Map.Entry<String, List<R>> ours : mods.get(0)) {
                for (Map.Entry<String, List<R>> theirs : mods.get(k)) {
                    if (!ours.getKey().equals(theirs.getKey())) continue;
                    for (R x : ours.getValue()) {
                        for (R y : theirs.getValue()) {
                            String what = x.mod + " " + x.recipe.id + " and " + y.mod + " " + y.recipe.id + " (" + ours.getKey() + ")";
                            if (x.build != null && y.build != null && !sameBuild(x.build, y.build)) {
                                System.out.println("info  " + what + ": other library builds");
                                continue;
                            }
                            long[] at = overlap(x.recipe, y.recipe);
                            if (at == null) {
                                System.out.println("ok    " + what + ": no region overlaps");
                            } else {
                                System.out.println("FAIL  " + what + ": overlap at 0x" + Long.toHexString(at[0]) + "-0x" + Long.toHexString(at[1])
                                        + "; on the car only one of them is applied");
                                fail = 1;
                            }
                        }
                    }
                }
            }
        }
        System.exit(fail);
    }

    /** a stock md5 and an id's 8 digits: the same build if one starts with the other */
    static boolean sameBuild(String a, String b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    /** the first overlap of two recipes' regions, [from, to), or null */
    static long[] overlap(MemRecipe a, MemRecipe b) {
        for (long[] x : a.regions()) {
            for (long[] y : b.regions()) {
                if (x[0] < y[1] && y[0] < x[1]) return new long[] { Math.max(x[0], y[0]), Math.min(x[1], y[1]) };
            }
        }
        return null;
    }
}
