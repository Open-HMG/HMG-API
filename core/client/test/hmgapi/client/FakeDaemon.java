package hmgapi.client;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * hmgapid for the client test: reads a request (LFD1, argument count, each length-prefixed),
 * keeps its arguments, and answers as the daemon does for a root caller: setting-get and
 * setting-set need the mod's id (with no id root is "unknown op or wrong arguments", code 2).
 */
public final class FakeDaemon {
    static final List<List<String>> calls = new ArrayList<List<String>>();

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static void frame(ByteArrayOutputStream o, char type, byte[] data) {
        o.write(type);
        for (int i = 0; i < 4; i++) o.write(data.length >> (8 * i) & 0xff);
        o.write(data, 0, data.length);
    }

    public static byte[] answer(byte[] req) {
        if (req.length < 8 || req[0] != 'L' || req[1] != 'F' || req[2] != 'D' || req[3] != '1') throw new AssertionError("not a request");
        int n = u32(req, 4), off = 8;
        List<String> args = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            int len = u32(req, off);
            args.add(new String(req, off + 4, len, java.nio.charset.Charset.forName("UTF-8")));
            off += 4 + len;
        }
        calls.add(args);
        boolean root = android.os.Process.myUid() == 0;
        String op = args.get(0);
        int code = 0;
        String out = "";
        if ("setting-get".equals(op)) {
            if (root ? args.size() == 2 : args.size() == 1) out = "{\"quality\":\"high\"}";
            else code = 2;
        } else if ("setting-set".equals(op)) {
            if (!(root ? args.size() == 3 : args.size() == 2)) code = 2;
        } else {
            code = 2;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (code != 0) out = "unknown op or wrong arguments\n";
        frame(o, 'O', out.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        frame(o, 'X', new byte[] {(byte) code, 0, 0, 0});
        return o.toByteArray();
    }

    static String last() {
        return calls.isEmpty() ? "" : Arrays.toString(calls.get(calls.size() - 1).toArray());
    }
}
