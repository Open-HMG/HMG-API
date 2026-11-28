package hmgapi.vehicle;

/** The cluster's shared battery-info reply and request cooldown. */
final class BatteryInfo {
    private static final long INTERVAL_MS = 2000;
    private int[] packet;
    private long receivedAt;
    private long requestedAt = -INTERVAL_MS;

    synchronized void received(int[] values, long now) {
        if (packet == null) {
            System.out.println("vehicle: battery info, first values: " + java.util.Arrays.toString(values));
        }
        packet = values;
        receivedAt = now;
    }

    synchronized void clear() {
        packet = null;
    }

    synchronized int[] fresh(long now) {
        return now - receivedAt < INTERVAL_MS ? packet : null;
    }

    /** Claim the request before calling the cluster, so a failed call also observes the cooldown. */
    synchronized boolean beginRequest(long now) {
        if (fresh(now) != null || now - requestedAt < INTERVAL_MS) return false;
        requestedAt = now;
        return true;
    }

    static int packTempMin(int[] values) {
        // Pack temperature remains usable while the energy and range fields are waking up.
        if (!hasData(values) || values[1] < -40 || values[1] > 60) return Integer.MIN_VALUE;
        return values[1];
    }

    static int rangeKm(int[] values) {
        if (!hasData(values) || values[2] == 0xffff || values[6] < 0 || values[6] >= 0xffff) return -1;
        return values[6];
    }

    private static boolean hasData(int[] values) {
        if (values == null || values.length != 8) return false;
        // The cluster can send an all-zero packet before the BMS has reported after startup.
        for (int value : values) if (value != 0) return true;
        return false;
    }
}
