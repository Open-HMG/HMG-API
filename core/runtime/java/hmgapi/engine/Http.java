package hmgapi.engine;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTPS GET to a file, for the catalogue and updates (Catalog), over a transport that only moves
 * bytes (NativeFetch on the unit, files in the off-car tests). Only https, only GitHub's hosts,
 * redirects followed between them, a size cap. Nothing is trusted because of where it came from
 * (see NativeFetch).
 */
final class Http {
    interface Transport {
        /** sends request to host:443 over TLS and writes the whole raw answer to rawOut; bytes written, or a negative error */
        int exchange(String host, byte[] request, File rawOut, int maxBytes);
    }

    static final Set<String> HOSTS = new HashSet<String>(Arrays.asList("github.com", "api.github.com", "raw.githubusercontent.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com", "codeload.github.com"));
    static final int MAX_REDIRECTS = 5;
    /** headers and chunk framing on top of the body's cap */
    static final int OVERHEAD = 65536;

    static final class Result {
        final int status;
        final String error;

        Result(int status, String error) {
            this.status = status;
            this.error = error;
        }

        boolean ok() {
            return status == 200 && error == null;
        }

        @Override
        public String toString() {
            return error != null ? error : "HTTP " + status;
        }
    }

    private final Transport transport;
    private final File tmp;

    Http(Transport transport, File tmpDir) {
        this.transport = transport;
        this.tmp = new File(tmpDir, "answer.raw");
    }

    /** GET url into out (replaced only on a 200); maxBytes: the body's cap */
    Result get(String url, File out, int maxBytes) throws IOException {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!url.startsWith("https://")) return new Result(0, "not https: " + url);
            String rest = url.substring(8);
            int slash = rest.indexOf('/');
            String host = slash < 0 ? rest : rest.substring(0, slash), path = slash < 0 ? "/" : rest.substring(slash);
            if (!HOSTS.contains(host)) return new Result(0, "host not allowed: " + host);
            if (path.indexOf(' ') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) return new Result(0, "bad path");
            byte[] request = ("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: HMG-API/" + Engine.HMGAPI_VERSION + "\r\n"
                    + ("api.github.com".equals(host) ? "Accept: application/vnd.github+json\r\nX-GitHub-Api-Version: 2022-11-28\r\n" : "Accept: */*\r\n")
                    + "Accept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes("US-ASCII");
            int n = transport.exchange(host, request, tmp, maxBytes + OVERHEAD);
            try {
                if (n < 0) return new Result(0, host + ": " + NativeFetch.why(n));
                InputStream in = new BufferedInputStream(new FileInputStream(tmp));
                try {
                    String statusLine = line(in);
                    String[] sp = statusLine.split(" ");
                    if (sp.length < 2 || !sp[0].startsWith("HTTP/1.")) return new Result(0, "not an HTTP answer");
                    int status;
                    try {
                        status = Integer.parseInt(sp[1]);
                    } catch (NumberFormatException e) {
                        return new Result(0, "not an HTTP answer");
                    }
                    Map<String, String> headers = new HashMap<String, String>();
                    for (String l = line(in); !l.isEmpty(); l = line(in)) {
                        int c = l.indexOf(':');
                        if (c > 0) headers.put(l.substring(0, c).trim().toLowerCase(Locale.US), l.substring(c + 1).trim());
                    }
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String to = headers.get("location");
                        if (to == null) return new Result(status, "redirect without a location");
                        url = to.startsWith("/") ? "https://" + host + to : to;
                        continue;
                    }
                    if (status != 200) return new Result(status, null);
                    File part = new File(out.getPath() + ".part");
                    OutputStream o = new FileOutputStream(part);
                    long got;
                    try {
                        got = "chunked".equalsIgnoreCase(headers.get("transfer-encoding")) ? chunked(in, o, maxBytes) : copy(in, o, maxBytes);
                    } finally {
                        o.close();
                    }
                    String len = headers.get("content-length");
                    if (got < 0 || (len != null && !len.equals(String.valueOf(got)))) {
                        part.delete();
                        return new Result(status, got < 0 ? "larger than " + maxBytes + " bytes" : "cut off: " + got + " of " + len + " bytes");
                    }
                    if (!part.renameTo(out)) {
                        part.delete();
                        return new Result(status, "can't write " + out);
                    }
                    return new Result(200, null);
                } finally {
                    in.close();
                }
            } finally {
                tmp.delete();
            }
        }
        return new Result(0, "too many redirects");
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c = in.read(); c >= 0 && c != '\n'; c = in.read()) {
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header line too long");
        }
        return b.toString("ISO-8859-1");
    }

    /** bytes copied, or -1 past the cap */
    private static long copy(InputStream in, OutputStream out, int max) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        for (int n = in.read(buf); n > 0; n = in.read(buf)) {
            total += n;
            if (total > max) return -1;
            out.write(buf, 0, n);
        }
        return total;
    }

    private static long chunked(InputStream in, OutputStream out, int max) throws IOException {
        long total = 0;
        byte[] buf = new byte[8192];
        for (;;) {
            String l = line(in);
            int semi = l.indexOf(';');
            int size;
            try {
                size = Integer.parseInt((semi < 0 ? l : l.substring(0, semi)).trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size");
            }
            if (size == 0) return total;
            total += size;
            if (total > max) return -1;
            for (int left = size; left > 0; ) {
                int n = in.read(buf, 0, Math.min(left, buf.length));
                if (n <= 0) throw new IOException("chunk cut off");
                out.write(buf, 0, n);
                left -= n;
            }
            line(in);   // the CRLF after the chunk
        }
    }
}
