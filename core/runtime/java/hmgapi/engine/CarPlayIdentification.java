package hmgapi.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What mods add to the head unit's iAP2 identification (the IdentificationInformation message, 0x1D01, the
 * iAP2 server sends an iPhone before CarPlay: what the car is and what it can do), settled into one list, so
 * two mods that each need something in it don't each rewrite it (mod.json "projection" "cp_identification"):
 *
 *   "projection": {"cp_identification": {
 *       "parameters": [{"component": "wireless_carplay", "id": 5, "type": "none"},
 *                      {"component": "wireless_carplay", "id": 6, "type": "utf8", "value": "44C9FA31EC:985:19.0"}],
 *       "messages_sent": ["0x4301"],          iAP2 messages the car will send as well
 *       "messages_received": ["0x4300"],      ... and take from the phone
 *       "provider": true}}                    this mod writes the settled additions into the message
 *
 * A parameter is one entry of one of the message's components (COMPONENTS: the groups a car describes itself
 * with), by its number in that group, with a type (TYPES) and a value. The same parameter asked with the same
 * type and value by two mods is one parameter; asked differently it is a conflict: the first mod by id has it,
 * and the owner picks with hmgapi.json overrides.projection {"cp_identification": {"wireless_carplay 6": "<mod>"}}.
 * Messages: what all mods ask, together.
 *
 * HMG-API settles and publishes (state.json "projection": "cp_identification", "cp_messages_sent",
 * "cp_messages_received", "cp_identification_by"); it doesn't change the message itself. The stock serializer
 * can't write parameters it has no table entry for, so that takes a patch of the car's iAP2 library, which
 * stays a mod's own (hooks.native): the provider. One provider at a time (two is a conflict, picked with
 * overrides.projection {"cp_identification": {"provider": "<mod>"}}); it reads the settled list in the iAP2
 * server's process (ModContext.carPlayIdentification) and serializes every mod's additions with its own.
 * With asks and no provider, the plan says nothing applies them.
 */
public final class CarPlayIdentification {
    private CarPlayIdentification() {}

    /** the components a parameter can be added to: name -> the group's number in IdentificationInformation */
    public static final Map<String, Integer> COMPONENTS = new LinkedHashMap<String, Integer>();
    static {
        COMPONENTS.put("usb_host_transport", 16);
        COMPONENTS.put("vehicle_information", 20);
        COMPONENTS.put("vehicle_status", 21);
        COMPONENTS.put("location_information", 22);
        COMPONENTS.put("wireless_carplay", 24);
        COMPONENTS.put("route_guidance_display", 30);
    }

    /** none: a parameter that is only there (a flag); blob: bytes as hex */
    public static final List<String> TYPES = java.util.Arrays.asList("none", "uint8", "uint16", "uint32", "utf8", "blob");
    static final int MAX_TEXT = 512, MAX_BLOB = 1024;

    static final class Param {
        String mod, component, type;
        int id;
        Object value;   // null (none), Long (numbers), String (utf8; blob as lower-case hex)

        String key() {
            return component + " " + id;
        }

        boolean same(Param o) {
            return type.equals(o.type) && (value == null ? o.value == null : value.equals(o.value));
        }

        String shown() {
            return type.equals("none") ? "set" : type.equals("utf8") ? "\"" + value + "\"" : String.valueOf(value);
        }
    }

    static final class Ask {
        String mod;
        final List<Param> params = new ArrayList<Param>();
        final List<Integer> sent = new ArrayList<Integer>(), received = new ArrayList<Integer>();
        boolean provider;

        boolean empty() {
            return params.isEmpty() && sent.isEmpty() && received.isEmpty();
        }
    }

    /** mod.json "projection" "cp_identification"; null if the mod has none */
    @SuppressWarnings("unchecked")
    static Ask of(String id, Object o, List<String> errors) {
        if (o == null) return null;
        String what = id + ": projection cp_identification";
        if (!(o instanceof Map)) {
            errors.add(what + ": must be an object");
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        Ask a = new Ask();
        a.mod = id;
        a.provider = Boolean.TRUE.equals(m.get("provider"));
        Object ps = m.get("parameters");
        if (ps != null && !(ps instanceof List)) errors.add(what + " parameters: a list");
        if (ps instanceof List) {
            for (Object x : (List<Object>) ps) {
                Param p = param(id, x, errors);
                if (p == null) continue;
                Param before = null;
                for (Param q : a.params) if (q.key().equals(p.key())) before = q;
                if (before == null) a.params.add(p);
                else if (!before.same(p)) errors.add(what + ": " + p.key() + " is there twice");
            }
        }
        messages(what + " messages_sent", m.get("messages_sent"), a.sent, errors);
        messages(what + " messages_received", m.get("messages_received"), a.received, errors);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Param param(String mod, Object x, List<String> errors) {
        String what = mod + ": projection cp_identification parameter";
        if (!(x instanceof Map)) {
            errors.add(what + ": must be an object");
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) x;
        Param p = new Param();
        p.mod = mod;
        p.component = String.valueOf(m.get("component"));
        if (!COMPONENTS.containsKey(p.component)) {
            errors.add(what + ": component " + m.get("component") + " (one of " + COMPONENTS.keySet() + ")");
            return null;
        }
        Object n = m.get("id");
        p.id = n instanceof Number ? ((Number) n).intValue() : -1;
        if (p.id < 0 || p.id > 0xffff) {
            errors.add(what + " " + p.component + ": id " + n + " isn't 0-65535");
            return null;
        }
        what += " " + p.key();
        p.type = String.valueOf(m.get("type"));
        if (!TYPES.contains(p.type)) {
            errors.add(what + ": type " + m.get("type") + " (one of " + TYPES + ")");
            return null;
        }
        Object v = m.get("value");
        if (p.type.equals("none")) {
            if (v != null) {
                errors.add(what + ": a \"none\" parameter has no value");
                return null;
            }
        } else if (p.type.equals("utf8")) {
            if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > MAX_TEXT || ((String) v).indexOf('\0') >= 0) {
                errors.add(what + ": value must be text of 1-" + MAX_TEXT + " characters");
                return null;
            }
            p.value = v;
        } else if (p.type.equals("blob")) {
            String h = v instanceof String ? ((String) v).toLowerCase(java.util.Locale.ROOT) : "";
            if (h.isEmpty() || h.length() % 2 != 0 || h.length() > 2 * MAX_BLOB || !h.matches("[0-9a-f]+")) {
                errors.add(what + ": value must be 1-" + MAX_BLOB + " bytes as hex");
                return null;
            }
            p.value = h;
        } else {
            long max = p.type.equals("uint8") ? 0xffL : p.type.equals("uint16") ? 0xffffL : 0xffffffffL;
            // whole numbers only: 2.5 isn't a uint
            boolean whole = v instanceof Number && ((Number) v).doubleValue() == Math.rint(((Number) v).doubleValue());
            long l = whole ? ((Number) v).longValue() : -1;
            if (l < 0 || l > max) {
                errors.add(what + ": value " + v + " isn't 0-" + max);
                return null;
            }
            p.value = l;
        }
        return p;
    }

    /** iAP2 message ids: numbers, or text like "0x4301" */
    @SuppressWarnings("unchecked")
    private static void messages(String what, Object o, List<Integer> into, List<String> errors) {
        if (o == null) return;
        if (!(o instanceof List)) {
            errors.add(what + ": a list of message ids");
            return;
        }
        for (Object x : (List<Object>) o) {
            int v = -1;
            try {
                if (x instanceof Number && ((Number) x).doubleValue() == Math.rint(((Number) x).doubleValue())) v = ((Number) x).intValue();
                else if (x instanceof String) v = Integer.decode(((String) x).trim());
            } catch (NumberFormatException e) {
                v = -1;
            }
            if (v < 0 || v > 0xffff) errors.add(what + ": " + x + " isn't a message id (0-0xFFFF)");
            else if (!into.contains(v)) into.add(v);
        }
    }

    /** the enabled mods' asks, in mod order, into state.json "projection"; overrides: hmgapi.json "overrides" */
    static void settle(List<Ask> asks, Map<String, Object> overrides, Map<String, Object> out, List<String> notes) {
        Object o = overrides == null ? null : overrides.get("projection");
        Object c = o instanceof Map ? ((Map<?, ?>) o).get("cp_identification") : null;
        Map<?, ?> picks = c instanceof Map ? (Map<?, ?>) c : null;

        Map<String, Param> won = new LinkedHashMap<String, Param>();
        for (Ask a : asks) {
            for (Param p : a.params) {
                Param has = won.get(p.key());
                Object pick = picks == null ? null : picks.get(p.key());
                if (has == null || (p.mod.equals(pick) && !has.mod.equals(pick))) won.put(p.key(), p);
            }
        }
        for (Ask a : asks) {
            for (Param p : a.params) {
                Param has = won.get(p.key());
                if (has == p || has.same(p)) continue;
                boolean picked = picks != null && has.mod.equals(picks.get(p.key()));
                notes.add("conflict: " + p.mod + " sets CarPlay identification " + p.key() + " to " + p.shown() + ", " + has.mod + " to " + has.shown()
                        + "; " + has.mod + " has it" + (picked ? " (the owner's choice)"
                                : " (pick another: hmgapi.json overrides.projection {\"cp_identification\": {\"" + p.key() + "\": \"<mod>\"}})"));
            }
        }
        List<Object> params = new ArrayList<Object>();
        for (Param p : won.values()) {
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("component", p.component);
            e.put("group", COMPONENTS.get(p.component));
            e.put("id", p.id);
            e.put("type", p.type);
            if (p.value != null) e.put("value", p.value);
            e.put("by", p.mod);
            params.add(e);
        }
        List<Object> sent = new ArrayList<Object>(), received = new ArrayList<Object>();
        List<String> askers = new ArrayList<String>();
        Ask provider = null;
        List<String> providers = new ArrayList<String>();
        Object pick = picks == null ? null : picks.get("provider");
        for (Ask a : asks) {
            for (Integer x : a.sent) if (!sent.contains(x)) sent.add(x);
            for (Integer x : a.received) if (!received.contains(x)) received.add(x);
            if (!a.empty()) askers.add(a.mod);
            if (a.provider) {
                providers.add(a.mod);
                if (provider == null || a.mod.equals(pick)) provider = a;
            }
        }
        if (providers.size() > 1) {
            notes.add("conflict: " + providers + " each apply CarPlay identification; " + provider.mod + " does"
                    + (provider.mod.equals(pick) ? " (the owner's choice)" : " (pick another: hmgapi.json overrides.projection {\"cp_identification\": {\"provider\": \"<mod>\"}})"));
        }
        if (provider == null && !askers.isEmpty()) {
            notes.add("CarPlay identification: nothing applies what " + askers + " " + (askers.size() == 1 ? "adds" : "add")
                    + " (it takes a mod whose projection cp_identification has \"provider\": true)");
        }
        if (!params.isEmpty()) out.put("cp_identification", params);
        if (!sent.isEmpty()) out.put("cp_messages_sent", sent);
        if (!received.isEmpty()) out.put("cp_messages_received", received);
        if (provider != null) out.put("cp_identification_by", provider.mod);
    }

    static List<String> describe(Ask a) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        for (Param p : a.params) out.add("projection CarPlay identification: " + p.key() + " " + (p.type.equals("none") ? "set" : "= " + p.shown()) + " [" + a.mod + "]");
        if (!a.sent.isEmpty() || !a.received.isEmpty()) out.add("projection CarPlay identification: messages sent " + hex(a.sent) + ", received " + hex(a.received) + " [" + a.mod + "]");
        if (a.provider) out.add("projection CarPlay identification: applies every mod's additions [" + a.mod + "]");
        return out;
    }

    private static List<String> hex(List<Integer> ids) {
        List<String> out = new ArrayList<String>();
        for (Integer i : ids) out.add(String.format("0x%04X", i));
        return out;
    }
}
