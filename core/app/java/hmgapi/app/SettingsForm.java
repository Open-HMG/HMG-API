package hmgapi.app;

import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One mod's settings, drawn from its settings.json (toggle, text, number, choice, action)
 * into a column (the Mods tab). Save writes the owner's values with hmgapid
 * setting-set; "Apply changes" then rebuilds what they affect.
 */
final class SettingsForm {
    private final Activity a;
    private final LinearLayout list;
    private final String id;
    private final Runnable saved;
    private final Map<String, View> inputs = new LinkedHashMap<String, View>();
    private final Map<String, String> types = new LinkedHashMap<String, String>();
    private TextView message;
    private final List<Button> buttons = new ArrayList<Button>();

    SettingsForm(Activity a, LinearLayout list, String id, Runnable saved) {
        this.a = a;
        this.list = list;
        this.id = id;
        this.saved = saved;
    }

    void load() {
        message = Ui.text(list, "Loading its settings…", 16, false, Ui.GREY);
        new Ui.Job<JSONObject[]>() {
            @Override
            JSONObject[] work() throws Exception {
                Hmgapid.Result s = Hmgapid.call("mod-file", id, "settings.json");
                if (s.code != 0) return null;
                String v = Hmgapid.call("setting-get", id).output.trim();
                return new JSONObject[] { new JSONObject(s.output), new JSONObject(v.isEmpty() ? "{}" : v) };
            }

            @Override
            void done(JSONObject[] r, Exception e) {
                if (e != null) {
                    message.setText("Can't read its settings: " + e.getMessage());
                    message.setTextColor(Ui.ERROR);
                    return;
                }
                JSONArray settings = r == null ? null : r[0].optJSONArray("settings");
                if (settings == null || settings.length() == 0) {
                    message.setText("No settings.");
                    return;
                }
                list.removeView(message);
                draw(settings, r[1]);
            }
        }.start(a);
    }

    private void draw(JSONArray settings, JSONObject values) {
        for (int i = 0; i < settings.length(); i++) {
            JSONObject s = settings.optJSONObject(i);
            if (s == null || s.optBoolean("private")) continue;
            String key = s.optString("key"), type = s.optString("type", "text");
            if ("action".equals(type)) {
                final String service = s.optString("service");
                buttons.add(Ui.button(list, s.optString("title", "Run"), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        save(service);
                    }
                }));
                if (!s.optString("description").isEmpty()) Ui.text(list, s.optString("description"), 15, false, Ui.GREY);
                if (!s.optString("warning").isEmpty()) Ui.text(list, s.optString("warning"), 15, false, Ui.WARN);
                continue;
            }
            Object value = values.has(key) ? values.opt(key) : s.opt("default");
            if ("toggle".equals(type)) {
                Switch sw = Ui.switchRow(list, s.optString("title", key));
                sw.setChecked(Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value)));
                inputs.put(key, sw);
            } else if ("choice".equals(type)) {
                Ui.text(list, s.optString("title", key), 19, false, Ui.TEXT);
                RadioGroup g = new RadioGroup(a);
                JSONArray opts = s.optJSONArray("options");
                for (int k = 0; opts != null && k < opts.length(); k++) {
                    JSONObject o = opts.optJSONObject(k);
                    RadioButton rb = new RadioButton(a);
                    rb.setText(o.optString("title", o.optString("value")));
                    rb.setTextColor(Ui.TEXT);
                    rb.setTag(o.optString("value"));
                    rb.setId(1000 + k);
                    g.addView(rb);
                    if (o.optString("value").equals(String.valueOf(value))) g.check(rb.getId());
                }
                list.addView(g);
                inputs.put(key, g);
            } else {
                Ui.text(list, s.optString("title", key), 19, false, Ui.TEXT);
                EditText t = new EditText(a);
                t.setText(value == null || value == JSONObject.NULL ? "" : String.valueOf(value));
                t.setTextColor(Ui.TEXT);
                if ("number".equals(type)) t.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                list.addView(t);
                inputs.put(key, t);
            }
            types.put(key, type);
            if (s.optString("description").length() > 0) Ui.text(list, s.optString("description"), 15, false, Ui.GREY);
            if (s.optString("warning").length() > 0) Ui.text(list, s.optString("warning"), 15, false, Ui.WARN);
        }
        buttons.add(Ui.button(list, "Save settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(null);
            }
        }));
        message = Ui.text(list, "", 16, false, Color.GRAY);
    }

    private void save(final String service) {
        final JSONObject out = new JSONObject();
        try {
            for (Map.Entry<String, View> e : inputs.entrySet()) {
                String type = types.get(e.getKey());
                View v = e.getValue();
                if ("toggle".equals(type)) {
                    out.put(e.getKey(), ((Switch) v).isChecked());
                } else if ("choice".equals(type)) {
                    RadioGroup g = (RadioGroup) v;
                    View sel = g.findViewById(g.getCheckedRadioButtonId());
                    if (sel != null) out.put(e.getKey(), sel.getTag());
                } else if ("number".equals(type)) {
                    String t = ((EditText) v).getText().toString().trim();
                    if (!t.isEmpty()) out.put(e.getKey(), Long.parseLong(t));
                } else {
                    out.put(e.getKey(), ((EditText) v).getText().toString());
                }
            }
        } catch (Exception e) {
            message.setText("Not saved: " + e.getMessage());
            message.setTextColor(Ui.ERROR);
            return;
        }
        for (Button b : buttons) b.setEnabled(false);
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                if (service != null) {
                    JSONObject mod = new JSONObject(Hmgapid.call("mod-file", id, "mod.json").output);
                    JSONArray services = mod.optJSONArray("services");
                    boolean manual = false;
                    for (int i = 0; services != null && i < services.length(); i++) {
                        JSONObject s = services.getJSONObject(i);
                        if (service.equals(s.optString("name")) && "manual".equals(s.optString("start"))) manual = true;
                    }
                    if (!manual) throw new Exception("Action must name a manual service of this mod.");
                    boolean enabled = false;
                    for (String line : Hmgapid.call("mods").output.split("\n")) {
                        String[] fields = line.split("\t");
                        if (fields.length >= 2 && id.equals(fields[0]) && "enabled".equals(fields[1].trim())) enabled = true;
                    }
                    if (!enabled) throw new Exception("Turn this mod on first.");
                    for (String line : Hmgapid.call("services", id).output.split("\n")) {
                        String[] fields = line.trim().split(" +");
                        if (fields.length == 5 && service.equals(fields[1])
                                && ("running".equals(fields[2]) || "waiting".equals(fields[2]))) {
                            throw new Exception("This action is already running; wait for its result.");
                        }
                    }
                }
                Hmgapid.Result r = Hmgapid.call("setting-set", id, out.toString());
                return r.code == 0 && service != null ? Hmgapid.call("service-start", id, service) : r;
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                // The supervisor consumes a start request on its next tick.
                list.postDelayed(new Runnable() {
                    @Override public void run() {
                        for (Button b : buttons) b.setEnabled(true);
                    }
                }, service != null && e == null && r.code == 0 ? 2000 : 0);
                if (e != null || r.code != 0) {
                    message.setText("Couldn't save or run: " + (e != null ? e.getMessage() : r.output.trim()));
                    message.setTextColor(Ui.ERROR);
                    return;
                }
                message.setText(service != null ? "Settings saved. Action requested; see the mod's log for its result."
                        : "Saved. Live settings take effect immediately; apply changes for other settings.");
                message.setTextColor(Ui.GREEN);
                if (saved != null) saved.run();
            }
        }.start(a);
    }
}
