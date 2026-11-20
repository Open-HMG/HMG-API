package hmgapi.hook;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Android Auto settings and events for mods (public/state.json "projection", settled
 * by hmgapi.engine.Projection), in GPM's process. Nothing in GPM is replaced:
 *
 * - density: VideoSink.nearestVideoConfiguration gets the asked DPI as density and
 *   real density (what aa-proxy-rs and AA DPI do), for every video setting
 * - sensors, fuel and connector types: SensorSource.init gets the changed lists
 *   (speed left out: the phone doesn't know the car reports speed and doesn't limit
 *   taps; types added; fuel/EV connector types instead of "unknown"). A type above 21
 *   is offered only where the mod that asks for it has its own recipe for Android Auto's
 *   library in place (hooks.native): the stock library can't read the phone's requests
 *   for those. HMG-API ships no recipe: native patches stay in the mods
 * - raw sensor batches from a mod's hook code (sendRaw, rawReady; mod.json "aa_raw"):
 *   through SensorSource.nativeReportDiagnosticsData, which the mod's own recipe turns
 *   into a send of the whole message
 * - events: the mods that ask hear hmgapi.intent.action.PROJECTION with extras
 *   "event" (aa_connected, aa_disconnected, aa_navigation_focus, aa_guidance,
 *   aa_navigation_message, cp_connected (value 1: wireless), cp_disconnected) and
 *   "value" (int) or "type" (int) + "data" (byte[], an
 *   Android Auto navigation message: 0x8006 destinations and charging stops, 0x8007
 *   next turn distance and time); hook code gets the navigation messages through
 *   addNavigationListener
 *
 * Settings are read when GPM's process starts; a change applies once GPM restarts
 * (applying changes in HMG-API restarts the apps they touch).
 */
public final class ProjectionHooks {
    private ProjectionHooks() {}

    public static final String ACTION = "hmgapi.intent.action.PROJECTION";
    static Context context;
    static final List<String> listeners = new ArrayList<String>();

    /** this process's settings and the apps that hear events */
    static JSONObject settings(LoadedApp app) throws Exception {
        context = app.context;
        JSONObject p;
        try {
            p = new JSONObject(Mods.read(Live.STATE)).optJSONObject("projection");
        } catch (Exception e) {
            p = null;
        }
        if (p == null) p = new JSONObject();
        listeners.clear();
        JSONArray ev = p.optJSONArray("events");
        for (int i = 0; ev != null && i < ev.length(); i++) listeners.add(ev.getString(i));
        return p;
    }

    /** CarPlay (DiPO's process): sessions */
    static void dipo(LoadedApp app) throws Exception {
        settings(app);
        if (listeners.isEmpty()) return;
        Points.hook(app.classLoader, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                tell("cp_connected", (Integer) m.args[0]);   // transport: 1 wireless
            }
        });
        Points.hook(app.classLoader, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                tell("cp_disconnected", 0);
            }
        });
    }

    static volatile boolean aaConnected;

    /** raw sensor batches: the session's sensor source and how a message goes out */
    static final AaMessages raw = new AaMessages();
    /** the mods that declared raw sends ("aa_raw"), and those told why theirs can't go */
    static final java.util.Set<String> rawMods = java.util.Collections.synchronizedSet(new java.util.HashSet<String>()),
            rawWarned = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** hook code that hears navigation messages, with its mod */
    static final List<Object[]> navListeners = new CopyOnWriteArrayList<Object[]>();

    /** the points HMG-API's own Android Auto settings and events hook (all mapped, or they're off) */
    static final String[] GPM_POINTS = { "gpm.video_sink.nearest", "gpm.sensor_source.speed", "gpm.sensor_source.init",
            "gpm.receiver.destroy", "gpm.receiver_service.disconnected", "gpm.receiver.send_navigation_focus",
            "gpm.navigation_status.status" };

    /** Hears Android Auto navigation messages (hook code; apps get them as the aa_navigation_message event). */
    public interface NavigationListener {
        /**
         * type: 0x8006 (destinations and charging stops) or 0x8007 (next turn distance
         * and time); data: the message (your own copy). Called on Android Auto's
         * thread: return quickly.
         */
        void onNavigationMessage(int type, byte[] data);
    }

    /**
     * Android Auto's navigation messages for hook code in GPM's process, while mod is
     * on. They come from the Wireless CarPlay/AA mod's turn-by-turn library patch:
     * require that mod to get any.
     */
    public static void addNavigationListener(ModContext mod, NavigationListener l) {
        if (mod == null || l == null) throw new IllegalArgumentException("addNavigationListener(mod, listener)");
        navListeners.add(new Object[] { mod, l });
    }

    public static void removeNavigationListener(NavigationListener l) {
        for (Object[] e : navListeners) if (e[1] == l) navListeners.remove(e);
    }

    /**
     * Can mod's sendRaw send now? True while the mod is on and declared raw sends
     * (mod.json "projection": {"aa_raw": true}), one of its own hooks.native recipes for
     * libautoreceiver_infobank.so is in place in this process (it is what makes the
     * library send the bytes as a whole message), an Android Auto session is up, and
     * this car's map has gpm.sensor_source.report_diagnostics.
     */
    public static boolean rawReady(ModContext mod) {
        return why(mod) == null && raw.ready();
    }

    /**
     * Sends one sensor batch to the phone for mod: the whole Android Auto message, its
     * type first (80 03, then the SensorBatch). True when it was handed to Android
     * Auto's library (which drops it if the sensor channel isn't open yet); false when
     * not ready (rawReady) and nothing was sent. Any other message type throws
     * IllegalArgumentException. Safe from any thread: a send never overlaps the
     * session's end.
     */
    public static boolean sendRaw(ModContext mod, byte[] message) {
        AaMessages.checkBatch(message);
        String no = why(mod);
        if (no != null) {
            if (mod != null && rawWarned.add(mod.id)) mod.error("sendRaw: nothing sent: " + no, null);
            return false;
        }
        String failed = raw.send(message);
        if (failed != null && !failed.isEmpty()) mod.error("sendRaw failed (logged once a session): " + failed, null);
        return failed == null;
    }

    /** why mod can't send raw messages at all (null: it can, while a session is up) */
    static String why(ModContext mod) {
        if (mod == null) return "no mod";
        if (!mod.enabled()) return "the mod is off";
        if (!rawMods.contains(mod.id)) return "the mod doesn't declare \"projection\": {\"aa_raw\": true}";
        if (!NativePatch.patched(mod.id, AaMessages.LIB)) return "none of its hooks.native recipes for " + AaMessages.LIB + " is in place";
        if (raw.sender == null) return "gpm.sensor_source.report_diagnostics isn't mapped on this car";
        return null;
    }

    static void gpm(LoadedApp app) throws Exception {
        final ClassLoader cl = app.classLoader;
        JSONObject p = settings(app);
        if (!Points.needAll("Android Auto projection", GPM_POINTS)) return;
        final int density = p.optInt("aa_density", 0);
        final boolean noSpeed = has(p.optJSONArray("aa_sensors_remove"), "speed");
        final int[] add = ints(p.optJSONArray("aa_sensors_add"));
        final int[] fuel = ints(p.optJSONArray("aa_fuel_types")), conn = ints(p.optJSONArray("aa_ev_connectors"));
        // types above 21, by the mod that asks: offered only with that mod's own library recipe in place
        final JSONObject addPatched = p.optJSONObject("aa_sensors_add_patched");
        // raw sends: through the native call the mods' own recipes repoint
        JSONArray rawBy = p.optJSONArray("aa_raw");
        rawMods.clear();
        for (int i = 0; rawBy != null && i < rawBy.length(); i++) rawMods.add(rawBy.getString(i));
        if (!rawMods.isEmpty()) {
            if (Points.needAll("Android Auto raw messages", "gpm.sensor_source.report_diagnostics")) {
                final Method report = Points.method(cl, "gpm.sensor_source.report_diagnostics");
                raw.sender = new AaMessages.Sender() {
                    @Override
                    public void send(Object source, byte[] message) throws Exception {
                        report.invoke(source, (Object) message);
                    }
                };
            }
        }
        if (density > 0) {
            Points.hook(cl, "gpm.video_sink.nearest", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param m) {
                    m.args[2] = density;
                    m.args[3] = density;
                }
            });
            HmgApi.i("projection: Android Auto density " + density + " (" + p.optString("aa_density_by") + ")");
        }
        final int speed = (Integer) Points.get(cl, "gpm.sensor_source.speed", null);
        Points.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            boolean dropLogged;

            @Override
            protected void beforeHookedMethod(Param m) {
                int[] sensors = (int[]) m.args[2];
                int[] more = add;
                StringBuilder dropNote = new StringBuilder();
                for (java.util.Iterator<String> it = addPatched == null ? null : addPatched.keys(); it != null && it.hasNext();) {
                    String mod = it.next();
                    List<Integer> dropped = new ArrayList<Integer>();
                    int[] t = AaMessages.offerable(ints(addPatched.optJSONArray(mod)), NativePatch.patched(mod, AaMessages.LIB), dropped);
                    more = AaMessages.sensors(more, -1, t);
                    if (!dropped.isEmpty()) dropNote.append(" ").append(mod).append(" ").append(dropped);
                }
                if (dropNote.length() > 0 && !dropLogged) {
                    dropLogged = true;
                    HmgApi.i("projection: sensor types not offered (the asking mod's recipe for " + AaMessages.LIB
                            + " isn't in place, and the stock library can't read them):" + dropNote);
                }
                if (sensors != null && (noSpeed || more.length > 0)) m.args[2] = AaMessages.sensors(sensors, noSpeed ? speed : -1, more);
                if (fuel.length > 0) m.args[4] = fuel;
                if (conn.length > 0) m.args[5] = conn;
            }

            @Override
            protected void afterHookedMethod(Param m) {
                aaConnected = true;
                tell("aa_connected", 0);
            }
        });
        // the session's sensor source, for sendRaw. Above every mod's hook: after hooks run
        // last-first, so this one's runs before theirs and a mod's own init hook finds rawReady(mod)
        MethodHook started = new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                if (Boolean.TRUE.equals(m.getResult())) raw.start(m.thisObject);
            }
        };
        started.priority = 2;
        Points.hook(cl, "gpm.sensor_source.init", started);
        // the sensor source ends before its native object is freed: no raw send after this.
        // Cars whose map lacks the point stop at the session's end below, as before (so do
        // cars that mapped it themselves: HMG-API's own hooks use only its map's points)
        MethodHook rawEnd = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                raw.stop();
            }
        };
        if (Points.has("gpm.sensor_source.shutdown") && !Points.localOnly("gpm.sensor_source.shutdown"))
            Points.hook(cl, "gpm.sensor_source.shutdown", rawEnd);
        // a clean end calls destroy; a phone that's just gone (Wi-Fi off, ping timeout) only
        // onDisconnected. One aa_disconnected per session either way
        MethodHook ended = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                raw.stop();
                if (!aaConnected) return;
                aaConnected = false;
                tell("aa_disconnected", 0);
            }
        };
        Points.hook(cl, "gpm.receiver.destroy", ended);
        Points.hook(cl, "gpm.receiver_service.disconnected", ended);
        Points.hook(cl, "gpm.receiver.send_navigation_focus", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                tell("aa_navigation_focus", (Integer) m.args[0]);   // NAVIGATION_FOCUS_STATE_NATIVE: the car's navigation
            }
        });
        Points.hook(cl, "gpm.navigation_status.status", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                tell("aa_guidance", (Integer) m.args[0]);   // 1 active, 2 inactive, 3 rerouting
            }
        });
    }

    /**
     * For a mod's code in GPM that sees Android Auto's navigation messages (the
     * wireless mod's library patch hands them over as "S|P <len> <hex>"): passes
     * them on to the mods that asked for projection events, and to the hook code
     * that listens (addNavigationListener).
     */
    public static void navigationDump(String line) {
        if (line == null || (listeners.isEmpty() && navListeners.isEmpty())) return;
        Object[] msg = AaMessages.navigation(line);
        if (msg == null) return;
        int type = (Integer) msg[0];
        byte[] data = (byte[]) msg[1];
        for (Object[] e : navListeners) {
            ModContext mod = (ModContext) e[0];
            if (!mod.enabled()) continue;
            try {
                ((NavigationListener) e[1]).onNavigationMessage(type, data.clone());
            } catch (Throwable t) {
                mod.error("navigation message listener", t);
            }
        }
        if (listeners.isEmpty()) return;
        Intent i = new Intent(ACTION).putExtra("event", "aa_navigation_message").putExtra("type", type).putExtra("data", data);
        send(i);
    }

    static void tell(String event, int value) {
        if (listeners.isEmpty()) return;
        send(new Intent(ACTION).putExtra("event", event).putExtra("value", value));
    }

    static void send(Intent i) {
        if (context == null) return;
        for (String pkg : listeners) {
            try {
                // a mod's app that was never opened is "stopped": without the flag Android drops it
                context.sendBroadcast(new Intent(i).setPackage(pkg).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            } catch (Throwable t) {
                HmgApi.e("projection: event to " + pkg, t);
            }
        }
    }

    static boolean has(JSONArray a, String v) {
        for (int i = 0; a != null && i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static int[] ints(JSONArray a) {
        int n = a == null ? 0 : a.length();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = a.optInt(i);
        return out;
    }
}
