package hmgapi.hook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** AaMessages off the car: raw sends against the session, the sensor list, navigation lines. */
public final class AaMessagesTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static final byte[] BATCH = { (byte) 0x80, 0x03, 0x0a, 0x00 };

    public static void main(String[] a) throws Exception {
        sensors();
        navigation();
        sends();
        endWaitsForSend();
        System.exit(fail);
    }

    static void sensors() {
        List<Integer> dropped = new ArrayList<Integer>();
        int[] add = { 6, 23, 22, 30 };
        check("no recipe: only types the stock library reads (up to 21)", Arrays.equals(AaMessages.offerable(add, false, dropped), new int[] { 6 })
                && dropped.equals(Arrays.asList(23, 22, 30)));
        dropped.clear();
        check("recipe in place: 22 and 23 too, nothing above", Arrays.equals(AaMessages.offerable(add, true, dropped), new int[] { 6, 23, 22 })
                && dropped.equals(Arrays.asList(30)));
        check("the offered list: speed left out, types added once", Arrays.equals(AaMessages.sensors(new int[] { 1, 3, 6, 10 }, 3, new int[] { 6, 23 }), new int[] { 1, 6, 10, 23 }));
    }

    static void navigation() {
        Object[] s = AaMessages.navigation("S 3 0a0b0c");
        check("S line: 0x8006 and its bytes", s != null && (Integer) s[0] == 0x8006 && Arrays.equals((byte[]) s[1], new byte[] { 10, 11, 12 }));
        Object[] p = AaMessages.navigation("P 1 ff\n");
        check("P line: 0x8007", p != null && (Integer) p[0] == 0x8007 && ((byte[]) p[1])[0] == (byte) 0xff);
        check("other lines: none", AaMessages.navigation("X 1 00") == null && AaMessages.navigation("S 1") == null
                && AaMessages.navigation("S 1 0") == null && AaMessages.navigation("S 1 zz") == null && AaMessages.navigation(null) == null);
    }

    static void sends() {
        AaMessages m = new AaMessages();
        final List<Object> sent = new ArrayList<Object>();
        m.sender = new AaMessages.Sender() {
            @Override
            public void send(Object source, byte[] message) {
                sent.add(source);
            }
        };
        check("no session: not ready, nothing sent", !m.ready() && "".equals(m.send(BATCH)) && sent.isEmpty());
        Object src = new Object();
        m.start(src, false);
        check("session without the recipe: not ready, nothing sent", !m.ready() && "".equals(m.send(BATCH)) && sent.isEmpty());
        m.start(src, true);
        check("session with the recipe: ready, sent to the session's sensor source", m.ready() && m.send(BATCH) == null && sent.size() == 1 && sent.get(0) == src);
        boolean threw = false;
        try {
            m.send(new byte[] { (byte) 0x80, 0x06, 0 });
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("anything but a sensor batch: refused", threw && sent.size() == 1);
        threw = false;
        try {
            m.send(new byte[] { (byte) 0x80 });
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("shorter than its type: refused", threw);
        m.stop();
        check("session ended: not ready, nothing sent", !m.ready() && "".equals(m.send(BATCH)) && sent.size() == 1);

        AaMessages none = new AaMessages();
        none.start(src, true);
        check("no sender (point not mapped): not ready", !none.ready() && "".equals(none.send(BATCH)));

        AaMessages bad = new AaMessages();
        bad.sender = new AaMessages.Sender() {
            @Override
            public void send(Object source, byte[] message) throws Exception {
                throw new java.lang.reflect.InvocationTargetException(new IllegalStateException("gone"));
            }
        };
        bad.start(src, true);
        String first = bad.send(BATCH), second = bad.send(BATCH);
        check("a failing send: the cause once, then quiet", first != null && first.contains("gone") && "".equals(second));
        bad.start(src, true);
        check("... and again once the next session starts", bad.send(BATCH) != null && bad.send(BATCH).isEmpty());
    }

    /** the session's end waits for a send in progress, so nothing reaches a freed sensor source */
    static void endWaitsForSend() throws Exception {
        final AaMessages m = new AaMessages();
        final CountDownLatch inSend = new CountDownLatch(1), letGo = new CountDownLatch(1);
        final boolean[] sendDone = new boolean[1];
        m.sender = new AaMessages.Sender() {
            @Override
            public void send(Object source, byte[] message) throws Exception {
                inSend.countDown();
                letGo.await(5, TimeUnit.SECONDS);
                sendDone[0] = true;
            }
        };
        m.start(new Object(), true);
        Thread sender = new Thread() {
            @Override
            public void run() {
                m.send(BATCH);
            }
        };
        sender.start();
        inSend.await(5, TimeUnit.SECONDS);
        final boolean[] stopped = new boolean[1];
        Thread end = new Thread() {
            @Override
            public void run() {
                m.stop();
                stopped[0] = true;
            }
        };
        end.start();
        end.join(300);
        boolean waited = !stopped[0];
        letGo.countDown();
        end.join(5000);
        sender.join(5000);
        check("the session's end waits for the send in progress", waited && stopped[0] && sendDone[0] && !m.ready());
    }
}
