package hmgapi.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A host's IPv4 addresses, asked of home Wi-Fi's own DNS server (dhcp.wlan0.dns1) over UDP, for the
 * engine's downloads (NativeFetch). Not the system resolver: the engine runs as root outside netd's
 * per-app setup, where lookups would go to the car's carrier DNS on mobile data. wifi.sh routes
 * wlan0's DNS servers through wlan0, so this query goes out on home Wi-Fi or not at all.
 */
final class DnsLookup {
    private DnsLookup() {}

    static final int TRIES = 3, TIMEOUT_MS = 3000;

    /** the server: home Wi-Fi's DHCP DNS, or its gateway; null when home Wi-Fi gave neither */
    static String server(Engine e) {
        for (String p : new String[] {"dhcp.wlan0.dns1", "dhcp.wlan0.gateway"}) {
            String v = e.getprop(p);
            if (v != null && v.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return v;
        }
        return null;
    }

    /** host's A records (dotted), first answer first; empty if none */
    static List<String> lookup(String server, String host) throws IOException {
        Random rnd = new Random();
        IOException last = null;
        for (int t = 0; t < TRIES; t++) {
            int id = rnd.nextInt(0x10000);
            byte[] q = query(id, host);
            DatagramSocket s = new DatagramSocket();
            try {
                s.setSoTimeout(TIMEOUT_MS);
                s.connect(InetAddress.getByAddress(dotted(server)), 53);
                s.send(new DatagramPacket(q, q.length));
                byte[] r = new byte[1500];
                DatagramPacket p = new DatagramPacket(r, r.length);
                s.receive(p);
                return answers(r, p.getLength(), id);
            } catch (IOException e) {
                last = e;
            } finally {
                s.close();
            }
        }
        throw last != null ? last : new IOException("no answer");
    }

    static byte[] query(int id, String host) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8); o.write(id); o.write(1); o.write(0);   // recursion desired
        o.write(0); o.write(1); for (int i = 0; i < 6; i++) o.write(0);
        for (String l : host.split("\\.")) {
            if (l.isEmpty() || l.length() > 63) throw new IllegalArgumentException("bad host " + host);
            o.write(l.length());
            for (int i = 0; i < l.length(); i++) o.write(l.charAt(i));
        }
        o.write(0); o.write(0); o.write(1); o.write(0); o.write(1);   // A, IN
        return o.toByteArray();
    }

    /** the A records of an answer to query id; a wrong id or an error code is an IOException */
    static List<String> answers(byte[] r, int n, int id) throws IOException {
        if (n < 12 || u16(r, 0) != id || (r[2] & 0x80) == 0) throw new IOException("not an answer to this query");
        int rcode = r[3] & 0xf;
        if (rcode == 3) return new ArrayList<String>();   // no such name
        if (rcode != 0) throw new IOException("DNS error " + rcode);
        int qd = u16(r, 4), an = u16(r, 6), off = 12;
        for (int i = 0; i < qd; i++) off = skipName(r, n, off) + 4;
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < an; i++) {
            off = skipName(r, n, off);
            if (off + 10 > n) throw new IOException("short answer");
            int type = u16(r, off), klass = u16(r, off + 2), len = u16(r, off + 8);
            off += 10;
            if (off + len > n) throw new IOException("short answer");
            if (type == 1 && klass == 1 && len == 4)
                out.add((r[off] & 0xff) + "." + (r[off + 1] & 0xff) + "." + (r[off + 2] & 0xff) + "." + (r[off + 3] & 0xff));
            off += len;
        }
        return out;
    }

    private static int skipName(byte[] r, int n, int off) throws IOException {
        for (int guard = 0; guard < 128; guard++) {
            if (off >= n) throw new IOException("short answer");
            int l = r[off] & 0xff;
            if (l == 0) return off + 1;
            if ((l & 0xc0) == 0xc0) return off + 2;   // a pointer ends the name
            off += 1 + l;
        }
        throw new IOException("bad name");
    }

    private static int u16(byte[] r, int off) {
        return ((r[off] & 0xff) << 8) | (r[off + 1] & 0xff);
    }

    private static byte[] dotted(String a) {
        String[] p = a.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(p[i]);
        return b;
    }
}
