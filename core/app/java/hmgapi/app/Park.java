package hmgapi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

/**
 * Steps that only start with the car in P: a restart (it takes the reverse camera's picture
 * away for about a minute), and anything that changes /system or stops system apps for a
 * while (an apply, an install, an HMG-API update), which a shift into reverse can cut off half
 * done. The gear comes from HMG-API's vehicle reading (hmgapid `vehicle gear.park`). Not in P:
 * the owner is told to shift to P, and nothing starts. No reading on this car: the warning
 * plus override (the owner says the car is in P, and the step runs with --gear-unknown-ok).
 * hmgapid's reboot and the engine check again (hmgapi.engine.Gear: exit codes 75 and 76), so
 * a shift between the check here and the step still stops it.
 */
final class Park {
    private Park() {}

    static final int NOT_PARKED = 75;
    static final int UNKNOWN = 76;
    static final String OVERRIDE = "--gear-unknown-ok";
    static final String STAY = "Keep the car in P until it's done.";

    interface Go {
        /** the step itself; unknownOk: add OVERRIDE (no gear reading, the owner said go on) */
        void go(boolean unknownOk);
    }

    /** what: the step, for the messages ("Restarting the head unit") */
    static void then(final Activity a, final String what, final Go go) {
        new Ui.Job<Integer>() {
            @Override
            Integer work() throws Exception {
                Hmgapid.Result r = Hmgapid.call("vehicle", "gear.park");
                return r.code == 0 ? parse(r.output) : -1;
            }

            @Override
            void done(Integer g, Exception e) {
                int v = e != null || g == null ? -1 : g;
                if (v == 1) go.go(false);
                else if (v == 0) notParked(a, what);
                else unknown(a, what, go);
            }
        }.start(a);
    }

    /** hmgapid's answer, "gear.park 1" ("-" when the car didn't answer): 1, 0, or -1 */
    static int parse(String out) {
        for (String l : out.split("\n")) {
            l = l.trim();
            if (l.equals("gear.park 1")) return 1;
            if (l.equals("gear.park 0")) return 0;
        }
        return -1;
    }

    static void notParked(Activity a, String what) {
        new AlertDialog.Builder(a).setTitle("Shift to P first")
                .setMessage(what + " only starts with the car in P: shifting into reverse while it runs can cut it off "
                        + "half done, and a restart takes the reverse camera's picture away for about a minute. Nothing was changed.")
                .setPositiveButton("OK", null).show();
    }

    static void unknown(Activity a, final String what, final Go go) {
        new AlertDialog.Builder(a).setTitle("Is the car in P?")
                .setMessage("HMG-API can't read the gear on this car, so it can't check that it's in P. " + what
                        + " should only run in P: shifting into reverse while it runs can cut it off half done, and a "
                        + "restart takes the reverse camera's picture away for about a minute.\n\nPut the car in P. " + STAY)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("It's in P: go on", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        go.go(true);
                    }
                }).show();
    }

    /**
     * An op the engine or hmgapid refused for the gear (75, 76): said here, true. For 76 the
     * owner may go on: retry runs the step again with OVERRIDE.
     */
    static boolean refused(Activity a, Hmgapid.Result r, String what, Go retry) {
        if (r == null) return false;
        if (r.code == NOT_PARKED) {
            notParked(a, what);
            return true;
        }
        if (r.code == UNKNOWN) {
            unknown(a, what, retry);
            return true;
        }
        return false;
    }

    /** a command with OVERRIDE added when asked */
    static String[] with(boolean unknownOk, String... cmd) {
        if (!unknownOk) return cmd;
        String[] c = new String[cmd.length + 1];
        System.arraycopy(cmd, 0, c, 0, cmd.length);
        c[cmd.length] = OVERRIDE;
        return c;
    }

    /** Restart the head unit: asked first, then only in P (every restart button: Main, Updates) */
    static void confirmRestart(final Activity a) {
        new AlertDialog.Builder(a)
                .setTitle("Restart the head unit?")
                .setMessage("The screen goes dark for about a minute, and the reverse camera's picture with it, so this "
                        + "only runs with the car in P. Don't use it with navigation or a call active.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restart", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        restart(a);
                    }
                })
                .show();
    }

    /** the restart itself, after the owner's yes: the gear, then hmgapid reboot */
    static void restart(final Activity a) {
        then(a, RESTART, new Go() {
            @Override
            public void go(boolean unknownOk) {
                restartNow(a, unknownOk);
            }
        });
    }

    static final String RESTART = "Restarting the head unit";

    /** hmgapid reboot, which checks the gear again (a shift since the check here: said, nothing done) */
    static void restartNow(final Activity a, final boolean unknownOk) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(with(unknownOk, "reboot"));
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (e == null && r.code == 0) return;
                if (e == null && refused(a, r, RESTART, new Go() {
                    @Override
                    public void go(boolean ok) {
                        restartNow(a, ok);
                    }
                })) return;
                new AlertDialog.Builder(a).setTitle("Couldn't restart")
                        .setMessage(e != null ? "hmgapid isn't running (" + e.getMessage() + ")" : r.output.trim())
                        .setPositiveButton("OK", null).show();
            }
        }.start(a);
    }
}
