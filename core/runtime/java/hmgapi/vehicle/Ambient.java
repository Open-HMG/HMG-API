package hmgapi.vehicle;

/**
 * The outside temperature from climate control's status, with its "no value yet".
 *
 * Climate control can report -40 (the same in F and C: the raw zero of the CAN signal)
 * before it has a real outside temperature, e.g. just after the car wakes; an Ioniq 5
 * showed -40 one morning through outside_temp. But -40 can be real too, so it isn't
 * dropped for good:
 *
 *   - any other value is a reading at once;
 *   - -40 when the last reading was already close to it (NEAR or colder) is a reading at
 *     once: the temperature got there;
 *   - otherwise (no reading yet, e.g. just after the car wakes, or a jump from a mild one)
 *     the last reading stays, or there is none, until -40 has held for HOLD_MS; then it's
 *     a reading. Climate control sends its status on change only, so that's decided when
 *     the value is read, not when a status comes in.
 *
 * Tenths of a degree; Integer.MIN_VALUE: no reading. Pure Java, so the rules are tested
 * off the car (core/runtime/commands-test.sh).
 */
final class Ambient {
    /** how long a -40 out of nowhere has to hold to count */
    static final long HOLD_MS = 10 * 60 * 1000L;
    /** a last reading at or below this (°F) makes -40 believable at once */
    static final float NEAR_F = -31f;

    private int f10 = Integer.MIN_VALUE, c10 = Integer.MIN_VALUE;
    private long pendingSince = -1;   // -40 not yet believed: since when; -1: none

    static boolean minus40(float t) {
        return Math.abs(t + 40f) <= 0.05f;
    }

    /**
     * A climate status: Fahrenheit, Celsius and the Celsius state (255: not valid, a US
     * car). Returns what to log: "ignored" for a -40 held back, "first" for the first
     * reading, null otherwise.
     */
    synchronized String status(float f, float c, int stateC, long now) {
        if (minus40(f)) {
            boolean near = f10 != Integer.MIN_VALUE && f10 <= Math.round(NEAR_F * 10);
            if (!near) {
                if (pendingSince < 0) pendingSince = now;
                return "ignored";
            }
        }
        pendingSince = -1;
        boolean first = f10 == Integer.MIN_VALUE;
        f10 = Math.round(f * 10);
        c10 = stateC == 255 ? Integer.MIN_VALUE : Math.round(c * 10);
        return first ? "first" : null;
    }

    /** Fahrenheit in tenths, or MIN_VALUE */
    synchronized int f10(long now) {
        settle(now);
        return f10;
    }

    /** Celsius in tenths when the car's is valid, or MIN_VALUE (convert f10 then) */
    synchronized int c10(long now) {
        settle(now);
        return c10;
    }

    /** a -40 held for HOLD_MS becomes the reading */
    private void settle(long now) {
        if (pendingSince >= 0 && now - pendingSince >= HOLD_MS) {
            f10 = -400;
            c10 = -400;
            pendingSince = -1;
        }
    }
}
