package hmgapi.app;

import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Logs: HMG-API's log (hmgapid log: the log keeper's file, every process's HMG-API
 * lines), newest last, filtered: everything, HMG-API itself, mods (their "[mod]"
 * lines), or warnings and errors only. "Record a problem" opens ProblemPage.
 */
final class LogsTab {
    static final String[] FILTERS = { "Everything", "HMG-API", "Mods", "Warnings and errors" };
    final MainActivity a;
    LinearLayout chips;
    TextView text;
    String raw = "";
    static int filter;
    static boolean problem;   // the "Record a problem" page is open

    LogsTab(MainActivity a) {
        this.a = a;
    }

    View view() {
        if (problem) return new ProblemPage(a).view();
        LinearLayout root = Ui.column(a);
        root.setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), Ui.dp(a, 12));
        LinearLayout bar = Ui.row(a);
        chips = Ui.row(a);
        bar.addView(chips, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Ui.button(bar, ProblemPage.recording ? "Recording a problem…" : "Record a problem", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                problem = true;
                a.show(3);
            }
        });
        Ui.button(bar, "Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        root.addView(bar);
        LinearLayout box = Ui.scrolling(root, 1, LinearLayout.LayoutParams.MATCH_PARENT);
        text = Ui.mono(box);
        text.setText("Loading…");
        drawChips();
        load();
        return root;
    }

    void drawChips() {
        chips.removeAllViews();
        for (int i = 0; i < FILTERS.length; i++) {
            final int k = i;
            TextView t = new TextView(a);
            t.setText(FILTERS[i]);
            Ui.size(t, 17);
            t.setTextColor(i == filter ? 0xff0a140c : Ui.TEXT);
            t.setBackground(Ui.box(a, i == filter ? Ui.ACCENT : Ui.PANEL2, i == filter ? 0 : Ui.LINE, 20));
            t.setPadding(Ui.dp(a, 18), Ui.dp(a, 8), Ui.dp(a, 18), Ui.dp(a, 8));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filter = k;
                    drawChips();
                    draw();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(a, 12);
            chips.addView(t, lp);
        }
    }

    void load() {
        new Ui.Job<String>() {
            @Override
            String work() throws Exception {
                return Hmgapid.call("log").output;
            }

            @Override
            void done(String r, Exception e) {
                raw = e != null ? "" : r;
                if (e != null) text.setText("Can't read the log: " + e.getMessage());
                else draw();
            }
        }.start(a);
    }

    /** "10-31 19:15:18.140 I/HMG-API ( 1058): msg" -> "19:15:18" */
    static String time(String l) {
        return l.length() > 14 && l.charAt(2) == '-' ? l.substring(6, 14) : "";
    }

    void draw() {
        SpannableStringBuilder s = new SpannableStringBuilder();
        String[] ls = raw.split("\n");
        int shown = 0;
        for (int i = Math.max(0, ls.length - 600); i < ls.length; i++) {
            String l = ls[i];
            int c = l.indexOf("): ");
            if (c < 0 || !l.contains("HMG-API")) continue;
            String msg = l.substring(c + 3);
            char level = l.length() > 19 ? l.charAt(19) : 'I';
            String src = "hmgapi";
            if (msg.startsWith("[") && msg.indexOf("] ") > 0) {
                src = msg.substring(1, msg.indexOf("] "));
                msg = msg.substring(msg.indexOf("] ") + 2);
            }
            if (filter == 1 && !"hmgapi".equals(src)) continue;
            if (filter == 2 && "hmgapi".equals(src)) continue;
            if (filter == 3 && level != 'W' && level != 'E') continue;
            int color = level == 'E' ? Ui.ERROR : level == 'W' ? Ui.WARN : Ui.TEXT;
            append(s, time(l) + "  ", Ui.OFF);
            append(s, pad(src, 22), Ui.GREEN);
            append(s, msg + "\n", color);
            shown++;
        }
        if (shown == 0) s.append("Nothing logged yet" + (filter == 0 ? "." : " for this filter."));
        text.setText(s);
        final android.widget.ScrollView sv = (android.widget.ScrollView) ((View) text.getParent()).getParent();
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);   // newest last, in view
            }
        });
    }

    static void append(SpannableStringBuilder s, String t, int color) {
        int at = s.length();
        s.append(t);
        s.setSpan(new ForegroundColorSpan(color), at, s.length(), 0);
    }

    static String pad(String s, int n) {
        if (s.length() >= n) return s.substring(0, n - 1) + " ";
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }
}
