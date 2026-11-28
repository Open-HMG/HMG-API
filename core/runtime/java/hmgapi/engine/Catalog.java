package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The mod catalogue and updates over home Wi-Fi: no server of our own, only GitHub. The catalogue is
 * HMG-API's catalog/ folder (catalog/<id>.json per mod, tools/catalog.py; catalog/index.json when the
 * repo has one, else GitHub's folder listing); HMG-API itself comes from its GitHub releases.
 *
 *   engine catalog [--refresh]              the catalogue and what each mod would do here (JSON, for the app)
 *   engine catalog-plan <id>... [--update] | --update --all
 *                                           what installing (updating) them takes: their requires, in order (JSON)
 *   engine catalog-install <id>...          download, check and install them with what they require
 *   engine updates [--refresh]              installed mods (and HMG-API) with a newer version (JSON)
 *   engine update <id>... | --all | hmgapi [--force]
 *                                           update them (with any new requires); hmgapi: HMG-API itself
 *   engine update-check                     wifi.sh, each time home Wi-Fi comes up (at most every 6 hours):
 *                                           refresh, and stage the mods' updates if the owner allowed it
 *   engine update-source <id> catalogue | releases | off | default
 *                                           where an installed mod's updates come from (the owner's choice)
 *   overrides, the owner's own choice, per mod: --any-car=<id>,... (not listed for this head unit),
 *   --any-author=<id>,... (a first install by an author key that isn't on this unit's trusted list);
 *   --any-car alone: every mod in the batch
 *
 * Network: home Wi-Fi only (NativeFetch: its socket is marked for table 95), never the car's
 * mobile data; a refresh refuses to start unless home Wi-Fi is up.
 *
 * Trust: the catalogue isn't signed, so an entry only says where to look and what to expect. Its
 * download must have its SHA-256, and Packages checks its signature: an update needs the installed
 * mod's author key; a first install needs the entry's key, and that key must already be on this
 * unit's trusted author list (maps/authors.json, which only HMG-API's own signed releases bring:
 * never refreshed from the catalogue), else the owner says yes per mod (--any-author=<id>).
 * HMG-API itself needs the release key (UsbUpdates). Every package is checked (Packages.check)
 * before the first one goes in. Nothing is applied here: the owner applies (engine apply) as after
 * any install.
 *
 * Mods outside the catalogue: their own GitHub releases (Releases), when their mod.json names the
 * repo. Each installed mod has one update source (source()): the catalogue, its releases, or none.
 * A maintainer can stop a version on both: catalog/withdrawn.json (withdrawn()).
 *
 * Dependencies: a mod's "requires" come from its entry (catalog.py copies them) and from the
 * downloaded package's mod.json (kept in learned.json, so the next plan has them); each is met by
 * what's installed, or by the catalogue's version, installed first. A required mod that's turned off
 * is turned on. After planning, every mod that will be on is checked against the result: its
 * requires met, and no conflicts either way. When a package requires more than the plan the owner
 * saw, nothing is installed and the new plan is handed back (exit 5) for a second OK.
 *
 * The automatic check stages only pure updates (nothing else installed or turned on, signed with the
 * installed key) into /data/hmgapi/pending/<id>, which the hook loader and apps never read; engine
 * apply puts them in place (the previous version kept in /data/hmgapi/previous/<id>) right before
 * it plans, so new code never runs next to the old version's files.
 */
final class Catalog {
    static final String BASE = "https://raw.githubusercontent.com/Open-HMG/HMG-API/main/catalog/";
    static final String RELEASES = "https://api.github.com/repos/Open-HMG/HMG-API/releases?per_page=15";
    static final int MAX_JSON = 1 << 20, MAX_ENTRY = 64 << 10, MAX_ENTRIES = 500;
    static final String ID = "[a-z0-9][a-z0-9-]{0,63}";
    /** a plan or install refreshes a catalogue older than this when it can */
    static final long STALE_MS = 15 * 60 * 1000L;
    /** update-check: at most one every 6 hours (each is 2 of GitHub's 60 API calls an hour, and one per repo of mods updated from their releases) */
    static final long CHECK_GAP_S = 6 * 3600;
    /** catalog-install / update: the packages require more than the plan said; the new plan is in the output */
    static final int PLAN_CHANGED = 5;
    static final String FROM_CATALOGUE = "catalogue", FROM_RELEASES = "releases", FROM_NONE = "off";

    final Engine e;
    final File dir, cacheFile, updatesFile, learnedFile;
    Map<String, Object> cache;

    Catalog(Engine e) {
        this.e = e;
        dir = new File(e.data, "catalog");
        cacheFile = new File(dir, "catalog.json");
        updatesFile = new File(dir, "updates.json");
        learnedFile = new File(dir, "learned.json");
    }

    /** the owner's per-mod choices for this run */
    static final class Overrides {
        boolean anyCarAll;
        final Set<String> anyCar = new LinkedHashSet<String>(), anyAuthor = new LinkedHashSet<String>();

        boolean car(String id) {
            return anyCarAll || anyCar.contains(id);
        }
    }

    // ---- the ops

    @SuppressWarnings("unchecked")
    static int run(Engine e, String[] args) throws Exception {
        Catalog c = new Catalog(e);
        String cmd = args[0];
        List<String> ids = new ArrayList<String>();
        boolean refresh = false, all = false, update = "update".equals(cmd), force = false;
        Overrides ov = new Overrides();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--refresh".equals(a)) refresh = true;
            else if ("--any-car".equals(a)) ov.anyCarAll = true;
            else if (a.startsWith("--any-car=") && idList(a.substring(10), ov.anyCar)) continue;
            else if (a.startsWith("--any-author=") && idList(a.substring(13), ov.anyAuthor)) continue;
            else if ("--all".equals(a)) all = true;
            else if ("--update".equals(a) && "catalog-plan".equals(cmd)) update = true;
            else if ("--force".equals(a) && "update".equals(cmd)) force = true;
            else if (a.matches(ID)) ids.add(a);
            else {
                e.out.add("not a mod id: " + a);
                return 2;
            }
        }
        c.load();
        if ("catalog".equals(cmd) || "updates".equals(cmd)) {
            String err = refresh ? c.refresh() : null;
            Map<String, Object> rep = "catalog".equals(cmd) ? c.report() : c.updates();
            if (err != null) rep.put("error", err);
            e.out.add(Json.write(rep));
            return 0;
        }
        if ("update-check".equals(cmd)) return c.updateCheck();
        if ("update-source".equals(cmd)) return c.setSource(args);
        // planning and installing: the catalogue as it is now, when home Wi-Fi is up
        String stale = null;
        if (c.cache == null || (c.offline() == null && c.stale())) {
            String err = c.refresh();
            if (err != null && c.cache == null) {
                e.out.add("no catalogue yet: " + err);
                return 1;
            }
            if (err != null) stale = "the catalogue couldn't be checked again (" + err + "): this is the last check's";
        }
        if ("update".equals(cmd) && ids.remove("hmgapi")) {
            if (!ids.isEmpty() || all) {
                e.out.add("update HMG-API on its own: engine update hmgapi");
                return 2;
            }
            return c.updateHmgApi(force);
        }
        if (update && all) {
            for (Object o : (List<Object>) c.updates().get("mods")) ids.add(String.valueOf(((Map<String, Object>) o).get("id")));
            if (ids.isEmpty() && "update".equals(cmd)) {
                e.out.add("every installed mod is up to date with the catalogue");
                return 0;
            }
        }
        if (ids.isEmpty() && !(update && all)) {
            e.out.add("usage: engine " + cmd + " <id>..." + ("update".equals(cmd) ? " | --all | hmgapi" : "catalog-plan".equals(cmd) ? " [--update] | --update --all" : ""));
            return 2;
        }
        Plan p = update && all ? c.planEach(ids, ov) : c.plan(ids, update, ov);
        if (stale != null) p.notes.add(stale);
        if ("catalog-plan".equals(cmd)) {
            e.out.add(Json.write(p.toJson()));
            return 0;
        }
        if (e.journalFile.exists()) {
            e.out.add("an earlier apply isn't finished or is a trial: run `engine keep` (trial) or `engine recover` first");
            return 1;
        }
        return c.execute(p);
    }

    static boolean idList(String s, Set<String> into) {
        for (String x : s.split(",")) {
            if (!x.matches(ID)) return false;
            into.add(x);
        }
        return true;
    }

    // ---- the catalogue: fetched, cached in /data/hmgapi/catalog/catalog.json

    @SuppressWarnings("unchecked")
    void load() {
        try {
            if (cacheFile.isFile()) cache = Json.object(Engine.read(cacheFile));
        } catch (Exception x) {
            cache = null;   // unreadable: fetched again
        }
    }

    /** older than STALE_MS, or checked "in the future" (the clock was set back since) */
    boolean stale() {
        long now = System.currentTimeMillis(), t = checked();
        return now - t > STALE_MS || now < t;
    }

    long checked() {
        Object t = cache == null ? null : cache.get("checked");
        return t instanceof Number ? ((Number) t).longValue() : 0;
    }

    /** the catalogue's folder: hmgapi.json "catalog": {"base": ...} (a fork, a test), else HMG-API's own */
    @SuppressWarnings("unchecked")
    String base() {
        Object c = e.hmgapi.get("catalog");
        if (c instanceof Map && ((Map<String, Object>) c).get("base") instanceof String) {
            String b = (String) ((Map<String, Object>) c).get("base");
            if (b.startsWith("https://") && b.endsWith("/")) return b;
        }
        return BASE;
    }

    /** hmgapi.json "catalog": {"prereleases": true}: every pre-release offered */
    @SuppressWarnings("unchecked")
    boolean prereleases() {
        Object c = e.hmgapi.get("catalog");
        return c instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) c).get("prereleases"));
    }

    /**
     * Is HMG-API's pre-release v offered here? With the owner's opt-in, any; else only the next ones of
     * the pre-release this unit is on (1.1.0-rc.10: 1.1.0-rc.11, never 1.2.0-alpha.1).
     */
    boolean offered(Version v) {
        if (prereleases()) return true;
        Version have = new Version(installedHmgApi());
        return !have.tag.isEmpty() && Arrays.equals(have.n, v.n);
    }

    String installedHmgApi() {
        return UsbUpdates.installedVersion(e);
    }

    /** null when home Wi-Fi is up (or the tests' web is there), else why not */
    String offline() {
        if (e.host && Engine.env("HMGAPI_TEST_WEB", null) != null) return null;
        File f = new File(e.data, "public/wifi.json");
        try {
            Object st = f.isFile() ? Json.object(Engine.read(f)).get("state") : null;
            if ("up".equals(st)) return null;
            if (st == null || "off".equals(st)) return "Home Wi-Fi is off (HMG-API settings): HMG-API only goes online through it";
            return "home Wi-Fi isn't connected (" + st + "): it joins while the car is off, or with Connect now in P";
        } catch (Exception x) {
            return "home Wi-Fi's state can't be read";
        }
    }

    /** a name for a file written whole and renamed into place: its own per run (a refresh may run next to another) */
    static File tmpName(File dir, String name) {
        return new File(dir, "." + name + "." + System.nanoTime() + ".tmp");
    }

    static void writeWhole(File f, String text, boolean world) throws IOException {
        f.getParentFile().mkdirs();
        File n = tmpName(f.getParentFile(), f.getName());
        Engine.write(n, text);
        if (world) n.setReadable(true, false);
        if (!n.renameTo(f)) {
            n.delete();
            throw new IOException("can't write " + f);
        }
    }

    /** Fetches the catalogue and HMG-API's releases; null when it worked, else why not (the old cache stays). */
    @SuppressWarnings("unchecked")
    String refresh() throws Exception {
        String off = offline();
        if (off != null) return off;
        List<String> why = new ArrayList<String>();
        Http.Transport t = NativeFetch.forEngine(e, why);
        if (t == null) return why.get(0);
        File tmp = new File(dir, "tmp-" + System.nanoTime());   // a refresh may run next to another (the app's, wifi.sh's)
        tmp.mkdirs();
        try {
            Http http = new Http(t, tmp);
            List<String> notes = new ArrayList<String>();
            List<Object> mods = new ArrayList<Object>();
            Map<String, Map<String, Object>> before = entries();
            String base = base();
            File f = new File(tmp, "index.json");
            Http.Result r = http.get(base + "index.json", f, MAX_JSON);
            if (r.ok()) {
                Object idx;
                try {
                    idx = Json.parse(Engine.read(f));
                } catch (Exception x) {
                    return "the catalogue's index.json isn't JSON (" + x.getMessage() + ")";
                }
                Object list = idx instanceof Map ? ((Map<String, Object>) idx).get("mods") : idx;
                if (!(list instanceof List)) return "the catalogue's index.json isn't a list of mods";
                for (Object o : (List<Object>) list) add(mods, o, notes);
            } else if (r.status == 404) {
                // no index: GitHub's listing of the folder, then each entry from raw (one API call)
                String api = listing(base);
                if (api == null) return "no catalog/index.json at " + base;
                r = http.get(api, f, MAX_JSON);
                if (!r.ok()) return "the catalogue's listing: " + r;
                Object list;
                try {
                    list = Json.parse(Engine.read(f));
                } catch (Exception x) {
                    return "the catalogue's listing isn't JSON";
                }
                if (!(list instanceof List)) return "the catalogue's listing isn't a list";
                for (Object o : (List<Object>) list) {
                    if (!(o instanceof Map)) continue;
                    String name = String.valueOf(((Map<String, Object>) o).get("name"));
                    if (!name.matches(ID + "\\.json") || "index.json".equals(name) || "withdrawn.json".equals(name)) continue;
                    if (mods.size() >= MAX_ENTRIES) break;
                    File ef = new File(tmp, name);
                    Http.Result er = http.get(base + name, ef, MAX_ENTRY);
                    String bad = er.ok() ? null : er.toString();
                    Object en = null;
                    if (bad == null) {
                        try {
                            en = Json.parse(Engine.read(ef));
                        } catch (Exception x) {
                            bad = "not JSON";
                        }
                    }
                    if (bad != null) {
                        // one entry that didn't come: the last check's stays, rather than the mod vanishing
                        Map<String, Object> old = before.get(name.substring(0, name.length() - 5));
                        notes.add(name + ": " + bad + (old != null ? " (the last check's entry kept)" : ""));
                        if (old != null) add(mods, old, notes);
                        continue;
                    }
                    add(mods, en, notes);
                }
            } else {
                return "the catalogue: " + r;
            }
            Map<String, Object> rel = null;
            String relErr = null;
            try {
                rel = latestRelease(http, tmp);
            } catch (Exception x) {
                relErr = x.getMessage();
            }
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("checked", System.currentTimeMillis());
            c.put("base", base);
            c.put("mods", mods);
            if (rel != null) c.put("hmgapi", rel);
            else if (relErr != null && cache != null && cache.get("hmgapi") != null) c.put("hmgapi", cache.get("hmgapi"));   // kept
            if (relErr != null) notes.add("HMG-API's releases: " + relErr);
            // what maintainers withdrew: a 404 is none; anything else keeps the last check's
            File wf = new File(tmp, "withdrawn.json");
            Http.Result wr = http.get(base + "withdrawn.json", wf, MAX_JSON);
            Object wd = null;
            if (wr.ok()) {
                try {
                    Object w = Json.parse(Engine.read(wf));
                    wd = w instanceof Map ? ((Map<String, Object>) w).get("withdrawn") : w;
                } catch (Exception x) {
                    wd = null;
                }
                if (!(wd instanceof List)) notes.add("the catalogue's withdrawn.json isn't a list: the last check's kept");
            } else if (wr.status == 404) {
                wd = new ArrayList<Object>();
            } else {
                notes.add("the catalogue's withdrawn.json: " + wr + " (the last check's kept)");
            }
            if (!(wd instanceof List) && cache != null && cache.get("withdrawn") instanceof List) wd = cache.get("withdrawn");
            if (wd instanceof List) c.put("withdrawn", wd);
            cache = c;   // the new catalogue first: which mods it lists decides their source
            Set<String> listed = new HashSet<String>();
            for (Object o : mods) listed.add(String.valueOf(((Map<String, Object>) o).get("id")));
            List<Releases.Mod> fromReleases = new ArrayList<Releases.Mod>();
            for (String id : installed().keySet()) {
                Releases.Mod m = Releases.mod(e, id);
                if (m != null && FROM_RELEASES.equals(source(id, m, listed))) fromReleases.add(m);
            }
            Releases.check(this, http, tmp, fromReleases, notes);
            if (!notes.isEmpty()) c.put("notes", notes);
            writeWhole(cacheFile, Json.write(c), false);
            writeUpdates();
            return null;
        } finally {
            Engine.rm(tmp);
        }
    }

    /** GitHub's API listing for a raw.githubusercontent.com folder, or null */
    static String listing(String base) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https://raw\\.githubusercontent\\.com/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)/([A-Za-z0-9._/-]+?)/((?:[A-Za-z0-9._-]+/)*)$")
                .matcher(base);
        if (!m.matches()) return null;
        String path = m.group(4).isEmpty() ? "" : m.group(4).substring(0, m.group(4).length() - 1);
        return "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2) + "/contents/" + path + "?ref=" + m.group(3);
    }

    /** one entry, kept if it is well-formed (what install relies on), with a note if not */
    @SuppressWarnings("unchecked")
    static void add(List<Object> mods, Object o, List<String> notes) {
        if (!(o instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) o;
        String id = String.valueOf(m.get("id"));
        String bad = null;
        if (!id.matches(ID) || "hmgapi".equals(id)) bad = "bad id";
        else if (!(m.get("version") instanceof String) || !valid((String) m.get("version"))) bad = "bad version";
        else if (!(m.get("download") instanceof String) || !((String) m.get("download")).startsWith("https://")) bad = "download isn't https";
        else if (!(m.get("sha256") instanceof String) || !((String) m.get("sha256")).matches("[0-9a-f]{64}")) bad = "bad sha256";
        else if (!(m.get("uuid") instanceof String)) bad = "no author key";
        for (Object x : mods) if (id.equals(((Map<String, Object>) x).get("id"))) bad = "listed twice";
        if (bad != null) notes.add(id + ": left out (" + bad + ")");
        else mods.add(m);
    }

    static boolean valid(String v) {
        try {
            new Version(v);
            return v.matches("v?\\d+(\\.\\d+){0,2}([-+][0-9A-Za-z.+-]+)?");
        } catch (IllegalArgumentException x) {
            return false;
        }
    }

    /**
     * HMG-API's newest release this unit is offered (pre-releases as offered() says): its update zip
     * and the SHA-256 from its SHA256SUMS; null if none is newer than what's installed.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> latestRelease(Http http, File tmp) throws Exception {
        File f = new File(tmp, "releases.json");
        Http.Result r = http.get(RELEASES, f, MAX_JSON);
        if (!r.ok()) throw new IOException(r.toString());
        Object list = Json.parse(Engine.read(f));
        if (!(list instanceof List)) throw new IOException("not a list of releases");
        Map<String, Object> best = null;
        Version bv = new Version(installedHmgApi());
        for (Object o : (List<Object>) list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> rel = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(rel.get("draft"))) continue;
            String tag = String.valueOf(rel.get("tag_name"));
            if (!tag.matches("v\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?")) continue;
            Version v = new Version(tag);
            if ((Boolean.TRUE.equals(rel.get("prerelease")) || !v.tag.isEmpty()) && !offered(v)) continue;
            if (v.compareTo(bv) <= 0) continue;
            String zip = null, sums = null;
            Object assets = rel.get("assets");
            for (Object a : assets instanceof List ? (List<Object>) assets : new ArrayList<Object>()) {
                Map<String, Object> am = (Map<String, Object>) a;
                String name = String.valueOf(am.get("name")), url = String.valueOf(am.get("browser_download_url"));
                if (name.equals("hmgapi-" + tag.substring(1) + "-update.zip")) zip = url;
                if (name.equals("SHA256SUMS")) sums = url;
            }
            if (zip == null || sums == null) continue;
            best = new LinkedHashMap<String, Object>();
            best.put("version", tag.substring(1));
            best.put("prerelease", Boolean.TRUE.equals(rel.get("prerelease")));
            best.put("download", zip);
            best.put("sums", sums);
            best.put("page", String.valueOf(rel.get("html_url")));
            String body = rel.get("body") instanceof String ? ((String) rel.get("body")).trim() : "";   // the release notes, for the app
            if (body.length() > 6000) body = body.substring(0, 6000) + "\n…";
            if (!body.isEmpty()) best.put("notes", body);
            else best.remove("notes");
            bv = v;
        }
        if (best == null) return null;
        File s = new File(tmp, "SHA256SUMS");
        r = http.get((String) best.get("sums"), s, MAX_ENTRY);
        if (!r.ok()) throw new IOException("SHA256SUMS: " + r);
        String want = "hmgapi-" + best.get("version") + "-update.zip";
        for (String l : Engine.read(s).split("\n")) {
            String[] p = l.trim().split("\\s+");
            if (p.length == 2 && p[1].replaceFirst("^\\*", "").equals(want) && p[0].matches("[0-9a-f]{64}")) best.put("sha256", p[0]);
        }
        if (best.get("sha256") == null) throw new IOException("SHA256SUMS doesn't list " + want);
        best.remove("sums");
        return best;
    }

    // ---- what's installed, and what the catalogue offers each

    static final class Inst {
        String version;
        boolean enabled;
        Object requires, conflicts;
    }

    @SuppressWarnings("unchecked")
    Map<String, Inst> installed() {
        Map<String, Inst> m = new TreeMap<String, Inst>();
        File[] ds = new File(e.data, "mods").listFiles();
        for (File d : ds == null ? new File[0] : ds) {
            File mj = new File(d, "mod.json");
            if (!d.getName().matches(ID) || !mj.isFile()) continue;
            try {
                Map<String, Object> j = Json.object(Engine.read(mj));
                Inst i = new Inst();
                i.version = String.valueOf(j.get("version"));
                i.enabled = !new File(d, ".disabled").exists();
                i.requires = j.get("requires");
                i.conflicts = j.get("conflicts");
                m.put(d.getName(), i);
            } catch (Exception x) {
                // a broken mod.json: the engine's other checks say so
            }
        }
        return m;
    }

    /** the catalogue's own entries, as listed (the Catalogue tab) */
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> listed() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        if (cache == null || !(cache.get("mods") instanceof List)) return m;
        for (Object o : (List<Object>) cache.get("mods")) m.put(String.valueOf(((Map<String, Object>) o).get("id")), (Map<String, Object>) o);
        return m;
    }

    /**
     * What planning and updates go by: the catalogue's entries, none that's withdrawn, with each mod whose
     * update source is its releases taking its release entry instead (by the installed key; none if no
     * newer release was found).
     */
    Map<String, Map<String, Object>> entries() {
        Map<String, Map<String, Object>> m = listed();
        for (String id : new ArrayList<String>(m.keySet())) {
            if (withdrawn(id, String.valueOf(m.get(id).get("version"))) != null) m.remove(id);
        }
        Map<String, Map<String, Object>> rel = Releases.entries(this);
        Set<String> ids = listed().keySet();
        for (String id : installed().keySet()) {
            Releases.Mod mod = Releases.mod(e, id);
            if (mod == null || !FROM_RELEASES.equals(source(id, mod, ids))) continue;
            m.remove(id);
            Map<String, Object> en = rel.get(id);
            if (en != null && mod.uuid != null && mod.uuid.equalsIgnoreCase(String.valueOf(en.get("uuid")))
                    && withdrawn(id, String.valueOf(en.get("version"))) == null) m.put(id, en);
        }
        return m;
    }

    /** why a maintainer withdrew id's version (catalog/withdrawn.json: [{"id", "versions": range, "why"}]), or null */
    @SuppressWarnings("unchecked")
    String withdrawn(String id, String version) {
        Object l = cache == null ? null : cache.get("withdrawn");
        for (Object o : l instanceof List ? (List<Object>) l : new ArrayList<Object>()) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> w = (Map<String, Object>) o;
            if (!id.equals(w.get("id"))) continue;
            Object r = w.get("versions");
            if (r != null && !sat(version, String.valueOf(r))) continue;
            return w.get("why") instanceof String ? (String) w.get("why") : "withdrawn by HMG-API's maintainers";
        }
        return null;
    }

    // ---- where each installed mod's updates come from

    /** the owner's choices: hmgapi.json "catalog": {"update_source": {"<id>": "catalogue" | "releases" | "off"}} */
    @SuppressWarnings("unchecked")
    Map<String, Object> choices() {
        Object c = e.hmgapi.get("catalog");
        Object u = c instanceof Map ? ((Map<String, Object>) c).get("update_source") : null;
        return u instanceof Map ? (Map<String, Object>) u : new LinkedHashMap<String, Object>();
    }

    /**
     * id's update source: the owner's choice, else the catalogue when it lists the mod, else its releases
     * when its mod.json names a GitHub repo, else none. A choice that can't be met (the catalogue for a
     * mod it doesn't list, releases with no GitHub repo) is none.
     */
    String source(String id, Releases.Mod m, Set<String> listed) {
        Object ch = choices().get(id);
        String s = ch instanceof String ? (String) ch : listed.contains(id) ? FROM_CATALOGUE : FROM_RELEASES;
        if (FROM_CATALOGUE.equals(s)) return listed.contains(id) ? FROM_CATALOGUE : FROM_NONE;
        if (FROM_RELEASES.equals(s)) return m != null && m.repo != null ? FROM_RELEASES : FROM_NONE;
        return FROM_NONE;
    }

    /** each installed mod's update source, for the app: {id: {"source", "choice"?, "repo"?, "listed"}} */
    Map<String, Object> sources() {
        Map<String, Object> out = new TreeMap<String, Object>();
        Set<String> ids = listed().keySet();
        Map<String, Object> ch = choices();
        for (String id : installed().keySet()) {
            Releases.Mod m = Releases.mod(e, id);
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("source", source(id, m, ids));
            if (ch.get(id) instanceof String) x.put("choice", ch.get(id));
            if (m != null && m.repo != null) x.put("repo", m.repo);
            x.put("listed", ids.contains(id));
            out.put(id, x);
        }
        return out;
    }

    /** engine update-source <id> catalogue | releases | off | default: the owner's choice, kept in hmgapi.json */
    @SuppressWarnings("unchecked")
    int setSource(String[] args) throws IOException {
        if (args.length != 3 || !args[1].matches(ID) || !Arrays.asList(FROM_CATALOGUE, FROM_RELEASES, FROM_NONE, "default").contains(args[2])) {
            e.out.add("usage: engine update-source <id> catalogue | releases | off | default");
            return 2;
        }
        String id = args[1], to = args[2];
        Map<String, Inst> inst = installed();
        if (!inst.containsKey(id)) {
            e.out.add(id + " isn't installed");
            return 1;
        }
        Releases.Mod m = Releases.mod(e, id);
        if (FROM_RELEASES.equals(to) && (m == null || m.repo == null)) {
            e.out.add(id + "'s mod.json names no GitHub repo (\"repo\": \"https://github.com/<owner>/<repo>\"), so it has no releases to check");
            return 1;
        }
        if (FROM_CATALOGUE.equals(to) && !listed().containsKey(id)) {
            e.out.add(id + " isn't in the catalogue" + (cache == null ? " (it hasn't been checked yet)" : ""));
            return 1;
        }
        Object c = e.hmgapi.get("catalog");
        Map<String, Object> cat = c instanceof Map ? (Map<String, Object>) c : new LinkedHashMap<String, Object>();
        Map<String, Object> ch = new TreeMap<String, Object>(choices());
        for (String k : new ArrayList<String>(ch.keySet())) if (!inst.containsKey(k)) ch.remove(k);   // removed mods' choices go
        if ("default".equals(to)) ch.remove(id);
        else ch.put(id, to);
        if (ch.isEmpty()) cat.remove("update_source");
        else cat.put("update_source", ch);
        e.hmgapi.put("catalog", cat);
        File lj = new File(e.data, "settings/hmgapi.json");
        lj.getParentFile().mkdirs();
        Engine.write(lj, Json.write(e.hmgapi));
        String now = source(id, m, listed().keySet());
        e.out.add(id + ": updates from " + (FROM_CATALOGUE.equals(now) ? "the catalogue" : FROM_RELEASES.equals(now) ? m.repo + "'s releases" : "nowhere (not checked)")
                + (FROM_RELEASES.equals(now) ? " (checked at the next refresh)" : ""));
        if (cache != null) writeUpdates();
        return 0;
    }

    /** a dependency list (requires or conflicts), optional ones left out (only NEMESIS's package builder honours them) */
    @SuppressWarnings("unchecked")
    static List<Version.Dep> deps(Object list) {
        List<Version.Dep> out = new ArrayList<Version.Dep>();
        if (!(list instanceof List)) return out;
        for (Object o : (List<Object>) list) {
            if (o instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) o).get("optional"))) continue;
            out.addAll(Version.deps(Arrays.asList(o)));
        }
        return out;
    }

    /** deps(), the unreadable ones (bad id or range) left out: for what's installed, which the engine's plan reports itself */
    static List<Version.Dep> readable(Object list) {
        List<Version.Dep> out = new ArrayList<Version.Dep>();
        for (Version.Dep d : deps(list)) if (d.id.matches(ID) && (d.range == null || Version.rangeError(d.range) == null)) out.add(d);
        return out;
    }

    static boolean sat(String v, String range) {
        try {
            return Version.satisfies(v, range);
        } catch (IllegalArgumentException x) {
            return false;
        }
    }

    static boolean has(List<Version.Dep> l, Version.Dep d) {
        for (Version.Dep k : l) if (k.toString().equals(d.toString())) return true;
        return false;
    }

    /**
     * why this unit can't take the entry, or null: its HMG-API range; "car: ..." for its "system" (an
     * override exists, and the owner's earlier choice, hmgapi.json overrides.ignore_system, counts)
     */
    String unfit(Map<String, Object> en) {
        Object need = en.get("hmgapi");
        if (need != null) {
            String bad = Version.rangeError(String.valueOf(need));
            if (bad != null) return "its \"hmgapi\" " + bad;
            if (!Version.meetsHmgapi(installedHmgApi(), String.valueOf(need))) return "needs HMG-API " + need + " (this is " + installedHmgApi() + ")";
        }
        if (en.get("system") != null) {
            String sys = SystemCheck.check(en.get("system"), e.facts, SystemCheck.asksHotspot(en));
            if (sys != null) return "car: " + sys;
        }
        return null;
    }

    boolean carChosen(String id) {
        return SystemCheck.ignored(e.hmgapi, id);
    }

    static String status(String have, String offered) {
        if (have == null) return "new";
        int c;
        try {
            c = new Version(offered).compareTo(new Version(have));
        } catch (IllegalArgumentException x) {
            return "same";
        }
        return c > 0 ? "update" : c == 0 ? "same" : "older";
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> report() {
        Map<String, Object> rep = new LinkedHashMap<String, Object>();
        rep.put("hmgapi", installedHmgApi());
        rep.put("online", offline() == null);
        if (offline() != null) rep.put("offline", offline());
        if (cache == null) {
            rep.put("mods", new ArrayList<Object>());
            return rep;
        }
        rep.put("checked", cache.get("checked"));
        Map<String, Inst> inst = installed();
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> en : listed().values()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            String id = String.valueOf(en.get("id"));
            for (String k : new String[] {"id", "name", "version", "description", "authors", "license", "repo", "uuid"}) {
                if (en.get(k) != null) m.put(k, en.get(k));
            }
            Inst i = inst.get(id);
            if (i != null) {
                m.put("installed", i.version);
                m.put("enabled", i.enabled);
            }
            m.put("status", status(i == null ? null : i.version, String.valueOf(en.get("version"))));
            String unfit = unfit(en);
            if (unfit != null) m.put("unfit", unfit);
            if (unfit != null && unfit.startsWith("car: ") && carChosen(id)) m.put("any_car", true);
            List<Object> req = new ArrayList<Object>();
            for (Version.Dep d : deps(en.get("requires"))) req.add(d.toString());
            if (!req.isEmpty()) m.put("requires", req);
            m.put("trusted", Packages.trustedAuthor(e, String.valueOf(en.get("uuid"))) != null);
            String wd = withdrawn(id, String.valueOf(en.get("version")));
            if (wd != null) m.put("withdrawn", wd);
            out.add(m);
        }
        rep.put("mods", out);
        if (cache.get("hmgapi") != null) rep.put("hmgapi_update", hmgapiOffer());
        if (cache.get("notes") != null) rep.put("notes", cache.get("notes"));
        return rep;
    }

    /** HMG-API's newer release from the cache, if it's still newer than what's installed */
    @SuppressWarnings("unchecked")
    Map<String, Object> hmgapiOffer() {
        Object h = cache == null ? null : cache.get("hmgapi");
        if (!(h instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) h;
        if (!"update".equals(status(installedHmgApi(), String.valueOf(m.get("version"))))) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>(m);
        out.put("installed", installedHmgApi());
        return out;
    }

    /** installed mods with a newer catalogue version this unit can take, and HMG-API's own */
    @SuppressWarnings("unchecked")
    Map<String, Object> updates() {
        Map<String, Object> rep = new LinkedHashMap<String, Object>();
        rep.put("online", offline() == null);
        List<Object> list = new ArrayList<Object>();
        if (cache != null) {
            rep.put("checked", cache.get("checked"));
            Map<String, Map<String, Object>> cat = entries();
            Map<String, Object> src = sources();
            for (Map.Entry<String, Inst> i : installed().entrySet()) {
                Map<String, Object> en = cat.get(i.getKey());
                if (en == null || !"update".equals(status(i.getValue().version, String.valueOf(en.get("version"))))) continue;
                Object s = src.get(i.getKey());
                if (s instanceof Map && FROM_NONE.equals(((Map<?, ?>) s).get("source"))) continue;   // the owner turned its updates off
                String unfit = unfit(en);
                if (unfit != null && !unfit.startsWith("car: ")) continue;   // not for this HMG-API: not offered
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", i.getKey());
                m.put("name", en.get("name") == null ? i.getKey() : en.get("name"));
                m.put("installed", i.getValue().version);
                m.put("version", en.get("version"));
                if (en.get("description") != null) m.put("description", en.get("description"));
                List<Object> req = new ArrayList<Object>();
                for (Version.Dep d : deps(en.get("requires"))) req.add(d.toString());
                if (!req.isEmpty()) m.put("requires", req);
                if (String.valueOf(en.get("version")).equals(stagedVersion(e, i.getKey()))) m.put("staged", true);
                if ("release".equals(en.get("source"))) {   // from the mod's own releases: said so, with its notes
                    m.put("source", "release");
                    for (String k : new String[] {"repo", "page", "notes", "prerelease"}) if (en.get(k) != null) m.put(k, en.get(k));
                }
                list.add(m);
            }
            List<Object> wd = new ArrayList<Object>();   // what's installed that a maintainer withdrew
            for (Map.Entry<String, Inst> i : installed().entrySet()) {
                String why = withdrawn(i.getKey(), i.getValue().version);
                if (why == null) continue;
                Map<String, Object> w = new LinkedHashMap<String, Object>();
                w.put("id", i.getKey());
                w.put("version", i.getValue().version);
                w.put("why", why);
                wd.add(w);
            }
            if (!wd.isEmpty()) rep.put("withdrawn", wd);
            rep.put("sources", src);
            Map<String, Object> h = hmgapiOffer();
            if (h != null) rep.put("hmgapi", h);
        }
        rep.put("mods", list);
        rep.put("count", list.size() + (rep.get("hmgapi") != null ? 1 : 0));
        return rep;
    }

    /** updates.json, also in public/ for the app (the Mods tab's "Updates (n)"); after any install or removal too */
    void writeUpdates() throws IOException {
        String text = Json.write(updates());
        writeWhole(updatesFile, text, false);
        writeWhole(new File(e.data, "public/updates.json"), text, true);
    }

    /** the same, from an install or removal outside the catalogue (a USB stick, adb): never fails it */
    static void updatesChanged(Engine e) {
        try {
            Catalog c = new Catalog(e);
            c.load();
            if (c.cache != null) c.writeUpdates();
        } catch (Exception x) {
            // the count catches up at the next check
        }
    }

    // ---- what the downloaded packages said: requires and conflicts their entries didn't have

    @SuppressWarnings("unchecked")
    Map<String, Object> learned() {
        try {
            if (learnedFile.isFile()) return Json.object(Engine.read(learnedFile));
        } catch (Exception x) {
            // unreadable: learned again from the next download
        }
        return new LinkedHashMap<String, Object>();
    }

    static String key(Map<String, Object> en) {
        return en.get("id") + " " + en.get("version") + " " + en.get("sha256");
    }

    /** keeps the package's requires and conflicts for its entry (this exact download); true if the plan didn't know one */
    @SuppressWarnings("unchecked")
    boolean learn(Plan p, Step s, Map<String, Object> mj) throws IOException {
        boolean more = false;
        for (String k : new String[] {"requires", "conflicts"}) {
            List<Version.Dep> known = "requires".equals(k) ? p.requires(s.id, s.entry) : p.conflicts(s.id, s.entry);
            for (Version.Dep d : deps(mj.get(k))) more |= !has(known, d);
        }
        if (!more) return false;
        Map<String, Object> all = learned();
        Map<String, Object> one = new LinkedHashMap<String, Object>();
        one.put("requires", mj.get("requires") instanceof List ? mj.get("requires") : new ArrayList<Object>());
        one.put("conflicts", mj.get("conflicts") instanceof List ? mj.get("conflicts") : new ArrayList<Object>());
        all.put(key(s.entry), one);
        for (String old : new ArrayList<String>(all.keySet())) {   // only the current entries' packages are kept
            Map<String, Object> en = p.cat.get(old.split(" ")[0]);
            if (en == null || !old.equals(key(en))) all.remove(old);
        }
        writeWhole(learnedFile, Json.write(all), false);
        p.learned.clear();
        p.learned.putAll(all);
        return true;
    }

    // ---- dependencies: what installing some mods takes, in order (what they require first)

    static final class Step {
        final String id, action, version, installed, because;
        final Map<String, Object> entry;
        /** a required mod that's off and is installed again: turned on too */
        boolean turnOn;
        File dir;

        Step(String id, String action, String version, String installed, String because, Map<String, Object> entry) {
            this.id = id;
            this.action = action;
            this.version = version;
            this.installed = installed;
            this.because = because;
            this.entry = entry;
        }

        String same() {
            return id + " " + action + " " + version + " " + turnOn;
        }
    }

    /** a mod as it would be after the plan */
    static final class Fin {
        String version;
        boolean on, changed;
        List<Version.Dep> requires, conflicts;
    }

    final class Plan {
        final List<Step> steps = new ArrayList<Step>();
        final List<String> problems = new ArrayList<String>(), notes = new ArrayList<String>();
        /** what the owner may say yes to: {"id", "kind": "car" | "author", "why"} */
        final List<Object> overrides = new ArrayList<Object>();
        /** update --all: the mods left out, each {"id", "why", "overrides"} */
        final List<Object> skipped = new ArrayList<Object>();
        final Map<String, Map<String, Object>> cat = entries();
        final Map<String, Inst> inst = installed();
        final Map<String, Object> learned = learned();
        final Set<String> visiting = new LinkedHashSet<String>();
        final List<String> wanted = new ArrayList<String>();
        final Overrides ov;
        boolean update;

        Plan(Overrides ov) {
            this.ov = ov == null ? new Overrides() : ov;
        }

        void problem(String s) {
            if (!problems.contains(s)) problems.add(s);
        }

        void override(String id, String kind, String why) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("why", why);
            if (!overrides.contains(m)) overrides.add(m);
        }

        Step planned(String id) {
            for (Step s : steps) if (s.id.equals(id)) return s;
            return null;
        }

        /** a dependency list from an entry or a package: an unreadable one (bad id or range) is a problem */
        List<Version.Dep> checked(Object list, String owner, String what, List<Version.Dep> into) {
            for (Version.Dep d : deps(list)) {
                String bad = !d.id.matches(ID) ? "\"" + d.id + "\" isn't a mod id" : d.range == null ? null : Version.rangeError(d.range);
                if (bad != null) problem(owner + ": its " + what + " \"" + d + "\" can't be read: " + bad);
                else if (!has(into, d)) into.add(d);
            }
            return into;
        }

        @SuppressWarnings("unchecked")
        List<Version.Dep> fromEntry(String id, Map<String, Object> en, String k) {
            String owner = id + " " + en.get("version");
            List<Version.Dep> r = checked(en.get(k), owner, k, new ArrayList<Version.Dep>());
            Object l = learned.get(key(en));
            if (l instanceof Map) checked(((Map<String, Object>) l).get(k), owner, k, r);
            return r;
        }

        List<Version.Dep> requires(String id, Map<String, Object> en) {
            return fromEntry(id, en, "requires");
        }

        List<Version.Dep> conflicts(String id, Map<String, Object> en) {
            return fromEntry(id, en, "conflicts");
        }

        boolean fits(Map<String, Object> en, String who) {
            String unfit = unfit(en);
            if (unfit == null) return true;
            String id = String.valueOf(en.get("id"));
            if (unfit.startsWith("car: ")) {
                if (ov.car(id) || carChosen(id)) {
                    notes.add(id + " isn't listed for this head unit (" + unfit.substring(5) + "): " + (ov.car(id) ? "installing it anyway, at your own risk"
                            : "you chose to use it here anyway"));
                    return true;
                }
                override(id, "car", unfit.substring(5));
            }
            problem((who != null ? who + " needs " : "") + id + " " + en.get("version") + ", which "
                    + (unfit.startsWith("car: ") ? "isn't for this head unit: " + unfit.substring(5) : unfit));
            return false;
        }

        /** a mod asked for by the owner: installed, or updated */
        void want(String id, boolean update) {
            if (!wanted.contains(id)) wanted.add(id);
            Map<String, Object> en = cat.get(id);
            Inst i = inst.get(id);
            if (en == null) {
                Releases.Mod m = i == null ? null : Releases.mod(e, id);
                String src = m == null ? null : source(id, m, listed().keySet());
                problem(FROM_RELEASES.equals(src) ? id + ": no newer release in " + m.repo + "'s releases (as last checked)"
                        : FROM_NONE.equals(src) && i != null && choices().get(id) != null ? id + "'s updates are turned off (update-source)"
                        : id + " isn't in the catalogue");
                return;
            }
            String st = status(i == null ? null : i.version, String.valueOf(en.get("version")));
            if ("same".equals(st) || "older".equals(st)) {
                if (i != null && !i.enabled && !update) add(new Step(id, "enable", i.version, i.version, null, en));
                else notes.add(id + " " + i.version + " is installed" + ("older".equals(st) ? " (newer than the catalogue's " + en.get("version") + ")" : ""));
                return;
            }
            if (update && i == null) {
                problem(id + " isn't installed: install it from the catalogue");
                return;
            }
            if (fits(en, null)) take(en, null);
        }

        /** a requirement: met by what's installed (turned on if it's off) or planned, else the catalogue's */
        void need(Version.Dep d, String who) {
            Inst i = inst.get(d.id);
            Step p = planned(d.id);
            if (p != null && !"enable".equals(p.action)) {   // its version: settle() checks it against everything that will be on
                if (i != null && !i.enabled) p.turnOn = true;
                return;
            }
            if (i != null && sat(i.version, d.range)) {
                if (!i.enabled && p == null) add(new Step(d.id, "enable", i.version, i.version, who, cat.get(d.id)));
                return;
            }
            Map<String, Object> en = cat.get(d.id);
            if (en == null) {
                problem(who + " needs " + d + (i != null ? " (" + i.version + " is installed)" : "") + ", which isn't in the catalogue");
                return;
            }
            if (!sat(String.valueOf(en.get("version")), d.range)) {
                problem(who + " needs " + d + ", but the catalogue has " + d.id + " " + en.get("version")
                        + (i != null ? " (" + i.version + " is installed)" : ""));
                return;
            }
            if (i != null && "older".equals(status(i.version, String.valueOf(en.get("version"))))) {
                problem(who + " needs " + d + ": the installed " + d.id + " " + i.version + " doesn't fit and the catalogue's is older");
                return;
            }
            if (fits(en, who)) {
                take(en, who);
                Step s = planned(d.id);
                if (s != null && i != null && !i.enabled) s.turnOn = true;
            }
        }

        void take(Map<String, Object> en, String who) {
            String id = String.valueOf(en.get("id"));
            if (planned(id) != null) return;
            if (!visiting.add(id)) {
                problem("a requires loop: " + visiting + " -> " + id);
                return;
            }
            Inst i = inst.get(id);
            if (i == null) trust(id, en);
            for (Version.Dep d : requires(id, en)) need(d, id);
            visiting.remove(id);
            add(new Step(id, i == null ? "install" : "update", String.valueOf(en.get("version")), i == null ? null : i.version, who, en));
        }

        /**
         * A first install's author key comes from the catalogue, which isn't signed: it must already be on this
         * unit's trusted list (maps/authors.json, from HMG-API's own release), or the owner says yes for this mod.
         */
        void trust(String id, Map<String, Object> en) {
            String uuid = String.valueOf(en.get("uuid"));
            if (Packages.trustedAuthor(e, uuid) != null) return;
            if (ov.anyAuthor.contains(id)) {
                notes.add("WARNING: " + id + " is by author key " + uuid + ", which isn't on this head unit's trusted author list: installing it"
                        + " because you said so. Its updates must come from this same key.");
                return;
            }
            override(id, "author", "author key " + uuid + " isn't on this head unit's trusted author list");
            problem(id + " " + en.get("version") + " is by author key " + uuid + ", which isn't on this head unit's trusted author list"
                    + " (it comes with HMG-API's releases). The catalogue isn't signed, so HMG-API doesn't take a new author's key from it"
                    + " on its own: install it only if you trust its author (\"Install anyway\", or --any-author=" + id + ")");
        }

        void add(Step s) {
            if (planned(s.id) == null) steps.add(s);
        }

        /**
         * The result as a whole: every mod that will be on has what it requires, on and in range, and no mod
         * that will be on conflicts with another, whichever side lists it. Only what this plan changes is
         * checked (what was already broken isn't this install's to refuse). pkgs: the downloaded packages'
         * mod.json, by id, once there are any.
         */
        void settle(Map<String, Map<String, Object>> pkgs) {
            if (!problems.isEmpty()) return;
            Map<String, Fin> fin = new TreeMap<String, Fin>();
            for (Map.Entry<String, Inst> x : inst.entrySet()) {
                Fin f = new Fin();
                f.version = x.getValue().version;
                f.on = x.getValue().enabled;
                f.requires = readable(x.getValue().requires);
                f.conflicts = readable(x.getValue().conflicts);
                fin.put(x.getKey(), f);
            }
            for (Step s : steps) {
                Fin old = fin.get(s.id);
                if ("enable".equals(s.action)) {
                    if (old != null) old.on = old.changed = true;
                    continue;
                }
                Fin f = new Fin();
                f.version = s.version;
                f.on = old == null || old.on || s.turnOn;
                f.changed = true;
                f.requires = requires(s.id, s.entry);
                f.conflicts = conflicts(s.id, s.entry);
                Map<String, Object> pk = pkgs.get(s.id);
                if (pk != null) {
                    checked(pk.get("requires"), s.id + " " + s.version, "requires", f.requires);
                    checked(pk.get("conflicts"), s.id + " " + s.version, "conflicts", f.conflicts);
                }
                fin.put(s.id, f);
            }
            for (Map.Entry<String, Fin> x : fin.entrySet()) {
                String id = x.getKey();
                Fin a = x.getValue();
                if (!a.on) continue;
                String who = (a.changed ? "" : "the installed ") + id + " " + a.version;
                for (Version.Dep d : a.requires) {
                    Fin b = fin.get(d.id);
                    if (!a.changed && (b == null || !b.changed)) continue;
                    if (b != null && b.on && sat(b.version, d.range)) continue;
                    problem(who + " needs " + d + ", but " + (b == null ? d.id + " wouldn't be installed"
                            : !b.on ? d.id + " would stay turned off" : d.id + " " + b.version + " would be " + (b.changed ? "the one installed" : "installed")));
                }
                for (Version.Dep c : a.conflicts) {
                    Fin b = fin.get(c.id);
                    if (b == null || !b.on || c.id.equals(id) || (!a.changed && !b.changed) || !sat(b.version, c.range)) continue;
                    problem(a.changed ? id + " " + a.version + " conflicts with " + c.id + " " + b.version + (b.changed ? " (being installed)" : " (installed)")
                            + ": remove or update " + c.id + " first"
                            : who + " conflicts with " + c.id + " " + b.version + " (its conflicts list: " + c + "): remove or update " + id + " first");
                }
            }
        }

        String sameAs() {
            StringBuilder b = new StringBuilder();
            for (Step s : steps) b.append(s.same()).append('|');
            return b.toString();
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", problems.isEmpty());
            m.put("wanted", wanted);
            List<Object> st = new ArrayList<Object>();
            for (Step s : steps) {
                Map<String, Object> x = new LinkedHashMap<String, Object>();
                x.put("id", s.id);
                x.put("name", s.entry != null && s.entry.get("name") != null ? s.entry.get("name") : s.id);
                x.put("action", s.action);
                x.put("version", s.version);
                if (s.installed != null) x.put("installed", s.installed);
                if (s.because != null) x.put("for", s.because);
                if (s.turnOn) x.put("turn_on", true);
                if (s.entry != null) x.put("trusted", Packages.trustedAuthor(e, String.valueOf(s.entry.get("uuid"))) != null);
                if (ov.car(s.id)) x.put("any_car", true);
                st.add(x);
            }
            m.put("steps", st);
            if (!problems.isEmpty()) m.put("problems", problems);
            if (!overrides.isEmpty()) m.put("overrides", overrides);
            if (!skipped.isEmpty()) m.put("skipped", skipped);
            if (!notes.isEmpty()) m.put("notes", notes);
            return m;
        }
    }

    Plan plan(List<String> ids, boolean update, Overrides ov) {
        Plan p = new Plan(ov);
        p.update = update;
        for (String id : ids) p.want(id, update);
        p.settle(new LinkedHashMap<String, Map<String, Object>>());
        return p;
    }

    /** update --all: each mod that can't be updated now is left out (with why), the rest planned together */
    Plan planEach(List<String> ids, Overrides ov) {
        List<String> ok = new ArrayList<String>();
        List<Object> skipped = new ArrayList<Object>();
        for (String id : ids) {
            Plan one = plan(Arrays.asList(id), true, ov);
            if (one.problems.isEmpty()) {
                ok.add(id);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("why", join(one.problems, "; "));
            if (!one.overrides.isEmpty()) m.put("overrides", one.overrides);
            skipped.add(m);
        }
        Plan p = plan(ok, true, ov);
        p.skipped.addAll(skipped);
        return p;
    }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() > 0 ? sep : "").append(s);
        return b.toString();
    }

    // ---- doing it: everything downloaded and checked first, then installed in order

    /** downloads one step's package into dl, checks its SHA-256 and that it is what the entry says, unzips it; null, or why not */
    @SuppressWarnings("unchecked")
    String fetch(Http http, File dl, Step s, Map<String, Map<String, Object>> pkgs) throws Exception {
        File z = new File(dl, s.id + "-" + s.version + ".zip");
        Http.Result r = http.get(String.valueOf(s.entry.get("download")), z, (int) Packages.MAX_BYTES);
        if (!r.ok()) return "can't download " + s.id + " " + s.version + ": " + r;
        boolean rel = "release".equals(s.entry.get("source"));
        String sha = Sha256.hex(z), want = s.entry.get("sha256") == null ? null : String.valueOf(s.entry.get("sha256"));
        if (want == null && !rel) return "refused " + s.id + " " + s.version + ": its catalogue entry has no SHA-256";
        if (want != null && !sha.equals(want)) {
            return "refused " + s.id + " " + s.version + ": the download's SHA-256 is " + sha + ", " + (rel ? "its release says " : "the catalogue says ") + want;
        }
        File u = new File(dl, s.id + "-" + s.version);
        Map<String, Object> mj = null;
        try {
            Packages.unzip(z, u);
            File root = Packages.root(u);
            mj = Json.object(Engine.read(new File(root, "mod.json")));
            s.dir = root;
        } catch (Exception x) {
            mj = null;
        }
        z.delete();
        if (mj == null || !s.id.equals(mj.get("id")) || !s.version.equals(String.valueOf(mj.get("version")))) {
            return "refused " + s.id + " " + s.version + ": the package is " + (mj == null ? "not a mod" : mj.get("id") + " " + mj.get("version"));
        }
        pkgs.put(s.id, mj);
        e.out.add("downloaded " + s.id + " " + s.version + (!rel ? " (SHA-256 as the catalogue's)" : want != null ? " from its release (SHA-256 as the release's)"
                : " from its release (no SHA-256 given: its signature decides)"));
        return null;
    }

    Http http(File dl) {
        List<String> why = new ArrayList<String>();
        Http.Transport t = NativeFetch.forEngine(e, why);
        if (t == null) {
            e.out.add("can't download: " + why.get(0));
            return null;
        }
        return new Http(t, dl);
    }

    int execute(Plan p) throws Exception {
        for (Object o : p.skipped) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            e.out.add("not updated: " + m.get("id") + ": " + m.get("why"));
        }
        if (!p.problems.isEmpty()) {
            for (String x : p.problems) e.out.add("can't: " + x);
            return 1;
        }
        if (p.steps.isEmpty()) {
            for (String n : p.notes) e.out.add(n);
            e.out.add("nothing to install");
            return p.skipped.isEmpty() ? 0 : 1;
        }
        String off = offline();
        if (off != null && needsDownload(p)) {
            e.out.add("can't download: " + off);
            return 1;
        }
        File dl = new File(dir, "downloads");
        Engine.rm(dl);
        dl.mkdirs();
        try {
            Http http = null;
            Map<String, Map<String, Object>> pkgs = new LinkedHashMap<String, Map<String, Object>>();
            boolean more = false;
            for (Step s : p.steps) {
                if ("enable".equals(s.action)) continue;
                if (http == null && (http = http(dl)) == null) return 1;
                String bad = fetch(http, dl, s, pkgs);
                if (bad != null) {
                    e.out.add(bad + " (nothing was installed)");
                    return 1;
                }
                more |= learn(p, s, pkgs.get(s.id));
            }
            // a package can require more than its entry says (an entry from before catalog.py copied requires):
            // its own mod.json is the truth. If that changes what this takes, the owner sees the new plan first.
            if (more) {
                Plan q = p.skipped.isEmpty() ? plan(p.wanted, p.update, p.ov) : planEach(p.wanted, p.ov);
                if (!q.problems.isEmpty() || !q.sameAs().equals(p.sameAs())) {
                    e.out.add(q.problems.isEmpty() ? "the packages require more than the catalogue says, so this takes more steps:"
                            + " nothing was installed. Check the new plan and install again" : "can't, with what the packages require:");
                    for (String x : q.problems) e.out.add("can't: " + x);
                    if (q.problems.isEmpty()) e.out.add("(nothing was installed)");
                    e.out.add(Json.write(q.toJson()));
                    return q.problems.isEmpty() ? PLAN_CHANGED : 1;
                }
            }
            p.settle(pkgs);
            if (!p.problems.isEmpty()) {
                for (String x : p.problems) e.out.add("can't: " + x);
                e.out.add("(nothing was installed)");
                return 1;
            }
            // every package checked as its install will check it (signature, author key, HMG-API, car), before the first goes in
            for (Step s : p.steps) {
                if ("enable".equals(s.action)) continue;
                Packages.Verdict v = Packages.check(e, s.dir, String.valueOf(s.entry.get("uuid")), false, p.ov.car(s.id));
                if (v.code != 0) {
                    e.out.add("refused " + s.id + " " + s.version + ": " + v.reason + " (nothing was installed)");
                    return v.code;
                }
            }
            for (String n : p.notes) e.out.add(n);
            int done = 0;
            for (Step s : p.steps) {
                if ("enable".equals(s.action)) {
                    new File(e.data, "mods/" + s.id + "/.disabled").delete();
                    e.out.add("turned on " + s.id + " " + s.version + (s.because != null ? " (" + s.because + " needs it)" : ""));
                    done++;
                    continue;
                }
                e.out.add((s.installed == null ? "installing " : "updating ") + s.id + (s.installed != null ? " " + s.installed + " ->" : "") + " "
                        + s.version + (s.because != null ? " (" + s.because + " needs it)" : ""));
                int rc = Packages.install(e, s.dir.getPath(), String.valueOf(s.entry.get("uuid")), false, p.ov.car(s.id));
                if (rc != 0) {
                    e.out.add("stopped at " + s.id + (done > 0 ? ": the " + done + " before it are installed (not applied yet)" : ": nothing was installed"));
                    if (done > 0) NemesisSync.run(e);
                    writeUpdates();
                    return rc;
                }
                if (s.turnOn && new File(e.data, "mods/" + s.id + "/.disabled").delete()) {
                    e.out.add("turned on " + s.id + " (" + s.because + " needs it)");
                }
                done++;
            }
            NemesisSync.run(e);
            LiveState.publish(e);
            writeUpdates();
            e.out.add("done: " + done + (done == 1 ? " mod" : " mods") + ". Apply changes (engine apply) to use "
                    + (done == 1 ? "it" : "them"));
            return 0;
        } finally {
            Engine.rm(dl);
        }
    }

    /** installed mods that require id (for a removal's warning) */
    static List<String> dependents(Engine e, String id) {
        List<String> out = new ArrayList<String>();
        File[] ds = new File(e.data, "mods").listFiles();
        for (File d : ds == null ? new File[0] : ds) {
            File mj = new File(d, "mod.json");
            if (d.getName().equals(id) || !mj.isFile()) continue;
            try {
                for (Version.Dep x : deps(Json.object(Engine.read(mj)).get("requires"))) if (x.id.equals(id)) out.add(d.getName());
            } catch (Exception ignored) {
                // a broken mod.json: not this check's to report
            }
        }
        return out;
    }

    static boolean needsDownload(Plan p) {
        for (Step s : p.steps) if (!"enable".equals(s.action)) return true;
        return false;
    }

    /** the package's mod.json (at its top, or in its one folder), or null */
    @SuppressWarnings("unchecked")
    static Map<String, Object> modJson(File zip) {
        ZipFile z = null;
        try {
            z = new ZipFile(zip);
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry x = en.nextElement();
                String n = x.getName();
                if (n.equals("mod.json") || (n.endsWith("/mod.json") && n.indexOf('/') == n.length() - 9)) {
                    if (x.getSize() > MAX_ENTRY) return null;
                    InputStream in = z.getInputStream(x);
                    return Json.object(new String(Engine.readAll(in), "UTF-8"));
                }
            }
            return null;
        } catch (Exception x) {
            return null;
        } finally {
            if (z != null) try { z.close(); } catch (IOException ignored) { /* read already */ }
        }
    }

    /** installed mods (turned on) whose "hmgapi" range the given HMG-API version doesn't meet */
    List<String> outgrown(String version) {
        List<String> out = new ArrayList<String>();
        File[] ds = new File(e.data, "mods").listFiles();
        for (File d : ds == null ? new File[0] : ds) {
            File mj = new File(d, "mod.json");
            if (!mj.isFile() || new File(d, ".disabled").exists()) continue;
            try {
                Map<String, Object> j = Json.object(Engine.read(mj));
                Object need = j.get("hmgapi");
                if (need != null && !Version.meetsHmgapi(version, String.valueOf(need))) {
                    out.add(d.getName() + " " + j.get("version") + " (needs HMG-API " + need + ")");
                }
            } catch (Exception x) {
                // a broken mod.json or range: the engine's plan says so
            }
        }
        return out;
    }

    /**
     * HMG-API itself: its update zip, checked against SHA256SUMS, then its own installer (UsbUpdates: the
     * release key). Refused while a mod that's on wouldn't take the new version (applies would stop on it),
     * unless the owner says so (--force).
     */
    @SuppressWarnings("unchecked")
    int updateHmgApi(boolean force) throws Exception {
        Map<String, Object> h = hmgapiOffer();
        if (h == null) {
            e.out.add("HMG-API " + installedHmgApi() + " is the newest release offered here (engine updates --refresh checks again)");
            return 0;
        }
        List<String> wont = outgrown(String.valueOf(h.get("version")));
        if (!wont.isEmpty()) {
            String list = join(wont, ", ");
            if (!force) {
                e.out.add("not yet: with HMG-API " + h.get("version") + " these mods wouldn't load, and applying stops until they're updated or"
                        + " turned off: " + list + ". Update or turn them off first, or update anyway (engine update hmgapi --force)");
                return 1;
            }
            e.out.add("WARNING: with HMG-API " + h.get("version") + " these mods won't load until they're updated or turned off: " + list);
        }
        String off = offline();
        if (off != null) {
            e.out.add("can't download: " + off);
            return 1;
        }
        File dl = new File(dir, "downloads");
        Engine.rm(dl);
        dl.mkdirs();
        try {
            Http http = http(dl);
            if (http == null) return 1;
            File z = new File(dl, "hmgapi-" + h.get("version") + "-update.zip");
            Http.Result r = http.get(String.valueOf(h.get("download")), z, 128 << 20);
            if (!r.ok()) {
                e.out.add("can't download HMG-API " + h.get("version") + ": " + r);
                return 1;
            }
            String sha = Sha256.hex(z);
            if (!sha.equals(h.get("sha256"))) {
                e.out.add("refused HMG-API " + h.get("version") + ": the download's SHA-256 is " + sha + ", its SHA256SUMS says " + h.get("sha256"));
                return 1;
            }
            e.out.add("downloaded HMG-API " + h.get("version") + " (" + z.length() + " bytes, SHA-256 as its release's SHA256SUMS)");
            return UsbUpdates.updateHmgApi(e, z.getPath(), false);
        } finally {
            Engine.rm(dl);
        }
    }

    // ---- the automatic check, and staged updates

    long checkGapMs() {
        String t = e.host ? Engine.env("HMGAPI_TEST_CHECK_GAP", null) : null;
        return (t != null ? Long.parseLong(t) : CHECK_GAP_S) * 1000L;
    }

    /**
     * wifi.sh, when home Wi-Fi comes up: refresh (at most every 6 hours), write updates.json, and stage
     * the mods' pure updates if the owner allowed it (hmgapid allow auto_stage_updates on). HMG-API itself
     * is never updated here. What happened goes to logs/hmgapi/updates.log.
     */
    @SuppressWarnings("unchecked")
    int updateCheck() throws Exception {
        List<String> log = new ArrayList<String>();
        File lastFile = new File(dir, "last-check");
        long now = System.currentTimeMillis(), last = checked();
        try {
            if (lastFile.isFile()) last = Math.max(last, Long.parseLong(Engine.read(lastFile).trim()));
        } catch (Exception x) {
            // unreadable: as if never
        }
        String err = null;
        if (now >= last && now - last < checkGapMs()) {
            log.add("not checked: the last check was " + (now - last) / 60000 + " min ago (at most one every " + checkGapMs() / 3600000
                    + " hours: GitHub allows 60 requests an hour)");
        } else {
            if (offline() == null) writeWhole(lastFile, String.valueOf(now), false);   // an attempt that reached for GitHub
            err = refresh();
            Map<String, Object> u = updates();
            if (err != null) log.add("check: " + err);
            else log.add("checked: " + u.get("count") + " update(s)" + (u.get("hmgapi") != null ? " (HMG-API " + ((Map<String, Object>) u.get("hmgapi")).get("version") + " among them)" : ""));
            if (err == null && new File(e.data, "settings/allow/auto_stage_updates").exists() && !((List<Object>) u.get("mods")).isEmpty()) {
                if (e.journalFile.exists()) {
                    log.add("not staged: an earlier apply isn't finished");
                } else {
                    for (Object o : (List<Object>) u.get("mods")) {
                        Map<String, Object> m = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(m.get("staged"))) continue;
                        String id = String.valueOf(m.get("id"));
                        int before = e.out.size();
                        String why = stage(id);
                        log.addAll(e.out.subList(before, e.out.size()));
                        while (e.out.size() > before) e.out.remove(e.out.size() - 1);
                        log.add(why == null ? "staged: " + id + " " + m.get("installed") + " -> " + m.get("version") + " (it goes in when you apply)"
                                : "not staged: " + id + ": " + why);
                    }
                    writeUpdates();
                }
            }
        }
        File lf = new File(e.data, "logs/hmgapi/updates.log");
        lf.getParentFile().mkdirs();
        String old = lf.isFile() ? Engine.read(lf) : "";
        if (old.length() > 64 << 10) old = old.substring(old.length() - (32 << 10));
        StringBuilder b = new StringBuilder(old);
        String when = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        for (String l : log) b.append(when).append(' ').append(l).append('\n');
        Engine.write(lf, b.toString());
        e.out.addAll(log.subList(0, Math.min(1, log.size())));
        return err != null ? 1 : 0;   // a mod not staged is in the log, not a failed check
    }

    /** why a plan isn't a pure update of id (that one step, nothing else installed or turned on), or null */
    static String pure(Plan p, String id) {
        if (!p.problems.isEmpty()) return join(p.problems, "; ");
        List<String> more = new ArrayList<String>();
        for (Step s : p.steps) {
            if (s.id.equals(id) && "update".equals(s.action) && !s.turnOn) continue;
            more.add(("enable".equals(s.action) || s.turnOn ? "turning on " : "install".equals(s.action) ? "installing " : "updating ") + s.id
                    + ("enable".equals(s.action) ? "" : " " + s.version));
        }
        if (p.steps.isEmpty()) return "nothing to update";
        if (!more.isEmpty()) return "it takes " + join(more, ", ") + " too: update it yourself (Updates in the Mods tab)";
        return null;
    }

    /**
     * The automatic check's staging of one mod: only a pure update, signed with the installed key, into
     * /data/hmgapi/pending/<id> (engine apply puts it in place). null when staged, else why not.
     */
    String stage(String id) throws Exception {
        Plan p = plan(Arrays.asList(id), true, null);
        String why = pure(p, id);
        if (why != null) return why;
        Step s = p.steps.get(0);
        String have = Packages.installedUuid(new File(e.data, "mods/" + id));
        if (have == null || !have.equalsIgnoreCase(String.valueOf(s.entry.get("uuid")))) {
            return "the catalogue names another author key than the installed " + id + "'s: update it yourself";
        }
        String off = offline();
        if (off != null) return off;
        File dl = new File(dir, "downloads");
        Engine.rm(dl);
        dl.mkdirs();
        try {
            Http http = http(dl);
            if (http == null) return "can't download";
            Map<String, Map<String, Object>> pkgs = new LinkedHashMap<String, Map<String, Object>>();
            String bad = fetch(http, dl, s, pkgs);
            if (bad != null) return bad;
            if (learn(p, s, pkgs.get(id))) {
                Plan q = plan(Arrays.asList(id), true, null);
                why = pure(q, id);
                if (why != null) return why + " (as its package says)";
            }
            p.settle(pkgs);
            if (!p.problems.isEmpty()) return join(p.problems, "; ");
            Packages.Verdict v = Packages.check(e, s.dir, have, false, false);
            if (v.code != 0) return v.reason;
            File pend = new File(e.data, "pending/" + id);
            Engine.rm(pend);
            pend.getParentFile().mkdirs();
            if (!s.dir.renameTo(pend)) throw new IOException("can't put the staged " + id + " in " + pend);
            return null;
        } finally {
            Engine.rm(dl);
        }
    }

    /** the version staged for id (pending/<id>), or null */
    static String stagedVersion(Engine e, String id) {
        return versionIn(new File(e.data, "pending/" + id));
    }

    static String installedVersion(Engine e, String id) {
        return versionIn(new File(e.data, "mods/" + id));
    }

    static String versionIn(File d) {
        File mj = new File(d, "mod.json");
        try {
            return mj.isFile() ? String.valueOf(Json.object(Engine.read(mj)).get("version")) : null;
        } catch (Exception x) {
            return null;
        }
    }

    /** engine plan: the staged updates, which go in at apply (their own changes are planned then) */
    static void stagedReport(Engine e) {
        File[] ps = new File(e.data, "pending").listFiles();
        for (File p : ps == null ? new File[0] : ps) {
            String id = p.getName(), want = stagedVersion(e, id), have = installedVersion(e, id);
            if (!id.matches(ID) || want == null || have == null) continue;
            e.out.add("update   " + id + " " + have + " -> " + want + " (staged by the automatic check: it goes in when you apply, and its changes"
                    + " are worked out then)");
        }
    }

    /**
     * engine apply, before it plans: each staged update checked again (the installed key, this car,
     * this HMG-API) and put in place, the version it replaces kept in /data/hmgapi/previous/<id>.
     * Returns the ids put in place (stagedBack undoes them if the apply doesn't go ahead).
     */
    static List<String> stagedIn(Engine e) throws Exception {
        List<String> done = new ArrayList<String>();
        File[] ps = new File(e.data, "pending").listFiles();
        if (ps != null) Arrays.sort(ps);
        for (File p : ps == null ? new File[0] : ps) {
            String id = p.getName();
            if (!id.matches(ID) || !p.isDirectory()) {
                Engine.rm(p);
                continue;
            }
            File inst = new File(e.data, "mods/" + id);
            String want = stagedVersion(e, id), have = installedVersion(e, id), why = null;
            if (want == null) why = "it isn't a mod";
            else if (have == null) why = id + " was removed since";
            else if (!"update".equals(status(have, want))) why = "the installed " + have + " is as new";
            else {
                Packages.Verdict v = Packages.check(e, p, Packages.installedUuid(inst), false, false);
                if (v.code != 0) why = v.reason;
            }
            if (why != null) {
                e.out.add("dropped the staged " + id + (want != null ? " " + want : "") + ": " + why);
                Engine.rm(p);
                continue;
            }
            File prev = new File(e.data, "previous/" + id);
            Engine.rm(prev);
            prev.getParentFile().mkdirs();
            boolean off = new File(inst, ".disabled").exists();
            if (!inst.renameTo(prev)) throw new IOException("can't move the installed " + id + " aside");
            if (!p.renameTo(inst)) {
                prev.renameTo(inst);
                throw new IOException("can't put the staged " + id + " in place");
            }
            if (off) new File(inst, ".disabled").createNewFile();
            e.chmodTree(inst);
            e.out.add("update   " + id + " " + have + " -> " + want + " (staged by the automatic check; " + have + " kept in /data/hmgapi/previous/" + id + ")");
            done.add(id);
        }
        return done;
    }

    /** an apply that didn't go ahead: the staged updates back to staged, the versions they replaced back in place */
    static void stagedBack(Engine e, List<String> ids) {
        for (String id : ids) {
            File inst = new File(e.data, "mods/" + id), prev = new File(e.data, "previous/" + id), pend = new File(e.data, "pending/" + id);
            Engine.rm(pend);
            if (!prev.isDirectory() || !inst.renameTo(pend) || !prev.renameTo(inst)) {
                e.out.add("couldn't put " + id + "'s previous version back: the staged one stays installed");
                continue;
            }
            new File(pend, ".disabled").delete();
        }
        if (!ids.isEmpty()) e.out.add("the staged updates (" + join(ids, ", ") + ") stay staged until an apply goes through");
    }
}
