package hmgapi.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Car events for a mod's hook code, in the app process it runs in (ModContext.onCarEvent):
 * the same hmgapi.intent.action.CAR_EVENT broadcast a mod's own apps get, sent by HMG-API's
 * vehicle service for the events enabled mods ask for in mod.json ("car_events").
 *
 *   parked    the car is parked and the driver has left (the owner's rule, Settings > Vehicle)
 *   unparked  switched on again after "parked", before the head unit shut down
 *   shutdown  the head unit is about to shut down: a few seconds, then it goes on anyway
 *   wake      back from a QuickBoot suspend (the app kept running through the off)
 *
 * One receiver per process, registered so that only a sender holding DEVICE_POWER
 * (HMG-API, as root) reaches it. Listeners run on a thread of their own, one after
 * another; the broadcast waits for them, LIMIT_MS at most per process.
 */
public final class CarEvents {
    private CarEvents() {}

    public static final String ACTION = "hmgapi.intent.action.CAR_EVENT";
    public static final String PARKED = "parked", UNPARKED = "unparked", SHUTDOWN = "shutdown", WAKE = "wake";
    static final long LIMIT_MS = 5000;

    /** what a mod's hook code gets: the event's name; runs off the app's main thread */
    public interface Listener {
        void onCarEvent(String event);
    }

    static final class Entry {
        final ModContext mod;
        final Listener listener;

        Entry(ModContext mod, Listener listener) {
            this.mod = mod;
            this.listener = listener;
        }
    }

    static final List<Entry> listeners = new CopyOnWriteArrayList<Entry>();
    static volatile boolean registered;

    /** the events the mod asked for in its mod.json (car_events), or none */
    @SuppressWarnings("unchecked")
    static boolean asked(ModContext mod, String event) {
        Map<String, Object> raw = Mods.rawOf.get(mod.id);
        Object ev = raw == null ? null : raw.get("car_events");
        return ev instanceof Map && ((Map<String, Object>) ev).containsKey(event);
    }

    static void add(ModContext mod, LoadedApp app, Listener l) {
        Map<String, Object> raw = Mods.rawOf.get(mod.id);
        if (raw == null || !(raw.get("car_events") instanceof Map)) {
            throw new IllegalStateException(mod.id + ": onCarEvent needs \"car_events\" in mod.json");
        }
        listeners.add(new Entry(mod, l));
        Context c = app.context;
        if (c == null) {
            mod.error("onCarEvent in " + app.processName + ": the app has no context yet (call it in appLoaded)", null);
            return;
        }
        synchronized (CarEvents.class) {
            if (registered) return;
            registered = true;
        }
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                final String event = i.getStringExtra("event");
                if (event == null || !event.matches("[a-z]+")) return;
                final BroadcastReceiver.PendingResult done = isOrderedBroadcast() ? goAsync() : null;
                Thread t = new Thread("hmgapi-car-event") {
                    @Override
                    public void run() {
                        try {
                            deliver(event);
                        } finally {
                            if (done != null) done.finish();
                        }
                    }
                };
                t.setDaemon(true);
                t.start();
            }
        }, new IntentFilter(ACTION), "android.permission.DEVICE_POWER", null);
        HmgApi.i("car events: listening in " + app.processName);
    }

    /** every listener whose mod is on and asked for the event; LIMIT_MS for all of them */
    static void deliver(final String event) {
        final long end = System.currentTimeMillis() + LIMIT_MS;
        for (final Entry e : listeners) {
            if (!e.mod.enabled || !asked(e.mod, event)) continue;
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                e.mod.error("car event " + event + ": no time left for this listener", null);
                continue;
            }
            Thread t = new Thread("hmgapi-car-event-" + e.mod.id) {
                @Override
                public void run() {
                    try {
                        e.listener.onCarEvent(event);
                    } catch (Throwable x) {
                        e.mod.error("car event " + event, x);
                    }
                }
            };
            t.setDaemon(true);
            t.start();
            try {
                t.join(left);
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
                return;
            }
            if (t.isAlive()) e.mod.error("car event " + event + ": the listener took longer than " + LIMIT_MS + " ms (left running)", null);
        }
    }
}
