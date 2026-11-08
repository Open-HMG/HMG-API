package hmgapi.engine;

/**
 * Version order and ranges (docs/releasing.md). With "order" it prints ORDER, one version a
 * line, for tools/version-test.sh to check the Python tools against; with "pv <version>..."
 * NemesisSync.packageVersion of each. With no arguments, its own checks.
 */
public final class VersionTest {
    static int fail;

    /** ascending, by semver precedence */
    static final String[] ORDER = {
        "0.9.0", "1.0.0-alpha.1", "1.0.0-beta.4", "1.0.0", "1.0.2",
        "1.1.0-alpha", "1.1.0-alpha.1", "1.1.0-alpha.2", "1.1.0-alpha.beta", "1.1.0-beta", "1.1.0-beta.1", "1.1.0-beta.2",
        "1.1.0-beta.11", "1.1.0-rc.1", "1.1.0-rc.2", "1.1.0-rc.9", "1.1.0-rc.10", "1.1.0-rc.11", "1.1.0-rc.100", "1.1.0",
        "1.1.1-rc.1", "1.1.1", "1.2.0-beta.1", "1.10.0", "2.0.0-rc.1", "2.0.0",
    };

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static int cmp(String a, String b) {
        return new Version(a).compareTo(new Version(b));
    }

    static void sat(String v, String range, boolean want) {
        check("\"" + v + "\" " + (want ? "meets" : "doesn't meet") + " \"" + range + "\"", Version.satisfies(v, range) == want);
    }

    static void core(String v, String range, boolean want) {
        check("HMG-API " + v + (want ? " meets" : " doesn't meet") + " \"hmgapi\": \"" + range + "\"", Version.meetsHmgapi(v, range) == want);
    }

    public static void main(String[] a) {
        if (a.length > 0 && "order".equals(a[0])) {
            for (String v : ORDER) System.out.println(v);
            return;
        }
        if (a.length > 0 && "pv".equals(a[0])) {
            for (int i = 1; i < a.length; i++) {
                try {
                    System.out.println(NemesisSync.packageVersion(a[i]));
                } catch (IllegalArgumentException e) {
                    System.out.println("none");
                }
            }
            return;
        }
        // every pair of the list, both ways
        boolean ok = true;
        for (int i = 0; i < ORDER.length; i++) {
            for (int j = 0; j < ORDER.length; j++) {
                int c = cmp(ORDER[i], ORDER[j]), want = Integer.signum(i - j);
                if (Integer.signum(c) != want) {
                    System.out.println("FAIL  " + ORDER[i] + " vs " + ORDER[j] + ": " + c + ", wanted " + want);
                    ok = false;
                }
            }
        }
        check("the " + ORDER.length + " versions sort in semver order, every pair", ok);
        check("rc.9 < rc.10 (numbers as numbers)", cmp("1.1.0-rc.9", "1.1.0-rc.10") < 0);
        check("alpha < beta < rc", cmp("1.1.0-alpha.3", "1.1.0-beta.1") < 0 && cmp("1.1.0-beta.9", "1.1.0-rc.1") < 0);
        check("any tag < no tag", cmp("1.1.0-rc.99", "1.1.0") < 0 && cmp("1.1.0", "1.1.0-zzz") > 0);
        check("numbers before words (rc.1 < rc.a)", cmp("1.1.0-rc.1", "1.1.0-rc.a") < 0);
        check("fewer parts first (rc < rc.1)", cmp("1.1.0-rc", "1.1.0-rc.1") < 0);
        check("same version, same tag: equal", cmp("1.1.0-rc.2", "v1.1.0-rc.2") == 0);
        check("+build ignored", cmp("1.1.0+abc", "1.1.0") == 0 && cmp("1.1.0-rc.1+x", "1.1.0-rc.1") == 0);
        check("a leading zero is a word, not a number (semver)", cmp("1.1.0-rc.01", "1.1.0-rc.2") > 0);
        check("toString keeps the tag", "1.1.0-rc.10".equals(new Version("v1.1.0-rc.10").toString()));
        check("missing parts are 0", cmp("1.1", "1.1.0") == 0);

        // ranges with a pre-release floor
        sat("1.1.0-rc.1", ">=1.1.0-rc.1", true);
        sat("1.1.0-rc.2", ">=1.1.0-rc.1", true);
        sat("1.1.0-rc.10", ">=1.1.0-rc.9", true);
        sat("1.1.0", ">=1.1.0-rc.1", true);
        sat("1.2.0", ">=1.1.0-rc.1", true);
        sat("1.1.0-beta.2", ">=1.1.0-rc.1", false);
        sat("1.0.2", ">=1.1.0-rc.1", false);
        sat("1.1.0-rc.1", ">=1.1.0", false);
        sat("1.1.0-rc.2", ">=1.1.0-rc.1 <1.2.0", true);
        sat("1.1.0-rc.3", "<1.1.0-rc.3", false);
        sat("1.1.0-rc.3", "=1.1.0-rc.3", true);
        sat("1.1.2", "^1.1.0-rc.1", true);
        sat("2.0.0", "^1.1.0-rc.1", false);
        sat("1.1.5", "~1.1.0-rc.1", true);
        sat("1.2.0", "~1.1.0-rc.1", false);

        // a mod's "hmgapi" against the core version: a final floor is met by that release's
        // pre-releases (mods made for 1.1.0 run on its rcs), a pre-release floor by the full version
        core("1.1.0-rc.1", ">=1.1.0", true);
        core("1.1.0-beta.1", ">=1.0.3", true);
        core("1.0.2", ">=1.1.0", false);
        core("1.1.0-rc.1", ">=1.1.0-rc.1", true);
        core("1.1.0-rc.2", ">=1.1.0-rc.1", true);
        core("1.1.0", ">=1.1.0-rc.1", true);
        core("1.1.0-beta.1", ">=1.1.0-rc.1", false);
        core("1.1.0-rc.9", ">=1.1.0-rc.10", false);
        core("1.1.0-rc.10", ">=1.1.0-rc.9", true);
        core("1.1.0", "", true);
        core("1.1.0", null, true);

        if (fail != 0) System.exit(1);
    }
}
