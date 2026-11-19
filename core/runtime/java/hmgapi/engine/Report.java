package hmgapi.engine;

import java.io.File;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * engine report [--usb]: the Car Report, what a maintainer needs to make HMG-API work
 * on a car it doesn't know yet (or fix what's missing on one it does), from the car
 * itself: what the head unit is (model, region, firmware), the hook map and which
 * points it lacks, this car mapped from its own files (MapProbe: names only, ready to
 * become its map), the last on-car map check, the unit's features, each installed
 * mod and whether it loads here, HMG-API's recent errors, and the unit's network
 * (NetReport: the stock firewall and its rules, routes, logged drops), and SELinux (selinux:
 * the mode, what HMG-API did at start, app_process's labels, the kernel's denials).
 *
 * Model-level facts only. Never: anything from /data but HMG-API's own state, VIN,
 * serials, phone numbers, IMEI, account details, addresses. Log lines are scrubbed
 * (scrub) before they're kept.
 *
 * HMG-API's errors come from its log keeper's file (logs/hmgapi/log.log), scrubbed.
 *
 * Writes /data/hmgapi/public/reports/<name>.json and .md (--usb: also to
 * "HMG-API reports" on every USB stick), and prints the summary, the file name and
 * a link to a pre-filled GitHub issue (issue-url: ...).
 */
final class Report {
    private Report() {}

    static final String REPO = "Open-HMG/HMG-API";
    static final String[] USB = { "/storage/usb0", "/storage/usb1", "/storage/usb2" };

    @SuppressWarnings("unchecked")
    static int run(Engine e, boolean usb) throws Exception {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date());
        r.put("report", "hmgapi car report 1");
        r.put("date", stamp);
        r.put("hmgapi", Engine.HMGAPI_VERSION);
        Map<String, Object> unit = new LinkedHashMap<String, Object>();
        for (String k : new String[] {"product", "make", "name", "model", "region", "firmware", "platform", "android", "onebin"}) {
            unit.put(k, e.facts.get(k));
        }
        unit.put("wifi_chip", e.facts.features().contains("hmgapi.wifi"));
        unit.put("wifi", e.facts.wifi());   // seen, stock hotspot, unpowered (the hotspot feature off) or none
        unit.put("features", new ArrayList<Object>(e.facts.features()));
        r.put("unit", unit);
        try {
            r.put("selinux", selinux(e));
        } catch (Throwable t) {
            Map<String, Object> se = new LinkedHashMap<String, Object>();
            se.put("error", scrub(String.valueOf(t)));
            r.put("selinux", se);
        }

        CarMap map = CarMap.load(e.root, e.facts, e.hmgapi);
        Map<String, Object> mj = new LinkedHashMap<String, Object>();
        mj.put("summary", map.describe());
        mj.put("status", map.status);
        mj.put("folder", map.folder);
        mj.put("hooks_on", map.runnable);
        mj.put("missing", notNoFiles(map.missing));
        mj.put("mapped_here", new ArrayList<Object>(map.local));   // state/local-map.json: for a maintainer to make official
        if (map.localWhy != null) mj.put("mapped_here_off", map.localWhy);
        r.put("map", mj);

        // this car mapped from its own files: names for a new map, or what changed on a known car
        Map<String, Object> pj = new LinkedHashMap<String, Object>();
        try {
            File maps = new File(e.root + "/data/hmgapi/maps");
            MapProbe p = new MapProbe(e.root + "/system", e.root + "/data/hmgapi/stock", maps);
            p.run(new File(maps, "catalogue"));
            pj.put("found", (long) p.points.size());
            pj.put("by_reference", new ArrayList<Object>(p.byReference));
            pj.put("not_found", notNoFiles(p.missing));
            Map<String, Object> noFiles = new TreeMap<String, Object>();
            for (Map.Entry<String, String> m : p.missing.entrySet()) {
                if (m.getValue().startsWith("no stock ")) noFiles.put(CarMap.appOfId(m.getKey()), m.getValue());
            }
            pj.put("apps_absent", noFiles);
            pj.put("files", new TreeMap<String, Object>(p.files));
            pj.put("app_files", p.appFiles);
            pj.put("points", p.points);
            if (map.folder != null) {
                List<Object> differs = new ArrayList<Object>();
                for (Map.Entry<String, Map<String, Object>> en : p.points.entrySet()) {
                    Map<String, Object> have = map.points.get(en.getKey());
                    if (have != null && !String.valueOf(have).equals(String.valueOf(en.getValue()))) differs.add(en.getKey());
                }
                pj.put("differs_from_map", differs);
                // points HMG-API's map itself lists as missing (car.json "missing") that this
                // unit finds: the map is likely wrong there (#47), a maintainer should look
                pj.put("found_here_missing_in_map", foundHereMissingInMap(
                        map.carJson == null ? null : (Map<String, Object>) map.carJson.get("missing"), p.points, map.points));
            }
        } catch (Throwable t) {
            pj.put("error", scrub(String.valueOf(t)));
        }
        r.put("map_car", pj);

        File check = new File(e.stateDir, "map-check.json");
        if (check.isFile()) {
            Map<String, Object> c = Json.object(Engine.read(check));
            Map<String, Object> cj = new LinkedHashMap<String, Object>();
            for (String k : new String[] {"folder", "ok", "points", "failed", "date"}) cj.put(k, c.get(k));
            r.put("verify_map", cj);
        }

        // mods: installed, on, and whether they load on this car
        HookPlan plan = e.hookPlan();
        List<Object> mods = new ArrayList<Object>();
        File[] ds = new File(e.data, "mods").listFiles();
        if (ds != null) {
            java.util.Arrays.sort(ds);
            for (File d : ds) {
                File mf = new File(d, "mod.json");
                if (!mf.isFile()) continue;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                try {
                    Map<String, Object> raw = Json.object(Engine.read(mf));
                    m.put("id", raw.get("id"));
                    m.put("version", raw.get("version"));
                    m.put("on", !new File(d, ".disabled").exists());
                    String sys = raw.containsKey("system") ? SystemCheck.check(raw.get("system"), e.facts, SystemCheck.asksHotspot(raw)) : null;
                    m.put("supports_this_car", sys == null);
                    if (sys != null) m.put("why_not", sys);
                    if (plan.refused.containsKey(d.getName())) m.put("hooks_not_loaded", plan.refused.get(d.getName()));
                } catch (Exception x) {
                    m.put("id", d.getName());
                    m.put("error", scrub(String.valueOf(x)));
                }
                mods.add(m);
            }
        }
        r.put("mods", mods);
        r.put("errors", errors(e));
        Map<String, Object> net;
        try {
            net = NetReport.collect(e);
            net.put("summary", NetReport.summary(net));
        } catch (Throwable t) {
            net = new LinkedHashMap<String, Object>();
            net.put("error", scrub(String.valueOf(t)));
        }
        r.put("network", net);

        // out: public/reports (the Car Report app reads it), and USB sticks
        String name = "car-report-" + safe(e.facts.get("model")) + "-" + safe(e.facts.get("region")) + "-"
                + safe(e.facts.get("firmware")) + "-" + stamp;
        String json = Json.write(r), md = markdown(r, name);
        File dir = new File(e.data, "public/reports");
        dir.mkdirs();
        dir.setReadable(true, false);   // adb pull (shell user) and the HMG-API app
        dir.setExecutable(true, false);
        Engine.write(new File(dir, name + ".json"), json);
        Engine.write(new File(dir, name + ".md"), md);
        new File(dir, name + ".json").setReadable(true, false);
        new File(dir, name + ".md").setReadable(true, false);
        List<String> sticks = new ArrayList<String>();
        if (usb) {
            for (String u : USB) {
                File root = new File(e.root + u);
                if (!root.isDirectory() || root.list() == null) continue;
                File d = new File(root, "HMG-API reports");
                if (!d.isDirectory() && !d.mkdirs()) continue;
                Engine.write(new File(d, name + ".json"), json);
                Engine.write(new File(d, name + ".md"), md);
                sticks.add(u + "/HMG-API reports");
            }
        }
        e.out.add("report: " + name);
        e.out.add("car: " + carName(unit) + " " + unit.get("product"));
        e.out.add(map.describe());
        e.out.add("map-car: " + pj.get("found") + " hook point(s) found, " + ((Map<?, ?>) pj.get("not_found") == null ? 0
                : ((Map<?, ?>) pj.get("not_found")).size()) + " not found");
        String dis = disagreement(pj);
        if (dis != null) e.out.add("check the map: " + dis);
        e.out.add("saved: " + dir.getPath() + "/" + name + ".json");
        if (net.get("summary") != null) e.out.add("network: " + net.get("summary"));
        for (String s : sticks) e.out.add("usb: " + s + "/" + name + ".json");
        if (usb && sticks.isEmpty()) e.out.add("usb: no USB stick found (the report is in HMG-API's folder; adb pull works too)");
        e.out.add("issue-url: " + issueUrl(r, name));
        return 0;
    }

    /** SELinux: off (no selinuxfs) on most of these units; what a car that boots Enforcing needs to be looked at. */
    static Map<String, Object> selinux(Engine e) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String mode = "disabled";
        try {
            String v = Engine.read(new File(e.root + "/sys/fs/selinux/enforce")).trim();
            mode = v.equals("1") ? "enforcing" : v.equals("0") ? "permissive" : mode;
        } catch (Exception ignored) {
            // no selinuxfs: disabled
        }
        m.put("mode", mode);
        try {
            m.put("boot", Engine.read(new File(e.root + "/dev/.hmgapi-selinux")).trim());   // the zygote wrapper's or boot.sh's note
        } catch (Exception ignored) {
            // nothing noted: not Enforcing at start, or an older wrapper
        }
        m.put("option_on", new File(e.data, "settings/allow/selinux_permissive").isFile());
        // the kernel's command line: only its SELinux word (the rest can hold a serial number)
        try {
            for (String w : Engine.read(new File(e.root + "/proc/cmdline")).trim().split("\\s+")) {
                if (w.startsWith("androidboot.selinux=") || w.startsWith("selinux=") || w.startsWith("enforcing=")) m.put("cmdline_" + w.split("=")[0], w.split("=", 2)[1]);
            }
        } catch (Exception ignored) {
            // not readable
        }
        if (!"disabled".equals(mode)) {
            Map<String, Object> labels = new LinkedHashMap<String, Object>();
            for (String f : new String[] {"/system/bin/app_process", "/system/bin/app_process.hmgapi-stock", "/system/bin/hmgapid",
                    "/system/lib/libhmgapi_hook.so", "/system/framework/hmgapi-hook.jar", "/system/etc/install-recovery.sh"}) {
                String l = NetReport.run("ls", "-Z", e.root + f).trim();
                for (String w : l.split("\\s+")) if (w.contains(":object_r:")) labels.put(f, w);
            }
            m.put("labels", labels);
            String saved = null;
            try {
                saved = Engine.read(new File(e.data, "stock/app_process.label")).trim();
            } catch (Exception ignored) {
                // not saved (SELinux was off at install)
            }
            m.put("app_process_stock_label", saved);
            // the kernel's denials (the last 40): which part of HMG-API (or a mod) the policy stops
            List<Object> avc = new ArrayList<Object>();
            try {
                for (String l : Engine.exec("dmesg").text.split("\n")) if (l.contains("avc:")) avc.add(scrub(l));
            } catch (Exception x) {
                avc.add("[dmesg: " + x.getMessage() + "]");
            }
            m.put("denials", avc.size() > 40 ? new ArrayList<Object>(avc.subList(avc.size() - 40, avc.size())) : avc);
        }
        return m;
    }

    static String selinuxLine(Map<String, Object> se) {
        if (se == null || se.get("mode") == null) return null;
        String s = String.valueOf(se.get("mode"));
        if (se.get("boot") != null) s += " (at start: " + se.get("boot") + ")";
        if (se.get("denials") instanceof List) s += ", " + ((List<?>) se.get("denials")).size() + " denial(s) logged";
        return s;
    }

    static Map<String, Object> notNoFiles(Map<String, String> missing) {
        Map<String, Object> m = new TreeMap<String, Object>();
        for (Map.Entry<String, String> x : missing.entrySet()) if (!x.getValue().startsWith("no stock ")) m.put(x.getKey(), x.getValue());
        return m;
    }

    static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9.]", "").toLowerCase();
    }

    /** HMG-API's recent warnings and errors: hmgapid's log and the log keeper's (scrubbed, last 60) */
    static List<Object> errors(Engine e) {
        List<String> lines = new ArrayList<String>();
        try {
            File log = new File(e.data, "hmgapid.log");
            if (log.isFile()) {
                for (String l : Engine.read(log).split("\n")) {
                    String x = l.toLowerCase();
                    if (x.contains("error") || x.contains("fail") || x.contains("refused") || x.contains("exception")) lines.add(l);
                }
            }
        } catch (Exception ignored) {
            // no log
        }
        // HMG-API's own log (the log keeper: every process's HMG-API lines), warnings and errors
        for (String name : new String[] { "log.log.1", "log.log" }) {
            try {
                File k = new File(e.data, "logs/hmgapi/" + name);
                if (!k.isFile()) continue;
                for (String l : Engine.read(k).split("\n")) if (l.contains(" W/HMG-API") || l.contains(" E/HMG-API")) lines.add(l);
            } catch (Exception ignored) {
                // unreadable: left out
            }
        }
        List<Object> out = new ArrayList<Object>();
        for (int i = Math.max(0, lines.size() - 60); i < lines.size(); i++) out.add(scrub(lines.get(i)));
        return out;
    }

    static final Pattern[] SECRETS = {
        Pattern.compile("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b"),           // MAC / Bluetooth address
        Pattern.compile("\\b[A-HJ-NPR-Z0-9]{17}\\b"),                             // VIN
        Pattern.compile("\\b\\d{14,20}\\b"),                                      // IMEI, ICCID, serials
        Pattern.compile("\\+?\\d[\\d ()-]{8,}\\d"),                               // phone numbers
        Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+"),                           // email
    };

    /** A log line with anything that could identify the car or a person taken out. */
    /** what to call the car: its name, or its model code when HMG-API has no name for it yet (never "null") */
    static String carName(Map<String, Object> unit) {
        Object n = unit.get("name") != null ? unit.get("name") : unit.get("model");
        return n == null ? "unknown model" : String.valueOf(n);
    }

    static String scrub(String s) {
        for (Pattern p : SECRETS) s = p.matcher(s).replaceAll("[removed]");
        return s;
    }

    @SuppressWarnings("unchecked")
    static String markdown(Map<String, Object> r, String name) {
        Map<String, Object> unit = (Map<String, Object>) r.get("unit"), map = (Map<String, Object>) r.get("map"),
                pj = (Map<String, Object>) r.get("map_car");
        StringBuilder s = new StringBuilder();
        s.append("# Car Report: ").append(carName(unit)).append(' ').append(unit.get("region")).append(' ').append(unit.get("firmware")).append("\n\n");
        s.append("- File: `").append(name).append(".json` (attach it to the issue)\n");
        s.append("- HMG-API ").append(r.get("hmgapi")).append(", product `").append(unit.get("product")).append("`, platform ")
                .append(unit.get("platform")).append(", Android ").append(unit.get("android")).append(", Wi-Fi chip: ")
                .append(Boolean.TRUE.equals(unit.get("wifi_chip")) ? "yes" : "no").append("\n");
        s.append("- ").append(map.get("summary")).append("\n");
        String sel = selinuxLine((Map<String, Object>) r.get("selinux"));
        if (sel != null) s.append("- SELinux: ").append(sel).append("\n");
        Map<String, Object> nf = (Map<String, Object>) pj.get("not_found");
        s.append("- Mapped from this car's files: ").append(pj.get("found")).append(" points found, ")
                .append(nf == null ? 0 : nf.size()).append(" not found\n");
        String dis = disagreement(pj);
        if (dis != null) s.append("- **Check the map:** ").append(dis).append("\n");
        s.append("\n");
        Map<String, Object> fh = (Map<String, Object>) pj.get("found_here_missing_in_map");
        List<Object> df = (List<Object>) pj.get("differs_from_map");
        if (dis != null) {
            s.append("## This unit's mapper and HMG-API's map disagree\n\n");
            if (fh != null) for (Map.Entry<String, Object> x : fh.entrySet()) {
                s.append("- `").append(x.getKey()).append("`: found here; the map says missing (").append(x.getValue()).append(")\n");
            }
            if (df != null) for (Object x : df) s.append("- `").append(x).append("`: found here under another name than the map's\n");
            s.append("\n");
        }
        if (nf != null && !nf.isEmpty()) {
            s.append("## Hook points not found\n\n");
            for (Map.Entry<String, Object> x : nf.entrySet()) s.append("- `").append(x.getKey()).append("`: ").append(x.getValue()).append("\n");
            s.append("\n");
        }
        s.append("## Mods\n\n");
        for (Object o : (List<Object>) r.get("mods")) {
            Map<String, Object> m = (Map<String, Object>) o;
            s.append("- ").append(m.get("id")).append(' ').append(m.get("version")).append(Boolean.TRUE.equals(m.get("on")) ? " (on)" : " (off)");
            if (m.get("why_not") != null) s.append(": not for this car (").append(m.get("why_not")).append(")");
            if (m.get("hooks_not_loaded") != null) s.append(": hooks not loaded (").append(m.get("hooks_not_loaded")).append(")");
            s.append("\n");
        }
        Map<String, Object> net = (Map<String, Object>) r.get("network");
        if (net != null && net.get("summary") != null) {
            s.append("\n## Network\n\n- ").append(net.get("summary")).append("\n");
            List<Object> drops = (List<Object>) net.get("drops");
            if (drops != null && !drops.isEmpty()) {
                s.append("\nLast packets the firewall logged:\n\n```\n");
                for (int i = Math.max(0, drops.size() - 15); i < drops.size(); i++) s.append(drops.get(i)).append("\n");
                s.append("```\n");
            }
        }
        Map<String, Object> se = (Map<String, Object>) r.get("selinux");
        List<Object> den = se == null ? null : (List<Object>) se.get("denials");
        if (den != null && !den.isEmpty()) {
            s.append("\n## SELinux denials\n\n```\n");
            for (int i = Math.max(0, den.size() - 15); i < den.size(); i++) s.append(den.get(i)).append("\n");
            s.append("```\n");
        }
        List<Object> errs = (List<Object>) r.get("errors");
        if (!errs.isEmpty()) {
            s.append("\n## Recent HMG-API errors\n\n```\n");
            for (Object l : errs) s.append(l).append("\n");
            s.append("```\n");
        }
        return s.toString();
    }

    /** the points the map's car.json lists as missing (said) that this unit found and the map still lacks, with the map's why */
    static Map<String, Object> foundHereMissingInMap(Map<String, Object> said, Map<String, ?> foundHere, Map<String, ?> inMap) {
        Map<String, Object> out = new TreeMap<String, Object>();
        if (said == null) return out;
        for (Map.Entry<String, Object> ms : said.entrySet()) {
            if (foundHere.containsKey(ms.getKey()) && !inMap.containsKey(ms.getKey())) out.put(ms.getKey(), ms.getValue());
        }
        return out;
    }

    /**
     * One line when this unit's own mapper and HMG-API's map disagree (map_car
     * found_here_missing_in_map, differs_from_map), else null: a sign the map is wrong.
     */
    @SuppressWarnings("unchecked")
    static String disagreement(Map<String, Object> pj) {
        Map<String, Object> fh = (Map<String, Object>) pj.get("found_here_missing_in_map");
        List<Object> df = (List<Object>) pj.get("differs_from_map");
        int a = fh == null ? 0 : fh.size(), b = df == null ? 0 : df.size();
        if (a + b == 0) return null;
        StringBuilder s = new StringBuilder("this unit's mapper and HMG-API's map disagree on " + (a + b) + " point(s)");
        if (a > 0) s.append(", ").append(a).append(" found here that the map lists as missing");
        if (b > 0) s.append(", ").append(b).append(" found here under other names");
        return s.toString();
    }

    /** A link that opens a new GitHub issue with the summary filled in (short: it goes in a QR code). */
    @SuppressWarnings("unchecked")
    static String issueUrl(Map<String, Object> r, String name) throws Exception {
        Map<String, Object> unit = (Map<String, Object>) r.get("unit"), map = (Map<String, Object>) r.get("map"),
                pj = (Map<String, Object>) r.get("map_car");
        Map<String, Object> nf = (Map<String, Object>) pj.get("not_found");
        String title = "Car Report: " + carName(unit) + " " + unit.get("region") + " " + unit.get("firmware");
        String body = "Product: " + unit.get("product") + "\nHMG-API: " + r.get("hmgapi") + "\nMap: " + map.get("status")
                + (Boolean.TRUE.equals(map.get("hooks_on")) ? " (hooks on)" : " (hooks off)")
                + "\nFound on this car: " + pj.get("found") + " points, " + (nf == null ? 0 : nf.size()) + " not found"
                + (selinuxLine((Map<String, Object>) r.get("selinux")) != null && !"disabled".equals(((Map<String, Object>) r.get("selinux")).get("mode"))
                        ? "\nSELinux: " + selinuxLine((Map<String, Object>) r.get("selinux")) : "")
                + (r.get("network") instanceof Map && ((Map<String, Object>) r.get("network")).get("summary") != null
                        ? "\nNetwork: " + ((Map<String, Object>) r.get("network")).get("summary") : "")
                + "\n\nPlease attach " + name + ".json (USB stick: HMG-API reports; adb: adb pull /data/hmgapi/public/reports/).";
        // the "Car or firmware support" issue form (.github/ISSUE_TEMPLATE/2-car.yml), its summary field filled in
        return "https://github.com/" + REPO + "/issues/new?template=2-car.yml&title=" + URLEncoder.encode(title, "UTF-8")
                + "&summary=" + URLEncoder.encode(body, "UTF-8");
    }
}
