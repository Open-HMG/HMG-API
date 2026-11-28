package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A /system file's mode, owner, group and SELinux label. Read before HMG-API first writes a
 * file (files.json "stockMeta" per path, and each journal entry) and put back with its bytes
 * on apply, rollback and restore: a stock /system/bin binary put back as 644 doesn't run, and
 * one put back as 0:0 instead of 0:2000, or with another label, may not either.
 *
 * On the unit: libcore's stat and android.os.SELinux (both in every 4.4 app_process), toolbox
 * as the fallback. Off the unit (HMGAPI_HOST=1) the mode is the desktop file's own (chmod),
 * and owners and labels, which a desktop user can't set, live in {@code <root>/meta.txt}, one
 * "<path> <uid> <gid> <label or ->" per line (no line: 0 0 -), so tests can set and check them.
 */
final class FileMeta {
    int mode, uid, gid;
    String context;   // null: not known (SELinux off or unreadable): left as the system labels it

    FileMeta(int mode, int uid, int gid, String context) {
        this.mode = mode;
        this.uid = uid;
        this.gid = gid;
        this.context = context;
    }

    /** android_filesystem_config.h's defaults for /system, when nothing was recorded. */
    static FileMeta defaults(String path) {
        if (path.startsWith("/system/bin/") || path.startsWith("/system/xbin/") || path.startsWith("/system/vendor/bin/")) {
            return new FileMeta(0755, 0, 2000, null);
        }
        return new FileMeta(0644, 0, 0, null);
    }

    String modeString() { return Integer.toOctalString(mode); }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mode", modeString());
        m.put("uid", (long) uid);
        m.put("gid", (long) gid);
        if (context != null) m.put("context", context);
        return m;
    }

    /** From toMap's form; null if o has no uid (nothing recorded, or a journal from before modes were read). */
    @SuppressWarnings("unchecked")
    static FileMeta from(Object o) {
        if (!(o instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        if (m.get("mode") == null || !(m.get("uid") instanceof Number) || !(m.get("gid") instanceof Number)) return null;
        String mode = String.valueOf(m.get("mode"));
        if (!mode.matches("[0-7]{1,4}")) return null;
        return new FileMeta(Integer.parseInt(mode, 8), ((Number) m.get("uid")).intValue(), ((Number) m.get("gid")).intValue(),
                m.get("context") == null ? null : String.valueOf(m.get("context")));
    }

    FileMeta copy() { return new FileMeta(mode, uid, gid, context); }

    /** the same as o (a label only counts when both are known) */
    boolean same(FileMeta o) {
        return o != null && mode == o.mode && uid == o.uid && gid == o.gid
                && (context == null || o.context == null || context.equals(o.context));
    }

    @Override
    public String toString() { return modeString() + " " + uid + ":" + gid + (context == null ? "" : " " + context); }

    // ------------------------------------------------------------------ reading and setting

    /** f's mode, owner and label. */
    static FileMeta read(Engine e, File f) throws IOException {
        if (e.host) {
            int mode = hostMode(f);
            String[] o = hostOwners(e).get(e.devicePath(f));
            return o == null ? new FileMeta(mode, 0, 0, null)
                    : new FileMeta(mode, Integer.parseInt(o[0]), Integer.parseInt(o[1]), "-".equals(o[2]) ? null : o[2]);
        }
        FileMeta m;
        try {
            Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
            Object st = Class.forName("libcore.io.Os").getMethod("stat", String.class).invoke(os, f.getPath());
            m = new FileMeta(st.getClass().getField("st_mode").getInt(st) & 07777, st.getClass().getField("st_uid").getInt(st),
                    st.getClass().getField("st_gid").getInt(st), null);
        } catch (Throwable t) {
            throw new IOException("can't read the mode and owner of " + f + ": " + t);
        }
        m.context = readContext(f);
        return m;
    }

    /** Sets f's owner, then its mode (chown clears setuid bits), then its label; any of them failing throws. */
    static void set(Engine e, File f, FileMeta m) throws Exception {
        if (e.host) {
            Engine.Run r = Engine.exec("chmod", m.modeString(), f.getPath());
            if (r.code != 0) throw new IOException("chmod " + m.modeString() + " " + f + ": " + r.text.trim());
            Map<String, String[]> all = hostOwners(e);
            all.put(e.devicePath(f), new String[] { String.valueOf(m.uid), String.valueOf(m.gid), m.context == null ? "-" : m.context });
            writeHostOwners(e, all);
            return;
        }
        Engine.Run r = Engine.exec("/system/bin/chown", m.uid + ":" + m.gid, f.getPath());
        if (r.code != 0) throw new IOException("chown " + m.uid + ":" + m.gid + " " + f + ": " + r.text.trim());
        r = Engine.exec("/system/bin/chmod", "0" + m.modeString(), f.getPath());
        if (r.code != 0) throw new IOException("chmod " + m.modeString() + " " + f + ": " + r.text.trim());
        if (m.context != null && !m.context.equals(readContext(f))) {
            boolean ok;
            try {
                ok = (Boolean) Class.forName("android.os.SELinux").getMethod("setFileContext", String.class, String.class)
                        .invoke(null, f.getPath(), m.context);
            } catch (Throwable t) {
                ok = Engine.exec("/system/bin/chcon", m.context, f.getPath()).code == 0;
            }
            if (!ok || !m.context.equals(readContext(f))) throw new IOException("label " + m.context + " on " + f + " didn't take");
        }
    }

    /** a rename of f to g: off the unit, its owner and label go with it */
    static void moved(Engine e, File f, File g) throws IOException {
        if (!e.host) return;
        Map<String, String[]> all = hostOwners(e);
        String[] o = all.remove(e.devicePath(f));
        if (o == null && all.remove(e.devicePath(g)) == null) return;
        if (o != null) all.put(e.devicePath(g), o);
        writeHostOwners(e, all);
    }

    /** f deleted: off the unit, its owner and label too */
    static void gone(Engine e, File f) throws IOException {
        if (!e.host) return;
        Map<String, String[]> all = hostOwners(e);
        if (all.remove(e.devicePath(f)) != null) writeHostOwners(e, all);
    }

    /** f's SELinux label, or null with SELinux off or the label unreadable. */
    static String readContext(File f) {
        try {
            Class<?> se = Class.forName("android.os.SELinux");
            if (!(Boolean) se.getMethod("isSELinuxEnabled").invoke(null)) return null;
            Object c = se.getMethod("getFileContext", String.class).invoke(null, f.getPath());
            return c == null || String.valueOf(c).isEmpty() ? null : String.valueOf(c);
        } catch (Throwable t) {
            // no android.os.SELinux: toolbox ls -Z ("-rwxr-xr-x root shell u:object_r:system_file:s0 netd")
        }
        try {
            for (String w : Engine.exec("/system/bin/ls", "-Z", f.getPath()).text.trim().split("\\s+")) {
                if (w.matches("[A-Za-z0-9_]+:[A-Za-z0-9_]+:[A-Za-z0-9_]+:s[0-9].*")) return w;
            }
        } catch (Exception ignored) {
            // unreadable: not known
        }
        return null;
    }

    static int hostMode(File f) throws IOException {
        try {
            Class<?> paths = Class.forName("java.nio.file.Paths"), files = Class.forName("java.nio.file.Files");
            Class<?> link = Class.forName("java.nio.file.LinkOption");
            Object p = paths.getMethod("get", String.class, String[].class).invoke(null, f.getPath(), new String[0]);
            Object none = java.lang.reflect.Array.newInstance(link, 0);
            Object v = files.getMethod("getAttribute", Class.forName("java.nio.file.Path"), String.class, none.getClass())
                    .invoke(null, p, "unix:mode", none);
            return ((Number) v).intValue() & 07777;
        } catch (Exception t) {
            throw new IOException("can't read the mode of " + f + ": " + t);
        }
    }

    static Map<String, String[]> hostOwners(Engine e) throws IOException {
        Map<String, String[]> m = new TreeMap<String, String[]>();
        File f = new File(e.root + "/meta.txt");
        if (!f.isFile()) return m;
        for (String l : Engine.read(f).split("\n")) {
            String[] w = l.trim().split("\\s+");
            if (w.length == 4) m.put(w[0], new String[] { w[1], w[2], w[3] });
        }
        return m;
    }

    static void writeHostOwners(Engine e, Map<String, String[]> m) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String[]> x : m.entrySet()) {
            b.append(x.getKey()).append(' ').append(x.getValue()[0]).append(' ').append(x.getValue()[1]).append(' ').append(x.getValue()[2]).append('\n');
        }
        Engine.write(new File(e.root + "/meta.txt"), b.toString());
    }
}
