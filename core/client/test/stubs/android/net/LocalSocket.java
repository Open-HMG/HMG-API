package android.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** test stand-in: the request goes to hmgapi.client.FakeDaemon, which makes the answer */
public class LocalSocket {
    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    public void connect(LocalSocketAddress a) throws IOException {
        if (!"hmgapid".equals(a.name)) throw new IOException("no such socket: " + a.name);
    }

    public void setSoTimeout(int ms) {}

    public OutputStream getOutputStream() {
        return sent;
    }

    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(hmgapi.client.FakeDaemon.answer(sent.toByteArray()));
    }

    public void close() {}
}
