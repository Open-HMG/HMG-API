package hmgapi.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Mods' file changes as NEMESIS LivePatch outputs (NEMESIS 1.17.1 or newer) instead of writes to /system.
 *
 * The engine builds, merges, checks, backs up and records every file as before; only where a result
 * goes changes. Engine.sys() is this folder, HMG-API's view of /system:
 *
 *   data/livepatch/files/system/...   a file HMG-API owns: its result
 *                                     any other: the stock file, copied from NEMESIS (`nemesis livepatch
 *                                     stock`, which reads under every LivePatch output) when first read,
 *                                     dropped at the next plan
 *
 * NEMESIS gets the owned results in HMG-API's own NEMESIS package, "hmgapi": [file.N] for each file,
 * [prop.N] for build.prop's key edits, and livepatch-first=1, which puts them under every NEMESIS
 * package's patch of the same file. /system itself isn't written: NEMESIS's farms over its folders stay
 * up, NEMESIS's safe mode shows stock, and HMG-API's package going (uninstall) takes the changes with it.
 *
 * The package always goes through NEMESIS's queue (`nemesis queue add`), never a nemesis command that
 * waits for NEMESIS: NEMESIS may be running this (a mod package's postinstall runs `engine apply`), and
 * a second NEMESIS job would wait for that one forever. NEMESIS asks for the restart when its job is done.
 *
 * build.prop: init reads it before /data is there, so NEMESIS never mounts it. A key HMG-API sets is a
 * [prop.N] (set before the framework starts; services that start before it see the stock value); when
 * a mod removes a key, or a value is one [prop.N] can't hold, HMG-API writes build.prop itself, as before.
 *
 * Without NEMESIS 1.17.1 (or once NEMESIS is gone), sys() is /system again and this folder goes: the
 * engine finds its files stock, takes them as found ("the stock HMG-API started from") and writes them
 * as before at the next apply.
 */
final class NemesisFiles {
    static final String NEED = "1.17.1";
    static final String BUILD_PROP = "/system/build.prop";
    static final int MAX_SECTIONS = 99;   // NEMESIS reads [file.1] to [file.99], [prop.1] to [prop.99]
    static final int PROP_VALUE_MAX = 91; // NEMESIS's [prop.N] value (Android's PROP_VALUE_MAX less one)

    final Engine e;
    final File nemesis, dir, state, marker, gen, propWritten;
    /** paths whose stock copy this run already asked NEMESIS for (absent stays absent) */
    final Set<String> fetched = new LinkedHashSet<String>();

    NemesisFiles(Engine e) {
        this.e = e;
        nemesis = new File(e.root + "/system/xbin/nemesis");
        dir = new File(e.data, "livepatch/files");
        state = new File(e.stateDir, "livepatch");
        marker = new File(state, "on");
        gen = new File(state, "generation");
        propWritten = new File(state, "build.prop.written");
    }

    /** NEMESIS that takes HMG-API's file changes, or null. */
    static NemesisFiles detect(Engine e) {
        NemesisFiles n = new NemesisFiles(e);
        if (!n.nemesis.isFile()) return null;
        try {
            Engine.Run v = NemesisSync.nemesisVersion(e, n.nemesis);
            String ver = v.text.trim();
            if (v.code != 0 || !ver.matches("[0-9]+(\\.[0-9]+)*") || NemesisSync.cmp(ver, NEED) < 0) return null;
        } catch (Exception x) {
            return null;
        }
        return n;
    }

    /** NEMESIS gone or too old: the view goes, so the engine sees /system again (and rebuilds onto it). */
    static void off(Engine e) {
        NemesisFiles n = new NemesisFiles(e);
        if (!n.marker.isFile()) return;
        Engine.rm(n.dir);
        Engine.rm(n.state);
        e.out.add("NEMESIS LivePatch isn't there any more: HMG-API's file changes go to /system again at the next apply");
    }

    /** HMG-API's own install files never go through here (mods can't change them anyway). */
    boolean routes(String path) {
        if (!path.startsWith("/system/")) return false;
        for (String x : Engine.HMGAPI_OWNED) if (x.equals(path)) return false;
        return true;
    }

    File file(String path) {
        return new File(dir, path.substring(1));
    }

    /** What HMG-API sees at path: its own result, or NEMESIS's stock file (or nothing: absent). */
    File view(String path) {
        File f = file(path);
        if (f.exists() || fetched.contains(path)) return f;
        // owned: its result is here, or (not moved yet, see migrate) still in /system
        if (owned().contains(path)) return marker.isFile() ? f : new File(e.root + path);
        fetched.add(path);
        try {
            f.getParentFile().mkdirs();
            Engine.Run r = Engine.exec(nemesis.getPath(), "livepatch", "stock", path, f.getPath());
            if (r.code != 0 && r.code != 2) throw new IOException("nemesis livepatch stock " + path + ": " + NemesisSync.last(r.text));
        } catch (IOException x) {
            throw new RuntimeException(x.getMessage(), x);
        } catch (InterruptedException x) {
            throw new RuntimeException("interrupted", x);
        }
        return f;
    }

    Set<String> owned;

    /** Paths HMG-API owns (state files, and a rebuilt app's odex beside it). */
    @SuppressWarnings("unchecked")
    Set<String> owned() {
        if (owned != null) return owned;
        Set<String> s = new TreeSet<String>();
        try {
            Map<String, Object> files = (Map<String, Object>) e.loadState().get("files");
            for (Map.Entry<String, Object> f : files.entrySet()) {
                s.add(f.getKey());
                if (Boolean.TRUE.equals(((Map<String, Object>) f.getValue()).get("app"))) s.add(odex(f.getKey()));
            }
        } catch (IOException x) {
            throw new RuntimeException(x.getMessage(), x);
        }
        return owned = s;
    }

    static String odex(String apk) {
        return apk.replaceAll("\\.apk$", ".odex");
    }

    /**
     * Before a plan (engine lock held): stock copies from the last run go (the firmware or NEMESIS may
     * have changed since), and files HMG-API wrote to /system before NEMESIS took them move here once.
     */
    void start(boolean restoreAll) throws Exception {
        owned = null;
        fetched.clear();
        prune(dir, "");
        if (e.writes && !marker.isFile() && !e.journalFile.exists()) migrate(restoreAll);
    }

    void prune(File d, String rel) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String p = rel + "/" + f.getName();
            if (f.isDirectory()) {
                prune(f, p);
                String[] left = f.list();
                if (left != null && left.length == 0) f.delete();
            } else if (!owned().contains(p)) {
                f.delete();
            }
        }
    }

    /**
     * HMG-API 1.1.0-rc.9 and older wrote /system: each file it owns that is still its result there is
     * copied here, and /system gets its stock bytes back (from HMG-API's backup), between `nemesis
     * remount rw --bypass-livepatch` and `ro`, so NEMESIS's read-only farms are out of the way while
     * those writes land. One that changed since is left as it is: the
     * plan sees it changed outside HMG-API and settles it as it always has.
     */
    @SuppressWarnings("unchecked")
    void migrate(boolean restoreAll) throws Exception {
        Map<String, Object> files = (Map<String, Object>) e.loadState().get("files");
        List<String> moved = new ArrayList<String>();
        boolean rw = false;
        try {
            for (Map.Entry<String, Object> en : files.entrySet()) {
                String path = en.getKey();
                Map<String, Object> f = (Map<String, Object>) en.getValue();
                File real = new File(e.root + path), mine = file(path);
                if (!routes(path) || !Engine.md5(real).equals(f.get("result"))) continue;
                String stock = String.valueOf(f.get("stock"));
                File bak = e.backup(path);
                if (!"absent".equals(stock) && !(bak.isFile() && Engine.md5(bak).equals(stock))) {
                    e.out.add("NEMESIS LivePatch: " + path + " stays as HMG-API wrote it to /system (no stock backup to put back)");
                    continue;
                }
                mine.getParentFile().mkdirs();
                Engine.copy(real, mine);
                boolean app = Boolean.TRUE.equals(f.get("app"));
                File realOdex = new File(e.root + odex(path));
                if (app && realOdex.isFile()) Engine.copy(realOdex, file(odex(path)));
                if (!rw) {
                    realRemount("rw");
                    rw = true;
                }
                if ("absent".equals(stock)) real.delete();
                else e.put(bak, real, "644");
                if (app && e.backup(odex(path)).isFile()) e.put(e.backup(odex(path)), realOdex, "644");
                moved.add(path);
            }
        } finally {
            if (rw) realRemount("ro");
        }
        state.mkdirs();
        Engine.write(marker, NEED + "\n");
        owned = null;
        if (moved.isEmpty()) return;
        e.out.add("NEMESIS LivePatch: " + moved.size() + " file(s) HMG-API had written to /system are stock there again,"
                + " and go to NEMESIS as LivePatch outputs: " + moved);
        if (!restoreAll) commit();
    }

    /**
     * NEMESIS's remount, also off the car: there it's the tests' NEMESIS. A plain `rw` leaves NEMESIS's
     * read-only farms up; `--bypass-livepatch` (NEMESIS 1.17.1, which this class requires) takes them
     * down until `ro` puts them back.
     */
    void realRemount(String mode) throws Exception {
        Engine.Run r = "rw".equals(mode)
                ? Engine.exec(nemesis.getPath(), "remount", "rw", "--bypass-livepatch")
                : Engine.exec(nemesis.getPath(), "remount", mode);
        if (r.code != 0 && "rw".equals(mode)) throw new IOException("nemesis remount rw: " + NemesisSync.last(r.text));
    }

    // ------------------------------------------------------------------ the package

    /** HMG-API's NEMESIS package's LivePatch part: its sections, the files they name, its generation. */
    static final class Pack {
        String text = "";
        Map<String, File> files = new TreeMap<String, File>();   // zip name -> file
        int generation;
    }

    /** The owned results as sections; build.prop's own way (see the class comment). */
    @SuppressWarnings("unchecked")
    Pack pack() throws Exception {
        owned = null;
        Pack p = new Pack();
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String path : owned()) {
            if (BUILD_PROP.equals(path)) continue;
            File f = file(path);
            if (!f.isFile()) continue;   // its result is "absent": stock shows (only ever a file HMG-API added)
            if (++n > MAX_SECTIONS) throw new IOException("HMG-API changes more than " + MAX_SECTIONS + " files, more than one NEMESIS package takes");
            String zipName = "files" + path;
            b.append("\n[file.").append(n).append("]\ntarget=").append(path).append("\nsource=").append(zipName).append('\n');
            p.files.put(zipName, f);
        }
        Map<String, String> props = buildProp();
        if (props != null) {
            int k = 0;
            for (Map.Entry<String, String> en : props.entrySet()) {
                b.append("\n[prop.").append(++k).append("]\nkey=").append(en.getKey()).append("\nvalue=").append(en.getValue()).append('\n');
            }
        }
        p.text = b.toString();
        // the package's version: HMG-API's, then a generation that goes up whenever this part changes
        StringBuilder sig = new StringBuilder(p.text);
        for (Map.Entry<String, File> en : p.files.entrySet()) sig.append(en.getKey()).append(' ').append(Engine.md5(en.getValue())).append('\n');
        String sum = md5(sig.toString());
        String[] was = gen.isFile() ? Engine.read(gen).trim().split(" ") : new String[0];
        p.generation = was.length == 2 && was[0].matches("[0-9]{1,9}") ? Integer.parseInt(was[0]) : 0;
        if (was.length != 2 || !was[1].equals(sum)) {
            p.generation++;
            state.mkdirs();
            Engine.write(gen, p.generation + " " + sum + "\n");
        }
        return p;
    }

    /**
     * build.prop: the keys HMG-API sets as [prop.N] (and /system's build.prop stock), or null when
     * HMG-API writes /system's build.prop itself (and does so here) or doesn't change it.
     */
    Map<String, String> buildProp() throws Exception {
        File mine = file(BUILD_PROP), real = new File(e.root + BUILD_PROP), bak = e.backup(BUILD_PROP);
        boolean owns = owned().contains(BUILD_PROP) && mine.isFile() && bak.isFile();
        String wrote = propWritten.isFile() ? Engine.read(propWritten).trim() : null;
        Map<String, String> set = null;
        String why = null;
        if (owns) {
            Map<String, String> stock = keys(bak), now = keys(mine);
            set = new LinkedHashMap<String, String>();
            for (String k : stock.keySet()) if (!now.containsKey(k)) why = "a mod removes " + k;
            for (Map.Entry<String, String> en : now.entrySet()) {
                String v = en.getValue();
                if (v.equals(stock.get(en.getKey()))) continue;
                if (v.getBytes("UTF-8").length > PROP_VALUE_MAX || v.startsWith("\"") || v.indexOf('\t') >= 0)
                    why = en.getKey() + "'s value is longer than " + PROP_VALUE_MAX + " bytes or starts with a quote";
                set.put(en.getKey(), v);
            }
            if (set.size() > MAX_SECTIONS) why = "more than " + MAX_SECTIONS + " keys";
        }
        // what /system's build.prop must hold now: HMG-API's own (it writes it), or stock back
        File want = owns && why != null ? mine : wrote != null && wrote.equals(Engine.md5(real)) ? bak : null;
        if (want != null && want.isFile() && !Engine.md5(want).equals(Engine.md5(real))) {
            realRemount("rw");
            try {
                e.put(want, real, "644");
            } finally {
                realRemount("ro");
            }
        }
        if (owns && why != null) {
            Engine.write(propWritten, Engine.md5(real) + "\n");
            e.out.add("NEMESIS LivePatch: HMG-API writes " + BUILD_PROP + " itself (" + why + "), so NEMESIS's safe mode doesn't undo it");
            return null;
        }
        propWritten.delete();
        return owns ? set : null;
    }

    static String md5(String text) throws Exception {
        java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
        StringBuilder s = new StringBuilder();
        for (byte x : d.digest(text.getBytes("UTF-8"))) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    /** build.prop's keys, as init reads them (the last one wins). */
    static Map<String, String> keys(File f) throws IOException {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String l : Engine.read(f).split("\n")) {
            l = l.trim();
            int eq = l.indexOf('=');
            if (l.isEmpty() || l.startsWith("#") || eq <= 0) continue;
            m.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
        }
        return m;
    }

    /**
     * After an apply or a recover: HMG-API's package with this part, to NEMESIS's queue. A failure
     * is said, never thrown: HMG-API's own records are done, and the next sync tries again.
     */
    void commit() {
        try {
            NemesisSync.queueHmgapi(e, this);
        } catch (Exception x) {
            e.out.add("NEMESIS LivePatch: couldn't hand HMG-API's file changes to NEMESIS: " + x.getMessage()
                    + " (engine nemesis-sync tries again)");
        }
    }

    /** Files for the zip, streamed (an app can be large). */
    static void putFile(ZipOutputStream z, String name, File f) throws IOException {
        ZipEntry en = new ZipEntry(name);
        en.setTime(1767225600000L);
        z.putNextEntry(en);
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) z.write(b, 0, n);
        } finally {
            in.close();
        }
        z.closeEntry();
    }

    /** HMG-API's package version for the last file changes handed over, or null (none yet). */
    String expected() throws IOException {
        String[] w = gen.isFile() ? Engine.read(gen).trim().split(" ") : new String[0];
        return w.length == 2 ? NemesisSync.packageVersion(Engine.HMGAPI_VERSION) + "." + w[0] : null;
    }

    /**
     * Why NEMESIS doesn't have HMG-API's last file changes: the last line of its failed queue job, or
     * null when NEMESIS has them (or its job is still waiting in the queue).
     */
    String failed() {
        try {
            String want = expected();
            if (want == null || want.equals(NemesisSync.listed(e).get("hmgapi"))) return null;
        } catch (IOException x) {
            return null;
        }
        File[] jobs = new File(e.root + "/data/nemesis/queue").listFiles();
        if (jobs == null) return null;
        java.util.Arrays.sort(jobs);
        String why = null;
        for (File j : jobs) {
            if (!j.getName().endsWith(".job")) continue;
            try {
                String t = "\n" + Engine.read(j) + "\n";
                if (!t.contains("\nid=hmgapi\n") || !t.contains("\nstate=failed\n")) continue;
                File o = new File(j.getPath().replaceAll("\\.job$", ".out"));
                why = o.isFile() ? NemesisSync.last(Engine.read(o)) : "no output";
            } catch (IOException x) {
                // being written: the next look sees it
            }
        }
        return why;
    }
}
