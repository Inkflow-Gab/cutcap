package com.motion.official;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Cached thumbnails for gallery rows.
 *
 * A phone gallery is a grid of thousands of pictures, and the WebView can only draw them from
 * files it is allowed to read. This turns a MediaStore row into one small JPEG the first time it
 * is looked at, then hands back that file forever after.
 *
 * The size is the point. Decoding a 12 MP photo to fill a 120 px square is what makes a gallery
 * stutter; the system's own loader on API 29+ never decodes more than the thumbnail, and the
 * fallback below scales down before anything is written to disk.
 */
final class Thumbnails {

    /** Enough for three tiles across on a high-density phone without shrinking visibly. */
    private static final int SIDE = 320;

    private Thumbnails() {}

    static File dir(Context context) {
        // Under the app's cache, so Settings → Clear cache takes them and they cost nothing to
        // rebuild: they are derived from the gallery, never the only copy of anything.
        File d = new File(context.getFilesDir(), "cache/thumbs");
        if (!d.exists() && !d.mkdirs()) return null;
        return d;
    }

    /** The thumbnail if it has already been made, or null if it has not. Cheap: one exists(). */
    static File cached(Context context, String kind, long id) {
        File d = dir(context);
        if (d == null) return null;
        File f = new File(d, kind + "-" + id + ".jpg");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /**
     * Makes the thumbnail, or returns null when the row cannot be read.
     *
     * A null is a real answer: the photo may have been deleted, may sit on a card that has been
     * removed, or may be a format the system cannot decode. The tile shows a placeholder rather
     * than a broken image, and nothing else in the grid is affected.
     */
    static File make(Context context, String kind, long id) {
        File d = dir(context);
        if (d == null) return null;
        Uri uri = ContentUris.withAppendedId(collectionFor(kind), id);
        ContentResolver resolver = context.getContentResolver();
        Bitmap bmp = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                bmp = resolver.loadThumbnail(uri, new android.util.Size(SIDE, SIDE), null);
            } else {
                bmp = decodeScaled(resolver, uri);
            }
            if (bmp == null) return null;
            // Written to a temp file and renamed, so a request that dies halfway — the app going
            // to the background mid-scroll — cannot leave a truncated JPEG to be served forever.
            File tmp = new File(d, kind + "-" + id + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            File f = new File(d, kind + "-" + id + ".jpg");
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return null;
            }
            return f;
        } catch (Exception e) {
            // The row is gone, or unreadable, or the card was pulled. Not an error worth logging
            // for every tile: the grid simply shows what it can.
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** API 26–28 have no loadThumbnail, so decode with a sample size that stays near SIDE. */
    private static Bitmap decodeScaled(ContentResolver resolver, Uri uri) throws java.io.IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap full;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            full = BitmapFactory.decodeStream(in, null, opts);
        }
        if (full == null) return null;
        int w = full.getWidth();
        int h = full.getHeight();
        int side = Math.min(w, h);
        if (side <= SIDE) return full;
        float scale = (float) SIDE / side;
        Bitmap small = Bitmap.createScaledBitmap(full, Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), true);
        if (small != full) full.recycle();
        return small;
    }

    /** The same table MotionNativePlugin uses; kept here so a thumbnail needs no plugin instance. */
    private static Uri collectionFor(String kind) {
        if ("video".equals(kind)) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }
}
