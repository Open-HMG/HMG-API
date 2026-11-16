package hmgapi.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A reader for the classes in a dex: an .odex (the head unit's /system/app and
 * /system/framework files: "dey" header, the dex inside), a plain classes.dex, or a
 * jar or APK holding one. For each class: its name, superclass, fields and methods
 * (names and Java types, static or not), and the strings and integers its code
 * loads and method names it calls, per method. That's what the hook point catalogue
 * describes points by (MapProbe), the same facts tools/map-car.py reads from
 * baksmali's output. Names and constants only; no code is kept.
 */
public final class Dex {
    public static final class Member {
        public String name, type, ret, owner;
        public List<String> params;
        public boolean isStatic;
        public Set<String> strings, calls;   // methods: constants and calls in its own code
        public Set<Integer> constants;
        public final List<Member> callers = new ArrayList<Member>();
        int dexIndex;
        List<Integer> callIndexes;
    }

    public static final class Cls {
        public String name, sup;
        public final List<Member> fields = new ArrayList<Member>();
        public final List<Member> methods = new ArrayList<Member>();
        public final Set<String> strings = new LinkedHashSet<String>();
    }

    final byte[] b;
    final int base;
    String[] strings;
    String[] types;

    Dex(byte[] data) {
        b = data;
        if (data.length > 16 && data[0] == 'd' && data[1] == 'e' && data[2] == 'y' && data[3] == '\n') base = u4(8);
        else if (data.length > 0x70 && data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n') base = 0;
        else throw new IllegalArgumentException("not a dex or odex");
    }

    /** Every class in an .odex, .dex, or the classes.dex of a .jar/.apk: name -> class. */
    public static Map<String, Cls> read(File f) throws Exception {
        byte[] data;
        String n = f.getName();
        if (n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".zip")) {
            ZipFile z = new ZipFile(f);
            try {
                ZipEntry e = z.getEntry("classes.dex");
                if (e == null) return new LinkedHashMap<String, Cls>();
                data = readAll(z.getInputStream(e));
            } finally {
                z.close();
            }
        } else {
            data = readAll(new FileInputStream(f));
        }
        return new Dex(data).classes();
    }

    static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    // ---- raw reads (little endian; dex offsets are from the dex's own start)

    int u2(int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    int u4(int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    int h(int field) {
        return u4(base + field);
    }

    int pos;

    int uleb() {
        int r = 0, s = 0, x;
        do {
            x = b[pos++] & 0xff;
            r |= (x & 0x7f) << s;
            s += 7;
        } while ((x & 0x80) != 0);
        return r;
    }

    String string(int idx) {
        if (strings[idx] != null) return strings[idx];
        pos = base + u4(base + h(0x3c) + idx * 4);
        int len = uleb();
        StringBuilder s = new StringBuilder(len);
        while (true) {   // modified UTF-8
            int c = b[pos++] & 0xff;
            if (c == 0) break;
            if (c < 0x80) s.append((char) c);
            else if ((c & 0xe0) == 0xc0) s.append((char) ((c & 0x1f) << 6 | (b[pos++] & 0x3f)));
            else s.append((char) ((c & 0x0f) << 12 | (b[pos++] & 0x3f) << 6 | (b[pos++] & 0x3f)));
        }
        return strings[idx] = s.toString();
    }

    String type(int idx) {
        if (types[idx] != null) return types[idx];
        return types[idx] = javaType(string(u4(base + h(0x44) + idx * 4)));
    }

    /** a dex descriptor as map-car.py's jtype writes it: Lcom/x/Y; -> com.x.Y, [I -> int[] */
    public static String javaType(String d) {
        int dims = 0;
        while (d.charAt(dims) == '[') dims++;
        String t = d.substring(dims);
        String j;
        switch (t.charAt(0)) {
            case 'V': j = "void"; break;
            case 'Z': j = "boolean"; break;
            case 'B': j = "byte"; break;
            case 'S': j = "short"; break;
            case 'C': j = "char"; break;
            case 'I': j = "int"; break;
            case 'J': j = "long"; break;
            case 'F': j = "float"; break;
            case 'D': j = "double"; break;
            default: j = t.substring(1, t.length() - 1).replace('/', '.');
        }
        StringBuilder s = new StringBuilder(j);
        for (int i = 0; i < dims; i++) s.append("[]");
        return s.toString();
    }

    Map<String, Cls> classes() {
        strings = new String[h(0x38)];
        types = new String[h(0x40)];
        Map<String, Cls> out = new LinkedHashMap<String, Cls>();
        int defs = h(0x64);
        for (int i = 0; i < h(0x60); i++) {
            int d = base + defs + i * 32;
            Cls c = new Cls();
            c.name = type(u4(d));
            int sup = u4(d + 8);
            c.sup = sup == -1 ? null : type(sup);
            int data = u4(d + 24);
            if (data != 0) classData(c, base + data);
            out.put(c.name, c);
        }
        Map<Integer, Member> methods = new LinkedHashMap<Integer, Member>();
        for (Cls c : out.values()) for (Member m : c.methods) methods.put(m.dexIndex, m);
        for (Cls c : out.values()) for (Member caller : c.methods) {
            for (Integer idx : caller.callIndexes) {
                Member target = methods.get(idx);
                if (target != null) target.callers.add(caller);
            }
        }
        return out;
    }

    void classData(Cls c, int at) {
        pos = at;
        int sf = uleb(), inf = uleb(), dm = uleb(), vm = uleb();
        int idx = 0;
        for (int i = 0; i < sf + inf; i++) {
            if (i == sf) idx = 0;
            idx += uleb();
            int acc = uleb();
            int save = pos;
            int f = base + h(0x54) + idx * 8;
            Member m = new Member();
            m.type = type(u2(f + 2));
            m.name = string(u4(f + 4));
            m.isStatic = (acc & 0x8) != 0;
            c.fields.add(m);
            pos = save;
        }
        idx = 0;
        for (int i = 0; i < dm + vm; i++) {
            if (i == dm) idx = 0;
            idx += uleb();
            int acc = uleb();
            int code = uleb();
            int save = pos;
            int mo = base + h(0x5c) + idx * 8;
            Member m = new Member();
            m.owner = c.name;
            m.dexIndex = idx;
            m.name = string(u4(mo + 4));
            int proto = base + h(0x4c) + u2(mo + 2) * 12;
            m.ret = type(u4(proto + 4));
            m.params = new ArrayList<String>();
            int pl = u4(proto + 8);
            if (pl != 0) {
                int n = u4(base + pl);
                for (int k = 0; k < n; k++) m.params.add(type(u2(base + pl + 4 + k * 2)));
            }
            m.isStatic = (acc & 0x8) != 0;
            m.strings = new LinkedHashSet<String>();
            m.calls = new LinkedHashSet<String>();
            m.constants = new LinkedHashSet<Integer>();
            m.callIndexes = new ArrayList<Integer>();
            if (code != 0) codeFacts(base + code, m.strings, m.calls, m.callIndexes, m.constants);
            c.strings.addAll(m.strings);
            if (m.strings.isEmpty()) m.strings = java.util.Collections.emptySet();   // most methods: the framework has ~100k
            if (m.calls.isEmpty()) m.calls = java.util.Collections.emptySet();
            if (m.constants.isEmpty()) m.constants = java.util.Collections.emptySet();
            c.methods.add(m);
            pos = save;
        }
    }

    String methodName(int idx) {
        int m = base + h(0x5c) + idx * 8;
        return string(u4(m + 4));
    }

    /** Constants and called method names in a code item (walks the instructions by their sizes). */
    void codeFacts(int item, Set<String> stringsOut, Set<String> callsOut, List<Integer> callIndexesOut,
                   Set<Integer> constantsOut) {
        int n = u4(item + 12), start = item + 16;
        int i = 0;
        while (i < n) {
            int at = start + i * 2, unit = u2(at), op = unit & 0xff;
            if (op == 0x00 && unit != 0) {   // switch and array data tables sit between instructions
                if (unit == 0x0100) i += 4 + u2(at + 2) * 2;
                else if (unit == 0x0200) i += 2 + u2(at + 2) * 4;
                else if (unit == 0x0300) i += 4 + (int) (((long) u2(at + 2) * u4(at + 4) + 1) / 2);
                else i += 1;
                continue;
            }
            if (op == 0x12) {
                int v = unit >> 12;
                constantsOut.add(v < 8 ? v : v - 16);
            } else if (op == 0x13) constantsOut.add((int) (short) u2(at + 2));
            else if (op == 0x14) constantsOut.add(u4(at + 2));
            else if (op == 0x15) constantsOut.add(((int) (short) u2(at + 2)) << 16);
            else if (op == 0x1a) stringsOut.add(string(u2(at + 2)));
            else if (op == 0x1b) stringsOut.add(string(u4(at + 2)));
            else if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                int idx = u2(at + 2);
                callsOut.add(methodName(idx));
                callIndexesOut.add(idx);
            }
            i += SIZE[op];
        }
    }

    /** instruction sizes in 16-bit units, by opcode (dex 035, plus Dalvik's odex opcodes 0xe3-0xfe) */
    static final int[] SIZE = new int[256];

    static {
        int[][] ranges = {
            {0x00, 0x01, 1}, {0x02, 0x02, 2}, {0x03, 0x03, 3}, {0x04, 0x04, 1}, {0x05, 0x05, 2}, {0x06, 0x06, 3},
            {0x07, 0x07, 1}, {0x08, 0x08, 2}, {0x09, 0x09, 3}, {0x0a, 0x12, 1}, {0x13, 0x13, 2}, {0x14, 0x14, 3},
            {0x15, 0x16, 2}, {0x17, 0x17, 3}, {0x18, 0x18, 5}, {0x19, 0x1a, 2}, {0x1b, 0x1b, 3}, {0x1c, 0x1c, 2},
            {0x1d, 0x1e, 1}, {0x1f, 0x20, 2}, {0x21, 0x21, 1}, {0x22, 0x23, 2}, {0x24, 0x26, 3}, {0x27, 0x28, 1},
            {0x29, 0x29, 2}, {0x2a, 0x2c, 3}, {0x2d, 0x3d, 2}, {0x3e, 0x43, 1}, {0x44, 0x6d, 2}, {0x6e, 0x72, 3},
            {0x73, 0x73, 1}, {0x74, 0x78, 3}, {0x79, 0x8f, 1}, {0x90, 0xaf, 2}, {0xb0, 0xcf, 1}, {0xd0, 0xe2, 2},
            {0xe3, 0xeb, 2}, {0xec, 0xec, 1}, {0xed, 0xed, 2}, {0xee, 0xf0, 3}, {0xf1, 0xf1, 1}, {0xf2, 0xf7, 2},
            {0xf8, 0xfb, 3}, {0xfc, 0xfe, 2}, {0xff, 0xff, 1},
        };
        for (int[] r : ranges) for (int op = r[0]; op <= r[1]; op++) SIZE[op] = r[2];
    }
}
