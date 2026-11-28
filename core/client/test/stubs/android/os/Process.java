package android.os;

/** test stand-in: the caller's uid from -Dtest.uid (an app's by default) */
public final class Process {
    public static int myUid() {
        return Integer.getInteger("test.uid", 10071);
    }
}
