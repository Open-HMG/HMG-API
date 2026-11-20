package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Research prototype (docs/research/self-mapping.md, idea 3): are this car's readings
 * plausible? Checks a run of samples of the vehicle readings (what `hmgapid vehicle`
 * answers, taken a few seconds apart) against what any EV on these platforms reports,
 * so a wrong scale or a wrong point shows on every car that runs it, not only the
 * maintainer's. Reads only: it gets samples, never asks the car for anything itself,
 * and never sends a command. Not wired into the engine or the Car Report yet.
 *
 * Samples: {"samples": [{"t": <ms>, "<reading>": "<value>" | "-", ...}, ...]}
 * (values as hmgapid vehicle prints them; "-" or absent: not known).
 *
 * Each check is pass, fail or skip (not enough data, or the car doesn't have it), with
 * the figures it saw, so a maintainer can tighten a band from many cars' reports.
 *
 *   java -cp <engine classes> hmgapi.engine.SelfTest <samples.json>   prints the result, exit 1 on a fail
 */
public final class SelfTest {
    private SelfTest() {}

    /** readings the car pushes when a listener registers: one should arrive within a run this long */
    static final String[] PUSHED_ON_REGISTER = { "drive_mode", "outside_temp" };
    static final long CALLBACK_RUN_MS = 10000;

    static Double num(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double median(List<Double> l) {
        List<Double> s = new ArrayList<Double>(l);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static String f(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    static Map<String, Object> result(String id, String about, String verdict, String detail) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("check", id);
        r.put("about", about);
        r.put("result", verdict);
        r.put("detail", detail);
        return r;
    }

    /** every value of one reading in the run, numbers only */
    static List<Double> values(List<Map<String, Object>> samples, String name) {
        List<Double> out = new ArrayList<Double>();
        for (Map<String, Object> s : samples) {
            Double v = num(s.get(name));
            if (v != null) out.add(v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> check(List<Map<String, Object>> samples) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();

        // 1. state of charge is a percentage
        List<Double> soc = values(samples, "hv.soc");
        if (soc.isEmpty()) {
            out.add(result("soc.range", "hv.soc is 0..100", "skip", "no hv.soc in the run"));
        } else {
            double lo = Collections.min(soc), hi = Collections.max(soc);
            out.add(result("soc.range", "hv.soc is 0..100", lo >= 0 && hi <= 100 ? "pass" : "fail",
                    soc.size() + " values, " + f(lo) + ".." + f(hi) + (hi > 100 && hi <= 200 ? " (raw 0.5 % steps not halved?)" : "")));
        }

        // 2. the nominal capacity is a passenger EV's (a scale off by 2 or 1000 shows)
        List<Double> cap = values(samples, "hv.capacity_nominal");
        Double capacity = cap.isEmpty() ? null : median(cap);
        if (capacity == null) {
            out.add(result("energy.capacity", "hv.capacity_nominal is 20..120 kWh", "skip", "no hv.capacity_nominal in the run"));
        } else {
            out.add(result("energy.capacity", "hv.capacity_nominal is 20..120 kWh", capacity >= 20000 && capacity <= 120000 ? "pass" : "fail",
                    f(capacity) + " Wh"));
        }

        // 3. energy left ~ state of charge x capacity
        List<Double> ratios = new ArrayList<Double>();
        for (Map<String, Object> s : samples) {
            Double e = num(s.get("hv.energy")), c = num(s.get("hv.soc"));
            if (e != null && c != null && c >= 20 && c <= 100 && capacity != null && capacity > 0) ratios.add(e / (c / 100.0 * capacity));
        }
        if (ratios.isEmpty()) {
            out.add(result("energy.vs_soc", "hv.energy is about hv.soc % of hv.capacity_nominal (0.80..1.10 of it)", "skip",
                    "needs hv.energy, hv.capacity_nominal and hv.soc of 20 % or more in the same samples"));
        } else {
            double m = median(ratios);
            out.add(result("energy.vs_soc", "hv.energy is about hv.soc % of hv.capacity_nominal (0.80..1.10 of it)",
                    m >= 0.80 && m <= 1.10 ? "pass" : "fail", "median ratio " + f(m) + " over " + ratios.size() + " samples"
                            + (m > 0.45 && m < 0.55 ? " (half: hv.energy's 2 Wh steps not doubled?)"
                                    : m > 1.8 && m < 2.2 ? " (twice: hv.capacity_nominal's 2 Wh steps not doubled?)" : "")));
        }

        // 4. range per % of charge is an EV's, and 5. range moves with the state of charge
        List<Double> perSoc = new ArrayList<Double>(), xs = new ArrayList<Double>(), ys = new ArrayList<Double>();
        for (Map<String, Object> s : samples) {
            Double r = num(s.get("range")), c = num(s.get("hv.soc"));
            if (r == null || c == null) continue;
            xs.add(c);
            ys.add(r);
            if (c >= 10) perSoc.add(r / c);
        }
        if (perSoc.isEmpty()) {
            out.add(result("range.per_soc", "range is 0.5..10 km per % of charge", "skip", "needs range and hv.soc of 10 % or more"));
        } else {
            double m = median(perSoc);
            out.add(result("range.per_soc", "range is 0.5..10 km per % of charge", m >= 0.5 && m <= 10 ? "pass" : "fail",
                    "median " + f(m) + " km per %"));
        }
        double spread = xs.isEmpty() ? 0 : Collections.max(xs) - Collections.min(xs);
        if (xs.size() < 5 || spread < 3) {
            out.add(result("range.tracks_soc", "range rises and falls with hv.soc (correlation 0.6 or more)", "skip",
                    "needs 5 samples with hv.soc changing by 3 % or more (" + xs.size() + " samples, " + f(spread) + " %)"));
        } else {
            double r = pearson(xs, ys);
            out.add(result("range.tracks_soc", "range rises and falls with hv.soc (correlation 0.6 or more)", r >= 0.6 ? "pass" : "fail",
                    "correlation " + f(r) + " over " + xs.size() + " samples, hv.soc " + f(Collections.min(xs)) + ".." + f(Collections.max(xs))));
        }

        // 6. charge limits are percentages in 0.5 steps
        for (String t : new String[] { "charge.target_ac", "charge.target_dc" }) {
            List<Double> v = values(samples, t);
            if (v.isEmpty()) {
                out.add(result(t + ".range", t + " is 0..100 in 0.5 steps", "skip", "no " + t + " in the run"));
                continue;
            }
            boolean ok = true;
            for (double x : v) if (x < 0 || x > 100 || Math.rint(x * 2) != x * 2) ok = false;
            out.add(result(t + ".range", t + " is 0..100 in 0.5 steps", ok ? "pass" : "fail", f(Collections.min(v)) + ".." + f(Collections.max(v))));
        }

        // 7. aux.power is its three parts
        int sums = 0, bad = 0;
        for (Map<String, Object> s : samples) {
            Double p = num(s.get("aux.power")), a = num(s.get("aux.climate_power")), b = num(s.get("aux.other_ecu_power")),
                    c = num(s.get("aux.battery_conditioning_power"));
            if (p == null || a == null || b == null || c == null) continue;
            sums++;
            if (Math.abs(p - (a + b + c)) > 0.5) bad++;
        }
        out.add(sums == 0 ? result("aux.sum", "aux.power is the three aux.* draws added up", "skip", "no sample with all four")
                : result("aux.sum", "aux.power is the three aux.* draws added up", bad == 0 ? "pass" : "fail", bad + " of " + sums + " samples differ"));

        // 8. listener callbacks arrive: values the car pushes when a listener registers
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (Map<String, Object> s : samples) {
            Double t = num(s.get("t"));
            if (t == null) continue;
            first = Math.min(first, t.longValue());
            last = Math.max(last, t.longValue());
        }
        long run = first == Long.MAX_VALUE ? 0 : last - first;
        for (String name : PUSHED_ON_REGISTER) {
            boolean asked = false;
            for (Map<String, Object> s : samples) if (s.containsKey(name)) asked = true;
            if (!asked) {
                out.add(result("callback." + name, name + " arrives from the car's listener", "skip", name + " wasn't sampled"));
            } else if (!values(samples, name).isEmpty()) {
                out.add(result("callback." + name, name + " arrives from the car's listener", "pass", "heard"));
            } else if (run < CALLBACK_RUN_MS) {
                out.add(result("callback." + name, name + " arrives from the car's listener", "skip", "not heard, but the run is only " + run + " ms"));
            } else {
                out.add(result("callback." + name, name + " arrives from the car's listener", "fail",
                        "nothing in " + run + " ms: the listener isn't registered, or its point is another transaction on this car"));
            }
        }
        return out;
    }

    static double pearson(List<Double> x, List<Double> y) {
        int n = x.size();
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x.get(i);
            my += y.get(i);
        }
        mx /= n;
        my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - mx, dy = y.get(i) - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxx == 0 || syy == 0 ? 0 : sxy / Math.sqrt(sxx * syy);
    }

    /** the Car Report's "self_test": every check, and "fail" if any failed */
    public static Map<String, Object> report(List<Map<String, Object>> samples) {
        List<Map<String, Object>> checks = check(samples);
        String overall = "pass";
        for (Map<String, Object> c : checks) if ("fail".equals(c.get("result"))) overall = "fail";
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("result", overall);
        r.put("samples", (long) samples.size());
        r.put("checks", checks);
        return r;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        Map<String, Object> in = Json.object(Engine.read(new File(a[0])));
        Map<String, Object> r = report((List<Map<String, Object>>) in.get("samples"));
        System.out.println(Json.write(r));
        System.exit("fail".equals(r.get("result")) ? 1 : 0);
    }
}
