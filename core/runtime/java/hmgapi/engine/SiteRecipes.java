package hmgapi.engine;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mod's relocatable native recipes ("hooks": {"native_sites": {"<lib>.so": ["<file>", ...]}},
 * SiteFind's format), resolved for the library this unit has. Next to "native" (fixed
 * recipes, one build each): an app tries a mod's fixed recipes first, then these.
 *
 * Finding a site can take an md5 per byte of the library: tens of seconds on the unit for
 * a site searched in the whole library. So the engine resolves them (engine sites: at
 * every apply, install and state publish, and at boot) and keeps each result by the
 * recipe's and the library's md5, in /data/hmgapi/public/sites/<mod>/, where the apps
 * read it:
 *   <name>.<recipe md5, 8>.<library md5>.recipe   the plain recipe (MemRecipe) for that build
 *   <name>.<recipe md5, 8>.<library md5>.why      why it doesn't fit that build
 * An app with no result for its library's md5 (the library changed since) resolves the
 * recipe itself only if every site says where to look (in <symbol> or at <ref>), which
 * costs little; otherwise the mod's native patch waits for the engine (the next start).
 *
 * Only hashes and the mod's own bytes are in a result: it's the mod's recipe, with the
 * offsets filled in. HMG-API ships the mechanism; the recipes stay the mods'.
 */
public final class SiteRecipes {
    private SiteRecipes() {}

    static final String DIR = "public/sites";

    /** a recipe file's name in the cache: its path in the mod, '/' made '_' */
    static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static File base(File data, String mod, String name, String text, String libMd5) {
        return new File(new File(data, DIR + "/" + safe(mod)), safe(name) + "." + MemRecipe.md5(bytes(text)).substring(0, 8) + "." + libMd5);
    }

    static byte[] bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** the device path a recipe patches (its "file" line), or null */
    static String fileOf(String text) {
        for (String raw : text.split("\n")) {
            String[] w = SiteFind.words(raw);
            if (w != null && w[0].equals("file") && w.length > 1) return w[1];
        }
        return null;
    }

    /**
     * In an app: this mod's relocatable recipe as a plain one for the library at libPath
     * (md5 libMd5), or null and why (why[0]).
     */
    public static MemRecipe forLibrary(File data, String mod, String name, String text, String libPath, String libMd5, String[] why) {
        try {
            File b = base(data, mod, name, text, libMd5);
            File ok = new File(b.getPath() + ".recipe"), no = new File(b.getPath() + ".why");
            if (ok.isFile()) return MemRecipe.parse(Engine.read(ok));
            if (no.isFile()) {
                why[0] = name + ": " + Engine.read(no).trim();
                return null;
            }
            if (!SiteFind.bounded(text)) {
                why[0] = name + ": not resolved for this build of the library yet (md5 " + libMd5 + "); the engine does it at the next start";
                return null;
            }
            SiteFind.Resolved r = SiteFind.resolve(text, SiteFind.bytes(new File(libPath)));
            if (r.recipe == null) {
                why[0] = name + ": " + r.why;
                return null;
            }
            return MemRecipe.parse(r.recipe);
        } catch (Exception e) {
            why[0] = name + ": " + e;
            return null;
        }
    }

    /** engine sites: every enabled mod's relocatable recipes, resolved for this unit's libraries (kept results are reused) */
    @SuppressWarnings("unchecked")
    static int run(Engine e) {
        File cache = new File(e.data, DIR);
        Set<String> mods = new LinkedHashSet<String>();
        File[] ds = new File(e.data, "mods").listFiles();
        if (ds != null) {
            java.util.Arrays.sort(ds);
            for (File d : ds) {
                File mf = new File(d, "mod.json");
                if (!d.isDirectory() || new File(d, ".disabled").exists() || !mf.isFile()) continue;
                Map<String, Object> mod;
                try {
                    mod = Json.object(Engine.read(mf));
                } catch (Exception x) {
                    continue;   // the mod loader reports it
                }
                Object hooks = mod.get("hooks");
                Object nat = hooks instanceof Map ? ((Map<String, Object>) hooks).get("native_sites") : null;
                if (!(nat instanceof Map)) continue;
                mods.add(safe(d.getName()));
                for (Map.Entry<String, Object> lib : ((Map<String, Object>) nat).entrySet()) {
                    if (!(lib.getValue() instanceof List)) continue;
                    for (Object f : (List<Object>) lib.getValue()) resolveOne(e, d, String.valueOf(f));
                }
            }
        }
        // results of mods that are gone or off
        File[] kept = cache.listFiles();
        if (kept != null) for (File k : kept) if (!mods.contains(k.getName())) Engine.rm(k);
        if (cache.isDirectory()) readable(cache);
        return 0;
    }

    static void resolveOne(Engine e, File modDir, String name) {
        String mod = modDir.getName();
        try {
            File rf = new File(modDir, name);
            if (new File(name).isAbsolute() || !rf.getCanonicalPath().startsWith(modDir.getCanonicalPath() + File.separator) || !rf.isFile()) {
                e.out.add("sites  " + mod + ": " + name + " isn't a file in the mod");
                return;
            }
            String text = Engine.read(rf), path = fileOf(text);
            if (path == null || !path.startsWith("/system/")) {
                e.out.add("sites  " + mod + ": " + name + " has no file /system/... line");
                return;
            }
            File lib = new File(e.root + path);
            if (!lib.isFile()) {
                e.out.add("sites  " + mod + ": " + name + ": no " + path + " on this unit");
                return;
            }
            String libMd5 = CarMap.md5(lib);
            File b = base(e.data, mod, name, text, libMd5);
            File ok = new File(b.getPath() + ".recipe"), no = new File(b.getPath() + ".why");
            File[] old = b.getParentFile().listFiles();
            if (old != null) {   // this recipe's results for other builds of the library (or older versions of it)
                for (File o : old) {
                    if (o.getName().startsWith(safe(name) + ".") && !o.getName().startsWith(b.getName() + ".")) o.delete();
                }
            }
            if (ok.isFile() || no.isFile()) {
                e.out.add("sites  " + mod + ": " + name + " on " + path + " (" + libMd5.substring(0, 8) + "): " + (ok.isFile() ? "resolved" : "doesn't fit") + " (kept)");
                return;
            }
            b.getParentFile().mkdirs();
            SiteFind.Resolved r = SiteFind.resolve(text, SiteFind.bytes(lib));
            Engine.write(r.recipe != null ? ok : no, r.recipe != null ? r.recipe : r.why + "\n");
            e.out.add("sites  " + mod + ": " + name + " on " + path + " (" + libMd5.substring(0, 8) + "): "
                    + (r.recipe != null ? "resolved (" + where(r.sites) + ")" : "doesn't fit: " + r.why));
        } catch (Exception x) {
            e.out.add("sites  " + mod + ": " + name + ": " + x);
        }
    }

    static String where(Map<String, Integer> sites) {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Integer> e : sites.entrySet()) s.append(s.length() == 0 ? "" : ", ").append(e.getKey()).append(" at 0x").append(Integer.toHexString(e.getValue()));
        return s.toString();
    }

    /** the apps read the results: the folders traversable and listable, the files readable */
    static void readable(File d) {
        d.setReadable(true, false);
        d.setExecutable(true, false);
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) readable(f);
            else f.setReadable(true, false);
        }
    }
}
