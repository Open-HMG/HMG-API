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
        System.exit(failed == 0 ? 0 : 1);
    }
}
