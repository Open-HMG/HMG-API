package hmgapi.app;

import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One mod's settings, drawn from its settings.json (toggle, text, number, choice)
 * into a column (the Mods tab). Each change is saved as it's made (hmgapid
 * setting-set): a switch or choice at once, a text or number field when it loses
 * focus or Done is pressed, and anything still open when "Apply changes" runs or
 * the screen changes (flush). "Apply changes" then rebuilds what they affect.
 */
final class SettingsForm {
    private final Activity a;
    private final LinearLayout list;
    private final String id;
    private final Runnable saved;
    private final Map<String, View> inputs = new LinkedHashMap<String, View>();
    private final Map<String, String> types = new LinkedHashMap<String, String>();
    private TextView message;
    /** the values as last saved (or loaded); null until drawn */
    private String stored;
    /** a setting-set is running / another is wanted after it */
    private boolean saving, again;
    /** what runs once the current values are saved (flush) */
    private final java.util.List<Runnable> after = new java.util.ArrayList<Runnable>();

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
            Object value = values.has(key) ? values.opt(key) : s.opt("default");
            if ("toggle".equals(type)) {
                Switch sw = Ui.switchRow(list, s.optString("title", key));
                sw.setChecked(Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value)));
                sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        save();
                    }
                });
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
                g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(RadioGroup group, int checked) {
                        save();
                    }
                });
                list.addView(g);
                inputs.put(key, g);
            } else {
                Ui.text(list, s.optString("title", key), 19, false, Ui.TEXT);
                EditText t = new EditText(a);
                t.setText(value == null || value == JSONObject.NULL ? "" : String.valueOf(value));
                t.setTextColor(Ui.TEXT);
                t.setSingleLine(true);
                t.setImeOptions(EditorInfo.IME_ACTION_DONE);
                if ("number".equals(type)) t.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean has) {
                        if (!has) save();
                    }
                });
                t.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                    @Override
                    public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                        save();
                        return false;   // the keyboard still closes
                    }
                });
                list.addView(t);
                inputs.put(key, t);
            }
            types.put(key, type);
            if (s.optString("description").length() > 0) Ui.text(list, s.optString("description"), 15, false, Ui.GREY);
            if (s.optString("warning").length() > 0) Ui.text(list, s.optString("warning"), 15, false, Ui.WARN);
        }
        message = Ui.text(list, "Changes are saved as you make them. Apply changes (top) puts them on the head unit.",
                16, false, Color.GRAY);
        try {
            stored = values().toString();
        } catch (Exception e) {
            stored = "";   // a stored value the form can't show: the first change saves them all
        }
    }

    /** Are there edits not saved yet (a text field still open)? */
    boolean unsaved() {
        if (stored == null) return false;
        try {
            return !values().toString().equals(stored);
        } catch (Exception e) {
            return true;   // not a number: shown by save()
        }
    }

    /**
     * Saves what's not saved yet, then runs next (on the UI thread). Nothing to save:
     * next runs now. Can't save (not a number, hmgapid refused): the reason is shown
     * by the field and next doesn't run. True: something is being saved (next runs after
     * it, and the saved callback is left to next's caller).
     */
    boolean flush(Runnable next) {
        if (!saving && !unsaved()) {
            if (next != null) next.run();
            return false;
        }
        if (next != null) after.add(next);
        save();
        return true;
    }

    private JSONObject values() throws Exception {
        JSONObject out = new JSONObject();
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
                try {
                    if (!t.isEmpty()) out.put(e.getKey(), Long.parseLong(t));
                } catch (NumberFormatException x) {
                    throw new Exception("\"" + t + "\" isn't a whole number");
                }
            } else {
                out.put(e.getKey(), ((EditText) v).getText().toString());
            }
        }
        return out;
    }

    /** One setting-set at a time, each with all the current values; a change while one runs saves again after it. */
    private void save() {
        if (stored == null) return;   // still drawing: the initial values aren't changes
        if (saving) {
            again = true;
            return;
        }
        final JSONObject out;
        try {
            out = values();
        } catch (Exception e) {
            fail(e.getMessage());
            return;
        }
        if (out.toString().equals(stored)) {
            done();
            return;
        }
        saving = true;
        message.setText("Saving…");
        message.setTextColor(Ui.GREY);
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call("setting-set", id, out.toString());
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                saving = false;
                if (e != null || r.code != 0) {
                    again = false;
                    fail(e != null ? e.getMessage() : r.output.trim());
                    return;
                }
                stored = out.toString();
                if (again) {
                    again = false;
                    save();
                    return;
                }
                message.setText("Saved. Apply changes (top) to put it on the head unit.");
                message.setTextColor(Ui.GREEN);
                if (saved != null && after.isEmpty()) saved.run();   // someone waiting (Apply) checks for itself
                SettingsForm.this.done();
            }
        }.start(a);
    }

    private void done() {
        java.util.List<Runnable> run = new java.util.ArrayList<Runnable>(after);
        after.clear();
        for (Runnable n : run) n.run();
    }

    private void fail(String why) {
        boolean waiting = !after.isEmpty();   // Apply (or leaving) was waiting: say so out loud, and it doesn't go on
        after.clear();
        message.setText("Not saved: " + why);
        message.setTextColor(Ui.ERROR);
        if (waiting && !a.isFinishing()) {
            new android.app.AlertDialog.Builder(a).setTitle("A setting isn't saved").setMessage(why)
                    .setPositiveButton("OK", null).show();
        }
    }
}
