package androidx.core.content;
import android.content.Context;
import android.net.Uri;
import java.io.File;
public class FileProvider extends android.content.ContentProvider {
  public static Uri getUriForFile(Context context, String authority, File file) { return null; }
  public boolean onCreate() { return true; }
  public android.database.Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
  public String getType(Uri uri) { return null; }
  public Uri insert(Uri uri, android.content.ContentValues values) { return null; }
  public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
  public int update(Uri uri, android.content.ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
