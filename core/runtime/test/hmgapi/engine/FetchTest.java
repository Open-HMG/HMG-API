package hmgapi.engine;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/** The engine's HTTPS pieces without a network: Http over NativeFetch.Web (files), Sha256, DnsLookup's reader. */
public final class FetchTest {
    static int failed;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failed++;
    }

    static void put(File web, String path, String body) throws Exception {
        File f = new File(web, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), body.getBytes("UTF-8"));
    }

    public static void main(String[] args) throws Exception {
        File d = Files.createTempDirectory("fetch").toFile(), web = new File(d, "web"), tmp = new File(d, "tmp");
        tmp.mkdirs();
        Http http = new Http(new NativeFetch.Web(web), tmp);
        File out = new File(d, "out");

        put(web, "raw.githubusercontent.com/a.txt", "hello, car");
        check("200: written", http.get("https://raw.githubusercontent.com/a.txt", out, 100).ok() && "hello, car".equals(Engine.read(out)));
        put(web, "github.com/r.zip.redirect", "https://objects.githubusercontent.com/x.zip");
        put(web, "objects.githubusercontent.com/x.zip", "zip bytes");
        check("a redirect to GitHub's asset host: followed", http.get("https://github.com/r.zip", out, 100).ok() && "zip bytes".equals(Engine.read(out)));
        put(web, "github.com/evil.redirect", "https://example.com/x");
        Http.Result r = http.get("https://github.com/evil", out, 100);
        check("a redirect to another host: refused (" + r + ")", !r.ok() && r.toString().contains("host not allowed"));
        check("plain http: refused", !http.get("http://github.com/a", out, 100).ok());
        check("another host: refused", !http.get("https://example.com/a", out, 100).ok());
        put(web, "api.github.com/repos/x/y/contents/c_ref=main", "[]");
        check("the API, with a query", http.get("https://api.github.com/repos/x/y/contents/c?ref=main", out, 100).ok());
        put(web, "raw.githubusercontent.com/big", new String(new char[500]).replace('\0', 'x'));
        File big = new File(d, "big");
        r = http.get("https://raw.githubusercontent.com/big", big, 100);
        check("over the cap: refused, nothing left (" + r + ")", !r.ok() && !big.exists() && !new File(d, "big.part").exists());
        check("404: reported", http.get("https://raw.githubusercontent.com/none", out, 100).status == 404);
        String body500 = new String(new char[500]).replace('\0', 'y');

        // the transport's own cap (the body's plus headers): past it, what fit and -9, nothing kept
        put(web, "raw.githubusercontent.com/huge", new String(new char[70000]).replace('\0', 'z'));
        File huge = new File(d, "huge");
        r = http.get("https://raw.githubusercontent.com/huge", huge, 100);
        check("past the transport's cap: refused (" + r + ")", !r.ok() && r.toString().contains("larger than allowed") && !huge.exists());

        // chunked
        put(web, "raw.githubusercontent.com/ch.txt", "hello, chunked car, in pieces");
        put(web, "raw.githubusercontent.com/ch.txt.chunked", "");
        check("chunked: joined", http.get("https://raw.githubusercontent.com/ch.txt", out, 100).ok() && "hello, chunked car, in pieces".equals(Engine.read(out)));
        put(web, "raw.githubusercontent.com/chbig", body500);
        put(web, "raw.githubusercontent.com/chbig.chunked", "");
        File chbig = new File(d, "chbig");
        r = http.get("https://raw.githubusercontent.com/chbig", chbig, 100);
        check("chunked over the cap: refused, nothing left (" + r + ")", !r.ok() && r.toString().contains("larger than 100") && !chbig.exists() && !new File(d, "chbig.part").exists());
        String head = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
        // a negative size took the running total down, so the next chunk passed the cap (wrote past it)
        put(web, "raw.githubusercontent.com/neg.raw", head + "a\r\n0123456789\r\n-a\r\n\r\n64\r\n" + body500.substring(0, 100) + "\r\n0\r\n\r\n");
        File neg = new File(d, "neg");
        r = http.get("https://raw.githubusercontent.com/neg", neg, 20);
        check("chunked: a negative size refused, nothing left (" + r + ")", !r.ok() && r.toString().contains("bad chunk size") && !neg.exists() && !new File(d, "neg.part").exists());
        for (String bad : new String[] {"zz", "0x5", "+5", "", "10000000", " "}) {
            put(web, "raw.githubusercontent.com/bad.raw", head + "5\r\nhello\r\n" + bad + "\r\nxx\r\n0\r\n\r\n");
            File b = new File(d, "bad");
            r = http.get("https://raw.githubusercontent.com/bad", b, 100);
            check("chunked: size [" + bad + "] refused, no .part left (" + r + ")", !r.ok() && !b.exists() && !new File(d, "bad.part").exists());
        }
        put(web, "raw.githubusercontent.com/noend.raw", head + "5\r\nhello\r\n");
        r = http.get("https://raw.githubusercontent.com/noend", new File(d, "noend"), 100);
        check("chunked: no last chunk refused (" + r + ")", !r.ok() && !new File(d, "noend").exists() && !new File(d, "noend.part").exists());
        put(web, "raw.githubusercontent.com/glued.raw", head + "5\r\nhelloXX0\r\n\r\n");
        r = http.get("https://raw.githubusercontent.com/glued", new File(d, "glued"), 100);
        check("chunked: a chunk not ended by CRLF refused (" + r + ")", !r.ok() && !new File(d, "glued.part").exists());
        put(web, "raw.githubusercontent.com/trail.raw", head + "5;x=y\r\nhello\r\n0\r\nX-Trailer: 1\r\n\r\n");
        check("chunked: an extension and a trailer read", http.get("https://raw.githubusercontent.com/trail", out, 100).ok() && "hello".equals(Engine.read(out)));

        // how the answer ended (the library: a TLS close, or CUT)
        put(web, "raw.githubusercontent.com/nl.txt", "no length, closed by the server");
        put(web, "raw.githubusercontent.com/nl.txt.nolength", "");
        check("no length, the server's close: whole", http.get("https://raw.githubusercontent.com/nl.txt", out, 100).ok() && "no length, closed by the server".equals(Engine.read(out)));
        put(web, "raw.githubusercontent.com/nl.txt.cut", "45");
        Files.write(out.toPath(), "before".getBytes("US-ASCII"));
        r = http.get("https://raw.githubusercontent.com/nl.txt", out, 100);
        check("no length, the connection dropped: cut off, not ok (" + r + ")", !r.ok() && r.toString().contains("cut off") && "before".equals(Engine.read(out)));
        put(web, "raw.githubusercontent.com/cl.txt", "0123456789");
        put(web, "raw.githubusercontent.com/cl.txt.cut", "1000");
        check("its Content-Length met, then dropped (no TLS close): whole", http.get("https://raw.githubusercontent.com/cl.txt", out, 100).ok() && "0123456789".equals(Engine.read(out)));
        put(web, "raw.githubusercontent.com/cl.txt.cut", "42");
        r = http.get("https://raw.githubusercontent.com/cl.txt", out, 100);
        check("its Content-Length short, dropped: cut off (" + r + ")", !r.ok() && r.toString().contains("cut off"));
        put(web, "raw.githubusercontent.com/ch.txt.cut", "1000");
        check("chunked to its last chunk, then dropped: whole", http.get("https://raw.githubusercontent.com/ch.txt", out, 100).ok());
        put(web, "raw.githubusercontent.com/ch.txt.cut", "60");
        r = http.get("https://raw.githubusercontent.com/ch.txt", out, 100);
        check("chunked, dropped before its last chunk: cut off (" + r + ")", !r.ok() && r.toString().contains("cut off"));
        put(web, "raw.githubusercontent.com/a.txt.cut", "20");
        r = http.get("https://raw.githubusercontent.com/a.txt", out, 100);
        check("dropped in the headers: cut off (" + r + ")", !r.ok() && r.toString().contains("cut off"));
        new File(web, "raw.githubusercontent.com/a.txt.cut").delete();
        put(web, "raw.githubusercontent.com/badlen.raw", "HTTP/1.1 200 OK\r\nContent-Length: -5\r\n\r\nhello");
        r = http.get("https://raw.githubusercontent.com/badlen", new File(d, "badlen"), 100);
        check("a Content-Length that isn't a number refused (" + r + ")", !r.ok() && r.toString().contains("bad Content-Length"));
        put(web, "raw.githubusercontent.com/biglen.raw", "HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\nhello");
        r = http.get("https://raw.githubusercontent.com/biglen", new File(d, "biglen"), 100);
        check("a Content-Length over the cap refused at once (" + r + ")", !r.ok() && r.toString().contains("larger than 100"));

        // redirects
        put(web, "github.com/rel.redirect", "/a.txt");
        put(web, "github.com/a.txt", "on github");
        check("a redirect to a path on the same host", http.get("https://github.com/rel", out, 100).ok() && "on github".equals(Engine.read(out)));
        put(web, "github.com/loop.redirect", "https://github.com/loop");
        r = http.get("https://github.com/loop", out, 100);
        check("a redirect loop: stops (" + r + ")", !r.ok() && r.toString().contains("too many redirects"));
        put(web, "github.com/plain.redirect", "http://github.com/a.txt");
        r = http.get("https://github.com/plain", out, 100);
        check("a redirect to plain http refused (" + r + ")", !r.ok() && r.toString().contains("not https"));
        put(web, "github.com/r307.raw", "HTTP/1.1 307 Temporary Redirect\r\nLocation: https://raw.githubusercontent.com/a.txt\r\n\r\n");
        check("a 307 followed", http.get("https://github.com/r307", out, 100).ok() && "hello, car".equals(Engine.read(out)));
        put(web, "github.com/noloc.raw", "HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n");
        check("a redirect without a location refused", !http.get("https://github.com/noloc", out, 100).ok());

        // one answer file per call, none left; a name that's taken is the library's O_EXCL error
        check("nothing left in the download folder", tmp.list().length == 0);
        File f1 = NativeFetch.fresh(tmp, "answer"), f2 = NativeFetch.fresh(tmp, "answer");
        check("each call's answer file its own name", !f1.equals(f2) && !f1.exists());
        Files.write(f1.toPath(), "x".getBytes("US-ASCII"));
        check("an answer file that's there already: not written over (O_EXCL)", new NativeFetch.Web(web).exchange("raw.githubusercontent.com", "GET /a.txt HTTP/1.1\r\n".getBytes("US-ASCII"), f1, 1000) == -8 && "x".equals(Engine.read(f1)));
        f1.delete();
        final Http shared = http;
        final File base = d;
        final boolean[] fine = {true};
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            final int k = i;
            put(web, "raw.githubusercontent.com/t" + k, "thread " + k + " " + body500.substring(0, 50 * k));
            ts[i] = new Thread() {
                @Override
                public void run() {
                    try {
                        for (int j = 0; j < 20; j++) {
                            File o = new File(base, "t" + k);
                            boolean ok = shared.get("https://raw.githubusercontent.com/t" + k, o, 1000).ok()
                                    && Engine.read(o).equals("thread " + k + " " + body500.substring(0, 50 * k));
                            synchronized (fine) { fine[0] &= ok; }
                        }
                    } catch (Exception x) {
                        synchronized (fine) { fine[0] = false; }
                    }
                }
            };
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        check("eight gets at once on one Http: each its own answer", fine[0] && tmp.list().length == 0);

        new File(web, "raw.githubusercontent.com.down").createNewFile();
        r = http.get("https://raw.githubusercontent.com/a.txt", out, 100);
        check("no connection: said (" + r + ")", !r.ok() && r.toString().contains("no connection"));

        boolean same = true;
        Random rnd = new Random(7);
        for (int len : new int[] {0, 1, 55, 56, 63, 64, 65, 119, 120, 1000, 100000}) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            Sha256 s = new Sha256();
            s.update(data, 0, Math.min(len, 7));
            if (len > 7) s.update(data, 7, len - 7);
            same &= Arrays.equals(s.digest(), MessageDigest.getInstance("SHA-256").digest(data));
        }
        check("SHA-256: the JDK's at every length tried", same);
        Files.write(out.toPath(), "abc".getBytes("US-ASCII"));
        check("SHA-256 of a file, hex", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(Sha256.hex(out)));

        byte[] q = DnsLookup.query(0x1234, "raw.githubusercontent.com");
        java.io.ByteArrayOutputStream a = new java.io.ByteArrayOutputStream();
        a.write(q, 0, q.length);
        byte[] ans = a.toByteArray();
        ans[2] = (byte) 0x81; ans[3] = (byte) 0x80; ans[7] = 3;
        a.reset(); a.write(ans, 0, ans.length);
        for (int[] rr : new int[][] {{0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0, 12},
                {0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 185, 199, 108, 133}, {0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 185, 199, 109, 133}}) {
            for (int b : rr) a.write(b);
        }
        byte[] dr = a.toByteArray();
        check("DNS: A records read, a CNAME skipped", DnsLookup.answers(dr, dr.length, 0x1234).toString().equals("[185.199.108.133, 185.199.109.133]"));
        boolean refused = false;
        try { DnsLookup.answers(dr, dr.length, 0x4321); } catch (java.io.IOException x) { refused = true; }
        check("DNS: an answer to another query refused", refused);
        refused = false;
        try { DnsLookup.answers(dr, dr.length - 3, 0x1234); } catch (java.io.IOException x) { refused = true; }
        check("DNS: a cut-off answer refused", refused);
        dr[3] = (byte) 0x83;
        check("DNS: no such name: none", DnsLookup.answers(dr, dr.length, 0x1234).isEmpty());

        // the server: wifi.sh's lease (there only while home Wi-Fi's route is up), never a leftover dhcp prop
        File root = new File(d, "root");
        check("DNS server: no lease, none (home Wi-Fi isn't up)", DnsLookup.server(root.getPath()) == null);
        put(root, "data/hmgapi/wifi/lease", "10.4.0.1 10.10.10.100\n");
        check("DNS server: the lease's", "10.10.10.100".equals(DnsLookup.server(root.getPath())));
        put(root, "data/hmgapi/wifi/lease", "garbage");
        check("DNS server: a lease that isn't one, none", DnsLookup.server(root.getPath()) == null);
        // lookup asks through the Asker (the library's marked, bound socket): tries again, then gives up
        final int[] asked = {0};
        java.util.List<String> got = DnsLookup.lookup("10.10.10.100", "raw.githubusercontent.com", new DnsLookup.Asker() {
            @Override
            public byte[] ask(String server, byte[] query) throws java.io.IOException {
                if (asked[0]++ == 0) throw new java.io.IOException("timed out");
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                b.write(query, 0, query.length);
                byte[] r2 = b.toByteArray();
                r2[2] = (byte) 0x81; r2[3] = (byte) 0x80; r2[7] = 1;
                b.reset(); b.write(r2, 0, r2.length);
                for (int x : new int[] {0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 185, 199, 110, 133}) b.write(x);
                return b.toByteArray();
            }
        });
        check("DNS: asked again after a failure, then the answer", asked[0] == 2 && "[185.199.110.133]".equals(got.toString()));
        refused = false;
        try {
            DnsLookup.lookup("10.10.10.100", "github.com", new DnsLookup.Asker() {
                @Override
                public byte[] ask(String server, byte[] query) throws java.io.IOException { throw new java.io.IOException("no route"); }
            });
        } catch (java.io.IOException x) { refused = true; }
        check("DNS: no answer in " + DnsLookup.TRIES + " tries: an error", refused);
        System.exit(failed == 0 ? 0 : 1);
    }
}
