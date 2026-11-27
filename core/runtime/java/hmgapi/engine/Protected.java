package hmgapi.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The safety functions no mod touches: the rear camera, the 360 view (AVM/SVM), the
 * parking guide lines (PGS) and telematics emergency calls (eCall, SOS, TMU). Built in
 * and the same for every owner: no setting, file or mod.json turns any of it off
 * (CLAUDE.md's one-warning-then-follow-the-owner rule doesn't cover these: what goes
 * wrong here goes wrong for someone reversing or calling for help).
 *
 *   - In a protected app process no mod loads, at boot or live, and the hooks mods put
 *     on in the zygote pass straight through there. HMG-API adds nothing of its own in
 *     that process (no live state, no driving guard), only the app-start hook that tells
 *     it which process it is. ({@link #process})
 *   - The camera and telematics services that run inside system_server (PgsService,
 *     TMUService) can't be left out as a process: there, no mod may hook their classes,
 *     declared or not. ({@link #hookTarget})
 *   - No hook point that reaches those services may be declared by a mod (the vehicle
 *     commands guide_lines and rear_camera are the way in). ({@link #point})
 *
 * Process and class names are from the stock files and logs of the US Ioniq 5 (NE EV,
 * 260507): the rear camera and AVM in com.hkmc.system.app.rearcamera (pid 1163, tags
 * AvmMode, RearCamera-JNI), PgsService (log tag, 624 = system_server), TMUService and
 * com.hkmc.pgs.* / com.hkmc.tmu.* (automotive.jar, automotive-service.jar). The name
 * patterns catch a renamed camera or eCall app on a build that hasn't been checked yet.
 *
 * Direct hooks (Bridge.hookMethod, Hooks.findAndHookMethod) outside a mod's declared
 * points still work elsewhere (egmp-aa-ev 0.8.4 uses one as a fallback), but each is
 * logged with {@link #UNDECLARED} and listed in the Car Report.
 */
public final class Protected {
    private Protected() {}

    /** log markers: the Car Report collects lines with these (Report.protection) */
    public static final String MARK = "protected: ", UNDECLARED = "undeclared hook: ";

    /** app processes (and every process of these packages, "pkg:name"): what runs there */
    public static final Map<String, String> PROCESSES;
    static {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("com.hkmc.system.app.rearcamera", "the rear camera, 360 view (AVM/SVM) and parking guide lines");
        p.put("com.hkmc.telematics.ECall", "the emergency call (eCall)");
        p.put("com.hkmc.telematics.service", "telematics (Bluelink / Kia Connect / Genesis Connected: SOS and crash calls)");
        PROCESSES = Collections.unmodifiableMap(p);
    }

    /**
     * any other stock process (a package of the head unit's makers, OEM below) whose name has
     * one of these: a camera or emergency-call app not listed above. Never a mod's own app
     * (a dashcam mod's package may say "rearcamera": its hooks are its own business)
     */
    static final Pattern NAMES = Pattern.compile(
            "(?i)(rear_?camera|rearview|\\.rvc\\b|\\.avm\\b|\\.svm\\b|\\.pgs\\b|surround_?view|\\becall|\\.sos\\b|emergency_?call)");

    static final String[] OEM = { "com.hkmc.", "com.mobis.", "com.daudio.", "com.hyundai.", "com.kia.", "com.genesis.",
        "com.android.", "com.tcc.", "com.mnsoft.", "com.lge." };

    static boolean oem(String name) {
        for (String o : OEM) if (name.startsWith(o)) return true;
        return false;
    }

    /** classes (prefix ending in '.': a package and its subpackages; else that class and its inner classes) */
    public static final Map<String, String> CLASSES;
    static {
        Map<String, String> c = new LinkedHashMap<String, String>();
        c.put("com.hkmc.pgs.", "parking guidance, the cameras and 360 view (PgsManager, AvmManager, FmvManager)");
        c.put("com.hkmc.micom.PgsService", "the camera and guide line service (system_server)");
        c.put("com.hkmc.micom.PgsServiceV2", "the camera and guide line service (system_server)");
        c.put("com.hkmc.micom.TMUService", "the telematics unit's service: SOS and emergency calls (system_server)");
        c.put("com.hkmc.micom.protocol.AVM", "the 360 view's MICOM protocol");
        c.put("com.hkmc.micom.protocol.Camera", "the cameras' MICOM protocol");
        c.put("com.hkmc.micom.protocol.camera.", "the cameras' MICOM protocol");
        c.put("com.hkmc.micom.protocol.PGS", "parking guidance's MICOM protocol");
        c.put("com.hkmc.micom.protocol.TMU", "the telematics unit's MICOM protocol");
        c.put("com.hkmc.tmu.", "the telematics unit: SOS, eCall, airbag and crash listeners (TMUManager)");
        c.put("com.hkmc.system.app.rearcamera.", "the rear camera app");
        CLASSES = Collections.unmodifiableMap(c);
    }

    /** hook point ids (a prefix: the point and every point of it) no mod may declare */
    public static final Map<String, String> POINTS;
    static {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("framework.pgs_service", "the parking guide lines and cameras (use the vehicle commands guide_lines and rear_camera)");
        p.put("framework.car_services.pgs", "the parking guide lines and cameras (use the vehicle commands guide_lines and rear_camera)");
        p.put("framework.tmu", "the telematics unit (SOS, eCall)");
        POINTS = Collections.unmodifiableMap(p);
    }

    /** the package of a process name ("pkg:name" -> "pkg") */
    static String packageOf(String process) {
        int c = process.indexOf(':');
        return c < 0 ? process : process.substring(0, c);
    }

    /**
     * Why mods stay out of this app process, or null. pkg: the app's package; process:
     * its process name (either may be null).
     */
    public static String process(String pkg, String process) {
        for (String s : new String[] { process, pkg }) {
            if (s == null || s.isEmpty()) continue;
            String why = PROCESSES.get(packageOf(s));
            if (why != null) return why;
        }
        for (String s : new String[] { process, pkg }) {
            if (s != null && oem(s) && NAMES.matcher(s).find()) return "a camera or emergency-call app (by its name)";
        }
        return null;
    }

    /** Why no mod may hook a member of this class, or null. */
    public static String hookTarget(String className) {
        if (className == null) return null;
        for (Map.Entry<String, String> c : CLASSES.entrySet()) {
            String k = c.getKey();
            if (k.endsWith(".") ? className.startsWith(k) : className.equals(k) || className.startsWith(k + "$")) return c.getValue();
        }
        return null;
    }

    /** Why no mod may declare this hook point, or null. */
    public static String point(String id) {
        if (id == null) return null;
        for (Map.Entry<String, String> p : POINTS.entrySet()) {
            String k = p.getKey();
            if (id.equals(k) || id.startsWith(k + ".") || id.startsWith(k + "_")) return p.getValue();
        }
        return null;
    }

    /** a direct hook: refused (why), allowed but reported, or allowed (a declared point's) */
    public static final class Direct {
        public final boolean refused;
        public final String line;

        Direct(boolean refused, String line) {
            this.refused = refused;
            this.line = line;
        }
    }

    /**
     * A mod's hook as Bridge.hookMethod sees it. mod: whose code it is (null: HMG-API's
     * own, always allowed); declared: it came through ModContext.hook (a declared point);
     * member: "Class.method"; process: where (null: the zygote); protectedHere: this
     * process was found protected at its start (Bridge.modsOff). Null: allowed, nothing
     * to say. Refused: never in a protected process, never on a protected class.
     * Undeclared elsewhere: allowed, and the line is logged for the Car Report.
     */
    public static Direct direct(String mod, boolean declared, String className, String member, String process, boolean protectedHere) {
        if (mod == null) return null;
        String where = process == null || process.isEmpty() ? "zygote" : process;
        String p = process == null ? null : process(null, process);
        if (p == null && protectedHere) p = "safety functions";
        if (p != null) return new Direct(true, MARK + "refused " + mod + "'s hook on " + member + " in " + where + ": that process runs " + p + "; no mod hooks there (built in, no override)");
        String c = hookTarget(className);
        if (c != null) return new Direct(true, MARK + "refused " + mod + "'s hook on " + member + " in " + where + ": " + c + " is a safety function; no mod hooks it (built in, no override)");
        if (declared) return null;
        return new Direct(false, UNDECLARED + mod + " hooked " + member + " in " + where + " directly, not through a hook point it declared in mod.json");
    }

    /** every rule, for `engine plan` and the docs' tests */
    public static List<String> describe() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> p : PROCESSES.entrySet()) out.add("process " + p.getKey() + ": " + p.getValue());
        out.add("stock process names matching " + NAMES.pattern().substring(4) + ": a camera or emergency-call app");
        for (Map.Entry<String, String> c : CLASSES.entrySet()) out.add("class " + c.getKey() + (c.getKey().endsWith(".") ? "*" : "") + ": " + c.getValue());
        for (Map.Entry<String, String> p : POINTS.entrySet()) out.add("point " + p.getKey() + "*: " + p.getValue());
        return out;
    }

}
