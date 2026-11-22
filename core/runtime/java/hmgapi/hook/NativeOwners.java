package hmgapi.hook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import hmgapi.engine.MemRecipe;

/**
 * Which native recipes are on which library in this process, and whose they are
 * (NativePatch puts them on; no Android here, so NativeOwnersTest runs it off the car).
 * Each recipe owns its verify regions; one overlapping a recipe already on the library
 * isn't applied, and the log names both. Of one owner's recipes for a library, the
 * first that fits (its stock bytes) is used.
 */
final class NativeOwners {
    private NativeOwners() {}

    interface Log {
        void info(String msg);

        /** t null: not applied, without an exception */
        void error(String msg, Throwable t);
    }

    static final class Want {
        final String owner, lib;
        final List<MemRecipe> recipes;
        final Log log;
        boolean done;
        /** one of its recipes is in place (applied, already there, or the library has it) */
        boolean applied;

        Want(String owner, String lib, List<MemRecipe> recipes, Log log) {
            this.owner = owner;
            this.lib = lib;
            this.recipes = recipes;
            this.log = log;
        }
    }

    static final class Owned {
        final String owner, recipe;
        final List<long[]> regions;

        Owned(String owner, String recipe, List<long[]> regions) {
            this.owner = owner;
            this.recipe = recipe;
            this.regions = regions;
        }
    }

    private static final List<Want> wants = new ArrayList<Want>();
    /** library path -> recipes applied to it in this process */
    private static final Map<String, List<Owned>> owned = new HashMap<String, List<Owned>>();

    static synchronized void add(Want w) {
        wants.add(w);
    }

    static synchronized boolean asked(String owner, String lib) {
        for (Want w : wants) if (w.owner.equals(owner) && w.lib.equals(lib)) return true;
        return false;
    }

    /** the wants not tried yet, in the order they were asked for */
    static synchronized List<Want> waiting() {
        List<Want> r = new ArrayList<Want>();
        for (Want w : wants) if (!w.done) r.add(w);
        return r;
    }

    static synchronized boolean patched(String owner, String lib) {
        for (Want w : wants) if (w.applied && w.owner.equals(owner) && w.lib.equals(lib)) return true;
        return false;
    }

    /** puts the first of w's recipes that fits on the library at path (mapped at base, code to textEnd) */
    static synchronized void apply(Want w, String path, long base, long textEnd, String md5, MemRecipe.Memory memory) {
        if (w.done) return;
        w.done = true;
        List<Owned> here = owned.get(path);
        if (here == null) owned.put(path, here = new ArrayList<Owned>());
        StringBuilder tried = new StringBuilder();
        for (MemRecipe r : w.recipes) {
            Owned clash = overlap(here, r.regions());
            if (clash != null) {
                tried.append("; ").append(r.id).append(": conflict with ").append(clash.owner).append("'s ").append(clash.recipe);
                continue;
            }
            MemRecipe.Result res;
            try {
                res = r.apply(memory, base, textEnd, md5);
            } catch (Throwable t) {
                w.log.error("native patch " + r.id + " on " + w.lib, t);
                return;
            }
            if (res.ok()) {
                here.add(new Owned(w.owner, r.id, r.regions()));
                w.applied = true;
                w.log.info("native patch " + w.lib + ": " + res.message);
                return;
            }
            tried.append("; ").append(res.message);
            if (res.status == MemRecipe.Result.FAILED) break;   // something was written: no other recipe on top
        }
        w.log.error("native patch " + w.lib + " not applied" + tried, null);
    }

    static Owned overlap(List<Owned> here, List<long[]> regions) {
        for (Owned o : here) {
            for (long[] a : o.regions) {
                for (long[] b : regions) {
                    if (a[0] < b[1] && b[0] < a[1]) return o;
                }
            }
        }
        return null;
    }

    /** forgets everything (tests) */
    static synchronized void clear() {
        wants.clear();
        owned.clear();
    }
}
