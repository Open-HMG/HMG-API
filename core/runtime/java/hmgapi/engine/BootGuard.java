package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The zygote wrapper's boot guard (core/zygote/zygote.c), told to people: the app when its
 * hooks aren't running, the problem log, and the owner's reset.
 *
 *   engine boot-guard          its state, one "key: value" per line
 *   engine boot-guard reset    hooks on again from the next start (the app then restarts)
 *
 * The wrapper's files in /data/hmgapi/hook: starts (starts in a row that didn't boot), boot
 * ("<boot_id> <starts in that kernel boot>") and tripped ("boot|row <starts> <this boot>",
 * written when a limit was reached; hooks stay off until it's gone). The limits are
 * zygote.c's MAXSTARTS and MAXBOOTS.
 */
final class BootGuard {
    static final int MAXSTARTS = 3, MAXBOOTS = 6;

    private BootGuard() {
    }

    static int run(Engine e, String[] args) throws IOException {
        String sub = args.length > 1 ? args[1] : "";
        if ("reset".equals(sub)) {
            reset(e);
            e.out.add("reset: hooks on again from the next restart");
        } else if (!"".equals(sub)) {
            e.out.add("usage: engine boot-guard [reset]");
            return 2;
        }
        e.out.addAll(describe(e));
        return 0;
    }

    /** what the owner's reset does (as a reinstall): counts from 0, not tripped */
    static void reset(Engine e) throws IOException {
        File hook = new File(e.data, "hook");
        Engine.write(new File(hook, "starts"), "0\n");
        new File(hook, "boot").delete();
        File t = new File(hook, "tripped");
        if (t.exists() && !t.delete()) throw new IOException("can't remove " + t);
    }

    /** "boot guard: tripped|ok", then why (tripped only) and the counts */
    static List<String> describe(Engine e) {
        File hook = new File(e.data, "hook");
        List<String> l = new ArrayList<String>();
        String tripped = text(new File(hook, "tripped"));
        String starts = text(new File(hook, "starts"));
        int row = number(starts);
        String why = null;
        if (tripped != null) {
            if (tripped.startsWith("boot ")) {
                why = "Android had to start again " + MAXSTARTS + " times in one boot with HMG-API's hooks (it crashed or hung each time), "
                        + "so HMG-API turned its hooks off.";
            } else if (tripped.startsWith("row ")) {
                why = MAXBOOTS + " starts in a row with HMG-API's hooks didn't get to the home screen, so HMG-API turned its hooks off.";
            } else {
                why = "HMG-API's boot guard turned its hooks off (" + tripped.trim() + ").";
            }
        } else if (row >= MAXBOOTS) {
            why = MAXBOOTS + " starts in a row with HMG-API's hooks didn't get to the home screen, so HMG-API turns its hooks off.";
        }
        l.add("boot guard: " + (why != null ? "tripped" : "ok"));
        if (why != null) l.add("why: " + why);
        l.add("starts in a row that didn't boot: " + (starts == null ? "none" : starts.trim()) + " (off at " + MAXBOOTS + ")");
        String boot = text(new File(hook, "boot")), id = text(new File(e.root + "/proc/sys/kernel/random/boot_id"));
        int inBoot = 0;
        if (boot != null && id != null && boot.startsWith(id.trim() + " ")) inBoot = number(boot.substring(id.trim().length() + 1));
        l.add("starts this boot: " + inBoot + " (the " + (MAXSTARTS + 1) + "th runs without hooks)");
        if (tripped != null) l.add("tripped: " + tripped.trim());
        if (new File(hook, "disable").exists()) l.add("hook/disable: yes (hooks switched off by hand)");
        return l;
    }

    private static String text(File f) {
        try {
            return f.isFile() ? Engine.read(f) : null;
        } catch (IOException x) {
            return null;
        }
    }

    private static int number(String s) {
        if (s == null) return -1;
        int i = 0, v = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && v < 100000000) v = v * 10 + (s.charAt(i++) - '0');
        return i == 0 ? -1 : v;
    }
}
