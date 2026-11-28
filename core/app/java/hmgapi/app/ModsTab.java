package hmgapi.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Mods: every installed mod on the left with its state (green on, yellow a
 * conflict, red not loaded on this car or an error, grey off), and the chosen one on
 * the right: its switch, why it isn't running if it isn't, its settings (saved as
 * they change), its log
 * (HMG-API's log lines for it and its services' output), Remove.
 */
final class ModsTab {
    final MainActivity a;
    LinearLayout list, detail;
    List<Mod> mods = new ArrayList<Mod>();
    String chosen;
    int updates;   // public/updates.json's count (mods and HMG-API)

    static final class Mod {
        String id, name, version = "", description = "", authors = "", license = "", state, error;
        String update;   // a newer version in the catalogue or the mod's releases (public/updates.json), or null
        String updateRepo;   // the update is from this repo's releases (not the catalogue), or null
        String source = "", repo, choice, withdrawn;   // where its updates come from (catalogue, releases, off); a maintainer's withdrawal
        boolean listed;
        List<String> requires = new ArrayList<String>();
        boolean enabled, hasCode;
        List<String> services = new ArrayList<String>();

        int color() {
            if (!enabled) return Ui.OFF;
            if ("conflict".equals(state)) return Ui.WARN;
            if ("error".equals(state) || "unsupported".equals(state)) return Ui.ERROR;
            return Ui.ACCENT;
        }

        String label() {
            if (!enabled) return "Off";
            if ("conflict".equals(state)) return "Conflict";
            if ("error".equals(state)) return "Error";
            if ("unsupported".equals(state)) return "Not for this car";
            if (hasCode && "disabled".equals(state)) return "On, loads at the next restart";
            return "On";
        }
    }

    ModsTab(MainActivity a) {
        this.a = a;
    }

    View view() {
        LinearLayout root = Ui.row(a);
        root.setGravity(0);
        LinearLayout side = Ui.column(a);
        side.setBackgroundColor(Ui.PANEL);
        list = Ui.scrolling(side, 1, LinearLayout.LayoutParams.MATCH_PARENT);
        root.addView(side, new LinearLayout.LayoutParams(Ui.dp(a, 460), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout right = Ui.column(a);
        detail = Ui.scrolling(right, 1, LinearLayout.LayoutParams.MATCH_PARENT);
        detail.setPadding(Ui.dp(a, 32), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 24));
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        Ui.text(list, "  Loading…", 18, false, Ui.GREY);
        load();
        return root;
    }

    void load() {
        new Ui.Job<List<Mod>>() {
            @Override
            List<Mod> work() throws Exception {
                JSONObject status = null;
                try {
                    status = new JSONObject(HmgApiStatus.json);
                } catch (Exception ignored) {
                    // hooks not running: states unknown
                }
                List<Mod> out = new ArrayList<Mod>();
                JSONObject up = null;
                try {
                    up = new JSONObject(UsbFolders.read(new java.io.File("/data/hmgapi/public/updates.json")));
                } catch (Exception ignored) {
                    // no check yet
                }
                updates = up == null ? 0 : up.optInt("count");
                JSONArray ups = up == null ? null : up.optJSONArray("mods");
                JSONObject srcs = up == null ? null : up.optJSONObject("sources");
                JSONArray wds = up == null ? null : up.optJSONArray("withdrawn");
                for (String line : Hmgapid.call("mods").output.split("\n")) {
                    String[] w = line.split("\t");
                    if (w.length < 2) continue;
                    Mod m = new Mod();
                    m.id = w[0];
                    m.name = m.id;
                    m.enabled = "enabled".equals(w[1].trim());
                    try {
                        JSONObject j = new JSONObject(Hmgapid.call("mod-file", m.id, "mod.json").output);
                        m.name = j.optString("name", m.id);
                        m.version = j.optString("version", "");
                        m.description = j.optString("description", "");
                        m.license = j.optString("license", "");
                        JSONArray au = j.optJSONArray("authors");
                        for (int i = 0; au != null && i < au.length(); i++) m.authors += (i > 0 ? ", " : "") + au.optString(i);
                        m.hasCode = j.has("hooks");
                        JSONArray rq = j.optJSONArray("requires");
                        for (int i = 0; rq != null && i < rq.length(); i++) {
                            Object o = rq.get(i);
                            String rid = o instanceof JSONObject ? ((JSONObject) o).optString("id") : String.valueOf(o).trim().split(" ")[0];
                            if (!(o instanceof JSONObject && ((JSONObject) o).optBoolean("optional"))) m.requires.add(rid);
                        }
                        JSONArray sv = j.optJSONArray("services");
                        for (int i = 0; sv != null && i < sv.length(); i++) m.services.add(sv.getJSONObject(i).optString("name"));
                    } catch (Exception e) {
                        m.state = "error";
                        m.error = "its mod.json can't be read: " + e.getMessage();
                    }
                    JSONArray st = status == null ? null : status.optJSONArray("mods");
                    for (int i = 0; st != null && i < st.length(); i++) {
                        JSONObject s = st.getJSONObject(i);
                        if (!m.id.equals(s.optString("id"))) continue;
                        if (m.state == null) m.state = s.optString("state");
                        if (m.error == null && s.has("error")) m.error = s.optString("error");
                    }
                    for (int i = 0; ups != null && i < ups.length(); i++) {
                        JSONObject u = ups.getJSONObject(i);
                        if (!m.id.equals(u.optString("id")) || !m.version.equals(u.optString("installed"))) continue;
                        m.update = u.optString("version");
                        if ("release".equals(u.optString("source"))) m.updateRepo = u.optString("repo").replace("https://github.com/", "");
                    }
                    JSONObject src = srcs == null ? null : srcs.optJSONObject(m.id);
                    if (src != null) {
                        m.source = src.optString("source");
                        m.repo = src.has("repo") ? src.optString("repo") : null;
                        m.choice = src.has("choice") ? src.optString("choice") : null;
                        m.listed = src.optBoolean("listed");
                    }
                    for (int i = 0; wds != null && i < wds.length(); i++) {
                        JSONObject wo = wds.getJSONObject(i);
                        if (m.id.equals(wo.optString("id")) && m.version.equals(wo.optString("version"))) m.withdrawn = wo.optString("why");
                    }
                    out.add(m);
                }
                return out;
            }

            @Override
            void done(List<Mod> r, Exception e) {
                list.removeAllViews();
                if (e != null) {
                    Ui.text(list, "  " + Hmgapid.unreachable(e), 17, false, Ui.ERROR);
                    return;
                }
                mods = r;
                if (r.isEmpty()) {
                    Ui.text(list, "  No mods installed. Get some in the Catalogue tab (over home Wi-Fi), or put mod packages in "
                            + "HMGAPI/updates/ on a USB stick and use Check USB updates.", 17, false, Ui.GREY);
                    return;
                }
                if (chosen == null) chosen = r.get(0).id;
                drawList();
                for (Mod m : r) if (m.id.equals(chosen)) show(m);
            }
        }.start(a);
    }

    void drawList() {
        list.removeAllViews();
        for (final Mod m : mods) {
            LinearLayout row = Ui.row(a);
            row.setPadding(Ui.dp(a, 18), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
            if (m.id.equals(chosen)) row.setBackgroundColor(Ui.PANEL2);
            row.addView(Ui.dot(a, m.color(), 18));
            LinearLayout t = Ui.column(a);
            Ui.text(t, m.name, 19, false, Ui.TEXT).setPadding(0, 0, 0, 0);
            TextView sub = Ui.text(t, m.version + "  ·  " + m.label() + (m.update != null ? "  ·  update " + m.update : ""), 15, false,
                    m.color() == Ui.ACCENT || m.color() == Ui.OFF ? (m.update != null ? Ui.GREEN : Ui.GREY) : m.color());
            sub.setPadding(0, 0, 0, 0);
            row.addView(t);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.saveThen(new Runnable() {
                        @Override
                        public void run() {
                            chosen = m.id;
                            drawList();
                            show(m);
                        }
                    });
                }
            });
            list.addView(row);
        }
    }

    void show(final Mod m) {
        a.form = null;
        detail.removeAllViews();
        LinearLayout head = Ui.row(a);
        LinearLayout names = Ui.column(a);
        Ui.text(names, m.name, 28, true, Ui.TEXT);
        Ui.text(names, join(m.version, m.authors, m.license), 16, false, Ui.GREY);
        head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch on = new Switch(a);
        on.setChecked(m.enabled);
        on.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, final boolean checked) {
                new Ui.Job<Hmgapid.Result>() {
                    @Override
                    Hmgapid.Result work() throws Exception {
                        return Hmgapid.call(checked ? "enable" : "disable", m.id);
                    }

                    @Override
                    void done(Hmgapid.Result r, Exception e) {
                        if (e != null || r.code != 0) {
                            a.message("Couldn't switch " + m.name, e != null ? e.getMessage() : r.output.trim());
                            return;
                        }
                        if (m.hasCode) a.needRestart();
                        else a.refreshStatus();
                        load();
                    }
                }.start(a);
            }
        });
        head.addView(on);
        detail.addView(head);
        if (m.enabled && m.color() != Ui.ACCENT && m.error != null) {
            TextView box = Ui.text(detail, (m.color() == Ui.WARN ? "Conflict\n" : "Not running\n") + m.error,
                    17, false, Ui.TEXT);
            box.setBackground(Ui.box(a, m.color() == Ui.WARN ? 0xff3a3320 : 0xff301a18, m.color(), 10));
            int p = Ui.dp(a, 14);
            box.setPadding(p, p, p, p);
            if (m.color() == Ui.ERROR) {
                Ui.button(detail, "Make a Car Report", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.show(MainActivity.REPORT);
                    }
                });
            }
        } else if (m.enabled) {
            Ui.pill(detail, m.label(), m.color());
        }
        if (!m.description.isEmpty()) Ui.text(detail, m.description, 16, false, Ui.GREY);
        if (m.withdrawn != null) {
            TextView box = Ui.text(detail, "Withdrawn\nHMG-API's maintainers withdrew " + m.name + " " + m.version + ": " + m.withdrawn
                    + ". Update it, or turn it off until there's a fixed version.", 17, false, Ui.TEXT);
            box.setBackground(Ui.box(a, 0xff3a3320, Ui.WARN, 10));
            int p = Ui.dp(a, 14);
            box.setPadding(p, p, p, p);
        }
        if (m.update != null) {
            Ui.text(detail, m.updateRepo != null ? "Version " + m.update + " is in " + m.updateRepo + "'s releases (signed by the same author; "
                    + "not reviewed for the catalogue)." : "Version " + m.update + " is in the catalogue.", 17, false, Ui.GREEN);
            Ui.button(detail, "Update to " + m.update, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CatalogFlow.start(a, java.util.Arrays.asList(m.id), true, new CatalogFlow.Done() {
                        @Override
                        public void installed(String output) {
                            a.message("Updated " + m.name, MainActivity.readable(output));
                            load();
                            a.plan();
                        }

                        @Override
                        public void failed(String output) {
                            a.message("Not updated", output);
                        }

                        @Override
                        void lost(String message) {
                            a.message("Still updating", message);
                            a.plan();   // waits for the engine, then shows what changed
                        }
                    });
                }
            });
        }
        if (!m.requires.isEmpty()) Ui.text(detail, "Needs " + join(m.requires.toArray(new String[0])).replace("  ·  ", ", "), 15, false, Ui.GREY);
        if (m.listed || m.repo != null) updatesFrom(m);

        Ui.line(detail);
        Ui.text(detail, "Settings", 21, true, Ui.GREEN);
        LinearLayout settings = Ui.column(a);   // filled when they're read: keeps its place above the log
        detail.addView(settings);
        a.form = new SettingsForm(a, settings, m.id, new Runnable() {
            @Override
            public void run() {
                a.refreshStatus();
            }
        });
        a.form.load();

        Ui.line(detail);
        Ui.text(detail, "Log", 21, true, Ui.GREEN);
        final TextView log = Ui.mono(detail);
        log.setText("Loading…");
        new Ui.Job<String>() {
            @Override
            String work() throws Exception {
                StringBuilder s = new StringBuilder();
                for (String l : Hmgapid.call("log").output.split("\n")) {
                    int i = l.indexOf("[" + m.id + "] ");
                    if (i >= 0) s.append(LogsTab.time(l)).append("  ").append(l.substring(i + m.id.length() + 3)).append('\n');
                }
                for (String sv : m.services) {
                    Hmgapid.Result r = Hmgapid.call("service-log", m.id, sv);
                    if (r.code != 0) continue;
                    String[] ls = r.output.split("\n");
                    s.append("\n— service ").append(sv).append(" —\n");
                    for (int i = Math.max(0, ls.length - 40); i < ls.length; i++) s.append(ls[i]).append('\n');
                }
                return s.toString().trim();
            }

            @Override
            void done(String r, Exception e) {
                log.setText(e != null ? "Can't read the log: " + e.getMessage() : r.isEmpty() ? "Nothing logged yet." : tail(r, 120));
            }
        }.start(a);

        Ui.line(detail);
        Ui.button(detail, "Remove " + m.name, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRemove(m);
            }
        });
    }

    /** where the mod's updates come from: the catalogue, its own GitHub releases, or nowhere (engine update-source) */
    void updatesFrom(final Mod m) {
        Ui.line(detail);
        Ui.text(detail, "Updates", 21, true, Ui.GREEN);
        String repo = m.repo == null ? null : m.repo.replace("https://github.com/", "");
        Ui.text(detail, "catalogue".equals(m.source) ? "From HMG-API's catalogue." + (repo != null ? " Its own releases (" + repo + ") can be followed instead, "
                + "for example to test a new version before it's listed." : "")
                : "releases".equals(m.source) ? "From " + repo + "'s releases on GitHub" + (m.listed ? ", instead of the catalogue." : ": it isn't in "
                + "the catalogue.") + " Only a release signed with the same author key as the installed version is offered."
                : "Off: HMG-API doesn't check for updates of " + m.name + ".", 16, false, Ui.GREY);
        LinearLayout row = Ui.row(a);
        if (m.listed && !"catalogue".equals(m.source)) source(row, m, "From the catalogue", "catalogue");
        if (repo != null && !"releases".equals(m.source)) source(row, m, "From its releases", "releases");
        if (!"off".equals(m.source)) source(row, m, "Don't check", "off");
        if (m.choice != null) source(row, m, "Default", "default");
        detail.addView(row);
    }

    private void source(LinearLayout row, final Mod m, String label, final String to) {
        Ui.button(row, label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Ui.Job<Hmgapid.Result>() {
                    @Override
                    Hmgapid.Result work() throws Exception {
                        return Hmgapid.call("engine", "update-source", m.id, to);
                    }

                    @Override
                    void done(Hmgapid.Result r, Exception e) {
                        if (e != null || r.code != 0) a.message("Not changed", e != null ? e.getMessage() : MainActivity.readable(r.output));
                        else android.widget.Toast.makeText(a, MainActivity.readable(r.output).trim(), android.widget.Toast.LENGTH_LONG).show();
                        load();
                    }
                }.start(a);
            }
        });
    }

    static String join(String... parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) if (p != null && !p.isEmpty()) s.append(s.length() > 0 ? "  ·  " : "").append(p);
        return s.toString();
    }

    static String tail(String s, int lines) {
        String[] ls = s.split("\n");
        if (ls.length <= lines) return s;
        StringBuilder b = new StringBuilder();
        for (int i = ls.length - lines; i < ls.length; i++) b.append(ls[i]).append('\n');
        return b.toString().trim();
    }

    /** A mod off the head unit: its folder goes now; what it changed goes back to stock with the next apply. */
    void confirmRemove(final Mod m) {
        List<String> need = new ArrayList<String>();
        for (Mod o : mods) if (o.requires.contains(m.id)) need.add(o.name);
        new AlertDialog.Builder(a)
                .setTitle("Remove " + m.name + "?")
                .setMessage((need.isEmpty() ? "" : join(need.toArray(new String[0])).replace("  ·  ", ", ") + (need.size() == 1 ? " needs" : " need")
                        + " it: " + (need.size() == 1 ? "it" : "they") + " won't load until it's installed again.\n\n")
                        + "Everything it changed goes back to stock when you apply the changes. Its settings are kept "
                        + "in case you install it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        new Ui.Job<Hmgapid.Result>() {
                            @Override
                            Hmgapid.Result work() throws Exception {
                                return Hmgapid.call("engine", "remove", m.id);
                            }

                            @Override
                            void done(Hmgapid.Result r, Exception e) {
                                if (e != null || r.code != 0) {
                                    a.message("Couldn't remove " + m.name, e != null ? e.getMessage() : MainActivity.readable(r.output));
                                    return;
                                }
                                if (m.hasCode) a.needRestart();
                                else a.refreshStatus();
                                chosen = null;
                                load();
                            }
                        }.start(a);
                    }
                })
                .show();
    }
}
