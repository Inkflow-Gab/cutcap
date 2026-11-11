package androidx.activity.result;
import android.content.Intent;
public final class ActivityResult {
  private final int resultCode; private final Intent data;
  public ActivityResult(int resultCode, Intent data) { this.resultCode = resultCode; this.data = data; }
  public int getResultCode() { return resultCode; }
  public Intent getData() { return data; }
}
