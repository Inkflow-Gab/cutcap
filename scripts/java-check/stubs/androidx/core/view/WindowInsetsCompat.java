package androidx.core.view;
public final class WindowInsetsCompat {
  public static final int TYPE_SYSTEM_BARS = 1;
  public static final int TYPE_DISPLAY_CUTOUT = 2;
  public static final int TYPE_IME = 4;
  public static final class Type {
    public static int systemBars() { return 1; }
    public static int displayCutout() { return 2; }
    public static int ime() { return 4; }
  }
  public Insets getInsets(int typeMask) { return null; }
  public static final class Insets {
    public final int left, top, right, bottom;
    private Insets(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
  }
}
