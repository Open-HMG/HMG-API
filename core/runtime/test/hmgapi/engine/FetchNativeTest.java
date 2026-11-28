package hmgapi.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * libhmgapi_fetch built for this computer (-DHOST: no mark or wlan0 here) against a local TLS
 * server: how an answer ends (a TLS close, a dropped connection, a server that goes quiet), the
 * cap, the answer file (O_EXCL, O_NOFOLLOW), and the DNS query's UDP exchange.
 *   FetchNativeTest <library> <libssl> <keystore.p12> <password>
 */
public final class FetchNativeTest {
    static int failed;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failed++;
    }

    interface Mode {
        void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception;
    }

    static SSLContext ctx;

    /** one TLS connection on a local port, served by mode; the port */
    static int server(final Mode mode) throws Exception {
        final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread() {
            @Override
            public void run() {
                try {
                    Socket raw = ss.accept();
                    SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, (InputStream) null, false);
                    s.setUseClientMode(false);
                    s.startHandshake();
                    InputStream in = s.getInputStream();
                    int st = 0;
                    while (st < 4) {   // the request, to its blank line
                        int c = in.read();
                        if (c < 0) break;
                        st = (c == '\r' || c == '\n') ? st + 1 : 0;
                    }
                    mode.serve(raw, s, s.getOutputStream());
                } catch (Exception x) {
                    // the client went away: that's some tests' point
                } finally {
                    try { ss.close(); } catch (Exception x) { }
                }
            }
        };
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    /** the server's end of an answer: its TLS close (close_notify), then, a moment later, the TCP close */
    static void closeTls(SSLSocket s, Socket raw) throws Exception {
        s.shutdownOutput();
        Thread.sleep(300);
        raw.close();
    }

    static String lib, ssl;

    static int fetch(int port, File out, int max, int timeoutMs) {
        byte[] req = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes();
        return NativeFetch.fetch(ssl, "libc", "localhost@127.0.0.1", String.valueOf(port), req, out.getPath(), max, timeoutMs);
    }

    public static void main(String[] args) throws Exception {
        System.load(new File(args[0]).getAbsolutePath());
        ssl = args[1];
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new FileInputStream(args[2]), args[3].toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, args[3].toCharArray());
        ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        File d = Files.createTempDirectory("fetchnative").toFile();
        final byte[] whole = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".getBytes();

        File out = new File(d, "a1");
        int n = fetch(server(new Mode() {
            public void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception { o.write(whole); o.flush(); closeTls(s, raw); }
        }), out, 1000, 5000);
        check("the server's TLS close: a whole answer, its length (" + n + ")", n == whole.length && new String(Files.readAllBytes(out.toPath())).endsWith("hello"));

        out = new File(d, "a2");
        n = fetch(server(new Mode() {
            public void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception { o.write(whole, 0, 30); o.flush(); Thread.sleep(200); raw.close(); }
        }), out, 1000, 5000);
        check("the connection dropped with no TLS close: CUT, what came kept (" + n + ")", n == NativeFetch.CUT && out.length() == 30);

        out = new File(d, "a3");
        long t0 = System.nanoTime();
        n = fetch(server(new Mode() {
            public void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception { Thread.sleep(8000); }
        }), out, 1000, 1500);
        long ms = (System.nanoTime() - t0) / 1000000;
        check("a server that goes quiet: the read times out (" + n + ", " + ms + " ms)", n == -10 && ms < 6000);

        out = new File(d, "a4");
        t0 = System.nanoTime();
        n = fetch(server(new Mode() {
            public void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception { o.write(whole, 0, 20); o.flush(); Thread.sleep(8000); }
        }), out, 1000, 1500);
        ms = (System.nanoTime() - t0) / 1000000;
        check("quiet mid-answer: times out, CUT (" + n + ", " + ms + " ms)", n == NativeFetch.CUT && ms < 6000 && out.length() == 20);

        out = new File(d, "a5");
        final byte[] big = new byte[50000];
        n = fetch(server(new Mode() {
            public void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception { o.write(big); o.flush(); closeTls(s, raw); }
        }), out, 1000, 5000);
        check("past the cap: -9 (" + n + ")", n == -9 && out.length() <= 1000);

        out = new File(d, "a6");
        Files.write(out.toPath(), "mine".getBytes());
        n = fetch(server(new Mode() {
            public void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception { o.write(whole); o.flush(); closeTls(s, raw); }
        }), out, 1000, 5000);
        check("an answer file that's there: not written over (O_EXCL, " + n + ")", n == -8 && "mine".equals(new String(Files.readAllBytes(out.toPath()))));

        File target = new File(d, "target"), link = new File(d, "link");
        Files.createSymbolicLink(link.toPath(), target.toPath());
        n = fetch(server(new Mode() {
            public void serve(Socket raw, SSLSocket s, OutputStream o) throws Exception { o.write(whole); o.flush(); closeTls(s, raw); }
        }), link, 1000, 5000);
        check("an answer file that's a link: not followed (" + n + ")", n == -8 && !target.exists());

        n = NativeFetch.fetch(ssl, "libc", "localhost", "443", whole, new File(d, "a7").getPath(), 1000, 5000);
        check("no address (the system resolver is never used): refused (" + n + ")", n == -11);

        // DNS: one UDP exchange
        final DatagramSocket us = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread() {
            @Override
            public void run() {
                try {
                    byte[] b = new byte[1500];
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    us.receive(p);
                    byte[] r = new byte[p.getLength() + 1];
                    System.arraycopy(b, 0, r, 0, p.getLength());
                    r[p.getLength()] = 42;
                    us.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
                } catch (Exception x) { }
            }
        };
        t.setDaemon(true);
        t.start();
        out = new File(d, "dns1");
        n = NativeFetch.dns("libc", "127.0.0.1", String.valueOf(us.getLocalPort()), "query".getBytes(), out.getPath(), 2000);
        check("DNS: the answer written (" + n + ")", n == 6 && "query*".equals(new String(Files.readAllBytes(out.toPath()))));
        us.close();
        final DatagramSocket quiet = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        t0 = System.nanoTime();
        n = NativeFetch.dns("libc", "127.0.0.1", String.valueOf(quiet.getLocalPort()), "query".getBytes(), new File(d, "dns2").getPath(), 1000);
        ms = (System.nanoTime() - t0) / 1000000;
        check("DNS: no answer times out (" + n + ", " + ms + " ms)", n == -10 && ms < 5000 && !new File(d, "dns2").exists());
        quiet.close();
        n = NativeFetch.dns("libc", "dns.example", "53", "q".getBytes(), new File(d, "dns3").getPath(), 1000);
        check("DNS: a server that isn't an address refused (" + n + ")", n == -3);
        System.exit(failed == 0 ? 0 : 1);
    }
}
