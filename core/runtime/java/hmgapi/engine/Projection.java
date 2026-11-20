package hmgapi.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What mods ask of phone projection (mod.json "projection"), settled into one set of
 * settings for the runtime in GPM (hook.Projection), so no mod rewrites GPM:
 *
 *   "projection": {"aa_density": 200,                       Android Auto's DPI (60-480; stock 240 on wide screens)
 *                  "aa_sensors": {"remove": ["speed"],       not offered to the phone (speed: no tap limit)
 *                                 "add": [23]},             sensor types offered as well (1-64)
 *                  "aa_fuel_types": [10], "aa_ev_connectors": [4, 1],
 *                  "aa_raw": true,                          its hook code sends raw sensor batches (ProjectionHooks.sendRaw)
 *                  "events": true}                          the mod's apps hear hmgapi.intent.action.PROJECTION
 *
 * Density: one value; two mods asking differently is a conflict (the first by id
 * wins, the owner picks with hmgapi.json overrides.projection {"aa_density": "<mod>"}).
 * Sensors, fuel types and connectors: what all mods ask, together.
 * "aa_raw" and sensor types above 21 need the mod's own recipe for Android Auto's
 * library (mod.json hooks.native "libautoreceiver_infobank.so"): HMG-API ships none,
 * native patches stay in the mods. state.json "aa_raw" lists the mods that send raw,
 * "aa_sensors_add_patched" each mod's types above 21; GPM's process offers those, and
 * lets a mod send, only where that mod's recipe is in place.
 */
public final class Projection {
    private Projection() {}

    public static final List<String> REMOVABLE = java.util.Arrays.asList("speed");
    /** the last sensor type the stock Android Auto library reads */
    public static final int STOCK_LAST_SENSOR = 21;
    /** Android Auto's native library: what a mod's recipe changes for raw sends and types above 21 */
    public static final String AA_LIB = "libautoreceiver_infobank.so";

    static final class Ask {
        String mod;
        int density;
        final List<String> remove = new ArrayList<String>();
        final List<Integer> add = new ArrayList<Integer>(), fuel = new ArrayList<Integer>(), connectors = new ArrayList<Integer>();
        boolean events, raw;
    }

    /** whether a mod's mod.json "hooks" has recipes for Android Auto's library in "native" */
    @SuppressWarnings("unchecked")
    static boolean hasAaRecipe(Object hooks) {
        Object nat = hooks instanceof Map ? ((Map<String, Object>) hooks).get("native") : null;
        Object files = nat instanceof Map ? ((Map<String, Object>) nat).get(AA_LIB) : null;
        return files instanceof List && !((List<Object>) files).isEmpty();
    }

    /** hooks: the mod's mod.json "hooks" (its own native recipes) */
    @SuppressWarnings("unchecked")
    static Ask of(String id, Object o, Object hooks, List<String> errors) {
        if (!(o instanceof Map)) {
            if (o != null) errors.add(id + ": projection: must be an object");
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        Ask a = new Ask();
        a.mod = id;
        Object d = m.get("aa_density");
        if (d != null) {
            int v = d instanceof Number ? ((Number) d).intValue() : -1;
            if (v < 60 || v > 480) errors.add(id + ": projection aa_density: 60-480");
            else a.density = v;
        }
        Object s = m.get("aa_sensors");
        if (s instanceof Map) {
            Object r = ((Map<String, Object>) s).get("remove"), ad = ((Map<String, Object>) s).get("add");
            if (r instanceof List) {
                for (Object x : (List<Object>) r) {
                    if (REMOVABLE.contains(String.valueOf(x))) a.remove.add(String.valueOf(x));
                    else errors.add(id + ": projection aa_sensors remove: " + x + " (only " + REMOVABLE + ")");
                }
            }
            ints(id, "aa_sensors add", ad, 1, 64, a.add, errors);
        }
        ints(id, "aa_fuel_types", m.get("aa_fuel_types"), 0, 31, a.fuel, errors);
        ints(id, "aa_ev_connectors", m.get("aa_ev_connectors"), 0, 31, a.connectors, errors);
        a.events = Boolean.TRUE.equals(m.get("events"));
        Object raw = m.get("aa_raw");
        if (raw != null && !(raw instanceof Boolean)) errors.add(id + ": projection aa_raw: true or false");
        a.raw = Boolean.TRUE.equals(raw);
        // what only a mod's own recipe for the library makes work
        if (!hasAaRecipe(hooks)) {
            String need = ": needs the mod's own recipe for " + AA_LIB + " in hooks.native (HMG-API ships none)";
            if (a.raw) errors.add(id + ": projection aa_raw" + need);
            a.raw = false;
            for (java.util.Iterator<Integer> it = a.add.iterator(); it.hasNext();) {
                int t = it.next();
                if (t > STOCK_LAST_SENSOR) {
                    errors.add(id + ": projection aa_sensors add " + t + need + " (the stock library reads up to " + STOCK_LAST_SENSOR + ")");
                    it.remove();
                }
            }
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    static void ints(String id, String what, Object o, int lo, int hi, List<Integer> into, List<String> errors) {
        if (o == null) return;
        if (!(o instanceof List)) {
            errors.add(id + ": projection " + what + ": a list of numbers");
            return;
        }
        for (Object x : (List<Object>) o) {
            int v = x instanceof Number ? ((Number) x).intValue() : -1;
            if (v < lo || v > hi) errors.add(id + ": projection " + what + ": " + x + " isn't " + lo + "-" + hi);
            else if (!into.contains(v)) into.add(v);
        }
    }

    /** the enabled mods' asks -> state.json "projection"; packages: mod -> its apps (for events) */
    static Map<String, Object> settle(List<Ask> asks, Map<String, List<String>> packages, Map<String, Object> overrides, List<String> notes) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Object o = overrides == null ? null : overrides.get("projection");
        Object pick = o instanceof Map ? ((Map<?, ?>) o).get("aa_density") : null;
        Ask dens = null;
        for (Ask a : asks) {
            if (a.density == 0) continue;
            if (dens == null || a.mod.equals(pick)) dens = a;
        }
        for (Ask a : asks) {
            if (a.density != 0 && dens != null && a != dens && a.density != dens.density) {
                notes.add("conflict: " + a.mod + " asks Android Auto density " + a.density + ", " + dens.mod + " " + dens.density + "; "
                        + dens.mod + " has it" + (pick == null ? " (pick another: hmgapi.json overrides.projection {\"aa_density\": \"<mod>\"})" : " (the owner's choice)"));
            }
        }
        if (dens != null) {
            out.put("aa_density", dens.density);
            out.put("aa_density_by", dens.mod);
        }
        List<Object> remove = new ArrayList<Object>(), add = new ArrayList<Object>(), fuel = new ArrayList<Object>(), conn = new ArrayList<Object>();
        List<Object> events = new ArrayList<Object>(), raw = new ArrayList<Object>();
        Map<String, Object> patched = new LinkedHashMap<String, Object>();
        for (Ask a : asks) {
            if (a.raw) raw.add(a.mod);
            for (String r : a.remove) if (!remove.contains(r)) remove.add(r);
            List<Object> own = new ArrayList<Object>();
            for (Integer x : a.add) {
                if (x > STOCK_LAST_SENSOR) own.add(x);   // only where this mod's recipe is in place
                else if (!add.contains(x)) add.add(x);
            }
            if (!own.isEmpty()) patched.put(a.mod, own);
            for (Integer x : a.fuel) if (!fuel.contains(x)) fuel.add(x);
            for (Integer x : a.connectors) if (!conn.contains(x)) conn.add(x);
            if (a.events && packages.get(a.mod) != null) for (String p : packages.get(a.mod)) if (!events.contains(p)) events.add(p);
        }
        if (!remove.isEmpty()) out.put("aa_sensors_remove", remove);
        if (!add.isEmpty()) out.put("aa_sensors_add", add);
        if (!fuel.isEmpty()) out.put("aa_fuel_types", fuel);
        if (!conn.isEmpty()) out.put("aa_ev_connectors", conn);
        if (!events.isEmpty()) out.put("events", events);
        if (!patched.isEmpty()) out.put("aa_sensors_add_patched", patched);
        if (!raw.isEmpty()) out.put("aa_raw", raw);
        return out;
    }

    static List<String> describe(Ask a) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        if (a.density != 0) out.add("projection Android Auto density " + a.density + " [" + a.mod + "]");
        if (!a.remove.isEmpty()) out.add("projection Android Auto: the phone isn't offered " + a.remove + " (for every mod) [" + a.mod + "]");
        if (!a.add.isEmpty()) {
            List<Integer> recipe = new ArrayList<Integer>();
            for (Integer t : a.add) if (t > STOCK_LAST_SENSOR) recipe.add(t);
            out.add("projection Android Auto sensors added " + a.add + (recipe.isEmpty() ? "" : " (" + recipe
                    + " only where its own " + AA_LIB + " recipe is in place)") + " [" + a.mod + "]");
        }
        if (a.raw) out.add("projection Android Auto raw sensor batches from its hook code (only where its own " + AA_LIB + " recipe is in place) [" + a.mod + "]");
        if (!a.fuel.isEmpty() || !a.connectors.isEmpty()) out.add("projection Android Auto fuel types " + a.fuel + ", EV connectors " + a.connectors + " [" + a.mod + "]");
        if (a.events) out.add("projection events to its apps [" + a.mod + "]");
        return out;
    }
}
