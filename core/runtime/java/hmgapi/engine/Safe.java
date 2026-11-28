package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safe mode: every mod off at once, without changing a file a mod installed, while
 * /data/hmgapi/SAFE is there. HMG-API and its app stay on, so the owner can turn it off.
 *
 *   hooks       the runtime loads no mod (hook.Mods); HMG-API's own hooks still run
 *   services    hmgapid runs only HMG-API's own (it reads the file itself, so boot-start
 *               services stay off even before the engine has run)
 *   apps        the mods' apps are turned off (ModApps: pm disable, their data kept)
 *   network     the mods' rules come out (Network: nothing wanted any more), but one: a rule a
 *               mod already has that lets adb through (adbOnly), so adb over the hotspot, the way
 *               to mend a unit with no screen, stays reachable
 *   state       state.json lists every mod as off, with "safe": true
 *
 * The mods' file changes (patches, binds, properties, settings) stay as they are: safe mode
 * stops what mods run, it doesn't rebuild /system. Turning a mod off and applying, or
 * uninstalling, puts those back.
 *
 * On: `hmgapid engine safe on` (root, adb's shell, the HMG-API app: its switch), the file
 * by hand (`hmgapid sh ': > /data/hmgapi/SAFE'`), or the zygote wrapper when its boot guard
 * trips. Off: `hmgapid engine safe off`, which also resets the boot guard's counters.
 * Hooks follow at the next restart; services, apps and network rules within seconds.
 */
public final class Safe {
    private Safe() {}

    public static final String FILE = "SAFE";

    /** the file in HMG-API's data folder (/data/hmgapi) */
    public static File file(File data) {
        return new File(data, FILE);
    }

    public static boolean on(File data) {
        return file(data).exists();
    }

    /** why it's on: the file's first line ("owner", "boot guard", or empty when made by hand) */
    public static String why(File data) {
        try {
            String s = Engine.read(file(data)).trim();
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** what the owner reads for the reason */
    static String describe(File data) {
        String w = why(data);
        if (w.startsWith("boot guard")) return "the boot guard turned it on: Android failed to start several times in a row";
        if (w.startsWith("owner")) return "turned on by the owner";
        return w.isEmpty() ? "turned on by hand (" + file(data) + ")" : w;
    }

    /** the keys an adb rule may have: nothing that reaches past adbd's own port */
    static final Set<String> ADB_RULE_KEYS = new HashSet<String>(Arrays.asList("chain", "in", "from", "proto", "ports", "action", "when", "ip"));

    /**
     * The network asks kept in safe mode: of the mods that are on (as if safe mode were off),
     * only rules that accept TCP to the one port adbd listens on (persist.adb.tcp.port) on the
     * way in. The adb mod opens it on the hotspot for cars whose stock firewall drops it; safe
     * mode taking it out would cut off adb, which is how a unit stuck on the boot animation is
     * mended. A mod that didn't ask for such a rule gets none, and adb over TCP that's off
     * (no port) keeps nothing. on gets the mods kept; their "when" still applies (Network).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> adbOnly(Engine e, Set<String> on) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        String port = e.getprop("persist.adb.tcp.port");
        if (port == null || !port.matches("[1-9][0-9]{0,4}")) return out;
        Set<String> wouldBe = new LinkedHashSet<String>();
        Map<String, Map<String, Object>> json = LiveState.mods(e, wouldBe, false);
        for (String id : wouldBe) {
            Object net = json.get(id).get("network");
            Object rules = net instanceof Map ? ((Map<String, Object>) net).get("rules") : null;
            if (!(rules instanceof List)) continue;
            List<Object> kept = new ArrayList<Object>();
            for (Object o : (List<Object>) rules) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> r = (Map<String, Object>) o;
                Object ports = r.get("ports");
                if (ADB_RULE_KEYS.containsAll(r.keySet()) && "input".equals(r.get("chain")) && "accept".equals(r.get("action"))
                        && "tcp".equals(r.get("proto")) && ports instanceof List && ((List<Object>) ports).size() == 1
                        && port.equals(String.valueOf(((List<Object>) ports).get(0)).replaceFirst("\\.0$", ""))) {
                    kept.add(r);
                }
            }
            if (kept.isEmpty()) continue;
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("rules", kept);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("network", n);
            out.put(id, m);
            on.add(id);
        }
        return out;
    }

    static final String WHAT = "no mod's hooks (from the next restart), services, apps or network rules (adb stays reachable); "
            + "HMG-API and its app stay on";

    /** engine safe [on|off]: prints the state; on/off change it and publish the new state */
    static int run(Engine e, String[] args) throws Exception {
        String arg = args.length > 1 ? args[1] : "status";
        File f = file(e.data);
        if ("status".equals(arg)) {
            e.out.add(on(e.data) ? "safe mode: on (" + describe(e.data) + "): " + WHAT : "safe mode: off");
            return 0;
        }
        if (!"on".equals(arg) && !"off".equals(arg)) {
            e.out.add("usage: engine safe [on | off]");
            return 2;
        }
        boolean was = on(e.data);
        if ("on".equals(arg)) {
            if (!was) {
                e.data.mkdirs();
                Engine.write(f, "owner\n");
            }
            e.out.add("safe mode on: " + WHAT + ".");
            e.out.add("Turn it off with the switch in HMG-API's Settings, or hmgapid engine safe off.");
        } else {
            if (was && !f.delete()) {
                e.out.add("can't remove " + f);
                return 1;
            }
            // the boot guard counts from 0 again: a trip left the counters high, and HMG-API's hooks
            // stay off until they're reset
            File hook = new File(e.data, "hook");
            if (hook.isDirectory()) {
                Engine.write(new File(hook, "starts"), "0");
                new File(hook, "boot").delete();
            }
            e.out.add("safe mode off: the mods run again (their hooks from the next restart).");
        }
        // what hmgapid acts on; it also notices the file by itself within a second
        int said = e.out.size();
        if (!e.lock("safe", 0)) {
            while (e.out.size() > said) e.out.remove(e.out.size() - 1);   // not lock()'s "try again": nothing to try again
            e.out.add("the state follows when the engine run in progress has finished (hmgapid publishes it)");
            return 0;
        }
        LiveState.publish(e);
        if (was != on(e.data)) e.out.add("restart the head unit for the hooks to follow");
        return 0;
    }
}
