package androidx.activity.result;
public abstract class ActivityResultRegistry {
  public final <I, O> ActivityResultLauncher<I> register(String key, ActivityResultContract<I, O> contract, ActivityResultCallback<O> callback) { return null; }
}
