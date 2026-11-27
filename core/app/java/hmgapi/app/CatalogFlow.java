package hmgapi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Installing or updating from the catalogue, for the Catalogue and Updates screens: what it takes
 * first (engine catalog-plan: the mods they require, in order), the owner's OK, then the downloads
 * and installs (engine catalog-install / update). The owner's "Install anyway" is per mod and per
 * reason (not listed for this head unit: --any-car=<id>; an author key that isn't on the trusted
 * list: --any-author=<id>). Update all plans each mod on its own (update --all): the ones that can't
 * go now are listed, the rest go. When a package turns out to need more than the plan said, the
 * engine stops (exit 5) and the new plan is shown for a second OK. Nothing is applied here: the
 * screen hands back RESULT_OK and the main screen applies (plan first), as after a USB install.
 */
final class CatalogFlow {
    private CatalogFlow() {}

    /** the engine's exit when the packages need more steps than the plan the owner saw */
    static final int PLAN_CHANGED = 5;

    abstract static class Done {
        abstract void installed(String output);

        abstract void failed(String output);

        /**
         * No answer in time (the install goes on by itself in the engine). The screen should hand back
         * RESULT_OK all the same: the main screen's plan waits for the engine, then shows what changed.
         */
        void lost(String message) {
            failed(message);
        }
    }

    /** what the owner said yes to, per mod */
    static final class Choice {
        final Set<String> anyCar = new LinkedHashSet<String>(), anyAuthor = new LinkedHashSet<String>();

        void args(List<String> c) {
            if (!anyCar.isEmpty()) c.add("--any-car=" + join(anyCar));
            if (!anyAuthor.isEmpty()) c.add("--any-author=" + join(anyAuthor));
        }
    }

    static String join(Iterable<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) b.append(b.length() > 0 ? "," : "").append(x);
        return b.toString();
    }

    /** JSON in an engine answer (it may print notes first) */
    static JSONObject json(String output) throws Exception {
        String t = output.trim();
        int i = t.indexOf('{');
        if (i < 0) throw new Exception(t.isEmpty() ? "no answer" : t);
        return new JSONObject(t.substring(i));
    }

    /** "update": engine update (ids are installed mods), else engine catalog-install */
    static void start(Activity a, List<String> ids, boolean update, Done done) {
        plan(a, ids, update, false, new Choice(), done);
    }

    /** Update all: each installed mod with an update, planned on its own */
    static void startAll(Activity a, Done done) {
        plan(a, new ArrayList<String>(), true, true, new Choice(), done);
    }

    private static void plan(final Activity a, final List<String> ids, final boolean update, final boolean all, final Choice ch, final Done done) {
        final AlertDialog wait = new AlertDialog.Builder(a).setMessage("Checking what it needs…").show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                List<String> c = new ArrayList<String>(Arrays.asList("engine", "catalog-plan"));
                if (all) c.add("--all");
                else c.addAll(ids);
                if (update) c.add("--update");
                ch.args(c);
                return Hmgapid.call(180000, c.toArray(new String[0]));
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                wait.dismiss();
                JSONObject p;
                try {
                    if (e != null) throw e;
                    p = json(r.output);
                } catch (Exception x) {
                    done.failed("Couldn't check: " + x.getMessage());
                    return;
                }
                confirm(a, p, ids, update, all, ch, null, done);
            }
        }.start(a);
    }

    /** the yes-or-no's in a plan: its own, and (update all) the left-out mods' */
    private static List<JSONObject> overrides(JSONObject p) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray o = p.optJSONArray("overrides");
        for (int i = 0; o != null && i < o.length(); i++) out.add(o.optJSONObject(i));
        JSONArray sk = p.optJSONArray("skipped");
        for (int i = 0; sk != null && i < sk.length(); i++) {
            JSONArray so = sk.optJSONObject(i).optJSONArray("overrides");
            for (int k = 0; so != null && k < so.length(); k++) out.add(so.optJSONObject(k));
        }
        return out;
    }

    private static void confirm(final Activity a, final JSONObject p, final List<String> ids, final boolean update, final boolean all,
                                final Choice ch, String intro, final Done done) {
        StringBuilder msg = new StringBuilder(intro == null ? "" : intro + "\n\n");
        JSONArray steps = p.optJSONArray("steps");
        boolean untrusted = false;
        int n = steps == null ? 0 : steps.length();
        for (int i = 0; i < n; i++) {
            JSONObject s = steps.optJSONObject(i);
            String act = s.optString("action");
            String line = "enable".equals(act) ? "Turn on " + s.optString("name")
                    : "update".equals(act) ? "Update " + s.optString("name") + " from " + s.optString("installed") + " to " + s.optString("version")
                    : "Install " + s.optString("name") + " " + s.optString("version");
            if (s.optBoolean("turn_on")) line += ", and turn it on";
            if (s.has("for")) line += "  (needed by " + s.optString("for") + ")";
            if (s.optBoolean("any_car")) line += "  (not listed for this head unit: your choice)";
            msg.append("•  ").append(line).append('\n');
            if ("install".equals(act) && !s.optBoolean("trusted", true)) untrusted = true;
        }
        final List<JSONObject> ov = overrides(p);
        JSONArray probs = p.optJSONArray("problems");
        JSONArray skipped = p.optJSONArray("skipped");
        JSONArray notes = p.optJSONArray("notes");
        if (probs != null && probs.length() > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < probs.length(); i++) b.append("•  ").append(probs.optString(i)).append('\n');
            AlertDialog.Builder d = new AlertDialog.Builder(a).setTitle(update ? "Can't update it" : "Can't install it").setMessage(b.toString().trim());
            d.setPositiveButton("OK", null);
            if (!ov.isEmpty()) anyway(a, d, ov, ids, update, all, ch, done);
            d.show();
            return;
        }
        if (skipped != null && skipped.length() > 0) {
            msg.append(n > 0 ? "\nNot updated now:\n" : "None of them can be updated now:\n");
            for (int i = 0; i < skipped.length(); i++) {
                JSONObject s = skipped.optJSONObject(i);
                msg.append("•  ").append(s.optString("id")).append(": ").append(s.optString("why")).append('\n');
            }
        }
        if (n == 0) {
            String t = msg.length() > 0 ? msg.toString().trim()
                    : notes != null && notes.length() > 0 ? notes.optString(0) : "Nothing to install: it's installed already.";
            AlertDialog.Builder d = new AlertDialog.Builder(a).setMessage(t).setPositiveButton("OK", null);
            if (!ov.isEmpty()) anyway(a, d, ov, ids, update, all, ch, done);
            d.show();
            return;
        }
        for (int i = 0; notes != null && i < notes.length(); i++) msg.append('\n').append(notes.optString(i)).append('\n');
        msg.append("\nDownloaded over home Wi-Fi from each mod's GitHub release, checked against the catalogue's checksum and "
                + "the author's signature, all before the first one is installed. Nothing changes on the head unit until you apply.");
        if (untrusted) {
            msg.append("\n\nYou chose to install mods by authors who aren't on HMG-API's trusted author list. A mod's code runs inside "
                    + "the head unit's apps.");
        }
        // what goes: the plan's own list (update all: the mods it kept), so a later check can't add any
        final List<String> go = new ArrayList<String>();
        JSONArray w = p.optJSONArray("wanted");
        for (int i = 0; w != null && i < w.length(); i++) go.add(w.optString(i));
        if (go.isEmpty()) go.addAll(ids);
        AlertDialog.Builder d = new AlertDialog.Builder(a)
                .setTitle(n == 1 ? (update ? "Update this mod?" : "Install this mod?") : "These " + n + " steps?")
                .setMessage(msg.toString().trim())
                .setNegativeButton("Cancel", null)
                .setPositiveButton(update ? "Update" : "Install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int which) {
                        run(a, go, update, ch, done);
                    }
                });
        if (!ov.isEmpty()) anyway(a, d, ov, ids, update, all, ch, done);
        d.show();
    }

    /**
     * "Install anyway…": the owner picks, mod by mod, what to say yes to (each with its reason), after
     * a warning that says what each kind of yes means. Then the plan again with those.
     */
    private static void anyway(final Activity a, AlertDialog.Builder d, final List<JSONObject> ov, final List<String> ids, final boolean update,
                               final boolean all, final Choice ch, final Done done) {
        d.setNeutralButton(update ? "Update anyway…" : "Install anyway…", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface x, int which) {
                final String[] items = new String[ov.size()];
                final boolean[] picked = new boolean[ov.size()];
                boolean car = false, author = false;
                for (int i = 0; i < ov.size(); i++) {
                    JSONObject o = ov.get(i);
                    boolean isCar = "car".equals(o.optString("kind"));
                    car |= isCar;
                    author |= !isCar;
                    items[i] = o.optString("id") + ": " + (isCar ? "not listed for this head unit (" + o.optString("why") + ")" : o.optString("why"));
                }
                StringBuilder warn = new StringBuilder();
                if (car) warn.append("Not listed for this head unit: its author didn't list this car, so it may not work here. HMG-API keeps "
                        + "a backup of every file it changes, and removing the mod puts them back. ");
                if (author) warn.append("An author who isn't on the trusted list: the catalogue isn't signed, so HMG-API can't tell this key "
                        + "is really the author's. A mod's code runs inside the head unit's apps: say yes only if you know and trust its author. ");
                new AlertDialog.Builder(a).setTitle("Which ones, at your own risk?")
                        .setMultiChoiceItems(items, picked, new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface y, int i, boolean on) {
                                picked[i] = on;
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface y, int ww) {
                                Choice next = new Choice();
                                next.anyCar.addAll(ch.anyCar);
                                next.anyAuthor.addAll(ch.anyAuthor);
                                boolean any = false;
                                for (int i = 0; i < ov.size(); i++) {
                                    if (!picked[i]) continue;
                                    any = true;
                                    String id = ov.get(i).optString("id");
                                    if ("car".equals(ov.get(i).optString("kind"))) next.anyCar.add(id);
                                    else next.anyAuthor.add(id);
                                }
                                if (any) plan(a, ids, update, all, next, done);
                            }
                        }).show();
                new AlertDialog.Builder(a).setTitle("At your own risk").setMessage(warn.toString().trim())
                        .setPositiveButton("I understand", null).show();
            }
        });
    }

    private static void run(final Activity a, final List<String> ids, final boolean update, final Choice ch, final Done done) {
        final AlertDialog wait = new AlertDialog.Builder(a)
                .setMessage("Downloading and installing… (leave the head unit on)").setCancelable(false).show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                List<String> c = new ArrayList<String>(Arrays.asList("engine", update ? "update" : "catalog-install"));
                c.addAll(ids);
                ch.args(c);
                return Hmgapid.call(1800000, c.toArray(new String[0]));
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                wait.dismiss();
                if (e != null) {
                    done.lost("No answer from HMG-API in time (" + e.getMessage() + "). The download and install go on by themselves: "
                            + "back on the main screen, HMG-API waits for them, then shows what changed.");
                } else if (r.code == PLAN_CHANGED) {
                    JSONObject q;
                    try {
                        q = json(r.output);
                    } catch (Exception x) {
                        done.failed(MainActivity.readable(r.output));
                        return;
                    }
                    confirm(a, q, ids, update, false, ch, "The downloaded packages need more than the catalogue said, so nothing was "
                            + "installed yet. This is what it takes now:", done);
                } else if (r.code != 0) {
                    done.failed(MainActivity.readable(r.output));
                } else {
                    done.installed(r.output);
                }
            }
        }.start(a);
    }

    /** "checked …" for a time the engine saved, when the unit's clock was plausible */
    static String checked(JSONObject d) {
        long t = d.optLong("checked", 0);
        if (t <= 0) return "Not checked yet.";
        if (t < 1735689600000L) return "Checked since the head unit started.";   // the clock before GPS time: 2015
        return "Checked " + new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(new java.util.Date(t)) + ".";
    }

    /** Home Wi-Fi for a while (the car in P), so a check can run now: hmgapid wifi now 15 */
    static void connect(final Activity a, final Runnable then) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                Hmgapid.Result r = Hmgapid.call("wifi", "now", "15");
                if (r.code != 0) return r;
                for (int i = 0; i < 45; i++) {   // up to 90 s to join
                    Thread.sleep(2000);
                    java.io.File f = new java.io.File("/data/hmgapi/public/wifi.json");
                    try {
                        if ("up".equals(new JSONObject(UsbFolders.read(f)).optString("state"))) return r;
                    } catch (Exception ignored) {
                        // not written yet
                    }
                }
                r.code = 1;
                r.output = "Home Wi-Fi didn't connect within 90 seconds. Is the car near your network, and is it saved in Settings → Home Wi-Fi?";
                return r;
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (e != null || r.code != 0) {
                    new AlertDialog.Builder(a).setTitle("Not connected").setMessage(e != null ? e.getMessage() : r.output.trim())
                            .setPositiveButton("OK", null).show();
                    return;
                }
                then.run();
            }
        }.start(a);
    }
}
