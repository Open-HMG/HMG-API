package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates for installed mods from their own GitHub releases: for a mod the catalogue doesn't list
 * (installed from USB or adb, a private mod, one waiting for its catalogue PR), or one whose owner
 * chose its releases (a test build from a fork, an entry that fell behind).
 *
 * Where to look: "repo" in the installed mod's own mod.json. It came inside the signed package, so
 * only the author's key chose it. Which zip: "release_asset" in mod.json ("my-mod-v{version}.zip"),
 * else the one .zip asset named "<id>-...". Each mod's update source (Catalog.source): the owner's
 * choice (engine update-source), else the catalogue when it lists the mod, else its releases when
 * its repo is on GitHub, else none.
 *
 * Each release found becomes an entry shaped like a catalogue entry ("source": "release"), kept in
 * /data/hmgapi/catalog/releases.json, so planning, staging and installing are the catalogue's own.
 * Trust is the installed mod's author key: a release is only ever an update by that key (never a
 * first install, a key change or a downgrade), checked by Packages as any update is; the package's
 * id and version must be the release's; its SHA-256 is checked when the release gives one
 * (SHA256SUMS, or the asset's digest). That is what catalog-automerge.yml takes for a catalogue
 * entry with no one looking: a newer version from the mod's own repo's releases, the same key.
 * A maintainer can still stop a bad version: catalog/withdrawn.json (Catalog.withdrawn) holds
 * for both.
 *
 * GitHub allows 60 API calls an hour without an account: one call per repo (mods from one repo
 * share it), at most MAX_REPOS a refresh, the repos checked longest ago first; the rest keep
 * their last answer until the next.
 */
final class Releases {
    static final int MAX_REPOS = 10, PER_PAGE = 10, MAX_NOTES = 6000;
    static final Pattern REPO = Pattern.compile("https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?");
    static final String TAG = "v?\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?";

    private Releases() {
    }

    /** "owner/repo" for a GitHub repo link, else null */
    static String repoOf(Object repo) {
        if (!(repo instanceof String)) return null;
        Matcher m = REPO.matcher((String) repo);
        return m.matches() ? m.group(1) + "/" + m.group(2) : null;
    }

    /** an installed mod, as a release check needs it */
    static final class Mod {
        String id, version, name, description, repo, asset, uuid;
    }

    @SuppressWarnings("unchecked")
    static Mod mod(Engine e, String id) {
        File d = new File(e.data, "mods/" + id);
        try {
            Map<String, Object> j = Json.object(Engine.read(new File(d, "mod.json")));
            Mod m = new Mod();
            m.id = id;
            m.version = String.valueOf(j.get("version"));
            m.name = j.get("name") instanceof String ? (String) j.get("name") : id;
            m.description = j.get("description") instanceof String ? (String) j.get("description") : null;
            m.repo = repoOf(j.get("repo"));
            m.asset = j.get("release_asset") instanceof String ? (String) j.get("release_asset") : null;
            m.uuid = Packages.installedUuid(d);
            return m;
        } catch (Exception x) {
            return null;
        }
    }

    /**
     * Checks the repos of the mods given (their source is releases) and writes releases.json; notes
     * get what was left out and why. The last answer for a repo not checked this time is kept.
     */
    @SuppressWarnings("unchecked")
    static void check(Catalog c, Http http, File tmp, List<Mod> mods, List<String> notes) throws IOException {
        Map<String, Object> old = load(c);
        Map<String, Object> oldRepos = old.get("repos") instanceof Map ? (Map<String, Object>) old.get("repos") : new LinkedHashMap<String, Object>();
        Map<String, List<Mod>> byRepo = new TreeMap<String, List<Mod>>();
        for (Mod m : mods) {
            if (m.repo == null || m.uuid == null) continue;
            if (!byRepo.containsKey(m.repo)) byRepo.put(m.repo, new ArrayList<Mod>());
            byRepo.get(m.repo).add(m);
        }
        // the repos checked longest ago (or never) first
        List<String> order = new ArrayList<String>(byRepo.keySet());
        final Map<String, Object> or = oldRepos;
        Collections.sort(order, new Comparator<String>() {
            public int compare(String a, String b) {
                long x = when(or.get(a)), y = when(or.get(b));
                return x < y ? -1 : x > y ? 1 : a.compareTo(b);
            }
        });
        Map<String, Object> repos = new TreeMap<String, Object>();
        List<Object> entries = new ArrayList<Object>();
        int asked = 0;
        boolean limited = false;
        for (String repo : order) {
            Map<String, Object> last = or.get(repo) instanceof Map ? (Map<String, Object>) or.get(repo) : null;
            List<Object> list = null;
            if (!limited && asked < MAX_REPOS) {
                asked++;
                File f = new File(tmp, "rel-" + asked + ".json");
                Http.Result r = http.get("https://api.github.com/repos/" + repo + "/releases?per_page=" + PER_PAGE, f, Catalog.MAX_JSON);
                if (r.status == 403 || r.status == 429) {
                    limited = true;
                    notes.add("GitHub's API limit (60 an hour) is reached: " + repo + " and the rest are checked next time");
                } else if (!r.ok()) {
                    notes.add(repo + "'s releases: " + r + (r.status == 404 ? " (is its mod.json \"repo\" right, and the repo public?)" : ""));
                } else {
                    try {
                        Object o = Json.parse(Engine.read(f));
                        if (o instanceof List) list = (List<Object>) o;
                        else notes.add(repo + "'s releases: not a list");
                    } catch (Exception x) {
                        notes.add(repo + "'s releases: not JSON");
                    }
                }
            }
            if (list == null) {   // not asked this time, or it failed: the last answer stays
                if (last != null) {
                    repos.put(repo, last);
                    for (Object en : last.get("entries") instanceof List ? (List<Object>) last.get("entries") : new ArrayList<Object>()) entries.add(en);
                }
                continue;
            }
            Map<String, Object> got = new LinkedHashMap<String, Object>();
            got.put("checked", System.currentTimeMillis());
            List<Object> found = new ArrayList<Object>();
            for (Mod m : byRepo.get(repo)) {
                Map<String, Object> en = newest(c, http, tmp, m, list, notes);
                if (en != null) found.add(en);
            }
            got.put("entries", found);
            repos.put(repo, got);
            entries.addAll(found);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("checked", System.currentTimeMillis());
        out.put("repos", repos);
        out.put("mods", entries);
        Catalog.writeWhole(file(c), Json.write(out), false);
    }

    static long when(Object repo) {
        Object t = repo instanceof Map ? ((Map<?, ?>) repo).get("checked") : null;
        return t instanceof Number ? ((Number) t).longValue() : 0;
    }

    static File file(Catalog c) {
        return new File(c.dir, "releases.json");
    }

    static Map<String, Object> load(Catalog c) {
        try {
            if (file(c).isFile()) return Json.object(Engine.read(file(c)));
        } catch (Exception x) {
            // unreadable: checked again
        }
        return new LinkedHashMap<String, Object>();
    }

    /** the release entries kept, by id */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> entries(Catalog c) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        Object l = load(c).get("mods");
        for (Object o : l instanceof List ? (List<Object>) l : new ArrayList<Object>()) {
            if (o instanceof Map) m.put(String.valueOf(((Map<String, Object>) o).get("id")), (Map<String, Object>) o);
        }
        return m;
    }

    /**
     * The newest release of m's repo that this unit is offered, newer than what's installed, with its
     * zip, as an entry; null if none (said why in notes when one was close).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> newest(Catalog c, Http http, File tmp, Mod m, List<Object> list, List<String> notes) {
        Version have;
        try {
            have = new Version(m.version);
        } catch (IllegalArgumentException x) {
            notes.add(m.id + ": its installed version " + m.version + " can't be compared");
            return null;
        }
        // a beta tester stays on that mod's betas; anyone else gets finals, unless the owner opted in to pre-releases
        boolean pre = c.prereleases() || !have.tag.isEmpty();
        Version bv = have;
        Map<String, Object> bestAsset = null, bestRel = null;
        String bestVer = null, bestAt = null, skipped = null;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> rel = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(rel.get("draft"))) continue;
            String tag = String.valueOf(rel.get("tag_name"));
            if (!tag.matches(TAG)) continue;
            String ver = tag.startsWith("v") ? tag.substring(1) : tag;
            if (!Catalog.valid(ver)) continue;
            Version v = new Version(ver);
            if ((Boolean.TRUE.equals(rel.get("prerelease")) || !v.tag.isEmpty()) && !pre) continue;
            if (v.compareTo(bv) <= 0) continue;
            String why = c.withdrawn(m.id, ver);
            if (why != null) {
                skipped = m.id + " " + ver + " is withdrawn (" + why + ")";
                continue;
            }
            Map<String, Object> a = asset(m, ver, rel.get("assets"));
            if (a == null) {
                skipped = m.id + " " + ver + ": its release has no " + (m.asset != null ? m.asset.replace("{version}", ver) : m.id + "-....zip") + " asset";
                continue;
            }
            Object size = a.get("size");
            if (size instanceof Number && ((Number) size).longValue() > Packages.MAX_BYTES) {
                skipped = m.id + " " + ver + ": its zip is " + size + " bytes, more than HMG-API takes (" + Packages.MAX_BYTES + ")";
                continue;
            }
            String at = where(rel, m.repo), url = String.valueOf(a.get("browser_download_url"));
            if (!url.startsWith("https://github.com/" + at + "/releases/download/")) {
                skipped = m.id + " " + ver + ": its zip isn't a download from " + at + "'s releases";
                continue;
            }
            bestAt = at;
            bestAsset = a;
            bestRel = rel;
            bestVer = ver;
            bv = v;
        }
        if (bestRel == null) {
            if (skipped != null) notes.add(skipped);
            return null;
        }
        String ver = bestVer;
        Map<String, Object> en = new LinkedHashMap<String, Object>();
        en.put("id", m.id);
        en.put("name", m.name);
        if (m.description != null) en.put("description", m.description);
        en.put("version", ver);
        en.put("uuid", m.uuid);
        en.put("repo", "https://github.com/" + bestAt);
        en.put("download", bestAsset.get("browser_download_url"));
        if (bestAsset.get("size") instanceof Number) en.put("size", bestAsset.get("size"));
        String sha = sha256(http, tmp, bestAt, String.valueOf(bestAsset.get("name")), bestRel.get("assets"), bestAsset);
        if (sha != null) en.put("sha256", sha);
        en.put("source", "release");
        en.put("prerelease", Boolean.TRUE.equals(bestRel.get("prerelease")) || !bv.tag.isEmpty());
        if (bestRel.get("html_url") instanceof String) en.put("page", bestRel.get("html_url"));
        String body = bestRel.get("body") instanceof String ? ((String) bestRel.get("body")).trim() : "";
        if (body.length() > MAX_NOTES) body = body.substring(0, MAX_NOTES) + "\n…";
        if (!body.isEmpty()) en.put("notes", body);
        return en;
    }

    /**
     * The repo a release is in, from its page: the one mod.json names, or where GitHub moved it (a
     * transfer to another owner, a rename; GitHub's API follows those). Its downloads must be that
     * repo's. Either way the package must still be signed by the installed key.
     */
    static String where(Map<String, Object> rel, String repo) {
        Object page = rel.get("html_url");
        if (page instanceof String) {
            Matcher m = Pattern.compile("https://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/releases/tag/.+").matcher((String) page);
            if (m.matches()) return m.group(1);
        }
        return repo;
    }

    /** the release's zip for m: "release_asset" with {version}, else the one .zip named "<id>-..." (the one with the version, if several) */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asset(Mod m, String ver, Object assets) {
        List<Map<String, Object>> zips = new ArrayList<Map<String, Object>>();
        for (Object o : assets instanceof List ? (List<Object>) assets : new ArrayList<Object>()) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> a = (Map<String, Object>) o;
            String n = String.valueOf(a.get("name"));
            if (m.asset != null) {
                if (n.equals(m.asset.replace("{version}", ver))) return a;
                continue;
            }
            if (n.startsWith(m.id + "-") && n.endsWith(".zip")) zips.add(a);
        }
        if (zips.size() == 1) return zips.get(0);
        Map<String, Object> hit = null;
        for (Map<String, Object> a : zips) {
            String n = String.valueOf(a.get("name"));
            if (n.equals(m.id + "-" + ver + ".zip") || n.equals(m.id + "-v" + ver + ".zip")) {
                if (hit != null) return null;
                hit = a;
            }
        }
        return hit;
    }

    /** the zip's SHA-256: from the release's SHA256SUMS, else GitHub's digest of the asset, else null (the signature decides) */
    @SuppressWarnings("unchecked")
    static String sha256(Http http, File tmp, String repo, String name, Object assets, Map<String, Object> asset) {
        for (Object o : assets instanceof List ? (List<Object>) assets : new ArrayList<Object>()) {
            if (!(o instanceof Map) || !"SHA256SUMS".equals(((Map<String, Object>) o).get("name"))) continue;
            String url = String.valueOf(((Map<String, Object>) o).get("browser_download_url"));
            if (!url.startsWith("https://github.com/" + repo + "/releases/download/")) break;
            try {
                File s = new File(tmp, "sums-" + name);
                if (!http.get(url, s, Catalog.MAX_ENTRY).ok()) break;
                for (String l : Engine.read(s).split("\n")) {
                    String[] p = l.trim().split("\\s+");
                    if (p.length == 2 && p[1].replaceFirst("^\\*", "").equals(name) && p[0].matches("[0-9a-f]{64}")) return p[0];
                }
            } catch (IOException x) {
                // no sums: the digest, or the signature alone
            }
            break;
        }
        Object d = asset.get("digest");
        if (d instanceof String && ((String) d).matches("sha256:[0-9a-f]{64}")) return ((String) d).substring(7);
        return null;
    }
}
