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
        /**
         * sends request to host:443 over TLS and writes the raw answer to rawOut (a new file: it must not
         * exist yet). Bytes written when the server ended the answer itself (a TLS close); CUT when the
         * connection ended any other way (dropped, a read error, a timeout), what came in rawOut; any
         * other negative: an error, nothing to read
         */
        int exchange(String host, byte[] request, File rawOut, int maxBytes);
    }

    static final int CUT = NativeFetch.CUT;

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
    private final File tmpDir;

    Http(Transport transport, File tmpDir) {
        this.transport = transport;
        this.tmpDir = tmpDir;
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
            File tmp = NativeFetch.fresh(tmpDir, "answer");   // one per call: gets may run at once
            int n = transport.exchange(host, request, tmp, maxBytes + OVERHEAD);
            try {
                if (n < 0 && n != CUT) return new Result(0, host + ": " + NativeFetch.why(n));
                boolean cut = n == CUT;
                InputStream in = new BufferedInputStream(new FileInputStream(tmp));
                try {
                    String statusLine = line(in);
                    String[] sp = statusLine == null ? new String[0] : statusLine.split(" ");
                    if (sp.length < 2 || !sp[0].startsWith("HTTP/1.")) return new Result(0, cut ? host + ": " + NativeFetch.why(CUT) : "not an HTTP answer");
                    int status;
                    try {
                        status = Integer.parseInt(sp[1]);
                    } catch (NumberFormatException e) {
                        return new Result(0, "not an HTTP answer");
                    }
                    Map<String, String> headers = new HashMap<String, String>();
                    for (String l = line(in); ; l = line(in)) {
                        if (l == null) return new Result(0, host + ": " + NativeFetch.why(CUT) + " (in its headers)");
                        if (l.isEmpty()) break;
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
                    return body(in, headers, cut, out, maxBytes);
                } finally {
                    in.close();
                }
            } finally {
                tmp.delete();
            }
        }
        return new Result(0, "too many redirects");
    }

    /**
     * a 200's body into out, only if it is whole: its Content-Length met, or its last chunk read, or
     * (with neither) the server ended the answer itself. A connection that ended any other way (cut)
     * with no length to check is not a whole answer. Nothing is left behind when it isn't.
     */
    private static Result body(InputStream in, Map<String, String> headers, boolean cut, File out, int maxBytes) throws IOException {
        boolean chunked = "chunked".equalsIgnoreCase(headers.get("transfer-encoding"));
        String len = chunked ? null : headers.get("content-length");
        long want = -1;
        if (len != null) {
            if (!len.matches("\\d{1,12}")) return new Result(200, "bad Content-Length: " + len);
            want = Long.parseLong(len);
            if (want > maxBytes) return new Result(200, "larger than " + maxBytes + " bytes");
        }
        File part = new File(out.getPath() + ".part");
        part.delete();
        boolean done = false;
        try {
            long got;
            OutputStream o = new FileOutputStream(part);
            try {
                got = chunked ? chunked(in, o, maxBytes) : copy(in, o, maxBytes);
            } finally {
                o.close();
            }
            if (got == -1) return new Result(200, "larger than " + maxBytes + " bytes");
            if (want >= 0 && got != want) return new Result(200, "cut off: " + got + " of " + want + " bytes");
            if (want < 0 && !chunked && cut) return new Result(200, "cut off: the connection ended after " + got + " bytes, with no length to check");
            if (!part.renameTo(out)) return new Result(200, "can't write " + out);
            done = true;
            return new Result(200, null);
        } catch (IOException x) {
            return new Result(200, "cut off: " + x.getMessage());
        } finally {
            if (!done) part.delete();
        }
    }

    /** a line without its CRLF; null at the end of the answer with no line break (a cut-off line) */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c = in.read(); ; c = in.read()) {
            if (c < 0) return null;
            if (c == '\n') break;
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

    /** a chunk's size: hex digits only (no sign, no 0x), at most 7 of them; anything else is an IOException */
    static int chunkSize(String l) throws IOException {
        if (l == null) throw new IOException("the chunks ended early");
        int semi = l.indexOf(';');
        String h = (semi < 0 ? l : l.substring(0, semi)).trim();
        if (h.isEmpty() || h.length() > 7) throw new IOException("bad chunk size");
        int size = 0;
        for (int i = 0; i < h.length(); i++) {
            char ch = h.charAt(i);
            int d = ch >= '0' && ch <= '9' ? ch - '0' : ch >= 'a' && ch <= 'f' ? ch - 'a' + 10 : ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
            if (d < 0) throw new IOException("bad chunk size");
            size = size * 16 + d;
        }
        return size;
    }

    /** body bytes, -1 past the cap; an IOException unless every chunk and the last (0) one are there */
    private static long chunked(InputStream in, OutputStream out, int max) throws IOException {
        long total = 0;
        byte[] buf = new byte[8192];
        for (;;) {
            int size = chunkSize(line(in));
            if (size == 0) {
                for (String t = line(in); t == null || !t.isEmpty(); t = line(in)) {   // trailers, then the blank line
                    if (t == null) throw new IOException("the chunks ended early");
                }
                return total;
            }
            if (size > max - total) return -1;
            total += size;
            for (int left = size; left > 0; ) {
                int n = in.read(buf, 0, Math.min(left, buf.length));
                if (n <= 0) throw new IOException("chunk cut off");
                out.write(buf, 0, n);
                left -= n;
            }
            String crlf = line(in);
            if (crlf == null || !crlf.isEmpty()) throw new IOException("bad chunk end");
        }
    }
}
