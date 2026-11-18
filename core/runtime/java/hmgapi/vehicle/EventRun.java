package hmgapi.vehicle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one car event for the mods that asked for it (state/events.txt, written by the
 * engine from each enabled mod's "car_events"):
 *
 *   <mod> <event> <service|->
 *
 * A mod's event script is one of its services ("on-<event>", start manual, restart no),
 * so hmgapid runs it as the user the mod gave, with its environment and its log
 * (logs/<mod>/on-<event>.log). The script is started through hmgapid's service control
 * (state/ctl) and watched in state/services.status. A manual service starts "stopped"
 * and ends "exited"; after each run it's put back to "stopped", so the next "exited" can
 * only be the next run's (the status line has no run count, and its format is the
 * client's).
 *
 * At the same time the mods' apps and hooks hear the event (an ordered broadcast; Unit
 * says when every receiver is done). Everything gets limitMs; what's still running then
 * is stopped (the script: TERM, then KILL from hmgapid 5 s later). The shutdown can't
 * wait: the car shuts down 10 s after it said so, whatever HMG-API answers.
 */
final class EventRun {
    interface Unit {
        /** the service's state in services.status (waiting, running, exited, failed, stopped), or null */
        String state(String mod, String service);

        /** a request for hmgapid's service control: start or stop */
        void ctl(String verb, String mod, String service);

        /** sends the event to apps and hooks; null when there's nothing to send it with */
        Waiter broadcast(String event);

        long now();

        void pause(long ms);

        void log(String line);
    }

    interface Waiter {
        boolean done();

        void cancel();
    }

    static final class Decl {
        final String mod, event, service;

        Decl(String mod, String event, String service) {
            this.mod = mod;
            this.event = event;
            this.service = service;
        }
    }

    static final long TICK_MS = 100, PARK_MS = 1500;

    /** events.txt: one line per mod and event */
    static List<Decl> parse(String text) {
        List<Decl> out = new ArrayList<Decl>();
        if (text == null) return out;
        for (String l : text.split("\n")) {
            String[] w = l.trim().split(" +");
            if (w.length != 3 || !w[1].matches("[a-z]+")) continue;
            out.add(new Decl(w[0], w[1], "-".equals(w[2]) ? null : w[2]));
        }
        return out;
    }

    static boolean asked(List<Decl> all, String event) {
        for (Decl d : all) if (d.event.equals(event)) return true;
        return false;
    }

    final Unit unit;

    EventRun(Unit unit) {
        this.unit = unit;
    }

    /** Puts every event script that has run back to "stopped" (the vehicle service just started: a run before it is over). */
    void settle(List<Decl> all) {
        for (Decl d : all) if (d.service != null && "exited".equals(unit.state(d.mod, d.service))) unit.ctl("stop", d.mod, d.service);
    }

    /** Runs the event; what happened, one line (null when no mod asked for it). */
    String run(String event, List<Decl> all, long limitMs) {
        long start = unit.now(), end = start + limitMs;
        List<Decl> scripts = new ArrayList<Decl>();
        boolean any = false;
        for (Decl d : all) {
            if (!d.event.equals(event)) continue;
            any = true;
            if (d.service != null) scripts.add(d);
        }
        if (!any) return null;
        // a script left "exited" by a run this service didn't see end: back to stopped first
        Map<Decl, String> state = new LinkedHashMap<Decl, String>();
        for (Decl d : scripts) {
            String s = unit.state(d.mod, d.service);
            if ("exited".equals(s) || "failed".equals(s)) unit.ctl("stop", d.mod, d.service);
        }
        for (Decl d : scripts) {
            String s = unit.state(d.mod, d.service);
            while (("exited".equals(s) || "failed".equals(s)) && unit.now() - start < PARK_MS) {
                unit.pause(TICK_MS);
                s = unit.state(d.mod, d.service);
            }
            if (s == null) {
                state.put(d, "not a service");   // the engine hasn't published it (yet)
                continue;
            }
            if ("running".equals(s)) state.put(d, "running");   // still on from before: waited for, not started twice
            else {
                unit.ctl("start", d.mod, d.service);
                state.put(d, "asked");
            }
        }
        Waiter w = unit.broadcast(event);
        boolean told = w == null;
        while (true) {
            boolean busy = false;
            for (Map.Entry<Decl, String> e : state.entrySet()) {
                String was = e.getValue();
                if (!"asked".equals(was) && !"running".equals(was)) continue;
                String s = unit.state(e.getKey().mod, e.getKey().service);
                if ("exited".equals(s) || "failed".equals(s)) {
                    e.setValue(s);
                    unit.ctl("stop", e.getKey().mod, e.getKey().service);   // parked for the next run
                } else if ("stopped".equals(s) && "running".equals(was)) {
                    e.setValue("stopped");   // someone stopped it (the mod turned off)
                } else {
                    if ("running".equals(s)) e.setValue("running");
                    busy = true;
                }
            }
            if (!told && w.done()) told = true;
            if ((!busy && told) || unit.now() >= end) break;
            unit.pause(TICK_MS);
        }
        StringBuilder b = new StringBuilder(event).append(":");
        for (Map.Entry<Decl, String> e : state.entrySet()) {
            String s = e.getValue();
            if ("asked".equals(s) || "running".equals(s)) {
                unit.ctl("stop", e.getKey().mod, e.getKey().service);
                s = "asked".equals(s) ? "didn't start in time" : "still running: stopped";
            }
            b.append(' ').append(e.getKey().mod).append('/').append(e.getKey().service).append(' ').append(s).append(',');
        }
        if (w == null) b.append(" apps and hooks not told (no broadcast)");
        else if (told) b.append(" apps and hooks told");
        else {
            w.cancel();
            b.append(" apps and hooks still busy: not waited for");
        }
        b.append(" (").append(unit.now() - start).append(" ms)");
        String line = b.toString();
        unit.log(line);
        return line;
    }
}
