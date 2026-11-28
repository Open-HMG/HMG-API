package hmgapi.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The mod loader's decisions (hook.Mods), off the car: which mods load at boot
 * (HookPlan.enabled on a mods folder, then HookPlan.of, the state the HMG-API app
 * shows) and whether a "live" mod loads in a running app (HookPlan.late). Only the
 * Dalvik part of the loader (class loaders, zygoteInit) isn't run here.
 * Run by core/runtime/hooks-test.sh with the folder to use.
 */
public final class HookPlanTest {
    static int fail;
    static File mods;
    static final Set<String> KNOWN = new HashSet<String>(Arrays.asList("launcher.menu_list.load", "framework.context_impl.start_service"));

    static void check(String what, boolean ok, Object got) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": got " + got));
        if (!ok) fail = 1;
    }

    static void mod(String id, String extra) throws Exception {
        File d = new File(mods, id);
        d.mkdirs();
        FileOutputStream o = new FileOutputStream(new File(d, "mod.json"));
        o.write(("{\"id\": \"" + id + "\", \"version\": \"1.0.0\", \"hooks\": {\"entry\": \"x.X\", \"points\": "
                + "{\"launcher.menu_list.load\": \"observe\"}}" + extra + "}").getBytes("UTF-8"));
        o.close();
    }

    static void off(String id, boolean off) throws Exception {
        File f = new File(mods, id + "/.disabled");
        if (off) new FileOutputStream(f).close();
        else f.delete();
    }

    /** boot: what Mods.load decides */
    static HookPlan boot() {
        return HookPlan.of(HookPlan.enabled(mods), KNOWN, null);
    }

    static String why(HookPlan p, String id) {
        return p.refused.get(id);
    }

    static boolean has(String s, String part) {
        return s != null && s.contains(part);
    }

    static Map<String, Object> json(String id) throws Exception {
        return Json.object(HookPlan.read(new File(mods, id + "/mod.json")));
    }

    public static void main(String[] a) throws Exception {
        mods = new File(a[0]);
        // two hook-only mods (no patches.json) that list each other, as egmp-aa-ev's two builds do
        mod("full", ", \"requires\": [\"wire\"], \"conflicts\": [\"lite\"]");
        mod("lite", ", \"conflicts\": [\"full\"]");
        mod("wire", "");
        HookPlan p = boot();
        check("boot: both on, the first in load order loads", why(p, "full") == null && p.points.get("launcher.menu_list.load").containsKey("full"), p.refused);
        check("... the other doesn't", has(why(p, "lite"), "conflict: lite 1.0.0 and full 1.0.0 can't run together (each lists the other in \"conflicts\"); full loads, turn it off to use lite instead"), why(p, "lite"));
        check("... its hooks aren't planned", !p.points.get("launcher.menu_list.load").containsKey("lite"), p.points);
        check("... the HMG-API app shows it as a conflict", "conflict".equals(HookPlan.state(why(p, "lite"))), HookPlan.state(why(p, "lite")));
        off("full", true);
        p = boot();
        check("the first one off: the other loads", why(p, "lite") == null, p.refused);
        off("full", false);
        off("wire", true);
        p = boot();
        check("the first one's requirement off: it doesn't load", has(why(p, "full"), "requires wire, which isn't installed and turned on"), why(p, "full"));
        check("... so it holds nothing back: the other loads", why(p, "lite") == null, p.refused);
        check("... shown as an error", "error".equals(HookPlan.state(why(p, "full"))), HookPlan.state(why(p, "full")));
        off("wire", false);
        mod("wire", ", \"hooks\": {\"entry\": \"x.X\", \"points\": {\"not.a.point\": \"observe\"}}");
        p = boot();
        check("the first one's requirement refused: it isn't loaded", has(why(p, "full"), "requires wire, which isn't loaded (hook point not.a.point"), why(p, "full"));
        check("... and the other loads", why(p, "lite") == null, p.refused);
        mod("wire", "");
        p = boot();
        check("all back: the first loads again", why(p, "full") == null && why(p, "lite") != null, p.refused);

        // one-sided: the first in load order loads, whichever one lists the other
        mod("one", ", \"conflicts\": [\"two\"]");
        mod("two", "");
        p = boot();
        check("only the first lists the second: the second doesn't load", has(why(p, "two"), "conflict: two 1.0.0 and one 1.0.0 can't run together (one's \"conflicts\" lists two); one loads"), why(p, "two"));
        mod("one", "");
        mod("two", ", \"conflicts\": [\"one\"]");
        p = boot();
        check("only the second lists the first: the same", has(why(p, "two"), "(two's \"conflicts\" lists one); one loads"), why(p, "two"));
        mod("two", ", \"conflicts\": [\"one <1.0\"]");
        p = boot();
        check("a version range the other isn't in: no conflict", why(p, "two") == null, why(p, "two"));
        mod("two", ", \"conflicts\": [{\"id\": \"one\", \"version\": \">=1.0\"}]");
        p = boot();
        check("the object form, in range: a conflict", has(why(p, "two"), "conflict: two"), why(p, "two"));
        mod("two", ", \"requires\": [\"one >=2\"]");
        p = boot();
        check("requires out of range: not loaded", has(why(p, "two"), "requires one >=2, but one 1.0.0 is installed"), why(p, "two"));
        new File(mods, "one/mod.json").delete();
        new File(mods, "one").delete();
        new File(mods, "two/mod.json").delete();
        new File(mods, "two").delete();

        // a mod with no hooks counts too: the loader doesn't load it (tiles, star keys) when refused
        File d = new File(mods, "tile");
        d.mkdirs();
        FileOutputStream o = new FileOutputStream(new File(d, "mod.json"));
        o.write("{\"id\": \"tile\", \"version\": \"1.0.0\", \"conflicts\": [\"full\"]}".getBytes("UTF-8"));
        o.close();
        p = boot();
        check("a mod without hooks after one it conflicts with: not loaded", has(why(p, "tile"), "conflict: tile 1.0.0 and full 1.0.0"), why(p, "tile"));
        new File(d, "mod.json").delete();
        d.delete();

        // live: turned on while an app runs (Mods.lateLoad)
        Map<String, String> on = new LinkedHashMap<String, String>();
        on.put("full", "1.0.0");
        on.put("lite", "1.0.0");
        on.put("wire", "1.0.0");
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<String, Map<String, Object>>();
        loaded.put("full", json("full"));
        loaded.put("wire", json("wire"));
        p = HookPlan.late(loaded, "lite", json("lite"), on, KNOWN, null);
        check("live: the other one turned on while the first runs: not loaded", has(why(p, "lite"), "conflict: lite"), why(p, "lite"));
        loaded.remove("full");
        loaded.put("lite", json("lite"));
        p = HookPlan.late(loaded, "full", json("full"), on, KNOWN, null);
        check("live: the first in load order turned on while the other is loaded: not loaded either", has(why(p, "full"), "full loads, turn it off to use lite instead (lite is loaded in this app already: the next restart settles it)"), why(p, "full"));
        check("... nothing of it planned", !p.points.get("launcher.menu_list.load").containsKey("full"), p.points);
        loaded.remove("lite");
        p = HookPlan.late(loaded, "full", json("full"), on, KNOWN, null);
        check("live: with no conflict, it loads", why(p, "full") == null && p.points.get("launcher.menu_list.load").containsKey("full"), p.refused);
        loaded.remove("wire");
        p = HookPlan.late(loaded, "full", json("full"), on, KNOWN, null);
        check("live: a requirement that's on but not loaded in this app counts", why(p, "full") == null, p.refused);
        on.remove("wire");
        p = HookPlan.late(loaded, "full", json("full"), on, KNOWN, null);
        check("live: one that's off doesn't", has(why(p, "full"), "requires wire, which isn't installed and turned on"), why(p, "full"));

        // live: two replace hooks, the new one first in load order
        Map<String, Object> r1 = Json.object("{\"id\": \"r1\", \"version\": \"1.0.0\", \"hooks\": {\"points\": {\"framework.context_impl.start_service\": \"replace\"}}}");
        Map<String, Object> r2 = Json.object("{\"id\": \"r2\", \"version\": \"1.0.0\", \"hooks\": {\"points\": {\"framework.context_impl.start_service\": \"replace\"}}}");
        loaded.clear();
        loaded.put("r2", r2);
        p = HookPlan.late(loaded, "r1", r1, on, KNOWN, null);
        check("live: a second replace hook on a point isn't loaded, even first in load order", has(why(p, "r1"), "both replace framework.context_impl.start_service"), why(p, "r1"));

        // optional "requires": one build of egmp-aa-ev that does more with the Wireless mod
        for (String id : new String[] {"full", "lite"}) {
            new File(mods, id + "/mod.json").delete();
            new File(mods, id).delete();
        }
        mod("ev", ", \"requires\": [{\"id\": \"zwire\", \"version\": \">=2\", \"optional\": true, \"adds\": \"preconditioning\"}, \"wire\"]");
        p = boot();
        check("optional, not installed: loads", why(p, "ev") == null && p.points.get("launcher.menu_list.load").containsKey("ev"), p.refused);
        check("... and says what it goes without", p.without.get("ev") != null && p.without.get("ev").contains("zwire (adds: preconditioning): isn't installed and turned on"), p.without);
        check("... in the plan's lines", p.report().contains("optional ev runs without zwire (adds: preconditioning): isn't installed and turned on"), p.report());
        mod("zwire", ", \"version\": \"2.1.0\"");
        p = boot();
        check("optional, there: loaded first (before ev, though its id sorts after)", p.order.indexOf("zwire") < p.order.indexOf("ev") && why(p, "ev") == null, p.order);
        check("... nothing said", p.without.get("ev") == null, p.without);
        mod("zwire", ", \"version\": \"1.0.0\"");
        p = boot();
        check("optional, out of range: loads without it", why(p, "ev") == null && has(String.valueOf(p.without.get("ev")), "zwire 1.0.0 is installed, it works with zwire >=2"), p.without);
        mod("zwire", ", \"version\": \"2.1.0\", \"hooks\": {\"points\": {\"not.a.point\": \"observe\"}}");
        p = boot();
        check("optional, refused: loads without it", why(p, "ev") == null && has(String.valueOf(p.without.get("ev")), "zwire (adds: preconditioning): isn't loaded (hook point not.a.point"), p.without);
        off("zwire", true);
        p = boot();
        check("optional, off: loads", why(p, "ev") == null, p.refused);
        off("wire", true);
        p = boot();
        check("a plain requires next to it still holds", has(why(p, "ev"), "requires wire, which isn't installed and turned on"), why(p, "ev"));
        off("wire", false);
        on.clear();
        on.put("wire", "1.0.0");
        loaded.clear();
        loaded.put("wire", json("wire"));
        p = HookPlan.late(loaded, "ev", json("ev"), on, KNOWN, null);
        check("live: optional not there: loads", why(p, "ev") == null, p.refused);

        // ModContext.modOn
        Set<String> here = new HashSet<String>(Arrays.asList("wire", "ev"));
        Map<String, Object> state = Json.object("{\"wire\": {\"enabled\": true}, \"ev\": {\"enabled\": false}, \"zwire\": {\"enabled\": true}}");
        check("modOn: loaded here, before the state is read: on", HookPlan.on("wire", here, null), "false");
        check("modOn: loaded and on in the state", HookPlan.on("wire", here, state), "false");
        check("modOn: loaded, switched off since", !HookPlan.on("ev", here, state), "true");
        check("modOn: on, but not loaded in this process", !HookPlan.on("zwire", here, state), "true");
        check("modOn: not installed", !HookPlan.on("nope", here, state), "true");
        System.exit(fail);
    }
}
