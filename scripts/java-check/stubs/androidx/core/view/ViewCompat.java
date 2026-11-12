package androidx.core.view;
import android.view.View;
public final class ViewCompat {
  public interface OnApplyWindowInsetsListener { WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets); }
  public static void setOnApplyWindowInsetsListener(View view, OnApplyWindowInsetsListener listener) {}
}
