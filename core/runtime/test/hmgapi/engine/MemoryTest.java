package hmgapi.engine;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Car Report on a 128 MB unit (the TCC897x builds, a US K5 on 1.1.0-rc.5): the engine ran
 * out of memory reading the framework. What keeps it small, and where an engine error says
 * it happened. Run with HMGAPI_ROOT set to an empty directory.
 */
public final class MemoryTest {
    static int checks;

    static void is(String label, Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(label + ": wanted " + want + ", got " + got);
        checks++;
    }

    static void yes(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 7);
        return b;
    }

    public static void main(String[] args) throws Exception {
        // a file read in one array of its size; a wrong size still reads it all
        byte[] b = bytes(200000);
        yes("read with the right size", Arrays.equals(b, Dex.readAll(new ByteArrayInputStream(b), b.length)));
        yes("read with a size too small", Arrays.equals(b, Dex.readAll(new ByteArrayInputStream(b), 1000)));
        is("read with a size too big", 200000, Dex.readAll(new ByteArrayInputStream(b), 300000).length);
        yes("read with no size", Arrays.equals(b, Dex.readAll(new ByteArrayInputStream(b), -1)));
        is("an empty file", 0, Dex.readAll(new ByteArrayInputStream(new byte[0]), 0).length);

        // const-strings as a sorted array: same answers as the set they came from
        Set<String> s = new LinkedHashSet<String>(Arrays.asList("zeta", "alpha", "Mid", "", "ünï"));
        Set<String> a = Dex.Strings.of(s);
        for (String x : s) yes("has " + x, a.contains(x));
        yes("not a missing one", !a.contains("beta"));
        yes("not a non-string", !a.contains(Integer.valueOf(3)));
        is("size", 5, a.size());
        yes("none: the one shared empty set", Dex.Strings.of(new LinkedHashSet<String>()) == Dex.Strings.NONE);

        // one copy of each name and parameter list
        String n1 = Dex.shared(new String("java.lang.String")), n2 = Dex.shared(new String("java.lang.String"));
        yes("a name shared", n1 == n2);
        List<String> p1 = Dex.shared(new ArrayList<String>(Arrays.asList("int", "android.content.Context")));
        List<String> p2 = Dex.shared(new ArrayList<String>(Arrays.asList("int", "android.content.Context")));
        yes("a parameter list shared", p1 == p2);
        yes("a shared list equals the catalogue's", p1.equals(Arrays.asList("int", "android.content.Context")));
        yes("no parameters equals an empty list", Dex.shared(new ArrayList<String>()).equals(new ArrayList<String>()));
        Dex.forget();
        yes("forget drops the pool", Dex.shared(new String("java.lang.String")) != n1);
        Dex.forget();

        // the strings a catalogue's shapes name, at any depth
        File root = new File(System.getenv("HMGAPI_ROOT"));
        File cat = new File(root, "catalogue");
        cat.mkdirs();
        Engine.write(new File(cat, "a.json"), "{\"app\":\"a\",\"points\":[{\"id\":\"a.x\",\"find\":{\"strings\":[\"one\"],"
                + "\"methods\":[{\"name\":\"m\",\"strings\":[\"two\",\"three\"]}]}}]}");
        Engine.write(new File(cat, "b.json"), "{\"app\":\"b\",\"points\":[]}");
        Engine.write(new File(cat, "notes.txt"), "{\"strings\":[\"not a catalogue file\"]}");
        is("catalogue strings", new java.util.TreeSet<String>(Arrays.asList("one", "two", "three")),
                new java.util.TreeSet<String>(MapProbe.catalogueStrings(cat)));

        // an engine error: its frames in the output, the trace in state/engine-error.txt
        new File(root, "data/hmgapi/state").mkdirs();
        Engine e = new Engine();
        Throwable t;
        try {
            throw new RuntimeException("outer", new IllegalStateException("inner"));
        } catch (RuntimeException x) {
            t = x;
        }
        e.out.add("engine error: " + t);
        e.failedAt(t, new String[] { "report", "--usb" });
        yes("first frame in the output: " + e.out, e.out.size() > 1 && e.out.get(1).startsWith("  at hmgapi.engine.MemoryTest.main(MemoryTest.java:"));
        yes("at most " + Engine.ERROR_FRAMES + " frames", e.out.size() <= 1 + Engine.ERROR_FRAMES + 3);
        boolean cause = false;
        for (String l : e.out) if (l.equals("  caused by java.lang.IllegalStateException: inner")) cause = true;
        yes("the cause in the output: " + e.out, cause);
        String saved = Engine.read(e.errorFile);
        yes("trace saved: " + saved, saved.startsWith("engine report at ") && saved.contains("MemoryTest.main(MemoryTest.java:") && saved.contains("Caused by: java.lang.IllegalStateException: inner"));
        List<Object> errs = Report.errors(e);
        boolean inReport = false;
        for (Object l : errs) if (String.valueOf(l).contains("java.lang.RuntimeException: outer")) inReport = true;
        yes("the next Car Report has it: " + errs, inReport);

        // which build a report is from (test builds share a version)
        is("no installed.txt: no build", null, Report.installedBuild(e));
        Engine.write(new File(root, "data/hmgapi/installed.txt"), "version 1.1.0-rc.5\nbuild c8b782c 2026-11-19\ndate x\n");
        is("the build installed", "c8b782c 2026-11-19", Report.installedBuild(e));

        Engine.roomToWork();   // no VMRuntime off the unit: nothing happens, nothing thrown
        System.out.println("ok    " + checks + " memory and engine-error checks");
    }
}
