package android.net;

/** test stand-in */
public class LocalSocketAddress {
    public enum Namespace { ABSTRACT, RESERVED, FILESYSTEM }

    final String name;

    public LocalSocketAddress(String name, Namespace ns) {
        this.name = name;
    }
}
