package hmgapi.engine;

/**
 * The gear, for the steps that only start in P: anything that changes /system, holds
 * system apps (pm disable while their files change) or restarts the unit. A restart takes
 * the reverse camera's picture away for about a minute, and an install or apply cut off by
 * a shift to reverse can leave the unit half changed (2026-11-27: a NEMESIS install, with
 * no HMG-API, was cut off that way and the unit didn't start again).
 *
 * Read through hmgapid (`vehicle gear.park`, HMG-API's vehicle service, which hmgapid runs
 * on every unit). Not in P: refused, with nothing changed, and no override. No reading (the
 * service isn't up yet, or the car's map has no gear point): refused unless the owner says
 * to go on (--gear-unknown-ok), the warning plus override. hmgapid's reboot and the
 * installers (lib.sh, gear_gate) answer with the same exit codes.
 */
final class Gear {
    private Gear() {}

    static final int NOT_PARKED = 75;
    static final int UNKNOWN = 76;
    static final String OVERRIDE = "--gear-unknown-ok";

    /** 1 in P, 0 not in P, -1 no reading. Off-car: HMGAPI_HMGAPID (a stand-in), else in P. */
    static int read(Engine e) {
        String hm = Engine.env("HMGAPI_HMGAPID", e.host ? null : "/system/bin/hmgapid");
        if (hm == null) return 1;
        try {
            Engine.Run r = Engine.exec(hm, "vehicle", "gear.park");
            return r.code == 0 ? parse(r.text) : -1;
        } catch (Exception x) {
            return -1;
        }
    }

    /** hmgapid's answer, "gear.park 1" ("-" when the car didn't answer) */
    static int parse(String text) {
        for (String l : text.split("\n")) {
            l = l.trim();
            if (!l.startsWith("gear.park ")) continue;
            String v = l.substring(10).trim();
            return "1".equals(v) ? 1 : "0".equals(v) ? 0 : -1;
        }
        return -1;
    }

    /**
     * Before a step that only starts in P: 0 to go on (the owner told to stay in P), else the
     * exit code, with the reason in the engine's output. what: "an apply", "the update"...
     */
    static int check(Engine e, String what, boolean unknownOk) {
        int g = read(e);
        if (g == 1) {
            e.out.add("in P     keep the car in P until " + what + " is done");
            return 0;
        }
        if (g == 0) {
            e.out.add("refused: the car isn't in P, and " + what + " only starts in P (shifting into reverse while it runs "
                    + "can cut it off half done). Shift to P, stay there, and start it again. Nothing was changed.");
            return NOT_PARKED;
        }
        if (unknownOk) {
            e.out.add("warning: the gear can't be read on this car, so HMG-API can't check that it's in P; going on as asked. "
                    + "Keep the car in P until " + what + " is done.");
            return 0;
        }
        e.out.add("refused: the gear can't be read on this car (no vehicle reading yet), so HMG-API can't check that it's in P. "
                + "Put the car in P and keep it there, then go on anyway (" + OVERRIDE + "). Nothing was changed.");
        return UNKNOWN;
    }
}
