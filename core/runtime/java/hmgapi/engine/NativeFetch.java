package hmgapi.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * The engine's HTTPS on the unit: /data/hmgapi/lib/libhmgapi_fetch.so (core/fetch/fetch.c), over the
 * unit's own libssl, its socket marked for home Wi-Fi. The server's certificate isn't checked
 * (the unit's root store and clock can't be relied on): Catalog trusts a download only through
 * the SHA-256 its catalogue entry names and the package's signature, which the engine checks.
 *
 * Off the car (the tests) Web stands in for it: HMGAPI_TEST_WEB=<dir> serves <dir>/<host>/<path>.
 */
final class NativeFetch implements Http.Transport {
    static final String LIB = "/data/hmgapi/lib/libhmgapi_fetch.so", SSL = "/system/lib/libssl.so", LIBC = "/system/lib/libc.so";

    private final Engine e;
    private final String sslLib, cLib;

    private NativeFetch(Engine e, String lib, String sslLib, String cLib) {
        System.load(lib);
        this.e = e;
        this.sslLib = sslLib;
        this.cLib = cLib;
    }

    /** the transport for this engine: the library on the unit, files in the tests; null (said why) when there's none */
    static Http.Transport forEngine(Engine e, List<String> why) {
        String web = Engine.env("HMGAPI_TEST_WEB", null);
        if (e.host && web != null) return new Web(new File(web));
        File lib = new File(e.root + LIB);
        if (!lib.isFile()) {
            why.add("HMG-API's HTTPS library isn't installed (" + LIB + "): install HMG-API again");
            return null;
        }
        try {
            // off the car (a live test from a computer): that computer's TLS library
            String ssl = e.host ? Engine.env("HMGAPI_TEST_SSL", SSL) : SSL;
            return new NativeFetch(e, lib.getPath(), ssl, LIBC);
        } catch (Throwable t) {
            why.add("HMG-API's HTTPS library didn't load: " + t);
            return null;
        }
    }

    /** each socket's send and receive timeout (connect, the handshake, every read and write) */
    static final int TIMEOUT_MS = 20000;
    static final int CUT = -13;

    /** host is "name@address"; outPath must not exist yet. Bytes written for an answer the server closed cleanly, CUT, or an error */
    static native int fetch(String sslLib, String cLib, String host, String port, byte[] request, String outPath, int maxBytes, int timeoutMs);

    /** one UDP query to server:port on a socket held to home Wi-Fi, its answer to outPath (must not exist yet): its length, or an error */
    static native int dns(String cLib, String server, String port, byte[] query, String outPath, int timeoutMs);

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.Random RND = new java.util.Random();

    /**
     * a name in dir for one call's file (several calls, threads or engines may run at once): not there
     * yet, and the library opens it O_EXCL and O_NOFOLLOW, so a name that is taken is an error, never
     * someone else's file written over
     */
    static File fresh(File dir, String what) {
        int r;
        synchronized (RND) {
            r = RND.nextInt() & 0x7fffffff;
        }
        return new File(dir, what + "." + SEQ.incrementAndGet() + "." + Integer.toHexString(r) + "." + Long.toHexString(System.nanoTime()));
    }

    @Override
    public int exchange(String host, byte[] request, File rawOut, int maxBytes) {
        // home Wi-Fi's DNS server while its route is up (wifi.sh's lease), asked on a marked, bound socket
        String server = DnsLookup.server(e.root);
        if (server == null) return -15;
        final File dir = rawOut.getParentFile();
        String target;
        try {
            List<String> a = DnsLookup.lookup(server, host, new DnsLookup.Asker() {
                @Override
                public byte[] ask(String srv, byte[] query) throws IOException {
                    File f = fresh(dir, "dns");
                    try {
                        int n = dns(cLib, srv, "53", query, f.getPath(), DnsLookup.TIMEOUT_MS);
                        if (n < 0) throw new IOException(why(n));
                        return Engine.readAll(new java.io.FileInputStream(f));
                    } finally {
                        f.delete();
                    }
                }
            });
            if (a.isEmpty()) return -3;
            target = host + "@" + a.get(0);
        } catch (IOException x) {
            return -3;
        }
        return fetch(sslLib, cLib, target, "443", request, rawOut.getPath(), maxBytes, TIMEOUT_MS);
    }

    static String why(int code) {
        switch (code) {
            case -1: return "the TLS or C library can't be opened";
            case -2: return "a function is missing from the TLS or C library";
            case -3: return "the name didn't resolve (is home Wi-Fi up?)";
            case -4: return "no connection (home Wi-Fi up, with internet?)";
            case -5: return "TLS setup failed";
            case -6: return "TLS handshake failed";
            case -7: return "the request couldn't be sent";
            case -8: return "the file couldn't be written";
            case -9: return "the answer is larger than allowed";
            case -10: return "no answer";
            case -12: return "the connection can't be held to home Wi-Fi (its mark or wlan0 refused)";
            case CUT: return "the answer was cut off (the connection ended before it was whole)";
            case -14: return "the connection couldn't be set up (its timeouts)";
            case -15: return "home Wi-Fi isn't up (no lease)";
            default: return "error " + code;
        }
    }

    /**
     * Off-car tests: <dir>/<host>/<path> (a query's '?' as '_'), 200 with its bytes and a Content-Length;
     * beside the file, <file>.redirect holds a Location for a 302; <file>.status a status to answer
     * with instead; <file>.chunked serves it chunked (7-byte chunks); <file>.nolength with no length;
     * <file>.raw is the whole answer as it is (headers too, for broken framing); <file>.cut holds N: only
     * the first N bytes of the answer come, then the connection drops (CUT). Anything else 404. As the
     * library: rawOut must be new, and past maxBytes it stops with what fit (-9). Each request's host and
     * path is appended to <dir>/requests.log.
     */
    static final class Web implements Http.Transport {
        private final File dir;

        Web(File dir) {
            this.dir = dir;
        }

        @Override
        public int exchange(String host, byte[] request, File rawOut, int maxBytes) {
            try {
                if (rawOut.exists()) return -8;   // the library's O_EXCL
                String req = new String(request, "US-ASCII");
                String path = req.substring(4, req.indexOf(' ', 4));
                File log = new File(dir, "requests.log");
                synchronized (Web.class) {   // the tests' log, read and written whole: one call at a time
                    Engine.write(log, (log.isFile() ? Engine.read(log) : "") + host + path + "\n");
                }
                if (new File(dir, host + ".down").exists()) return -4;
                File f = new File(dir, host + path.replace('?', '_'));
                String p = f.getPath();
                java.io.ByteArrayOutputStream a = new java.io.ByteArrayOutputStream();
                if (new File(p + ".redirect").isFile()) {
                    a.write(("HTTP/1.1 302 Found\r\nLocation: " + Engine.read(new File(p + ".redirect")).trim() + "\r\nContent-Length: 0\r\n\r\n").getBytes("US-ASCII"));
                } else if (new File(p + ".status").isFile()) {
                    a.write(("HTTP/1.1 " + Engine.read(new File(p + ".status")).trim() + " X\r\nContent-Length: 0\r\n\r\n").getBytes("US-ASCII"));
                } else if (new File(p + ".raw").isFile()) {
                    a.write(Engine.readAll(new java.io.FileInputStream(p + ".raw")));
                } else if (f.isFile()) {
                    byte[] body = Engine.readAll(new java.io.FileInputStream(f));
                    if (new File(p + ".chunked").isFile()) {
                        a.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes("US-ASCII"));
                        for (int i = 0; i < body.length; i += 7) {
                            int n = Math.min(7, body.length - i);
                            a.write((Integer.toHexString(n) + "\r\n").getBytes("US-ASCII"));
                            a.write(body, i, n);
                            a.write("\r\n".getBytes("US-ASCII"));
                        }
                        a.write("0\r\n\r\n".getBytes("US-ASCII"));
                    } else {
                        boolean noLength = new File(p + ".nolength").isFile();
                        a.write(("HTTP/1.1 200 OK\r\n" + (noLength ? "" : "Content-Length: " + body.length + "\r\n") + "\r\n").getBytes("US-ASCII"));
                        a.write(body);
                    }
                } else {
                    a.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));
                }
                byte[] answer = a.toByteArray();
                int n = answer.length;
                boolean cut = new File(p + ".cut").isFile();
                if (cut) n = Math.min(n, Integer.parseInt(Engine.read(new File(p + ".cut")).trim()));
                boolean big = n > maxBytes;
                OutputStream o = new FileOutputStream(rawOut);
                try {
                    o.write(answer, 0, Math.min(n, maxBytes));
                } finally {
                    o.close();
                }
                return big ? -9 : n == 0 ? -10 : cut ? CUT : n;
            } catch (Exception x) {
                return -10;
            }
        }
    }
}
