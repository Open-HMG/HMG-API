package hmgapi.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** When the car counts as parked (Parking), and how an event runs (EventRun), against fakes. */
public final class ParkingTest {
    static int fails;

    static void check(String what, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": wanted [" + want + "], got [" + got + "]"));
        if (!ok) fails++;
    }

    static String step(Parking p, long now) {
        return p.update(now, new StringBuilder());
    }

    static void doors(Parking p, int d, int pa, int rl, int rr) {
        p.doors[0] = d;
        p.doors[1] = pa;
        p.doors[2] = rl;
        p.doors[3] = rr;
    }

    static void parking() {
        check("rule: door", "door", Parking.rule("door"));
        check("rule: closed", "closed", Parking.rule(" closed\n"));
        check("rule: seconds", "60", Parking.rule("60"));
        check("rule: too short", null, Parking.rule("5"));
        check("rule: too long", null, Parking.rule("3600"));
        check("rule: anything else", null, Parking.rule("soon"));

        Parking p = new Parking();
        check("nothing known: nothing", null, step(p, 0));
        p.power = Parking.ACC_ON;
        p.park = 0;
        doors(p, 0, 0, 0, 0);
        check("driving: nothing", null, step(p, 1000));
        p.park = 1;
        p.power = Parking.LOGIC_ON;
        check("off, in P, doors closed, nobody got out yet (door rule): nothing", null, step(p, 2000));
        doors(p, 1, 0, 0, 0);
        check("the driver's door opens: nothing yet", null, step(p, 3000));
        doors(p, 0, 0, 0, 0);
        check("... and closes: parked", "parked", step(p, 4000));
        check("once per stop", null, step(p, 5000));
        doors(p, 1, 0, 0, 0);
        step(p, 6000);
        doors(p, 0, 0, 0, 0);
        check("in and out again: still once", null, step(p, 7000));
        p.power = Parking.ACC_ON;
        check("switched on again: unparked", "unparked", step(p, 8000));
        check("... once", null, step(p, 9000));

        // the driver opens the door first, then switches off, gets out and closes it (a US EV6, 2026-11-18)
        Parking first = new Parking();
        first.power = Parking.ACC_ON;
        first.park = 1;
        doors(first, 1, 0, 0, 0);
        check("door open while still on: nothing", null, step(first, 0));
        first.power = Parking.DELAY_MODE;
        check("switched off with the door open: not yet", null, step(first, 1000));
        doors(first, 0, 0, 0, 0);
        check("... and it closes: parked", "parked", step(first, 2000));

        p.power = Parking.OFF;
        check("off (255) counts as off", null, step(p, 10000));
        doors(p, 0, 1, 0, 0);
        step(p, 11000);
        p.park = 0;
        doors(p, 0, 0, 0, 0);
        check("not in P: nothing", null, step(p, 12000));
        p.park = 1;
        check("in P: parked", "parked", step(p, 13000));

        Parking q = new Parking();
        q.rule = "closed";
        q.power = Parking.ACC_ON;
        q.park = 1;
        doors(q, 0, 0, 0, 0);
        step(q, 0);
        q.power = Parking.LOGIC_ON;
        check("closed rule: off, in P, closed: parked at once", "parked", step(q, 1000));
        q.power = Parking.ACC_ON;
        check("closed rule: on again: unparked", "unparked", step(q, 2000));
        q.power = Parking.ACC_ON;
        check("on, no parked before: no unparked", null, step(q, 3000));

        Parking w = new Parking();
        w.rule = "60";
        w.power = Parking.LOGIC_ON;
        w.park = 1;
        doors(w, 0, 0, 0, 0);
        check("60 s rule: closed just now: nothing", null, step(w, 0));
        check("... 59 s: nothing", null, step(w, 59000));
        doors(w, 0, 0, 1, 0);
        step(w, 59500);
        doors(w, 0, 0, 0, 0);
        check("a door opened: the minute starts again", null, step(w, 61000));
        check("... a minute after it closed: parked", "parked", step(w, 121000));

        Parking u = new Parking();
        u.power = Parking.LOGIC_ON;
        u.park = 1;
        StringBuilder log = new StringBuilder();
        check("no door status: no parked", null, u.update(0, log));
        check("... said once", true, log.toString().contains("no door status"));
        u.doors[0] = 1;
        check("one door known open, others unknown: not closed", 0, u.doorsClosed());

        Parking s = new Parking();
        s.power = Parking.SYSTEM_READY;
        s.park = 1;
        doors(s, 0, 0, 0, 0);
        check("system ready (booting) isn't off", null, step(s, 0));
        s.power = Parking.LOGIC_ON;
        doors(s, 1, 0, 0, 0);
        step(s, 1000);
        doors(s, 0, 0, 0, 0);
        check("then off and out: parked", "parked", step(s, 2000));
        s.reset();
        s.power = Parking.ACC_ON;
        check("after a reset (resumed from suspend): no unparked", null, step(s, 3000));
    }

    /** a fake hmgapid: services that run for a while, or exit at once, or never start */
    static final class FakeUnit implements EventRun.Unit {
        long now;
        final Map<String, String> state = new HashMap<String, String>();
        final Map<String, Long> runsFor = new HashMap<String, Long>(), endAt = new HashMap<String, Long>();
        final List<String> calls = new ArrayList<String>();
        long broadcastFor = 200, sentAt = -1;
        boolean cancelled, noBroadcast, deaf;
        String last;

        public String state(String mod, String service) {
            String k = mod + "/" + service;
            Long e = endAt.get(k);
            if (e != null && now >= e) {
                state.put(k, "exited");
                endAt.remove(k);
            }
            return state.get(k);
        }

        public void ctl(String verb, String mod, String service) {
            String k = mod + "/" + service;
            calls.add(verb + " " + k);
            if (deaf) return;
            if ("stop".equals(verb)) {
                state.put(k, "stopped");
                endAt.remove(k);
            } else if ("start".equals(verb)) {
                state.put(k, "running");
                endAt.put(k, now + (runsFor.containsKey(k) ? runsFor.get(k) : 0));
            }
        }

        public EventRun.Waiter broadcast(String event) {
            if (noBroadcast) return null;
            calls.add("broadcast " + event);
            sentAt = now;
            return new EventRun.Waiter() {
                public boolean done() { return now - sentAt >= broadcastFor; }

                public void cancel() { cancelled = true; }
            };
        }

        public long now() { return now; }

        public void pause(long ms) { now += ms; }

        public void log(String line) { last = line; }
    }

    static void events() {
        List<EventRun.Decl> all = EventRun.parse("cam parked on-parked\ncam shutdown on-shutdown\nwifi parked -\nwifi shutdown -\nbad line\nodd Parked -\n");
        check("events.txt: lines that fit", 4, all.size());
        check("events.txt: no script is null", null, all.get(2).service);
        check("asked: parked", true, EventRun.asked(all, "parked"));
        check("asked: wake (nobody)", false, EventRun.asked(all, "wake"));

        FakeUnit u = new FakeUnit();
        u.state.put("cam/on-parked", "stopped");
        u.state.put("cam/on-shutdown", "stopped");
        u.runsFor.put("cam/on-parked", 300L);
        String r = new EventRun(u).run("parked", all, 60000);
        check("parked: the script ran and the apps were told", "parked: cam/on-parked exited, apps and hooks told (300 ms)", r);
        check("... started, then put back to stopped", "[start cam/on-parked, broadcast parked, stop cam/on-parked]", u.calls.toString());
        check("... stopped, ready for the next run", "stopped", u.state.get("cam/on-parked"));
        check("nobody asked: nothing", null, new EventRun(u).run("wake", all, 60000));

        FakeUnit q = new FakeUnit();
        q.state.put("cam/on-parked", "exited");   // a run the vehicle service didn't see end
        q.runsFor.put("cam/on-parked", 0L);
        r = new EventRun(q).run("parked", all, 60000);
        check("left exited: stopped first, then started", "[stop cam/on-parked, start cam/on-parked, broadcast parked, stop cam/on-parked]", q.calls.toString());
        check("... a script that exits at once still counts", true, r.contains("cam/on-parked exited"));

        FakeUnit s = new FakeUnit();
        s.state.put("cam/on-shutdown", "stopped");
        s.runsFor.put("cam/on-shutdown", 60000L);
        s.broadcastFor = 60000;
        r = new EventRun(s).run("shutdown", all, 7000);
        check("shutdown: a script still running at the limit is stopped", true, r.contains("cam/on-shutdown still running: stopped"));
        check("... the apps not waited for past it", true, r.contains("still busy: not waited for") && s.cancelled);
        check("... within the limit", true, s.now <= 7100);

        FakeUnit d = new FakeUnit();
        d.deaf = true;   // hmgapid never picks the request up
        d.state.put("cam/on-parked", "stopped");
        r = new EventRun(d).run("parked", all, 2000);
        check("a script that never starts: said so", true, r.contains("didn't start in time"));

        FakeUnit n = new FakeUnit();
        n.noBroadcast = true;
        r = new EventRun(n).run("parked", all, 2000);
        check("not a service yet (the engine hasn't published it)", true, r.contains("cam/on-parked not a service"));
        check("... no broadcast: said so", true, r.contains("not told"));

        FakeUnit h = new FakeUnit();
        h.state.put("cam/on-shutdown", "stopped");
        r = new EventRun(h).run("shutdown", EventRun.parse("wifi shutdown -\n"), 7000);
        check("hooks only: just the broadcast", "[broadcast shutdown]", h.calls.toString());

        FakeUnit z = new FakeUnit();
        z.state.put("cam/on-parked", "exited");
        new EventRun(z).settle(all);
        check("settle: an exited event script goes back to stopped", "[stop cam/on-parked]", z.calls.toString());
    }

    public static void main(String[] a) {
        parking();
        events();
        if (fails > 0) {
            System.out.println(fails + " failed");
            System.exit(1);
        }
    }
}
