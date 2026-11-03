package hmgapi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Updates from a USB stick: every zip in HMGAPI/updates/ (engine usb-scan), with what
 * installing it would do. Mod packages install with hmgapid engine install (a new mod
 * by an author not on the trusted list asks first); a HMG-API release runs its own installer. After a
 * mod install the main screen applies the changes (plan first).
 */
public class UpdatesActivity extends Activity {
    private LinearLayout list, rows;
    private TextView message;
    private boolean installedMods;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        list = Ui.page(this);
        Ui.text(list, "Updates from USB", 30, true, Ui.GREEN);
        Ui.text(list, "Put HMG-API releases and mod packages (zips) in HMGAPI/updates/ on a USB stick.", 16, false, Color.GRAY);
        message = Ui.text(list, "", 18, false, Ui.WARN);
        Ui.button(list, "Check the USB stick again", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        list.addView(rows);
        scan();
    }

    @Override
    public void finish() {
        setResult(installedMods ? RESULT_OK : RESULT_CANCELED);   // the main screen applies after mod installs
        super.finish();
    }

    private void scan() {
        message.setText("Looking at the USB stick…");
        rows.removeAllViews();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(300000, "engine", "usb-scan");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (e != null || r.code != 0) {
                    message.setText("Couldn't read the stick: " + (e != null ? e.getMessage() : r.output.trim()));
                    return;
                }
                try {
                    JSONObject d = new JSONObject(r.output.trim().substring(r.output.trim().indexOf('{')));
                    JSONArray p = d.optJSONArray("packages");
                    if (d.has("hint")) message.setText(d.optString("hint"));
                    else if (p == null || p.length() == 0) message.setText("No zips in HMGAPI/updates/ on the stick.");
                    else message.setText(p.length() + (p.length() == 1 ? " zip" : " zips") + " on the stick (HMG-API "
                            + d.optString("hmgapi") + " installed)");
                    for (int i = 0; p != null && i < p.length(); i++) row(p.getJSONObject(i));
                } catch (Exception x) {
                    message.setText("Couldn't read the scan: " + x.getMessage());
                }
            }
        }.start(this);
    }

    private void row(final JSONObject p) {
        Ui.gap(rows, 12);
        String kind = p.optString("kind"), status = p.optString("status");
        String file = p.optString("file");
        String title = "unknown".equals(kind) ? file.substring(file.lastIndexOf('/') + 1)
                : p.optString("name") + "  " + p.optString("version");
        Ui.text(rows, title, 22, true, "refused".equals(status) ? Color.GRAY : Color.WHITE);
        String what;
        if ("refused".equals(status)) what = "Can't install: " + p.optString("reason").replaceFirst("\\. To install it anyway.*$", "");
        else if ("new".equals(status)) what = "New mod";
        else if ("update".equals(status)) what = "Update from " + p.optString("installed");
        else if ("same".equals(status)) what = "Already installed (this version)";
        else what = "Older than the installed " + p.optString("installed");
        Ui.text(rows, what, 16, false, "refused".equals(status) ? Ui.ERROR : Color.LTGRAY);
        if (p.optString("description").length() > 0) Ui.text(rows, p.optString("description"), 15, false, Color.GRAY);
        JSONArray notes = p.optJSONArray("notes");
        for (int i = 0; notes != null && i < notes.length(); i++) {
            Ui.text(rows, notes.optString(i).replaceFirst("^note +", ""), 15, false, Ui.WARN);
        }
        if ("refused".equals(status) && p.has("any_car")) {
            Button b = new Button(this);
            b.setText("Install anyway…");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmAnyCar(p);
                }
            });
            rows.addView(b);
            return;
        }
        if ("refused".equals(status) || "older".equals(status)) return;
        String label = "hmgapi".equals(kind) ? ("same".equals(status) ? "Reinstall HMG-API" : "Update HMG-API")
                : "same".equals(status) ? "Reinstall" : "update".equals(status) ? "Update" : "Install";
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("hmgapi".equals(p.optString("kind"))) confirmHmgApi(p);
                else if ("new".equals(p.optString("status")) && p.optString("author", "").isEmpty()) confirmAuthor(p, false);
                else install(p, null, false);
            }
        });
        rows.addView(b);
    }

    /** Only the car is wrong (mod.json "system"): the owner may install it anyway. */
    private void confirmAnyCar(final JSONObject p) {
        new AlertDialog.Builder(this)
                .setTitle("Install " + p.optString("name") + " anyway?")
                .setMessage("Its author didn't list this head unit:\n\n" + p.optString("reason").replaceFirst("\\. To install it anyway.*$", "")
                        + "\n\nIt may not work here, or may need something this unit doesn't have. HMG-API keeps a backup of "
                        + "every file it changes, and removing the mod puts them back. Install it only if you know it suits "
                        + "this car (for example, the author or a Car Report says so).")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install anyway", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if ("new".equals(p.optString("any_car")) && p.optString("author", "").isEmpty()) confirmAuthor(p, true);
                        else install(p, null, true);
                    }
                }).show();
    }

    private void confirmAuthor(final JSONObject p, final boolean anyCar) {
        new AlertDialog.Builder(this)
                .setTitle("Install " + p.optString("name") + "?")
                .setMessage("This mod's author isn't on HMG-API's trusted author list (author key "
                        + p.optString("uuid") + ").\n\nOnly install mods from authors you trust: a mod's code runs inside "
                        + "the head unit's apps. Updates to it will only be accepted from this same key.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        install(p, null, anyCar);
                    }
                }).show();
    }

    private void install(final JSONObject p, final String uuid, final boolean anyCar) {
        message.setText("Installing " + p.optString("name") + "…");
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                java.util.List<String> c = new java.util.ArrayList<String>(java.util.Arrays.asList("engine", "install", p.optString("file")));
                if (uuid != null) c.addAll(java.util.Arrays.asList("--uuid", uuid));
                if (anyCar) c.add("--any-car");
                return Hmgapid.call(300000, c.toArray(new String[0]));
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (e != null || r.code != 0) {
                    message.setText("Not installed: " + (e != null ? e.getMessage() : MainActivity.readable(r.output)));
                    return;
                }
                installedMods = true;
                message.setText("Installed " + p.optString("name") + ". Go back to apply the changes.");
                scan();
            }
        }.start(this);
    }

    private void confirmHmgApi(final JSONObject p) {
        new AlertDialog.Builder(this)
                .setTitle("Update HMG-API to " + p.optString("version") + "?")
                .setMessage("HMG-API's installer runs (it takes a minute; leave the head unit on), then the head unit "
                        + "needs a restart. Your mods and settings stay.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        updateHmgApi(p);
                    }
                }).show();
    }

    private void updateHmgApi(final JSONObject p) {
        message.setText("Updating HMG-API…");
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(900000, "engine", "update-hmgapi", p.optString("file"));
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (e != null) {
                    message.setText("Lost contact while updating (" + e.getMessage() + "). The installer finishes on its "
                            + "own; restart the head unit once it's done.");
                    return;
                }
                AlertDialog.Builder d = new AlertDialog.Builder(UpdatesActivity.this)
                        .setTitle(r.code == 0 ? "HMG-API updated" : "Not updated").setMessage(r.output.trim());
                if (r.code == 0) {
                    d.setNegativeButton("Later", null).setPositiveButton("Restart now", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface x, int which) {
                            new Ui.Job<Hmgapid.Result>() {
                                @Override
                                Hmgapid.Result work() throws Exception {
                                    return Hmgapid.call("reboot");
                                }

                                @Override
                                void done(Hmgapid.Result rr, Exception ee) {}
                            }.start(UpdatesActivity.this);
                        }
                    });
                } else {
                    d.setPositiveButton("OK", null);
                }
                d.show();
                message.setText(r.code == 0 ? "HMG-API updated: restart the head unit." : "Not updated.");
            }
        }.start(this);
    }
}
