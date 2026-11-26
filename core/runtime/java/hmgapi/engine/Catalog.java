package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
 *   engine catalog-plan <id>...             what installing them takes: their requires, in order (JSON)
 *   engine catalog-install <id>... [--any-car]   download, check and install them with what they require
 *   engine updates [--refresh]              installed mods (and HMG-API) with a newer version (JSON)
 *   engine update <id>... | --all | hmgapi  update them (with any new requires); hmgapi: HMG-API itself
 *   engine update-check                     wifi.sh, each time home Wi-Fi comes up: refresh, and stage the
 *                                           mods' updates if the owner allowed it (allow auto_stage_updates)
 *
 * Network: home Wi-Fi only (NativeFetch: its socket is marked for table 95), never the car's
 * mobile data; a refresh refuses to start unless home Wi-Fi is up. Trust: an entry only says where
 * to look. Its download must have its SHA-256, and Packages checks its signature: an update needs
 * the installed mod's author key, a first install the entry's (--uuid), and HMG-API itself needs
 * the release key (UsbUpdates). Nothing is applied here: the owner applies (engine apply) as after
 * any install. Dependencies: a mod's "requires" come from its entry (catalog.py copies them) and,
 * to be sure, from the downloaded package's mod.json; each is met by what's installed, or by the
 * catalogue's version, installed first. A required mod that's turned off is turned on.
 */
final class Catalog {
    static final String BASE = "https://raw.githubusercontent.com/Open-HMG/HMG-API/main/catalog/";
    static final String RELEASES = "https://api.github.com/repos/Open-HMG/HMG-API/releases?per_page=15";
    static final int MAX_JSON = 1 << 20, MAX_ENTRY = 64 << 10, MAX_ENTRIES = 500;
    static final String ID = "[a-z0-9][a-z0-9-]{0,63}";

    final Engine e;
    final File dir, cacheFile, updatesFile;
    Map<String, Object> cache;

    Catalog(Engine e) {
        this.e = e;
        dir = new File(e.data, "catalog");
        cacheFile = new File(dir, "catalog.json");
        updatesFile = new File(dir, "updates.json");
    }

    // ---- the ops

    static int run(Engine e, String[] args) throws Exception {
        Catalog c = new Catalog(e);
        String cmd = args[0];
        List<String> ids = new ArrayList<String>();
        boolean refresh = false, anyCar = false, all = false;
        for (int i = 1; i < args.length; i++) {
            if ("--refresh".equals(args[i])) refresh = true;
            else if ("--any-car".equals(args[i])) anyCar = true;
            else if ("--all".equals(args[i])) all = true;
            else if (args[i].matches(ID)) ids.add(args[i]);
            else {
                e.out.add("not a mod id: " + args[i]);
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
        if (c.cache == null) {
            String err = c.refresh();
            if (err != null) {
                e.out.add("no catalogue yet: " + err);
                return 1;
            }
        }
        if ("update".equals(cmd) && ids.remove("hmgapi")) {
            if (!ids.isEmpty() || all) {
                e.out.add("update HMG-API on its own: engine update hmgapi");
                return 2;
            }
            return c.updateHmgApi();
        }
        if ("update".equals(cmd) && all) {
            for (Object o : (List<Object>) c.updates().get("mods")) ids.add(String.valueOf(((Map<String, Object>) o).get("id")));
            if (ids.isEmpty()) {
                e.out.add("every installed mod is up to date with the catalogue");
                return 0;
            }
        }
        if (ids.isEmpty()) {
            e.out.add("usage: engine " + cmd + " <id>..." + ("update".equals(cmd) ? " | --all | hmgapi" : ""));
            return 2;
        }
        Plan p = c.plan(ids, "update".equals(cmd), anyCar);
        if ("catalog-plan".equals(cmd)) {
            e.out.add(Json.write(p.toJson()));
            return 0;
        }
        if (e.journalFile.exists()) {
            e.out.add("an earlier apply isn't finished or is a trial: run `engine keep` (trial) or `engine recover` first");
            return 1;
        }
        return c.execute(p, anyCar);
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

    @SuppressWarnings("unchecked")
    boolean prereleases() {
        Object c = e.hmgapi.get("catalog");
        if (c instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) c).get("prereleases"))) return true;
        return !new Version(installedHmgApi()).tag.isEmpty();   // on a pre-release: offered the next ones
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
            String base = base();
            File f = new File(tmp, "index.json");
            Http.Result r = http.get(base + "index.json", f, MAX_JSON);
            if (r.ok()) {
                Object idx = Json.parse(Engine.read(f));
                Object list = idx instanceof Map ? ((Map<String, Object>) idx).get("mods") : idx;
                if (!(list instanceof List)) return "the catalogue's index.json isn't a list of mods";
                for (Object o : (List<Object>) list) add(mods, o, notes);
            } else if (r.status == 404) {
                // no index: GitHub's listing of the folder, then each entry from raw (one API call)
                String api = listing(base);
                if (api == null) return "no catalog/index.json at " + base;
                r = http.get(api, f, MAX_JSON);
                if (!r.ok()) return "the catalogue's listing: " + r;
                Object list = Json.parse(Engine.read(f));
                if (!(list instanceof List)) return "the catalogue's listing isn't a list";
                for (Object o : (List<Object>) list) {
                    if (!(o instanceof Map)) continue;
                    String name = String.valueOf(((Map<String, Object>) o).get("name"));
                    if (!name.matches(ID + "\\.json") || "index.json".equals(name)) continue;
                    if (mods.size() >= MAX_ENTRIES) break;
                    File ef = new File(tmp, name);
                    Http.Result er = http.get(base + name, ef, MAX_ENTRY);
                    if (!er.ok()) {
                        notes.add(name + ": " + er);
                        continue;
                    }
                    try {
                        add(mods, Json.parse(Engine.read(ef)), notes);
                    } catch (Exception x) {
                        notes.add(name + ": not JSON");
                    }
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
            if (!notes.isEmpty()) c.put("notes", notes);
            dir.mkdirs();
            File n = new File(dir, ".catalog.json.tmp");
            Engine.write(n, Json.write(c));
            if (!n.renameTo(cacheFile)) throw new IOException("can't write " + cacheFile);
            cache = c;
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
     * HMG-API's newest release this unit is offered (pre-releases only on a pre-release, or with
     * hmgapi.json "catalog": {"prereleases": true}): its update zip and the SHA-256 from its
     * SHA256SUMS; null if none is newer than what's installed.
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
        boolean pre = prereleases();
        for (Object o : (List<Object>) list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> rel = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(rel.get("draft"))) continue;
            if (Boolean.TRUE.equals(rel.get("prerelease")) && !pre) continue;
            String tag = String.valueOf(rel.get("tag_name"));
            if (!tag.matches("v\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?")) continue;
            Version v = new Version(tag);
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
    }

    @SuppressWarnings("unchecked")
    Map<String, Inst> installed() {
        Map<String, Inst> m = new TreeMap<String, Inst>();
        File[] ds = new File(e.data, "mods").listFiles();
        for (File d : ds == null ? new File[0] : ds) {
            File mj = new File(d, "mod.json");
            if (!d.getName().matches(ID) || !mj.isFile()) continue;
            try {
                Inst i = new Inst();
                i.version = String.valueOf(Json.object(Engine.read(mj)).get("version"));
                i.enabled = !new File(d, ".disabled").exists();
                m.put(d.getName(), i);
            } catch (Exception x) {
                // a broken mod.json: the engine's other checks say so
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> entries() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        if (cache == null || !(cache.get("mods") instanceof List)) return m;
        for (Object o : (List<Object>) cache.get("mods")) m.put(String.valueOf(((Map<String, Object>) o).get("id")), (Map<String, Object>) o);
        return m;
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

    /** why this unit can't take the entry, or null: its HMG-API range; "car: ..." for its "system" (an override exists) */
    @SuppressWarnings("unchecked")
    String unfit(Map<String, Object> en) {
        Object need = en.get("hmgapi");
        if (need != null && !Version.meetsHmgapi(installedHmgApi(), String.valueOf(need))) {
            return "needs HMG-API " + need + " (this is " + installedHmgApi() + ")";
        }
        if (en.get("system") != null) {
            String sys = SystemCheck.check(en.get("system"), e.facts, false);
            if (sys != null) return "car: " + sys;
        }
        return null;
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
        for (Map<String, Object> en : entries().values()) {
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
            List<Object> req = new ArrayList<Object>();
            for (Version.Dep d : deps(en.get("requires"))) req.add(d.toString());
            if (!req.isEmpty()) m.put("requires", req);
            m.put("trusted", Packages.trustedAuthor(e, String.valueOf(en.get("uuid"))) != null);
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
            for (Map.Entry<String, Inst> i : installed().entrySet()) {
                Map<String, Object> en = cat.get(i.getKey());
                if (en == null || !"update".equals(status(i.getValue().version, String.valueOf(en.get("version"))))) continue;
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
                list.add(m);
            }
            Map<String, Object> h = hmgapiOffer();
            if (h != null) rep.put("hmgapi", h);
        }
        rep.put("mods", list);
        rep.put("count", list.size() + (rep.get("hmgapi") != null ? 1 : 0));
        return rep;
    }

    /** updates.json, also in public/ for the app (the Mods tab's "Updates (n)") */
    void writeUpdates() throws IOException {
        String text = Json.write(updates());
        dir.mkdirs();
        Engine.write(updatesFile, text);
        File pub = new File(e.data, "public");
        pub.mkdirs();
        File tmp = new File(pub, ".updates.json.tmp");
        Engine.write(tmp, text);
        tmp.setReadable(true, false);
        if (!tmp.renameTo(new File(pub, "updates.json"))) throw new IOException("can't write public/updates.json");
    }

    // ---- dependencies: what installing some mods takes, in order (what they require first)

    static final class Step {
        final String id, action, version, installed, because;
        final Map<String, Object> entry;
        File zip;

        Step(String id, String action, String version, String installed, String because, Map<String, Object> entry) {
            this.id = id;
            this.action = action;
            this.version = version;
            this.installed = installed;
            this.because = because;
            this.entry = entry;
        }
    }

    final class Plan {
        final List<Step> steps = new ArrayList<Step>();
        final List<String> problems = new ArrayList<String>(), notes = new ArrayList<String>();
        final Map<String, Map<String, Object>> cat = entries();
        final Map<String, Inst> inst = installed();
        /** requires learned from the downloaded packages (their mod.json), by id */
        final Map<String, List<Version.Dep>> learned = new LinkedHashMap<String, List<Version.Dep>>();
        final Set<String> visiting = new LinkedHashSet<String>();
        boolean anyCar;

        Step planned(String id) {
            for (Step s : steps) if (s.id.equals(id)) return s;
            return null;
        }

        List<Version.Dep> requires(String id, Map<String, Object> en) {
            List<Version.Dep> r = new ArrayList<Version.Dep>(deps(en.get("requires")));
            if (learned.containsKey(id)) r.addAll(learned.get(id));
            return r;
        }

        boolean fits(Map<String, Object> en, String who) {
            String unfit = unfit(en);
            if (unfit == null) return true;
            if (unfit.startsWith("car: ") && anyCar) {
                notes.add(en.get("id") + " isn't listed for this head unit (" + unfit.substring(5) + "): installing it anyway, at your own risk");
                return true;
            }
            problems.add((who != null ? who + " needs " : "") + en.get("id") + " " + en.get("version") + ", which "
                    + (unfit.startsWith("car: ") ? "isn't for this head unit: " + unfit.substring(5) : unfit));
            return false;
        }

        /** a mod asked for by the owner: installed, or updated */
        void want(String id, boolean update) {
            Map<String, Object> en = cat.get(id);
            Inst i = inst.get(id);
            if (en == null) {
                problems.add(id + " isn't in the catalogue");
                return;
            }
            String st = status(i == null ? null : i.version, String.valueOf(en.get("version")));
            if ("same".equals(st) || "older".equals(st)) {
                if (i != null && !i.enabled && !update) add(new Step(id, "enable", i.version, i.version, null, en));
                else notes.add(id + " " + i.version + " is installed" + ("older".equals(st) ? " (newer than the catalogue's " + en.get("version") + ")" : ""));
                return;
            }
            if (update && i == null) {
                problems.add(id + " isn't installed: install it from the catalogue");
                return;
            }
            if (fits(en, null)) take(en, null);
        }

        /** a requirement: met by what's installed (turned on if it's off) or planned, else the catalogue's */
        void need(Version.Dep d, String who) {
            Step p = planned(d.id);
            if (p != null && !"enable".equals(p.action)) {
                if (!Version.satisfies(p.version, d.range)) problems.add(who + " needs " + d + ", but " + d.id + " " + p.version + " is the one being installed");
                return;
            }
            Inst i = inst.get(d.id);
            if (i != null && Version.satisfies(i.version, d.range)) {
                if (!i.enabled && p == null) add(new Step(d.id, "enable", i.version, i.version, who, cat.get(d.id)));
                return;
            }
            Map<String, Object> en = cat.get(d.id);
            if (en == null) {
                problems.add(who + " needs " + d + (i != null ? " (" + i.version + " is installed)" : "") + ", which isn't in the catalogue");
                return;
            }
            if (!Version.satisfies(String.valueOf(en.get("version")), d.range)) {
                problems.add(who + " needs " + d + ", but the catalogue has " + d.id + " " + en.get("version")
                        + (i != null ? " (" + i.version + " is installed)" : ""));
                return;
            }
            if (i != null && "older".equals(status(i.version, String.valueOf(en.get("version"))))) {
                problems.add(who + " needs " + d + ": the installed " + d.id + " " + i.version + " doesn't fit and the catalogue's is older");
                return;
            }
            if (fits(en, who)) take(en, who);
        }

        void take(Map<String, Object> en, String who) {
            String id = String.valueOf(en.get("id"));
            if (planned(id) != null) return;
            if (!visiting.add(id)) {
                problems.add("a requires loop: " + visiting + " -> " + id);
                return;
            }
            for (Version.Dep d : requires(id, en)) need(d, id);
            visiting.remove(id);
            Inst i = inst.get(id);
            add(new Step(id, i == null ? "install" : "update", String.valueOf(en.get("version")), i == null ? null : i.version, who, en));
        }

        void add(Step s) {
            if (planned(s.id) == null) steps.add(s);
        }

        /** conflicts: theirs (entry, package) against what's installed and on, and what's planned */
        void conflicts(Map<String, List<Version.Dep>> pkgConflicts) {
            for (Step s : steps) {
                if ("enable".equals(s.action) || s.entry == null) continue;
                List<Version.Dep> cs = new ArrayList<Version.Dep>(deps(s.entry.get("conflicts")));
                if (pkgConflicts.containsKey(s.id)) cs.addAll(pkgConflicts.get(s.id));
                for (Version.Dep c : cs) {
                    Step p = planned(c.id);
                    Inst i = inst.get(c.id);
                    String v = p != null ? p.version : i != null && i.enabled ? i.version : null;
                    if (v != null && Version.satisfies(v, c.range)) {
                        problems.add(s.id + " " + s.version + " conflicts with " + c.id + " " + v + (p != null ? " (being installed)" : " (installed)")
                                + ": remove or update " + c.id + " first");
                    }
                }
            }
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", problems.isEmpty());
            List<Object> st = new ArrayList<Object>();
            for (Step s : steps) {
                Map<String, Object> x = new LinkedHashMap<String, Object>();
                x.put("id", s.id);
                x.put("name", s.entry != null && s.entry.get("name") != null ? s.entry.get("name") : s.id);
                x.put("action", s.action);
                x.put("version", s.version);
                if (s.installed != null) x.put("installed", s.installed);
                if (s.because != null) x.put("for", s.because);
                if (s.entry != null) x.put("trusted", Packages.trustedAuthor(e, String.valueOf(s.entry.get("uuid"))) != null);
                st.add(x);
            }
            m.put("steps", st);
            if (!problems.isEmpty()) m.put("problems", problems);
            if (!notes.isEmpty()) m.put("notes", notes);
            return m;
        }
    }

    Plan plan(List<String> ids, boolean update, boolean anyCar) {
        Plan p = new Plan();
        p.anyCar = anyCar;
        for (String id : ids) p.want(id, update);
        p.conflicts(new LinkedHashMap<String, List<Version.Dep>>());
        return p;
    }

    // ---- doing it: everything downloaded and checked first, then installed in order

    @SuppressWarnings("unchecked")
    int execute(Plan p, boolean anyCar) throws Exception {
        if (!p.problems.isEmpty()) {
            for (String x : p.problems) e.out.add("can't: " + x);
            return 1;
        }
        if (p.steps.isEmpty()) {
            for (String n : p.notes) e.out.add(n);
            e.out.add("nothing to install");
            return 0;
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
            // a package can require more than its entry says (an entry from before catalog.py copied
            // requires): its own mod.json is the truth, so the plan is made again until nothing new turns up
            Map<String, List<Version.Dep>> pkgConflicts = new LinkedHashMap<String, List<Version.Dep>>();
            for (int round = 0; ; round++) {
                boolean more = false;
                for (Step s : p.steps) {
                    if ("enable".equals(s.action) || s.zip != null) continue;
                    if (http == null) {
                        List<String> why = new ArrayList<String>();
                        Http.Transport t = NativeFetch.forEngine(e, why);
                        if (t == null) {
                            e.out.add("can't download: " + why.get(0));
                            return 1;
                        }
                        http = new Http(t, dl);
                    }
                    File z = new File(dl, s.id + "-" + s.version + ".zip");
                    Http.Result r = http.get(String.valueOf(s.entry.get("download")), z, (int) Packages.MAX_BYTES);
                    if (!r.ok()) {
                        e.out.add("can't download " + s.id + " " + s.version + ": " + r + " (nothing was installed)");
                        return 1;
                    }
                    String sha = Sha256.hex(z), want = String.valueOf(s.entry.get("sha256"));
                    if (!sha.equals(want)) {
                        e.out.add("refused " + s.id + " " + s.version + ": the download's SHA-256 is " + sha + ", the catalogue says " + want
                                + " (nothing was installed)");
                        return 1;
                    }
                    Map<String, Object> mj = modJson(z);
                    if (mj == null || !s.id.equals(mj.get("id")) || !s.version.equals(String.valueOf(mj.get("version")))) {
                        e.out.add("refused " + s.id + " " + s.version + ": the package is " + (mj == null ? "not a mod" : mj.get("id") + " " + mj.get("version"))
                                + " (nothing was installed)");
                        return 1;
                    }
                    s.zip = z;
                    e.out.add("downloaded " + s.id + " " + s.version + " (" + z.length() + " bytes, SHA-256 as the catalogue's)");
                    pkgConflicts.put(s.id, deps(mj.get("conflicts")));
                    List<Version.Dep> req = deps(mj.get("requires")), known = p.requires(s.id, s.entry);
                    for (Version.Dep d : req) {
                        boolean have = false;
                        for (Version.Dep k : known) have |= k.id.equals(d.id) && String.valueOf(k.range).equals(String.valueOf(d.range));
                        if (!have) {
                            if (!p.learned.containsKey(s.id)) p.learned.put(s.id, new ArrayList<Version.Dep>());
                            p.learned.get(s.id).add(d);
                            more = true;
                        }
                    }
                }
                if (!more) break;
                if (round >= 5) {
                    e.out.add("can't: the packages' requires keep changing (nothing was installed)");
                    return 1;
                }
                // again with what the packages said; the downloads already made are kept
                Plan q = new Plan();
                q.anyCar = anyCar;
                q.learned.putAll(p.learned);
                List<String> wanted = new ArrayList<String>();
                for (Step s : p.steps) if (s.because == null) wanted.add(s.id);
                for (String id : wanted) q.want(id, false);
                for (Step s : q.steps) {
                    Step old = p.planned(s.id);
                    if (old != null && old.version.equals(s.version)) s.zip = old.zip;
                }
                if (!q.problems.isEmpty()) {
                    for (String x : q.problems) e.out.add("can't: " + x);
                    e.out.add("(nothing was installed)");
                    return 1;
                }
                p = q;
            }
            p.conflicts(pkgConflicts);
            if (!p.problems.isEmpty()) {
                for (String x : p.problems) e.out.add("can't: " + x);
                e.out.add("(nothing was installed)");
                return 1;
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
                int rc = Packages.install(e, s.zip.getPath(), String.valueOf(s.entry.get("uuid")), false, anyCar);
                if (rc != 0) {
                    e.out.add("stopped at " + s.id + (done > 0 ? ": the " + done + " before it are installed (not applied yet)" : ": nothing was installed"));
                    if (done > 0) NemesisSync.run(e);
                    writeUpdates();
                    return rc;
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

    /** HMG-API itself: its update zip, checked against SHA256SUMS, then its own installer (UsbUpdates: the release key) */
    @SuppressWarnings("unchecked")
    int updateHmgApi() throws Exception {
        Map<String, Object> h = hmgapiOffer();
        if (h == null) {
            e.out.add("HMG-API " + installedHmgApi() + " is the newest release offered here (engine updates --refresh checks again)");
            return 0;
        }
        String off = offline();
        if (off != null) {
            e.out.add("can't download: " + off);
            return 1;
        }
        List<String> why = new ArrayList<String>();
        Http.Transport t = NativeFetch.forEngine(e, why);
        if (t == null) {
            e.out.add("can't download: " + why.get(0));
            return 1;
        }
        File dl = new File(dir, "downloads");
        Engine.rm(dl);
        dl.mkdirs();
        try {
            File z = new File(dl, "hmgapi-" + h.get("version") + "-update.zip");
            Http.Result r = new Http(t, dl).get(String.valueOf(h.get("download")), z, 128 << 20);
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

    /**
     * wifi.sh, when home Wi-Fi comes up: refresh, write updates.json, and stage the mods' updates if
     * the owner allowed it (hmgapid allow auto_stage_updates on). HMG-API itself is never updated
     * here. What happened goes to logs/hmgapi/updates.log.
     */
    @SuppressWarnings("unchecked")
    int updateCheck() throws Exception {
        List<String> log = new ArrayList<String>();
        String err = refresh();
        Map<String, Object> u = updates();
        if (err != null) log.add("check: " + err);
        else log.add("checked: " + u.get("count") + " update(s)" + (u.get("hmgapi") != null ? " (HMG-API " + ((Map<String, Object>) u.get("hmgapi")).get("version") + " among them)" : ""));
        int rc = 0;
        if (err == null && new File(e.data, "settings/allow/auto_stage_updates").exists() && !((List<Object>) u.get("mods")).isEmpty()) {
            List<String> ids = new ArrayList<String>();
            for (Object o : (List<Object>) u.get("mods")) ids.add(String.valueOf(((Map<String, Object>) o).get("id")));
            if (e.journalFile.exists()) {
                log.add("not staged: an earlier apply isn't finished");
            } else {
                int before = e.out.size();
                rc = execute(plan(ids, true, false), false);
                log.addAll(e.out.subList(before, e.out.size()));
                log.add(rc == 0 ? "staged: " + ids + " (the owner applies them)" : "not staged (" + rc + ")");
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
        return err != null ? 1 : rc;
    }
}
