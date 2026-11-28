package hmgapi.app;

import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Logs > Record a problem: the problem log (hmgapid engine problem record), for an owner with
 * an issue to send to whoever helps them. Says first what it takes, then records while the
 * owner makes the problem happen, and saves a .txt on the USB stick and in HMG-API's folder.
 * The sensitive parts (crash dumps, Android's crash reports, the kernel log) are the owner's
 * choice, off until they turn them on; recording can be off too (collect at once). The recording keeps going
 * when the owner leaves the page; coming back shows it.
 */
final class ProblemPage {
    static final int MAX_SECONDS = 1800;
    static boolean recording;
    /** record new log lines (on), crash dumps, Android's crash reports, kernel log (off: the owner's choice) */
    static final boolean[] on = { true, false, false, false };
    static final String[] FLAGS = { null, "--crash-dumps", "--crash-reports", "--kernel" };
    static long startedAt;
    static String result, error;

    final MainActivity a;
    TextView status;
    Button goButton;
    final Handler tick = new Handler();

    ProblemPage(MainActivity a) {
        this.a = a;
    }

    View view() {
        LinearLayout root = Ui.row(a);
        LinearLayout list = Ui.scrolling(root, 1, 0);
        list.setPadding(Ui.dp(a, 36), Ui.dp(a, 16), Ui.dp(a, 36), Ui.dp(a, 24));
        Ui.button(list, "‹ Back to the log", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogsTab.problem = false;
                a.show(MainActivity.LOGS);
            }
        });
        Ui.text(list, "Record a problem", 28, true, Ui.TEXT);
        Ui.text(list, "For when something goes wrong (an app crashes, CarPlay stops, no sound, HMG-API's hooks stop) and "
                + "someone is helping you find out why. Pick what to include, then start recording, make the problem "
                + "happen and stop (30 minutes at most; keep the USB stick in). For a problem that already happened, "
                + "turn recording off and collect at once.", 17, false, Ui.TEXT);
        Ui.text(list, "Always in the file", 20, true, Ui.TEXT);
        Ui.text(list, "• The head unit: model, region, firmware, platform, and HMG-API's version.\n"
                + "• Your installed mods, the system files they add or patch (checksums), and whether those are there.\n"
                + "• HMG-API's state and recent errors: whether its hooks are on, its boot guard, and its own lines from "
                + "each start.\n"
                + "Device addresses, phone numbers, VIN, serial numbers, device ids and GPS positions are removed from "
                + "every line. Open the file and look before you send it.", 16, false, Ui.GREY);
        Ui.text(list, "Your choice", 20, true, Ui.TEXT);
        option(list, 0, "Record new log lines", "While it records: lines from crashes, CarPlay, Android Auto, audio, Bluetooth "
                + "connections, the Wi-Fi hotspot, HMG-API and its mods. Lines from every other app (phone, contacts, messages, "
                + "navigation, radio) are left out, only counted. CarPlay and Android Auto lines can name your phone or what's playing.");
        option(list, 1, "Crash dumps (native)", "The head unit's records of the last program crashes (/data/tombstones): the "
                + "newest 5, cut down to which program crashed, how, and where in its code. The memory contents and log lines "
                + "a dump also holds are left out.");
        option(list, 2, "Android's crash reports", "Android's own reports of app and system crashes and freezes "
                + "(/data/system/dropbox), kept across restarts, so they include crashes while the head unit was starting: "
                + "the newest 10, each cut to which program, the error and where in its code. An error message can quote "
                + "what the program was handling (a name, a song).");
        option(list, 3, "Kernel log", "The head unit's low-level log since it started (drivers, USB, Wi-Fi, Bluetooth). It "
                + "names the devices you plugged in or paired; their serial numbers and addresses are removed.");
        LinearLayout buttons = Ui.row(a);
        list.addView(buttons);
        final Button go = Ui.button(buttons, recording ? "Stop" : on[0] ? "Start recording" : "Collect now", true, null);
        goButton = go;
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) stop(go);
                else start();
            }
        });
        status = Ui.text(list, "", 18, false, Ui.WARN);
        draw();
        return root;
    }

    void option(LinearLayout list, final int i, String title, String what) {
        final Switch sw = Ui.switchRow(list, title);
        sw.setChecked(on[i]);
        sw.setEnabled(!recording);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                on[i] = v;
                if (i == 0 && goButton != null) goButton.setText(v ? "Start recording" : "Collect now");
            }
        });
        Ui.text(list, what, 16, false, Ui.GREY);
    }

    void start() {
        recording = true;
        startedAt = System.currentTimeMillis();
        result = error = null;
        final java.util.List<String> cmd = new java.util.ArrayList<String>(java.util.Arrays.asList("engine", "problem", "record", "--usb"));
        if (!on[0]) cmd.add("--no-log");
        for (int i = 1; i < on.length; i++) if (on[i]) cmd.add(FLAGS[i]);
        new Ui.Job<String>() {
            @Override
            String work() throws Exception {
                Hmgapid.Result r = Hmgapid.call((MAX_SECONDS + 120) * 1000, cmd.toArray(new String[0]));
                if (r.code != 0) throw new Exception(r.output.trim());
                return r.output;
            }

            @Override
            void done(String r, Exception e) {
                recording = false;
                result = r;
                error = e == null ? null : String.valueOf(e.getMessage());
                if (LogsTab.problem && a.current == MainActivity.LOGS) a.show(MainActivity.LOGS);   // redraw, if the page is still open
            }
        }.start(a);
        a.show(MainActivity.LOGS);
    }

    void stop(final Button go) {
        go.setEnabled(false);
        go.setText("Stopping…");
        new Ui.Job<String>() {
            @Override
            String work() throws Exception {
                return Hmgapid.call("engine", "problem", "stop").output;
            }

            @Override
            void done(String r, Exception e) {
                if (e != null) status.setText("Couldn't stop it: " + e.getMessage() + " (it stops by itself after 30 minutes)");
            }
        }.start(a);
    }

    void draw() {
        if (recording) {
            long s = (System.currentTimeMillis() - startedAt) / 1000;
            status.setTextColor(Ui.WARN);
            status.setText(!on[0] ? "Collecting…" : "Recording: " + s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60
                    + " (stops by itself at 30:00). Make the problem happen now, then come back here and tap Stop.");
            tick.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (status.isShown()) draw();
                }
            }, 1000);
            return;
        }
        if (error != null) {
            status.setTextColor(Ui.ERROR);
            status.setText("Couldn't record: " + error);
            return;
        }
        if (result == null) {
            status.setText("");
            return;
        }
        String name = null, kept = null;
        StringBuilder usb = new StringBuilder();
        for (String l : result.split("\n")) {
            if (l.startsWith("problem: ")) name = l.substring(9).trim();
            else if (l.startsWith("kept: ")) kept = l.substring(6).trim();
            else if (l.startsWith("usb: ")) usb.append(l.substring(5).trim()).append('\n');
        }
        status.setTextColor(Ui.TEXT);
        status.setText("Saved: " + name + ".txt (" + kept + ")\n\n"
                + "USB stick: " + (usb.length() > 0 ? usb.toString() : "none\n")
                + "adb (from a computer):\n    adb pull /data/hmgapi/public/reports/" + name + ".txt\n\n"
                + "Look through it, then send it to whoever is helping you (attach it to the GitHub issue).");
    }
}
