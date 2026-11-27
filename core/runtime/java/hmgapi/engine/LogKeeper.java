package hmgapi.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HMG-API's log keeper, run by hmgapid as HMG-API's own service "log" from boot: its
 * output is HMG-API's log tag (every process: the zygote's hooks, system_server, apps,
 * mods' "[mod] ..." lines) as logcat sees it, which hmgapid keeps in
 * /data/hmgapi/logs/hmgapi/log.log (256 KB, then .1). logcat itself holds only seconds
 * on these units (the CAN status lines fill it), so this is what the app's Logs tab
 * and the Car Report read. Lines are kept as they are: the Car Report scrubs what it
 * takes from them.
 *
 * A mod logging on a hot path mustn't push HMG-API's own lines out of the file: each mod's
 * lines ("[<id>] ...") are kept up to MOD_LINES a minute, the rest of that minute left out
 * with one line saying so. HMG-API's own lines are always kept. hmgapid caps the file itself
 * (256 KB, then .1; at most 256 KB a minute).
 */
public final class LogKeeper {
    private LogKeeper() {}

    static final int MOD_LINES = 300;
    static final long WINDOW = 60000000000L;   // ns

    public static void main(String[] a) throws Exception {
        Process p = new ProcessBuilder("/system/bin/logcat", "-v", "time", "-s", "HMG-API:V").redirectErrorStream(true).start();
        BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        Throttle t = new Throttle(MOD_LINES);
        String l;
        while ((l = in.readLine()) != null) {
            if (l.startsWith("---------")) continue;   // logcat's buffer headers
            for (String out : t.line(l, System.nanoTime())) System.out.println(out);
        }
        System.exit(p.waitFor());   // restarted by hmgapid
    }

    /** "[<id>]" for a mod's line ("... I/HMG-API( 123): [<id>] text"), null for HMG-API's own */
    static String source(String l) {
        int i = l.indexOf("): ");
        if (i < 0 || i + 3 >= l.length() || l.charAt(i + 3) != '[') return null;
        int e = l.indexOf(']', i + 3);
        if (e < 0 || e - i > 72) return null;
        String id = l.substring(i + 4, e);
        return id.matches("[a-z0-9][a-z0-9-]*") && !"hmgapi".equals(id) ? id : null;
    }

    /** each mod's lines up to max per window; what to print for each line read */
    static final class Throttle {
        final int max;
        final Map<String, int[]> counts = new LinkedHashMap<String, int[]>();   // id -> {kept, left out}
        long start = Long.MIN_VALUE;

        Throttle(int max) {
            this.max = max;
        }

        java.util.List<String> line(String l, long now) {
            java.util.List<String> out = new java.util.ArrayList<String>(2);
            if (start == Long.MIN_VALUE || now - start >= WINDOW) {
                for (Map.Entry<String, int[]> c : counts.entrySet()) {
                    if (c.getValue()[1] > 0) out.add("HMG-API log keeper: [" + c.getKey() + "] " + c.getValue()[1] + " lines left out");
                }
                counts.clear();
                start = now;
            }
            String id = source(l);
            if (id == null) {
                out.add(l);
                return out;
            }
            int[] c = counts.get(id);
            if (c == null) counts.put(id, c = new int[2]);
            if (c[0] < max) {
                c[0]++;
                out.add(l);
            } else {
                if (c[1]++ == 0) out.add("HMG-API log keeper: [" + id + "] logs more than " + max + " lines a minute: the rest of this minute is left out");
            }
            return out;
        }
    }
}
