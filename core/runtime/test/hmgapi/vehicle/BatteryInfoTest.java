package hmgapi.vehicle;

/** Simulates cluster replies and overlapping reads on an elapsed-realtime clock. */
public final class BatteryInfoTest {
    private static void check(String message, boolean ok) {
        if (!ok) throw new AssertionError(message);
        System.out.println("ok    " + message);
    }

    public static void main(String[] args) {
        BatteryInfo info = new BatteryInfo();
        check("first battery-info request is sent", info.beginRequest(0));
        check("a second reader shares the in-flight request", !info.beginRequest(0));
        check("a timeout does not allow a request within 2 s", !info.beginRequest(1999));
        check("request can be retried after 2 s", info.beginRequest(2000));

        int[] warm = { 80, 32, 0xffff, 0, 0, 0, 0, 0 };
        info.received(warm, 2400);
        check("range and temperature can read the same reply", info.fresh(2400) == warm);
        check("unknown usable energy preserves a valid pack temperature", BatteryInfo.packTempMin(warm) == 32);
        check("unknown usable energy suppresses wake-up range", BatteryInfo.rangeKm(warm) == -1);
        check("cached reply is reused for 2 s", !info.beginRequest(4399));
        check("expired reply triggers a new request", info.fresh(4400) == null && info.beginRequest(4400));

        int[] invalidTemperature = { 80, 127, 20000, 0, 0, 0, 240, 0 };
        check("out-of-bounds temperature is unavailable", BatteryInfo.packTempMin(invalidTemperature) == Integer.MIN_VALUE);
        check("out-of-bounds temperature preserves valid range", BatteryInfo.rangeKm(invalidTemperature) == 240);
        int[] startup = new int[8];
        check("all-zero startup packet has no temperature or range",
                BatteryInfo.packTempMin(startup) == Integer.MIN_VALUE && BatteryInfo.rangeKm(startup) == -1);
        int[] depleted = { 0, 12, 0, 0, 0, 0, 0, 0 };
        check("valid zero range and pack temperature survive an empty battery",
                BatteryInfo.rangeKm(depleted) == 0 && BatteryInfo.packTempMin(depleted) == 12);

        info.received(depleted, 4500);
        info.clear();
        check("wake discards the previous reply", info.fresh(4600) == null);
    }
}
