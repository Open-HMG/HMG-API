package hmgapi.engine;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Methods only a smali pattern finds: another build's name is only a hint. The method taken is the
 * one whose code is the same as another map's ("sig"); with several such methods, none.
 * Seen on the Korean 260902 builds: py()Z is the test gate elsewhere but reads ro.product.model
 * there (the gate is pB), and gl()I is the getter of another field (the point is gk).
 */
public final class MapProbeMethodTest {
    static int checks;

    static Dex.Member method(String name, long sig) {
        Dex.Member m = new Dex.Member();
        m.name = name;
        m.ret = "boolean";
        m.isStatic = true;
        m.params = Arrays.asList();
        m.sig = sig;
        return m;
    }

    static void reference(File maps, String build, String name, String sig) throws Exception {
        File dir = new File(maps, "make/model/region/" + build);
        dir.mkdirs();
        Engine.write(new File(dir, "test.json"), "{\"test.gate.on\":{\"method\":\"" + name
                + "\",\"params\":[],\"ret\":\"boolean\",\"static\":true" + (sig == null ? "" : ",\"sig\":\"" + sig + "\"") + "}}");
    }

    static final Map<String, Object> FIND = find();

    static Map<String, Object> find() {
        try {
            return Json.object("{\"pattern\":\"gate\",\"group\":\"m\",\"params\":[],\"ret\":\"boolean\",\"static\":true}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void found(String label, MapProbe p, Dex.Cls c, String want) throws Exception {
        String got = p.methodByReference(c, "test.gate.on", FIND).name;
        if (!want.equals(got)) throw new AssertionError(label + ": wanted " + want + ", got " + got);
        checks++;
    }

    static void missing(String label, MapProbe p, Dex.Cls c, String reason) throws Exception {
        try {
            String got = p.methodByReference(c, "test.gate.on", FIND).name;
            throw new AssertionError(label + ": took " + got);
        } catch (MapProbe.Miss miss) {
            if (!miss.getMessage().contains(reason)) throw new AssertionError(label + ": " + miss.getMessage());
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]), maps = new File(root, "maps");
        String gate = Dex.sigHex(0x1111L);

        // the other builds say py; here py is something else and the gate is pB
        reference(maps, "us", "py", gate);
        MapProbe p = new MapProbe(root.getPath(), null, maps);
        Dex.Cls c = new Dex.Cls();
        c.name = "sample.v";
        c.methods.add(method("py", 0x2222L));
        c.methods.add(method("pB", 0x1111L));
        found("same code under another name", p, c, "pB");
        if (!p.byReference.contains("test.gate.on")) throw new AssertionError("not recorded by reference");
        checks++;

        // the name alone is never enough
        c.methods.get(1).sig = 0x3333L;
        missing("a same-named method with other code", p, c, "0 methods in sample.v have the other cars' code");

        // several methods with that code (getters of different fields): no guess, even by name
        c.methods.get(0).sig = 0x1111L;
        c.methods.get(1).sig = 0x1111L;
        missing("two methods with the same code", p, c, "needs this car's map");

        // the shape still has to fit
        c.methods.get(1).ret = "int";
        found("shape first, then code", p, c, "py");

        // maps without signatures give no evidence
        File old = new File(root, "old-maps");
        reference(old, "us", "py", null);
        missing("references without sig", new MapProbe(root.getPath(), null, old), c, "no other car's map has its code signature");

        // Dex.walk signs the same sample as tools/dexsig.py (SAMPLE_UNITS, SAMPLE_SIG)
        int[] units = { 0x0162, 0x0007, 0x011d, 0x10f2, 0x0010, 0x011e, 0x002b, 0x0004, 0x0000, 0x000f,
                        0x0100, 0x0001, 0x0000, 0x0000, 0x0005, 0x0000 };
        String want = args[1];
        if (!want.equals(Dex.sigHex(sign(units)))) throw new AssertionError("Dex.walk: wanted " + want + ", got " + Dex.sigHex(sign(units)));
        checks++;
        units[0] = 0x0262;   // another register, another field index, another table value
        units[1] = 0x0099;
        units[13] = 0x7777;
        if (!want.equals(Dex.sigHex(sign(units)))) throw new AssertionError("Dex.walk: registers, indices or table data changed the signature");
        checks++;
        units[4] = 0x0014;   // another field offset
        if (want.equals(Dex.sigHex(sign(units)))) throw new AssertionError("Dex.walk: a quickened operand didn't change the signature");
        checks++;
        System.out.println("ok    by-reference methods by their code (" + checks + " checks)");
    }

    /** a code item (16-byte header, then the units) after a bare dex header */
    static long sign(int[] units) {
        byte[] b = new byte[0x70 + 16 + units.length * 2];
        b[0] = 'd';
        b[1] = 'e';
        b[2] = 'x';
        b[3] = '\n';
        int item = 0x70, n = units.length;
        b[item + 12] = (byte) n;
        b[item + 13] = (byte) (n >> 8);
        for (int i = 0; i < n; i++) {
            b[item + 16 + i * 2] = (byte) units[i];
            b[item + 17 + i * 2] = (byte) (units[i] >> 8);
        }
        return new Dex(b).walk(item, new LinkedHashSet<String>());
    }
}
