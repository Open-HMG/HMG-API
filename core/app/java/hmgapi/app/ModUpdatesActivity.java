package hmgapi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Updates: installed mods with a newer version in the catalogue, and a newer HMG-API release
 * (engine updates; HMG-API checks by itself when home Wi-Fi comes up, at most every 6 hours;
 * "Check now" checks again). An update takes any new mods it requires (CatalogFlow); Update all
 * plans each mod on its own, so one that can't go doesn't hold back the rest. HMG-API's own runs
 * its installer, then the head unit restarts. Mod updates are applied by the main screen; ones the
 * automatic check downloaded ("staged") wait aside until then.
 */
public class ModUpdatesActivity extends Activity {
    private LinearLayout rows;
    private TextView message, when;
    private Button connect, all;
    private boolean installed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout list = Ui.page(this);
        Ui.text(list, "Updates", 30, true, Ui.GREEN);
        Ui.text(list, "Newer versions of your mods from HMG-API's catalogue, and of HMG-API itself. HMG-API checks each time "
                + "the head unit joins your home Wi-Fi (the car off at home), and never over the car's mobile data.", 16, false, Color.GRAY);
        when = Ui.text(list, "", 15, false, Color.GRAY);
        message = Ui.text(list, "", 18, false, Ui.WARN);
        LinearLayout buttons = Ui.row(this);
        Ui.button(buttons, "Check now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(true);
            }
        });
        connect = Ui.button(buttons, "Connect to home Wi-Fi (15 min)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                message.setText("Connecting to home Wi-Fi… (wireless CarPlay and Android Auto are off meanwhile)");
                CatalogFlow.connect(ModUpdatesActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        load(true);
                    }
                });
            }
        });
        connect.setVisibility(View.GONE);
        all = Ui.button(buttons, "Update all mods", true, null);
        all.setVisibility(View.GONE);
        list.addView(buttons);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        list.addView(rows);

        Ui.line(list);
        Ui.text(list, "Automatic", 21, true, Ui.GREEN);
        Switch check = Ui.switchRow(list, "Check for updates when home Wi-Fi connects");
        Ui.allowSwitch(this, check, "no_update_check", true, null);
        Switch stage = Ui.switchRow(list, "Also download mod updates then: they wait until you apply");
        Ui.allowSwitch(this, stage, "auto_stage_updates", false, null);
        Ui.text(list, "Only plain updates are downloaded by themselves: one that needs another mod installed or turned on waits for your "
                + "Update. Nothing downloaded runs before you apply. HMG-API itself is only ever updated when you tap its Update.", 15, false, Color.GRAY);
        load(false);
    }

    @Override
    public void finish() {
        setResult(installed ? RESULT_OK : RESULT_CANCELED);
        super.finish();
    }

    private void load(final boolean refresh) {
        message.setText(refresh ? "Checking for updates…" : "Loading…");
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return refresh ? Hmgapid.call(180000, "engine", "updates", "--refresh") : Hmgapid.call(60000, "engine", "updates");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                rows.removeAllViews();
                JSONObject d;
                try {
                    if (e != null) throw e;
                    d = CatalogFlow.json(r.output);
                } catch (Exception x) {
                    message.setText(e != null ? Hmgapid.unreachable(e) : "Couldn't read the updates: " + x.getMessage());
                    return;
                }
                final boolean online = d.optBoolean("online");
                connect.setVisibility(online ? View.GONE : View.VISIBLE);
                when.setText(CatalogFlow.checked(d));
                JSONArray mods = d.optJSONArray("mods");
                int n = d.optInt("count");
                if (d.has("error")) message.setText("Not checked: " + d.optString("error"));
                else if (n == 0) message.setText(d.optLong("checked", 0) > 0 ? "Everything is up to date." : "Not checked yet: tap Check now"
                        + (online ? "." : " (home Wi-Fi isn't connected: Connect to home Wi-Fi, in P)."));
                else message.setText(n + (n == 1 ? " update" : " updates") + " available" + (online ? "." : " (offline: connect to install)."));
                if (d.has("hmgapi")) hmgapiRow(d.optJSONObject("hmgapi"), online);
                int count = 0;
                for (int i = 0; mods != null && i < mods.length(); i++) {
                    row(mods.optJSONObject(i), online);
                    count++;
                }
                all.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
                all.setEnabled(online);
                all.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        update(null);   // each mod planned on its own (update --all)
                    }
                });
            }
        }.start(this);
    }

    private void row(final JSONObject m, boolean online) {
        Ui.gap(rows, 14);
        Ui.text(rows, m.optString("name", m.optString("id")), 22, true, Color.WHITE);
        Ui.text(rows, m.optString("installed") + "  →  " + m.optString("version"), 16, false, Ui.GREEN);
        if (m.optString("description").length() > 0) Ui.text(rows, m.optString("description"), 15, false, Color.GRAY);
        if (m.optBoolean("staged")) Ui.text(rows, "Downloaded by the automatic check: it goes in when you apply", 15, false, Ui.WARN);
        JSONArray req = m.optJSONArray("requires");
        if (req != null && req.length() > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < req.length(); i++) b.append(i > 0 ? ", " : "").append(req.optString(i));
            Ui.text(rows, "Needs " + b + " (installed or updated with it if needed)", 15, false, Color.GRAY);
        }
        Button b = Ui.button(rows, "Update", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                update(Arrays.asList(m.optString("id")));
            }
        });
        b.setEnabled(online);
    }

    /** ids null: update all */
    private void update(List<String> ids) {
        CatalogFlow.Done done = new CatalogFlow.Done() {
            @Override
            public void installed(String output) {
                installed = true;
                new AlertDialog.Builder(ModUpdatesActivity.this).setTitle("Updated")
                        .setMessage(MainActivity.readable(output) + "\n\nGo back to apply the changes.")
                        .setPositiveButton("OK", null).show();
                load(false);
            }

            @Override
            public void failed(String output) {
                new AlertDialog.Builder(ModUpdatesActivity.this).setTitle("Not updated").setMessage(output)
                        .setPositiveButton("OK", null).show();
                load(false);
            }

            @Override
            void lost(String message) {
                installed = true;   // the main screen's plan waits for the update, then shows what changed
                new AlertDialog.Builder(ModUpdatesActivity.this).setTitle("Still updating").setMessage(message)
                        .setPositiveButton("OK", null).show();
            }
        };
        if (ids == null) CatalogFlow.startAll(this, done);
        else CatalogFlow.start(this, ids, true, done);
    }

    private void hmgapiRow(final JSONObject h, boolean online) {
        Ui.gap(rows, 14);
        Ui.text(rows, "HMG-API", 22, true, Color.WHITE);
        Ui.text(rows, h.optString("installed") + "  →  " + h.optString("version") + (h.optBoolean("prerelease") ? "  (pre-release)" : ""),
                16, false, Ui.GREEN);
        if (h.optString("notes").length() > 0 || h.optString("page").length() > 0) {
            Ui.button(rows, "Release notes", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    notes(h);
                }
            });
        }
        Button b = Ui.button(rows, "Update HMG-API", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(ModUpdatesActivity.this)
                        .setTitle("Update HMG-API to " + h.optString("version") + "?")
                        .setMessage("It's downloaded over home Wi-Fi and checked against its release's checksums and HMG-API's release "
                                + "key. Then HMG-API's installer runs (a minute; leave the head unit on) and the head unit needs a restart. "
                                + "Your mods and settings stay.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                updateHmgApi(false);
                            }
                        }).show();
            }
        });
        b.setEnabled(online);
    }

    /** the release's notes (the engine keeps them from GitHub: the head unit may have no browser), and its page */
    private void notes(JSONObject h) {
        final String page = h.optString("page");
        String text = h.optString("notes").length() > 0 ? h.optString("notes").replace("\r", "") : "No notes with this release.";
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView t = new TextView(this);
        t.setText(text + (page.length() > 0 ? "\n\n" + page : ""));
        t.setTextIsSelectable(true);
        Ui.size(t, 16);
        int pad = Ui.dp(this, 16);
        t.setPadding(pad, pad, pad, pad);
        sv.addView(t);
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("HMG-API " + h.optString("version")).setView(sv)
                .setPositiveButton("OK", null);
        final android.content.Intent open = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(page));
        if (page.startsWith("https://") && open.resolveActivity(getPackageManager()) != null) {
            d.setNeutralButton("Open the page", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface x, int w) {
                    try {
                        startActivity(open);
                    } catch (Exception ignored) {
                        // no browser after all: the link is in the text
                    }
                }
            });
        }
        d.show();
    }

    /** force: the owner's "Update anyway" after mods that wouldn't take the new version were named */
    private void updateHmgApi(final boolean force) {
        final AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("Downloading and installing HMG-API… (leave the head unit on)").setCancelable(false).show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return force ? Hmgapid.call(900000, "engine", "update", "hmgapi", "--force") : Hmgapid.call(900000, "engine", "update", "hmgapi");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                wait.dismiss();
                if (e != null) {
                    message.setText("Lost contact while updating (" + e.getMessage() + "). The installer finishes on its own; restart the "
                            + "head unit once it's done.");
                    return;
                }
                AlertDialog.Builder d = new AlertDialog.Builder(ModUpdatesActivity.this)
                        .setTitle(r.code == 0 ? "HMG-API updated" : "Not updated").setMessage(MainActivity.readable(r.output));
                if (r.code == 0) {
                    d.setNegativeButton("Later", null).setPositiveButton("Restart now", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface x, int w) {
                            new Ui.Job<Hmgapid.Result>() {
                                @Override
                                Hmgapid.Result work() throws Exception {
                                    return Hmgapid.call("reboot");
                                }

                                @Override
                                void done(Hmgapid.Result rr, Exception ee) {}
                            }.start(ModUpdatesActivity.this);
                        }
                    });
                } else if (!force && r.output.contains("engine update hmgapi --force")) {   // mods that wouldn't load with it: the owner decides
                    d.setNegativeButton("Cancel", null).setNeutralButton("Update anyway", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface x, int w) {
                            new AlertDialog.Builder(ModUpdatesActivity.this).setTitle("Update anyway?")
                                    .setMessage("The mods named won't load with the new HMG-API, and applying changes stops until you update or "
                                            + "turn them off.")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Update anyway", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface y, int ww) {
                                            updateHmgApi(true);
                                        }
                                    }).show();
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
