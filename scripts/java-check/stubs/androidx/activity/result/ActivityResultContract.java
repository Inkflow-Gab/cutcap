package androidx.activity.result;
import android.content.Intent;
public abstract class ActivityResultContract<I, O> {
  public abstract Intent createIntent(android.content.Context context, I input);
  public abstract O parseResult(int resultCode, Intent intent);
}
