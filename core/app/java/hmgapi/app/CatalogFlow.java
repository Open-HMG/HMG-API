package hmgapi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Installing or updating from the catalogue, for the Catalogue and Updates screens: what it takes
 * first (engine catalog-plan: the mods they require, in order), the owner's OK, then the downloads
 * and installs (engine catalog-install / update). Nothing is applied here: the screen hands back
 * RESULT_OK and the main screen applies (plan first), as after a USB install.
 */
final class CatalogFlow {
    private CatalogFlow() {}

    interface Done {
        void installed(String output);

        void failed(String output);
    }

    /** JSON in an engine answer (it may print notes first) */
    static JSONObject json(String output) throws Exception {
        String t = output.trim();
        int i = t.indexOf('{');
        if (i < 0) throw new Exception(t.isEmpty() ? "no answer" : t);
        return new JSONObject(t.substring(i));
    }

    /** "update": engine update (ids are installed mods), else engine catalog-install */
    static void start(final Activity a, final List<String> ids, final boolean update, final boolean anyCar, final Done done) {
        final AlertDialog wait = new AlertDialog.Builder(a).setMessage("Checking what it needs…").show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                List<String> c = new ArrayList<String>(Arrays.asList("engine", "catalog-plan"));
                c.addAll(ids);
                if (anyCar) c.add("--any-car");
                return Hmgapid.call(120000, c.toArray(new String[0]));
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
                confirm(a, p, ids, update, anyCar, done);
            }
        }.start(a);
    }

    private static void confirm(final Activity a, JSONObject p, final List<String> ids, final boolean update, final boolean anyCar, final Done done) {
        StringBuilder msg = new StringBuilder();
        JSONArray steps = p.optJSONArray("steps");
        boolean untrusted = false;
        int n = steps == null ? 0 : steps.length();
        for (int i = 0; i < n; i++) {
            JSONObject s = steps.optJSONObject(i);
            String act = s.optString("action");
            String line = "enable".equals(act) ? "Turn on " + s.optString("name")
                    : "update".equals(act) ? "Update " + s.optString("name") + " from " + s.optString("installed") + " to " + s.optString("version")
                    : "Install " + s.optString("name") + " " + s.optString("version");
            if (s.has("for")) line += "  (needed by " + s.optString("for") + ")";
            msg.append("•  ").append(line).append('\n');
            if (!"enable".equals(act) && !s.optBoolean("trusted", true)) untrusted = true;
        }
        JSONArray probs = p.optJSONArray("problems");
        JSONArray notes = p.optJSONArray("notes");
        if (probs != null && probs.length() > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < probs.length(); i++) b.append("•  ").append(probs.optString(i)).append('\n');
            boolean car = false;
            for (int i = 0; i < probs.length(); i++) car |= probs.optString(i).contains("isn't for this head unit");
            AlertDialog.Builder d = new AlertDialog.Builder(a).setTitle("Can't install it").setMessage(b.toString().trim());
            d.setPositiveButton("OK", null);
            if (car && !anyCar) {
                d.setNeutralButton("Install anyway…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        new AlertDialog.Builder(a).setTitle("Install anyway?")
                                .setMessage("Its author didn't list this head unit. It may not work here. HMG-API keeps a backup of every "
                                        + "file it changes, and removing the mod puts them back. Install it only if you know it suits this car.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Install anyway", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface y, int ww) {
                                        start(a, ids, update, true, done);
                                    }
                                }).show();
                    }
                });
            }
            d.show();
            return;
        }
        if (n == 0) {
            String t = notes != null && notes.length() > 0 ? notes.optString(0) : "Nothing to install: it's installed already.";
            new AlertDialog.Builder(a).setMessage(t).setPositiveButton("OK", null).show();
            return;
        }
        for (int i = 0; notes != null && i < notes.length(); i++) msg.append('\n').append(notes.optString(i)).append('\n');
        msg.append("\nDownloaded over home Wi-Fi from each mod's GitHub release, checked against the catalogue's checksum and "
                + "the author's signature. Nothing changes on the head unit until you apply.");
        if (untrusted) {
            msg.append("\n\nSome of these are by authors who aren't on HMG-API's trusted author list. A mod's code runs inside "
                    + "the head unit's apps: install mods only from authors you trust.");
        }
        new AlertDialog.Builder(a)
                .setTitle(n == 1 ? (update ? "Update this mod?" : "Install this mod?") : "These " + n + " steps?")
                .setMessage(msg.toString().trim())
                .setNegativeButton("Cancel", null)
                .setPositiveButton(update ? "Update" : "Install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        run(a, ids, update, anyCar, done);
                    }
                }).show();
    }

    private static void run(final Activity a, final List<String> ids, final boolean update, final boolean anyCar, final Done done) {
        final AlertDialog wait = new AlertDialog.Builder(a)
                .setMessage("Downloading and installing… (leave the head unit on)").setCancelable(false).show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                List<String> c = new ArrayList<String>(Arrays.asList("engine", update ? "update" : "catalog-install"));
                c.addAll(ids);
                if (anyCar) c.add("--any-car");
                return Hmgapid.call(900000, c.toArray(new String[0]));
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                wait.dismiss();
                if (e != null) done.failed("Lost contact with HMG-API: " + e.getMessage());
                else if (r.code != 0) done.failed(MainActivity.readable(r.output));
                else done.installed(r.output);
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
