package hmgapi.vehicle;

/** The outside temperature's -40 rules (Ambient), off the car. */
public final class AmbientTest {
    static int fails;

    static void check(String what, int want, int got) {
        if (want == got) System.out.println("ok    " + what);
        else {
            System.out.println("FAIL  " + what + ": wanted " + want + ", got " + got);
            fails++;
        }
    }

    public static void main(String[] a) {
        final int NONE = Integer.MIN_VALUE;
        long t = 1000000, min = 60000;

        Ambient x = new Ambient();
        x.status(-40f, -40f, 255, t);
        check("-40 just after waking: no reading yet", NONE, x.f10(t));
        check("... still none after 9 min", NONE, x.f10(t + 9 * min));
        x.status(28.4f, -2f, 255, t + 2 * min);
        check("... then a real value: that's the reading", 284, x.f10(t + 2 * min));
        check("... US car: Celsius left to convert", NONE, x.c10(t + 2 * min));
        check("... and the old -40 never comes back", 284, x.f10(t + 30 * min));

        x = new Ambient();
        x.status(-40f, -40f, 255, t);
        check("-40 that holds 10 min: believed", -400, x.f10(t + 10 * min));
        check("... in Celsius too", -400, x.c10(t + 10 * min));

        x = new Ambient();
        x.status(51f, 10.5f, 0, t);
        x.status(-40f, -40f, 0, t + min);
        check("a jump from 51 to -40: 51 stays", 510, x.f10(t + 2 * min));
        check("... Celsius stays too", 105, x.c10(t + 2 * min));
        check("... unless -40 holds 10 min", -400, x.f10(t + 11 * min));

        x = new Ambient();
        x.status(-33f, -36.1f, 0, t);
        x.status(-40f, -40f, 0, t + min);
        check("-40 after -33: believed at once (a real cold snap)", -400, x.f10(t + min));
        x.status(-41f, -40.6f, 0, t + 2 * min);
        check("... and colder still is a reading", -410, x.f10(t + 2 * min));

        x = new Ambient();
        x.status(-39.9f, -39.9f, 0, t);
        check("-39.9 isn't the no-value: a reading", -399, x.f10(t));

        x = new Ambient();
        check("nothing heard yet: no reading", NONE, x.f10(t));

        if (fails > 0) System.exit(1);
    }
}
