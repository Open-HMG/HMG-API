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

    static native int fetch(String sslLib, String cLib, String host, String port, byte[] request, String outPath, int maxBytes);

    @Override
    public int exchange(String host, byte[] request, File rawOut, int maxBytes) {
        String dns = DnsLookup.server(e);
        if (dns == null) return -3;
        String target;
        try {
            List<String> a = DnsLookup.lookup(dns, host);
            if (a.isEmpty()) return -3;
            target = host + "@" + a.get(0);
        } catch (IOException x) {
            return -3;
        }
        return fetch(sslLib, cLib, target, "443", request, rawOut.getPath(), maxBytes);
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
            default: return "error " + code;
        }
    }

    /**
     * Off-car tests: <dir>/<host>/<path> (a query's '?' as '_'), 200 with its bytes; <file>.redirect holds a
     * Location for a 302; <file>.status a status to answer with instead; anything else 404. Each request's
     * host and path is appended to <dir>/requests.log.
     */
    static final class Web implements Http.Transport {
        private final File dir;

        Web(File dir) {
            this.dir = dir;
        }

        @Override
        public int exchange(String host, byte[] request, File rawOut, int maxBytes) {
            try {
                String req = new String(request, "US-ASCII");
                String path = req.substring(4, req.indexOf(' ', 4));
                File log = new File(dir, "requests.log");
                Engine.write(log, (log.isFile() ? Engine.read(log) : "") + host + path + "\n");
                if (new File(dir, host + ".down").exists()) return -4;
                File f = new File(dir, host + path.replace('?', '_'));
                String head;
                byte[] body = new byte[0];
                if (new File(f.getPath() + ".redirect").isFile()) {
                    head = "HTTP/1.1 302 Found\r\nLocation: " + Engine.read(new File(f.getPath() + ".redirect")).trim() + "\r\nContent-Length: 0\r\n";
                } else if (new File(f.getPath() + ".status").isFile()) {
                    head = "HTTP/1.1 " + Engine.read(new File(f.getPath() + ".status")).trim() + " X\r\nContent-Length: 0\r\n";
                } else if (f.isFile()) {
                    body = Engine.readAll(new java.io.FileInputStream(f));
                    head = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n";
                } else {
                    head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n";
                }
                OutputStream o = new FileOutputStream(rawOut);
                try {
                    o.write((head + "\r\n").getBytes("US-ASCII"));
                    o.write(body);
                } finally {
                    o.close();
                }
                return (int) rawOut.length();
            } catch (Exception x) {
                return -10;
            }
        }
    }
}
