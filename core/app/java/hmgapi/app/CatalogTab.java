package hmgapi.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * Get mods: HMG-API's mod catalogue (engine catalog, kept from the last check; "Check now"
 * fetches it again over home Wi-Fi). Each mod with what installing it would do here; Install
 * takes the mods it requires too (CatalogFlow), then offers to apply. A tab of the main screen.
 */
final class CatalogTab {
    final MainActivity a;

    CatalogTab(MainActivity a) {
        this.a = a;
    }

    private LinearLayout rows;
    private TextView message, when;
    private Button connect;

    View view() {
        LinearLayout root = Ui.column(a);
        root.setBackgroundColor(Ui.BG);
        LinearLayout list = Ui.scrolling(root, 1, LinearLayout.LayoutParams.MATCH_PARENT);
        list.setPadding(Ui.dp(a, 32), Ui.dp(a, 16), Ui.dp(a, 32), Ui.dp(a, 24));
        Ui.text(list, "Get mods", 30, true, Ui.GREEN);
        Ui.text(list, "HMG-API's mod catalogue: mods the HMG-API project lists, each from its author's own GitHub release. "
                + "Mods they need are installed with them. Checked and downloaded over home Wi-Fi only.", 16, false, Color.GRAY);
        when = Ui.text(list, "", 15, false, Color.GRAY);
        message = Ui.text(list, "", 18, false, Ui.WARN);
        LinearLayout buttons = Ui.row(a);
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
                CatalogFlow.connect(a, new Runnable() {
                    @Override
                    public void run() {
                        load(true);
                    }
                });
            }
        });
        connect.setVisibility(View.GONE);
        list.addView(buttons);
        rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        list.addView(rows);
        load(false);
        return root;
    }

    private void load(final boolean refresh) {
        message.setText(refresh ? "Checking the catalogue…" : "Loading…");
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return refresh ? Hmgapid.call(180000, "engine", "catalog", "--refresh") : Hmgapid.call(60000, "engine", "catalog");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                rows.removeAllViews();
                JSONObject d;
                try {
                    if (e != null) throw e;
                    d = CatalogFlow.json(r.output);
                } catch (Exception x) {
                    message.setText(e != null ? Hmgapid.unreachable(e) : "Couldn't read the catalogue: " + x.getMessage());
                    return;
                }
                boolean online = d.optBoolean("online");
                connect.setVisibility(online ? View.GONE : View.VISIBLE);
                when.setText(CatalogFlow.checked(d));
                JSONArray mods = d.optJSONArray("mods");
                if (d.has("error")) message.setText("Not checked: " + d.optString("error"));
                else if (mods == null || mods.length() == 0) message.setText(online ? "Tap Check now to get the catalogue."
                        : "Not checked yet. " + d.optString("offline", "") + ". Tap Connect to home Wi-Fi, in P, to check now.");
                else message.setText(online ? "" : "Offline (" + d.optString("offline") + "): showing the last check.");
                for (int i = 0; mods != null && i < mods.length(); i++) row(mods.optJSONObject(i), online);
            }
        }.start(a);
    }

    private void row(final JSONObject m, boolean online) {
        Ui.gap(rows, 14);
        String st = m.optString("status");
        boolean unfit = m.has("unfit"), hmgapiUnfit = unfit && !m.optString("unfit").startsWith("car: ");
        Ui.text(rows, m.optString("name", m.optString("id")) + "  " + m.optString("version"), 22, true, hmgapiUnfit ? Color.GRAY : Color.WHITE);
        String what;
        if ("same".equals(st)) what = "Installed" + (m.optBoolean("enabled", true) ? "" : " (turned off)");
        else if ("update".equals(st)) what = "Update from " + m.optString("installed");
        else if ("older".equals(st)) what = "Installed " + m.optString("installed") + " (newer than the catalogue's)";
        else what = "Not installed";
        Ui.text(rows, what, 16, false, "update".equals(st) ? Ui.GREEN : Color.LTGRAY);
        if (m.optString("description").length() > 0) Ui.text(rows, m.optString("description"), 15, false, Color.GRAY);
        JSONArray au = m.optJSONArray("authors");
        StringBuilder by = new StringBuilder();
        for (int i = 0; au != null && i < au.length(); i++) by.append(i > 0 ? ", " : "").append(au.optString(i));
        if (by.length() > 0) Ui.text(rows, "By " + by + (m.optBoolean("trusted") ? "" : "  ·  not on HMG-API's trusted author list"
                + ("new".equals(st) ? ": installing it needs your yes" : "")), 15, false,
                m.optBoolean("trusted") ? Color.GRAY : Ui.WARN);
        JSONArray req = m.optJSONArray("requires");
        if (req != null && req.length() > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < req.length(); i++) b.append(i > 0 ? ", " : "").append(req.optString(i));
            Ui.text(rows, "Needs " + b + " (installed with it if missing)", 15, false, Color.GRAY);
        }
        if (unfit) Ui.text(rows, hmgapiUnfit ? "Can't install: " + m.optString("unfit") : "Not listed for this head unit: " + m.optString("unfit").substring(5)
                + (m.optBoolean("any_car") ? " (you chose to use it here)" : ""),
                15, false, hmgapiUnfit ? Ui.ERROR : Ui.WARN);
        if (hmgapiUnfit || "older".equals(st) || ("same".equals(st) && m.optBoolean("enabled", true))) return;
        final boolean update = "update".equals(st);
        String label = update ? "Update" : "same".equals(st) ? "Turn on" : "Install";
        Button b = Ui.button(rows, label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CatalogFlow.start(a, Arrays.asList(m.optString("id")), update, new CatalogFlow.Done() {
                    @Override
                    public void installed(String output) {
                        CatalogFlow.applyNow(a, "Installed", MainActivity.readable(output));
                        load(false);
                    }

                    @Override
                    public void failed(String output) {
                        new AlertDialog.Builder(a).setTitle("Not installed").setMessage(output)
                                .setPositiveButton("OK", null).show();
                        load(false);
                    }

                    @Override
                    void lost(String message) {
                        new AlertDialog.Builder(a).setTitle("Still installing").setMessage(message)
                                .setPositiveButton("OK", null).show();
                    }
                });
            }
        });
        if (!online && !"same".equals(st)) b.setEnabled(false);
    }
}
