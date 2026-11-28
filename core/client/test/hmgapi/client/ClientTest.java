package hmgapi.client;

import org.json.JSONObject;

/**
 * HmgApi's settings calls as the daemon sees them. A service hmgapid runs as root is "root" to the
 * daemon, so it names its mod (HMGAPI_MOD); an app, or root with no valid id, doesn't.
 *   ClientTest <what> <the id expected in the calls, or "-"> [refused]   (HMGAPI_MOD and -Dtest.uid set by test.sh)
 * refused: the call goes out with no id and the daemon says no (root that isn't a service of a mod)
 */
public final class ClientTest {
    public static void main(String[] a) throws Exception {
        String what = a[0], id = "-".equals(a[1]) ? null : a[1];
        HmgApi api = HmgApi.connect();
        String get, set;
        boolean failed = false;
        try {
            JSONObject s = api.settings();
            get = FakeDaemon.last() + " -> " + s;
            api.saveSettings(new JSONObject("{\"quality\":\"low\"}"));
            set = FakeDaemon.last();
        } catch (HmgApi.HmgApiException x) {
            get = set = FakeDaemon.last() + " refused: " + x.code;
            failed = true;
        }
        String wantGet = (id == null ? "[setting-get]" : "[setting-get, " + id + "]") + " -> {\"quality\":\"high\"}";
        String wantSet = id == null ? "[setting-set, {\"quality\":\"low\"}]" : "[setting-set, " + id + ", {\"quality\":\"low\"}]";
        boolean refused = a.length > 2 && "refused".equals(a[2]);
        if (refused) wantGet = wantSet = "[setting-get] refused: 2";
        boolean ok = failed == refused && wantGet.equals(get) && wantSet.equals(set);
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": wanted [" + wantGet + " | " + wantSet + "], got [" + get + " | " + set + "]"));
        System.exit(ok ? 0 : 1);
    }
}
