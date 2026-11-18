package hmgapi.vehicle;

/**
 * When the car counts as parked, for the "parked" and "unparked" car events. The car stays
 * on for a while after it's switched off (the head unit keeps running), so "off, in P, doors
 * closed" is already true while the driver still sits in the car. The owner picks the rule
 * (hmgapid park-rule, settings/park_rule):
 *
 *   door     (the default) off, in P, and since the car went off a door opened and then all
 *            four closed: the driver got out
 *   closed   off, in P, all four doors closed
 *   <secs>   off, in P, all four doors closed for that long (10-1800 s), without a door opening
 *
 * "parked" fires once per stop. "unparked" fires when the car is switched on again after a
 * "parked" (before the head unit shut down), so a mod can bring back what it put away.
 *
 * Off: the power service's status (MicomPowerConstants.AccStatus) is logic on, ADM, delay
 * mode, preparing system off or off; on: ACC on. Doors: body control's door status (the
 * trunk and hood don't count). With no door status from the car there's no "parked":
 * guessing would put a mod's things away with the driver still in the car.
 */
final class Parking {
    static final int ACC_ON = 0, LOGIC_ON = 1, ADM = 2, SYSTEM_READY = 5, DELAY_MODE = 6, PRE_SYSTEM_OFF = 7, OFF = 0xff;
    static final String DOOR = "door", CLOSED = "closed";
    static final int MIN_WAIT = 10, MAX_WAIT = 1800;

    /** the rule: DOOR, CLOSED, or seconds as text */
    String rule = DOOR;
    /** the car's power status, gear P (1, 0, -1 unknown), the four doors' open state (1, 0, -1 unknown) */
    int power = -1, park = -1;
    final int[] doors = { -1, -1, -1, -1 };

    boolean off, parked, opened, saidNoDoors;
    long closedSince = -1;

    /** a rule as the owner gave it; null when it isn't one */
    static String rule(String s) {
        if (s == null) return null;
        s = s.trim();
        if (DOOR.equals(s) || CLOSED.equals(s)) return s;
        if (!s.matches("[0-9]{1,4}")) return null;
        int n = Integer.parseInt(s);
        return n >= MIN_WAIT && n <= MAX_WAIT ? String.valueOf(n) : null;
    }

    static boolean isOff(int power) {
        return power == LOGIC_ON || power == ADM || power == DELAY_MODE || power == PRE_SYSTEM_OFF || power == OFF;
    }

    static String powerName(int p) {
        switch (p) {
            case ACC_ON: return "on";
            case LOGIC_ON: return "logic_on";
            case ADM: return "adm";
            case SYSTEM_READY: return "system_ready";
            case DELAY_MODE: return "delay_mode";
            case PRE_SYSTEM_OFF: return "preparing_off";
            case OFF: return "off";
            default: return null;
        }
    }

    /** 1 all four closed, 0 one is open, -1 not known */
    int doorsClosed() {
        boolean known = true;
        for (int d : doors) {
            if (d == 1) return 0;
            if (d != 0) known = false;
        }
        return known ? 1 : -1;
    }

    /** a new session (the head unit resumed from suspend): nothing carries over */
    void reset() {
        power = -1;
        park = -1;
        for (int i = 0; i < doors.length; i++) doors[i] = -1;
        off = parked = opened = false;
        closedSince = -1;
    }

    /**
     * After any new value: the event to fire now ("parked", "unparked"), or null. log gets
     * one line on what changed, for the car tests.
     */
    String update(long now, StringBuilder log) {
        if (power == ACC_ON) {
            boolean was = parked;
            if (off) log.append("car on");
            off = parked = opened = false;
            closedSince = -1;
            return was ? "unparked" : null;
        }
        if (!isOff(power)) return null;   // not known yet, or system ready (booting)
        if (!off) {
            off = true;
            opened = false;
            closedSince = -1;
            log.append("car off (").append(powerName(power)).append(")");
        }
        if (parked) return null;
        int closed = doorsClosed();
        if (closed == 0) {
            if (!opened) log.append(log.length() > 0 ? "; " : "").append("a door opened");
            opened = true;
            closedSince = -1;
            return null;
        }
        if (closed < 0) {
            if (!saidNoDoors) log.append(log.length() > 0 ? "; " : "").append("no door status from the car: no parked event");
            saidNoDoors = true;
            return null;
        }
        if (closedSince < 0) closedSince = now;
        if (park != 1) return null;
        boolean go;
        if (DOOR.equals(rule)) go = opened;
        else if (CLOSED.equals(rule)) go = true;
        else go = now - closedSince >= Long.parseLong(rule) * 1000L;
        if (!go) return null;
        parked = true;
        return "parked";
    }
}
