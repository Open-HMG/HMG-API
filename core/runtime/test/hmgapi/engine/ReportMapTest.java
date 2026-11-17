package hmgapi.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Car Report says when this unit's own mapper and HMG-API's map disagree (#47: the
 * map listed the Phone Projection points as missing under a borrowed class name; the unit
 * found them all).
 */
public final class ReportMapTest {
    static int checks;

    static void check(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            System.out.println("FAIL  " + what + ": wanted [" + want + "], got [" + got + "]");
            System.exit(1);
        }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Map<String, Object> said = m("p.open", "0 methods match in x.iP", "p.ready", "0 methods match in x.iP",
                "p.later", "not in the Car Report: newer than the HMG-API that made it");
        Map<String, Object> here = m("p", m("class", "x.iO"), "p.open", m("method", "aQ"), "p.ready", m("method", "ih"));
        Map<String, Object> inMap = m("p", m("class", "x.iP"), "p.ready", m("method", "ih"));   // p.ready filled on the unit
        Map<String, Object> fh = Report.foundHereMissingInMap(said, here, inMap);
        check("found here, missing in the map: only what the map says is missing and still lacks", "[p.open]", fh.keySet().toString());
        check("... with the map's own why", "0 methods match in x.iP", fh.get("p.open"));
        check("no missing list in the map: nothing", 0, Report.foundHereMissingInMap(null, here, inMap).size());

        Map<String, Object> pj = m("found", 3L, "not_found", m(), "found_here_missing_in_map", fh,
                "differs_from_map", new ArrayList<Object>(Arrays.asList("p")));
        String line = Report.disagreement(pj);
        check("one line for both kinds",
                "this unit's mapper and HMG-API's map disagree on 2 point(s), 1 found here that the map lists as missing, 1 found here under other names",
                line);
        check("agreeing: no line", null, Report.disagreement(m("found_here_missing_in_map", m(), "differs_from_map", new ArrayList<Object>())));
        check("an older report without the lists: no line", null, Report.disagreement(m()));

        Map<String, Object> r = m("hmgapi", "1.1.0-rc.4", "unit", m("make", "kia", "name", "Test", "model", "TEST", "region", "can", "firmware", "260206"),
                "map", m("summary", "hook map: probed"), "map_car", pj, "mods", new ArrayList<Object>(), "errors", new ArrayList<Object>());
        String md = Report.markdown(r, "car-report-test");
        check("the summary says to check the map", true, md.contains("- **Check the map:** " + line));
        check("... and lists each point", true, md.contains("- `p.open`: found here; the map says missing (0 methods match in x.iP)")
                && md.contains("- `p`: found here under another name than the map's"));
        pj.put("found_here_missing_in_map", m());
        pj.put("differs_from_map", new ArrayList<Object>());
        check("agreeing: no such section", false, Report.markdown(r, "car-report-test").contains("disagree"));
        System.out.println("ok    Car Report: the unit's mapper against HMG-API's map (" + checks + " checks)");
    }
}
