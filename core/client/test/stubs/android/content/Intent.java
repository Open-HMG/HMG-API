package android.content;

/** test stand-in */
public class Intent {
    public Intent(String action) {}

    public Intent putExtra(String k, String v) { return this; }

    public Intent putExtra(String k, boolean v) { return this; }
}
