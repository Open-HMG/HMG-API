package hmgapi.hook;

import java.util.List;

/**
 * The parts of ProjectionHooks that don't touch Android or the hooks (tested off the car,
 * AaMessagesTest): raw sends guarded by the Android Auto session, which sensor types
 * can be offered, and the Wireless mod's navigation message lines.
 */
final class AaMessages {
    /** the last sensor type the stock library reads; HMG-API's library recipe adds 22-26 (23: EV energy model) */
    static final int STOCK_LAST_SENSOR = 21, RECIPE_LAST_SENSOR = 26;
    /** the one message type sendRaw takes: a sensor batch (SENSOR_MESSAGE_BATCH) */
    static final int SENSOR_BATCH = 0x8003;
    /** Android Auto navigation messages: destinations and charging stops, next turn distance and time */
    static final int NAV_DESTINATIONS = 0x8006, NAV_NEXT_TURN = 0x8007;

    interface Sender {
        void send(Object source, byte[] message) throws Exception;
    }

    private final Object lock = new Object();
    /** the session's SensorSource (null between sessions) and whether the library recipe is on */
    private Object source;
    private boolean recipe;
    private boolean failLogged;
    /** how a message goes out (null: raw messages off on this car) */
    volatile Sender sender;

    /** an Android Auto session's sensor source is up (SensorSource.init returned true) */
    void start(Object sensorSource, boolean recipeApplied) {
        synchronized (lock) {
            source = sensorSource;
            recipe = recipeApplied;
            failLogged = false;
        }
    }

    /** the sensor source is ending: returns once no send is running, and none starts after */
    void stop() {
        synchronized (lock) {
            source = null;
        }
    }

    boolean ready() {
        synchronized (lock) {
            return source != null && recipe && sender != null;
        }
    }

    /**
     * null: handed to the library. Otherwise not sent: "" (no session, no recipe, or a
     * failure already logged this session) or the failure to log.
     */
    String send(byte[] message) {
        if (message == null || message.length < 2 || ((message[0] & 0xff) << 8 | (message[1] & 0xff)) != SENSOR_BATCH) {
            throw new IllegalArgumentException("sendRaw takes a sensor batch: 80 03, then the SensorBatch");
        }
        synchronized (lock) {
            if (source == null || !recipe || sender == null) return "";
            try {
                sender.send(source, message);
                return null;
            } catch (Throwable t) {
                if (failLogged) return "";
                failLogged = true;
                Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                return String.valueOf(c);
            }
        }
    }

    /**
     * The sensor types to add to the offered list: up to 21 always, 22-26 only with
     * the library recipe in place; the others go to dropped.
     */
    static int[] offerable(int[] add, boolean recipeApplied, List<Integer> dropped) {
        int[] tmp = new int[add.length];
        int n = 0;
        for (int t : add) {
            if (t <= STOCK_LAST_SENSOR || (recipeApplied && t <= RECIPE_LAST_SENSOR)) tmp[n++] = t;
            else dropped.add(t);
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** the offered sensor list without one type (-1: none) and with the added ones */
    static int[] sensors(int[] in, int drop, int[] add) {
        int[] tmp = new int[in.length + add.length];
        int n = 0;
        for (int s : in) if (s != drop) tmp[n++] = s;
        for (int a : add) {
            boolean dup = false;
            for (int i = 0; i < n; i++) dup |= tmp[i] == a;
            if (!dup) tmp[n++] = a;
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /**
     * One navigation message from the Wireless mod's line ("S <len> <hex>": 0x8006,
     * "P <len> <hex>": 0x8007), as {type, data}; null if it isn't one.
     */
    static Object[] navigation(String line) {
        if (line == null) return null;
        String[] w = line.trim().split(" ");
        if (w.length < 3) return null;
        int type = "S".equals(w[0]) ? NAV_DESTINATIONS : "P".equals(w[0]) ? NAV_NEXT_TURN : 0;
        if (type == 0 || w[2].length() % 2 != 0) return null;
        byte[] data = new byte[w[2].length() / 2];
        try {
            for (int i = 0; i < data.length; i++) data[i] = (byte) Integer.parseInt(w[2].substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return new Object[] { type, data };
    }
}
