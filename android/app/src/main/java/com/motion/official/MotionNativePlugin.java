package com.motion.official;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The phone end of `window.openroll`.
 *
 * The renderer is the desktop one, unchanged, so this behaves like src/main/* does: picked or
 * shared media is copied into the app's own store and addressed by paths, projects save
 * atomically with a .bak and a rolling journal beside them, and an export streams through a
 * positioned writer so a long file never sits in memory. Paths handed to the page are real
 * file paths on this device; the page reads them back as URLs that MediaRangeClient serves
 * with proper Range answers, which is what video seeking needs.
 *
 * Nothing here listens on a socket and no file leaves the device.
 */
@CapacitorPlugin(
    name = "MotionNative",
    permissions = {
        // Android 13 and later grant this instead of a storage permission. Older versions read
        // the gallery through READ_EXTERNAL_STORAGE, which the manifest caps at API 32.
        @Permission(alias = "images", strings = { "android.permission.READ_MEDIA_IMAGES" }),
        @Permission(alias = "video", strings = { "android.permission.READ_MEDIA_VIDEO" }),
        @Permission(alias = "storage", strings = { "android.permission.READ_EXTERNAL_STORAGE" })
    }
)
public class MotionNativePlugin extends Plugin {

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Object lock = new Object();
    private final Map<Integer, Writer> writers = new HashMap<>();
    private int nextWriter = 1;

    // ---------------------------------------------------------------- storage layout

    private File root() {
        return new File(getContext().getFilesDir(), "motion");
    }

    private File dir(String name) {
        File f = new File(root(), name);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    private File mediaDir() {
        return dir("media");
    }

    private File projectDir() {
        return dir("projects");
    }

    private File cacheDir() {
        return dir("cache");
    }

    private File journalDir() {
        return dir("journal");
    }

    private File versionsDir() {
        return dir("versions");
    }

    private File templatesDir() {
        return dir("templates");
    }

    private File stateDir() {
        return dir("state");
    }

    File exportDir() {
        return dir("exports");
    }

    /** Where Android shares files from; MediaRangeClient also serves these. */
    File sharedDir() {
        File f = new File(getContext().getCacheDir(), "motion-shared");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    // ---------------------------------------------------------------- urls

    /**
     * The URL the WebView fetches bytes from. The pathname is the absolute file path with its
     * leading slash dropped and every segment encoded, which MediaRangeClient decodes back.
     */
    String mediaUrlFor(String path) {
        return mediaUrl(path);
    }

    /**
     * A URL for a file, or for a gallery thumbnail.
     *
     * A `thumb:` path is not a file: it names a MediaStore row, and MediaRangeClient makes the
     * JPEG when the WebView actually asks for it. That matters — building URLs for a listing must
     * never decode the photos in it, or opening an album of 4000 pictures would decode 4000 of
     * them before the first tile appeared.
     */
    static String mediaUrl(String path) {
        String p = path.startsWith("file://") ? Uri.parse(path).getPath() : path;
        if (p == null) p = "";
        String rel = p.startsWith("/") ? p.substring(1) : p;
        return "https://localhost/motion-media/" + Uri.encode(rel, "/");
    }

    // ---------------------------------------------------------------- gallery

    /**
     * The device's own gallery, read through MediaStore.
     *
     * Android's document picker shows a file browser, not photos: no thumbnails, no albums, no
     * sense of what is recent. A phone editor has to show the gallery the phone already has, so
     * this reads MediaStore instead — albums for the tab row, every image and video for the grid.
     *
     * The rows are returned as `id`, not as content URIs, so the renderer can ask for a thumbnail
     * of one and import only the ones that were actually chosen.
     */
    @PluginMethod
    public void galleryPickAlbum(PluginCall call) {
        String kind = call.getString("kind", "image");
        if (!galleryAllowed(call, "galleryAlbums", kind)) return;
        io.execute(() -> {
            try {
                call.resolve(galleryAlbums(kind));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void galleryList(PluginCall call) {
        String kind = call.getString("kind", "image");
        if (!galleryAllowed(call, "galleryItems", kind)) return;
        io.execute(() -> {
            try {
                call.resolve(galleryItems(kind));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    /** Brings chosen gallery rows into app storage and describes them like any other import. */
    @PluginMethod
    public void galleryImport(PluginCall call) {
        JSArray ids = call.getArray("ids");
        String kind = call.getString("kind", "image");
        if (ids == null || ids.length() == 0) {
            call.resolve(new JSObject().put("files", new JSArray()));
            return;
        }
        io.execute(() -> {
            JSArray files = new JSArray();
            int failed = 0;
            for (int i = 0; i < ids.length(); i++) {
                long id = ids.optLong(i, -1);
                if (id < 0) {
                    failed++;
                    continue;
                }
                try {
                    files.put(importUri(uriFor(kind, id)));
                } catch (Exception e) {
                    failed++;
                }
            }
            JSObject out = new JSObject().put("files", files);
            if (failed > 0) out.put("failed", failed);
            call.resolve(out);
        });
    }

    /**
     * Guards a gallery read: true when the read may go ahead.
     *
     * Three outcomes, and the difference matters. Granted: read. Not answered yet: the system
     * dialog goes up and the callback re-runs the caller, so nothing is settled here. Answered
     * no: reject, because a promise that never settles leaves the sheet spinning over an empty
     * grid forever.
     *
     * The gate is per kind — a person who allows photos and declines videos still gets their
     * photos — but the first dialog asks for both, so tapping between the tabs does not raise a
     * second prompt straight after the first.
     */
    private boolean galleryAllowed(PluginCall call, String callback, String kind) {
        String alias = aliasFor(kind);
        if (has(alias)) return true;
        PermissionState state = getPermissionState(alias);
        if (state != PermissionState.PROMPT && state != PermissionState.PROMPT_WITH_RATIONALE) {
            // Answered no, or the alias is not declared at all. Both mean waiting will not help.
            call.reject(kind.equals("video")
                ? "Allow access to videos in Settings to browse your videos."
                : "Allow access to photos in Settings to browse your photos.");
            return false;
        }
        // Below 33 there is one permission for both kinds. Above it, asking for the storage
        // permission raises a dialog the system never shows, so the request would come back
        // empty and the callback would never fire: the aliases have to match the API level.
        String[] aliases = Build.VERSION.SDK_INT >= 33
            ? new String[] { "images", "video" }
            : new String[] { "storage" };
        requestPermissionForAliases(aliases, call, callback);
        return false;
    }

    /** The permission this kind of item is actually read with. */
    private static String aliasFor(String kind) {
        if (Build.VERSION.SDK_INT < 33) return "storage";
        return kind.equals("video") ? "video" : "images";
    }

    private boolean has(String alias) {
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    @PermissionCallback
    private void galleryAlbums(PluginCall call) {
        galleryPickAlbum(call);
    }

    @PermissionCallback
    private void galleryItems(PluginCall call) {
        galleryList(call);
    }

    private static Uri collectionFor(String kind) {
        if (kind.equals("video")) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if (kind.equals("audio")) return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    private static Uri uriFor(String kind, long id) {
        return ContentUris.withAppendedId(collectionFor(kind), id);
    }

    private JSObject galleryAlbums(String kind) throws JSONException {
        Uri collection = collectionFor(kind);
        String[] cols = {
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns._ID
        };
        String order = MediaStore.MediaColumns.DATE_ADDED + " DESC";
        // Newest first, so the first row seen for a bucket is that album's cover. A linked map
        // keeps the albums in that same order — the one the person browsed — while counts are
        // still being added up, so nothing has to be rebuilt afterwards.
        Map<String, JSObject> found = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> covers = new HashMap<>();
        try (Cursor c = getContext().getContentResolver().query(collection, cols, null, null, order)) {
            if (c == null) return new JSObject().put("albums", new JSArray());
            while (c.moveToNext()) {
                String bucket = c.getString(0);
                if (bucket == null) continue;
                Integer seen = counts.get(bucket);
                if (seen == null) {
                    JSObject album = new JSObject();
                    album.put("id", bucket);
                    album.put("name", c.getString(1) != null ? c.getString(1) : "Other");
                    found.put(bucket, album);
                    covers.put(bucket, c.getLong(2));
                }
                counts.put(bucket, seen == null ? 1 : seen + 1);
            }
        }
        JSArray albums = new JSArray();
        for (Map.Entry<String, JSObject> e : found.entrySet()) {
            String bucket = e.getKey();
            JSObject album = e.getValue();
            Integer n = counts.get(bucket);
            album.put("count", n == null ? 0 : n.intValue());
            Long cover = covers.get(bucket);
            album.put("cover", cover == null ? "" : mediaUrlFor("thumb:" + kind + ":" + cover.longValue()));
            albums.put(album);
        }
        return new JSObject().put("albums", albums);
    }

    private JSObject galleryItems(String kind) throws JSONException {
        Uri collection = collectionFor(kind);
        String idCol = MediaStore.MediaColumns._ID;
        String[] cols = {
            idCol,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.SIZE
        };
        JSArray items = new JSArray();
        String order = MediaStore.MediaColumns.DATE_ADDED + " DESC";
        try (Cursor c = getContext().getContentResolver().query(collection, cols, null, null, order)) {
            if (c == null) return new JSObject().put("items", items);
            int limit = Math.min(c.getCount(), 4000);
            for (int i = 0; i < limit && c.moveToNext(); i++) {
                JSObject o = new JSObject();
                long id = c.getLong(0);
                o.put("id", id);
                o.put("name", c.getString(1) != null ? c.getString(1) : "Untitled");
                o.put("added", c.getLong(2) * 1000L);
                o.put("album", c.getString(3) != null ? c.getString(3) : "");
                o.put("size", c.getLong(4));
                // The URL names the row; the handler makes the JPEG on the first request.
                // Generating them here would mean decoding 4000 photos before the grid appeared.
                o.put("thumb", mediaUrl("thumb:" + kind + ":" + id));
                items.put(o);
            }
        }
        return new JSObject().put("items", items);
    }

    // ---------------------------------------------------------------- picking

    /**
     * The MIME types the editor can actually open, mirroring MEDIA_EXT in src/main/main.ts. A
     * phone picker with a wildcard type shows every file on the device, most of which the editor
     * will refuse; narrowing it here means the wrong choice is never offered.
     */
    private static final String[] MEDIA_TYPES = {
        "video/*", "audio/*", "image/*",
        // Containers Android does not tag as video/* but which decoders handle.
        "application/ogg", "application/x-matroska", "application/vnd.apple.mpegurl",
    };

    @PluginMethod
    public void pickMedia(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, MEDIA_TYPES);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "pickedMedia");
    }

    @ActivityCallback
    private void pickedMedia(PluginCall call, ActivityResult result) {
        if (call == null) return;
        List<Uri> uris = new ArrayList<>();
        Intent data = result.getData();
        if (result.getResultCode() == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        io.execute(() -> {
            JSArray files = new JSArray();
            int failed = 0;
            for (Uri uri : uris) {
                try {
                    files.put(importUri(uri));
                } catch (Exception e) {
                    // One unreadable pick must not lose the rest.
                    failed++;
                }
            }
            JSObject out = new JSObject().put("files", files);
            // A whole batch that failed must not look like the person cancelled the picker.
            if (failed > 0) out.put("failed", failed);
            call.resolve(out);
        });
    }

    /** Copies a document into the app store and describes it the way FileInfo expects. */
    JSObject importUri(Uri uri) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        String name = queryName(resolver, uri);
        if (name == null || name.isEmpty()) name = "clip";
        File target = uniqueIn(mediaDir(), digest(uri + ":" + System.nanoTime()) + extensionOf(name));
        try (InputStream in = new BufferedInputStream(resolver.openInputStream(uri));
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("That file could not be read.");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return describe(target, name);
    }

    private String queryName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
    }

    private JSObject describe(File f, String displayName) {
        JSObject o = new JSObject();
        o.put("path", f.getAbsolutePath());
        o.put("name", displayName != null ? displayName : f.getName());
        o.put("size", f.length());
        o.put("mtime", f.lastModified());
        return o;
    }

    @PluginMethod
    public void pathsForDropped(PluginCall call) {
        // Phones have no desktop drop; shared content arrives through the share sheet already imported.
        call.resolve(new JSObject().put("files", new JSArray()));
    }

    /** A document another app shared into OpenRoll: copied in, then described like a pick. */
    @PluginMethod
    public void importShared(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) {
            call.reject("Nothing was shared.");
            return;
        }
        io.execute(() -> {
            try {
                JSArray files = new JSArray();
                files.put(importUri(Uri.parse(uri)));
                call.resolve(new JSObject().put("files", files));
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "That file could not be opened.");
            }
        });
    }

    @PluginMethod
    public void stat(PluginCall call) {
        String path = call.getString("path");
        File f = path == null ? null : new File(stripScheme(path));
        if (f == null || !f.exists() || !f.isFile()) {
            call.resolve(new JSObject().put("file", null));
            return;
        }
        call.resolve(new JSObject().put("file", describe(f, f.getName())));
    }

    @PluginMethod
    public void pickLut(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(call, intent, "pickedLut");
    }

    @ActivityCallback
    private void pickedLut(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Uri uri = result.getResultCode() == Activity.RESULT_OK && result.getData() != null ? result.getData().getData() : null;
        if (uri == null) {
            call.resolve(new JSObject().put("lut", null));
            return;
        }
        io.execute(() -> {
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                JSObject lut = new JSObject();
                lut.put("name", queryName(getContext().getContentResolver(), uri));
                lut.put("text", new String(readAll(in), StandardCharsets.UTF_8));
                call.resolve(new JSObject().put("lut", lut));
            } catch (Exception e) {
                call.reject("The colour table could not be read.");
            }
        });
    }

    // ---------------------------------------------------------------- writers

    private static final class Writer {
        RandomAccessFile file;
        File target;
        String name;
    }

    @PluginMethod
    public void createWriter(PluginCall call) {
        String name = call.getString("suggestedName", "export");
        io.execute(() -> {
            try {
                File target = uniqueIn(exportDir(), safeName(name));
                Writer w = new Writer();
                w.file = new RandomAccessFile(target, "rw");
                w.file.setLength(0);
                w.target = target;
                w.name = target.getName();
                int id;
                synchronized (lock) {
                    id = nextWriter++;
                    writers.put(id, w);
                }
                ExportService.begin(getContext(), "Rendering " + w.name);
                JSObject o = new JSObject();
                o.put("id", id);
                o.put("path", target.getAbsolutePath());
                o.put("url", mediaUrlFor(target.getAbsolutePath()));
                call.resolve(o);
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "The export file could not be made.");
            }
        });
    }

    @PluginMethod
    public void write(PluginCall call) {
        Integer id = call.getInt("id");
        long position = call.getLong("position", 0L);
        String data = call.getString("data");
        if (id == null || data == null) {
            call.reject("write needs an id and data");
            return;
        }
        io.execute(() -> {
            try {
                byte[] bytes = Data.decode(data);
                synchronized (lock) {
                    Writer w = writers.get(id);
                    if (w == null) throw new IOException("The export file is not open.");
                    w.file.seek(position);
                    w.file.write(bytes);
                }
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void resetWriter(PluginCall call) {
        Integer id = call.getInt("id");
        io.execute(() -> {
            try {
                synchronized (lock) {
                    Writer w = id == null ? null : writers.get(id);
                    if (w != null) w.file.setLength(0);
                }
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void closeWriter(PluginCall call) {
        Integer id = call.getInt("id");
        boolean abort = Boolean.TRUE.equals(call.getBoolean("abort", false));
        io.execute(() -> {
            try {
                Writer w;
                boolean last;
                synchronized (lock) {
                    w = id == null ? null : writers.remove(id);
                    last = writers.isEmpty();
                }
                if (last) ExportService.end(getContext());
                if (w == null) {
                    call.resolve(new JSObject().put("result", null));
                    return;
                }
                w.file.close();
                if (abort) {
                    //noinspection ResultOfMethodCallIgnored
                    w.target.delete();
                    call.resolve(new JSObject().put("result", null));
                    return;
                }
                JSObject result = describe(w.target, w.name);
                result.put("url", mediaUrlFor(w.target.getAbsolutePath()));
                result.put("mime", MimeTypes.of(w.target));
                call.resolve(new JSObject().put("result", result));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- projects

    @PluginMethod
    public void projectsDir(PluginCall call) {
        call.resolve(new JSObject().put("dir", projectDir().getAbsolutePath()));
    }

    @PluginMethod
    public void projectsList(PluginCall call) {
        io.execute(() -> {
            JSArray out = new JSArray();
            File[] files = projectDir().listFiles((d, n) -> n.endsWith(".motion") && !n.startsWith("."));
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : files) {
                    JSObject o = new JSObject();
                    o.put("path", f.getAbsolutePath());
                    o.put("name", f.getName().substring(0, f.getName().length() - ".motion".length()));
                    o.put("modified", f.lastModified());
                    File thumb = thumbFor(f);
                    if (thumb.exists()) o.put("thumb", thumb.getAbsolutePath());
                    out.put(o);
                }
            }
            call.resolve(new JSObject().put("projects", out));
        });
    }

    private File thumbFor(File project) {
        return new File(project.getPath() + ".thumb");
    }

    @PluginMethod
    public void projectsLoad(PluginCall call) {
        String path = call.getString("path");
        io.execute(() -> {
            try {
                File f = path == null ? null : new File(stripScheme(path));
                call.resolve(new JSObject().put("text", readable(f) ? new String(readBytes(f, 64 * 1024 * 1024), StandardCharsets.UTF_8) : null));
            } catch (Exception e) {
                call.resolve(new JSObject().put("text", null));
            }
        });
    }

    @PluginMethod
    public void projectsSave(PluginCall call) {
        String path = call.getString("path");
        String name = call.getString("name", "Untitled");
        String json = call.getString("json");
        if (json == null) {
            call.reject("Nothing to save.");
            return;
        }
        io.execute(() -> {
            try {
                File target = path == null
                    ? uniqueIn(projectDir(), safeName(name) + ".motion")
                    : new File(stripScheme(path));
                if (target.getParentFile() != null) target.getParentFile().mkdirs();
                writeBytes(target, json.getBytes(StandardCharsets.UTF_8));
                recordVersion(json, target);
                JSObject o = new JSObject();
                o.put("ok", true);
                o.put("path", target.getAbsolutePath());
                call.resolve(o);
            } catch (Exception e) {
                JSObject o = new JSObject();
                o.put("ok", false);
                o.put("error", e.getMessage() != null ? e.getMessage() : "The project could not be saved.");
                call.resolve(o);
            }
        });
    }

    @PluginMethod
    public void projectsSaveThumb(PluginCall call) {
        String path = call.getString("path");
        String jpeg = call.getString("jpeg");
        io.execute(() -> {
            try {
                File f = path == null ? null : new File(stripScheme(path));
                if (f != null && jpeg != null) writeBytes(thumbFor(f), Data.decode(jpeg));
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void projectsRemove(PluginCall call) {
        String path = call.getString("path");
        io.execute(() -> {
            File f = path == null ? null : new File(stripScheme(path));
            if (f != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                //noinspection ResultOfMethodCallIgnored
                thumbFor(f).delete();
                //noinspection ResultOfMethodCallIgnored
                new File(f.getPath() + ".bak").delete();
            }
            call.resolve();
        });
    }

    /**
     * The rolling journal, mirroring src/main/journal.ts: a run of saves inside three minutes
     * folds into one version, the newest twenty stay, and identical text is not stored twice.
     */
    private void recordVersion(String json, File project) {
        try {
            String id = JournalId.of(json);
            if (id == null) return;
            File journal = new File(journalDir(), id);
            journal.mkdirs();
            JSONObject meta = new JSONObject();
            meta.put("path", project.getAbsolutePath());
            meta.put("name", project.getName().replace(".motion", ""));
            writeBytes(new File(journal, "meta.json"), meta.toString().getBytes(StandardCharsets.UTF_8));

            File indexFile = new File(journal, "index.json");
            JSONArray versions = readIndex(indexFile);
            JSONObject newest = versions.length() > 0 ? versions.optJSONObject(0) : null;
            long now = System.currentTimeMillis();
            boolean fold = newest != null && now - newest.optLong("started", 0) < 3 * 60_000L;
            String file = fold ? newest.optString("file") : now + ".json";
            File target = new File(journal, file);
            if (target.exists()) {
                String existing = new String(readBytes(target, 64 * 1024 * 1024), StandardCharsets.UTF_8);
                if (existing.equals(json)) return;
            }
            writeBytes(target, json.getBytes(StandardCharsets.UTF_8));
            if (!fold) {
                JSONObject entry = new JSONObject();
                entry.put("file", file);
                entry.put("time", now);
                entry.put("bytes", json.getBytes(StandardCharsets.UTF_8).length);
                entry.put("started", now);
                versions = prepend(versions, entry);
                while (versions.length() > 20) {
                    JSONObject old = versions.optJSONObject(versions.length() - 1);
                    //noinspection ResultOfMethodCallIgnored
                    new File(journal, old.optString("file")).delete();
                    versions = without(versions, versions.length() - 1);
                }
                writeBytes(indexFile, versions.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // The journal is a safety net; it must never fail the save itself.
        }
    }

    @PluginMethod
    public void historyList(PluginCall call) {
        String project = call.getString("projectPath");
        io.execute(() -> {
            JSArray out = new JSArray();
            try {
                String id = idOfProject(project);
                if (id != null) {
                    JSONArray versions = readIndex(new File(new File(journalDir(), id), "index.json"));
                    for (int i = 0; i < versions.length(); i++) {
                        JSONObject v = versions.getJSONObject(i);
                        JSObject o = new JSObject();
                        o.put("file", v.getString("file"));
                        o.put("time", v.optLong("time"));
                        o.put("bytes", v.optLong("bytes"));
                        out.put(o);
                    }
                }
            } catch (Exception ignored) {
            }
            call.resolve(new JSObject().put("list", out));
        });
    }

    @PluginMethod
    public void historyRestore(PluginCall call) {
        String project = call.getString("projectPath");
        String file = call.getString("file");
        io.execute(() -> {
            try {
                String id = idOfProject(project);
                File f = project == null ? null : new File(stripScheme(project));
                if (id == null || f == null || file == null || !file.matches("^\\d+\\.json$")) {
                    call.resolve(new JSObject().put("restored", false));
                    return;
                }
                File version = new File(new File(journalDir(), id), file);
                if (!version.exists()) {
                    call.resolve(new JSObject().put("restored", false));
                    return;
                }
                // Keep what is on screen as a version first, so a restore can itself be undone.
                if (f.exists()) recordVersion(new String(readBytes(f, 64 * 1024 * 1024), StandardCharsets.UTF_8), f);
                writeBytes(f, readBytes(version, 64 * 1024 * 1024));
                call.resolve(new JSObject().put("restored", true));
            } catch (Exception e) {
                call.resolve(new JSObject().put("restored", false));
            }
        });
    }

    @PluginMethod
    public void snapshotsSave(PluginCall call) {
        String name = call.getString("name", "Version");
        String json = call.getString("json");
        io.execute(() -> {
            try {
                String id = JournalId.of(json);
                if (id == null) {
                    call.resolve(new JSObject().put("entry", null));
                    return;
                }
                File dir = new File(versionsDir(), id);
                dir.mkdirs();
                JSONArray index = readIndex(new File(dir, "index.json"));
                long stamp = System.currentTimeMillis();
                while (new File(dir, stamp + ".json").exists()) stamp++;
                String file = stamp + ".json";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                writeBytes(new File(dir, file), bytes);
                String trimmed = name.trim();
                if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80);
                JSONObject entry = new JSONObject();
                entry.put("file", file);
                entry.put("name", trimmed.isEmpty() ? "Version" : trimmed);
                entry.put("time", stamp);
                entry.put("bytes", bytes.length);
                index = prepend(index, entry);
                while (index.length() > 100) {
                    JSONObject old = index.optJSONObject(index.length() - 1);
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, old.optString("file")).delete();
                    index = without(index, index.length() - 1);
                }
                writeBytes(new File(dir, "index.json"), index.toString().getBytes(StandardCharsets.UTF_8));
                call.resolve(new JSObject().put("entry", entry));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void snapshotsList(PluginCall call) {
        String id = call.getString("projectId");
        io.execute(() -> {
            JSONArray out = safeId(id) ? readIndex(new File(new File(versionsDir(), id), "index.json")) : new JSONArray();
            call.resolve(new JSObject().put("list", out));
        });
    }

    @PluginMethod
    public void snapshotsRead(PluginCall call) {
        String id = call.getString("projectId");
        String file = call.getString("file");
        io.execute(() -> {
            String text = null;
            try {
                if (safeId(id) && file != null && file.matches("^\\d+\\.json$")) {
                    File f = new File(new File(versionsDir(), id), file);
                    if (f.exists()) text = new String(readBytes(f, 64 * 1024 * 1024), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
            call.resolve(new JSObject().put("text", text));
        });
    }

    @PluginMethod
    public void snapshotsRemove(PluginCall call) {
        String id = call.getString("projectId");
        String file = call.getString("file");
        io.execute(() -> {
            boolean removed = false;
            try {
                if (safeId(id) && file != null && file.matches("^\\d+\\.json$")) {
                    File dir = new File(versionsDir(), id);
                    JSONArray index = readIndex(new File(dir, "index.json"));
                    JSONArray next = new JSONArray();
                    for (int i = 0; i < index.length(); i++) {
                        JSONObject e = index.optJSONObject(i);
                        if (e != null && file.equals(e.optString("file"))) removed = true;
                        else next.put(e);
                    }
                    if (removed) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(dir, file).delete();
                        writeBytes(new File(dir, "index.json"), next.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
            call.resolve(new JSObject().put("removed", removed));
        });
    }

    private String idOfProject(String projectPath) {
        if (projectPath == null) return null;
        File f = new File(stripScheme(projectPath));
        if (!f.exists()) return null;
        try {
            return JournalId.of(new String(readBytes(f, 64 * 1024 * 1024), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private static final class JournalId {

        static String of(String json) {
            if (json == null) return null;
            try {
                String id = new JSONObject(json).optString("id", null);
                return safeId(id) ? id : null;
            } catch (JSONException e) {
                return null;
            }
        }
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("^\\w[\\w.-]{0,79}$");
    }

    // ---------------------------------------------------------------- cache

    @PluginMethod
    public void cacheGet(PluginCall call) {
        String key = call.getString("key");
        io.execute(() -> {
            String data = null;
            try {
                File f = cacheFile(key);
                if (f != null && f.exists()) data = Data.encode(readBytes(f, 64 * 1024 * 1024));
            } catch (Exception ignored) {
            }
            call.resolve(new JSObject().put("data", data));
        });
    }

    @PluginMethod
    public void cachePut(PluginCall call) {
        String key = call.getString("key");
        String data = call.getString("data");
        io.execute(() -> {
            try {
                File f = cacheFile(key);
                if (f != null && data != null) writeBytes(f, Data.decode(data));
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void cacheFile(PluginCall call) {
        String key = call.getString("key");
        io.execute(() -> {
            File f = cacheFile(key);
            call.resolve(new JSObject().put("file", f != null && f.exists() ? describe(f, f.getName()) : null));
        });
    }

    private File cacheFile(String key) {
        if (key == null || key.contains("/") || key.contains("\\") || key.contains("..")) return null;
        return new File(cacheDir(), key);
    }

    @PluginMethod
    public void cacheInfo(PluginCall call) {
        io.execute(() -> call.resolve(new JSObject().put("dir", cacheDir().getAbsolutePath()).put("bytes", sizeOf(cacheDir()))));
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        io.execute(() -> {
            File[] files = cacheDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    // Generated sound effects are media a project points at, not cache.
                    if (!f.getName().matches("^sfx-[\\w.-]+\\.wav$")) deleteTree(f);
                }
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void saveRecording(PluginCall call) {
        String name = call.getString("name", "recording");
        String ext = call.getString("ext", "wav");
        String data = call.getString("data");
        io.execute(() -> {
            try {
                File f = uniqueIn(mediaDir(), safeName(name) + "." + ext);
                writeBytes(f, Data.decode(data == null ? "" : data));
                call.resolve(new JSObject().put("file", describe(f, f.getName())));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- settings

    private File settingsFile() {
        return new File(stateDir(), "settings.json");
    }

    /**
     * Every key the renderer's Settings type has, with the same defaults as DEFAULT_SETTINGS in
     * src/shared/settings.ts. A settings file starts out empty on a new install, and returning
     * that bare object made the viewer read an undefined preview quality; complete settings here
     * mean the first frame never depends on whether a file happened to exist.
     */
    private static JSObject settingsDefaults() {
        JSObject d = new JSObject();
        d.put("previewQuality", "auto");
        d.put("hardwareDecoding", true);
        d.put("proxyMode", "auto");
        d.put("projectsDir", (Object) null);
        // Updates and the news feed are GitHub fetches; an APK is replaced by installing one.
        d.put("autoUpdate", false);
        d.put("news", false);
        d.put("communityTemplates", false);
        d.put("aiControl", false);
        d.put("reduceMotion", false);
        d.put("offerCaptions", false);
        return d;
    }

    private static JSObject withDefaults(JSONObject raw) throws JSONException {
        JSObject merged = new JSObject();
        for (Iterator<String> it = settingsDefaults().keys(); it.hasNext(); ) {
            String k = it.next();
            merged.put(k, settingsDefaults().opt(k));
        }
        if (raw != null) {
            for (Iterator<String> it = raw.keys(); it.hasNext(); ) {
                String k = it.next();
                merged.put(k, raw.opt(k));
            }
        }
        return merged;
    }

    @PluginMethod
    public void settingsGet(PluginCall call) {
        io.execute(() -> {
            try {
                JSONObject stored = settingsFile().exists()
                    ? new JSONObject(new String(readBytes(settingsFile(), 1024 * 1024), StandardCharsets.UTF_8))
                    : new JSONObject();
                call.resolve(new JSObject().put("settings", withDefaults(stored)));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void settingsSet(PluginCall call) {
        JSObject patch = call.getObject("patch", new JSObject());
        io.execute(() -> {
            try {
                JSONObject current = settingsFile().exists()
                    ? new JSONObject(new String(readBytes(settingsFile(), 1024 * 1024), StandardCharsets.UTF_8))
                    : new JSONObject();
                for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
                    String k = it.next();
                    current.put(k, patch.opt(k));
                }
                writeBytes(settingsFile(), current.toString().getBytes(StandardCharsets.UTF_8));
                JSObject merged = withDefaults(current);
                getBridge().triggerWindowJSEvent("motion-settings", merged.toString());
                call.resolve(new JSObject().put("settings", merged));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void settingsPickProjectsDir(PluginCall call) {
        // Projects live in app storage, which every build can write without a permission prompt.
        call.resolve(new JSObject().put("dir", null));
    }

    // ---------------------------------------------------------------- session (crash recovery)

    @PluginMethod
    public void sessionTake(PluginCall call) {
        io.execute(() -> {
            JSObject notice = null;
            File marker = new File(stateDir(), "open.json");
            try {
                if (marker.exists()) {
                    JSONObject prev = new JSONObject(new String(readBytes(marker, 1024 * 1024), StandardCharsets.UTF_8));
                    if (!prev.optBoolean("clean", true) && !prev.optString("path", "").isEmpty()) {
                        notice = new JSObject();
                        notice.put("reason", "crash");
                        notice.put("path", prev.optString("path"));
                        notice.put("name", prev.optString("name"));
                    }
                }
            } catch (Exception ignored) {
            }
            call.resolve(new JSObject().put("notice", notice));
        });
    }

    @PluginMethod
    public void sessionOpened(PluginCall call) {
        String path = call.getString("path", "");
        String name = call.getString("name", "");
        io.execute(() -> {
            try {
                if (path == null || path.isEmpty()) {
                    new File(stateDir(), "open.json").delete();
                } else {
                    JSONObject o = new JSONObject();
                    o.put("clean", false);
                    o.put("path", path);
                    o.put("name", name);
                    o.put("at", System.currentTimeMillis());
                    writeBytes(new File(stateDir(), "open.json"), o.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void sessionClosed(PluginCall call) {
        io.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("clean", true);
                writeBytes(new File(stateDir(), "open.json"), o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            call.resolve();
        });
    }

    // ---------------------------------------------------------------- sharing

    @PluginMethod
    public void shareFile(PluginCall call) {
        String path = call.getString("path");
        String mime = call.getString("mime", "application/octet-stream");
        File f = path == null ? null : new File(stripScheme(path));
        if (f == null || !f.exists()) {
            call.reject("That file is not here any more.");
            return;
        }
        Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".files", f);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getActivity().startActivity(Intent.createChooser(send, "Share"));
        call.resolve();
    }

    /** Files the WebView may load. MediaRangeClient asks this before every read. */
    static boolean isServable(File f) {
        return f != null && f.isFile();
    }

    @PluginMethod
    public void systemInfo(PluginCall call) {
        JSObject o = new JSObject();
        o.put("app", BuildConfig.VERSION_NAME);
        o.put("electron", "");
        o.put("chrome", android.webkit.WebSettings.getDefaultUserAgent(getContext()));
        o.put("os", "android " + android.os.Build.VERSION.RELEASE);
        o.put("arch", android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "");
        o.put("gpu", new JSArray());
        call.resolve(o);
    }

    /** Opens the system share sheet for a finished export. */
    @PluginMethod
    public void shareExport(PluginCall call) {
        String path = call.getString("path");
        String mime = call.getString("mime", null);
        File f = path == null ? null : new File(stripScheme(path));
        if (f == null || !f.isFile()) {
            call.reject("That export is not here any more.");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".files", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime != null ? mime : MimeTypes.of(f));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(Intent.createChooser(send, "Share"));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "That export could not be shared.");
        }
    }

    /**
     * Opens this app's page in system Settings.
     *
     * A declined permission can only be changed there, and saying "allow it in Settings" without
     * offering to open it is the kind of dead end that reads as a broken app.
     */
    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Settings could not be opened.");
        }
    }

    @PluginMethod
    public void revealInFolder(PluginCall call) {
        // There is no folder to reveal on a phone; the export dialog offers Share instead.
        call.resolve();
    }

    // ---------------------------------------------------------------- helpers

    static String stripScheme(String path) {
        if (path.startsWith("file://")) {
            String p = Uri.parse(path).getPath();
            return p == null ? path : p;
        }
        return path;
    }

    private static boolean readable(File f) {
        return f != null && f.exists() && f.isFile();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] readBytes(File f, int max) throws IOException {
        if (!readable(f)) throw new IOException("That file is not here any more.");
        if (f.length() > max) throw new IOException("The file is too large to open.");
        try (FileInputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static void writeBytes(File f, byte[] bytes) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            copy(tmp, f);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static void copy(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static long sizeOf(File dir) {
        long n = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) n += f.isDirectory() ? sizeOf(f) : f.length();
        return n;
    }

    static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteTree(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static File uniqueIn(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, stem + " " + i + ext);
            if (!f.exists()) return f;
        }
    }

    static String safeName(String name) {
        if (name == null) name = "Untitled";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim().replaceAll("[. ]+$", "");
        if (s.isEmpty()) s = "Untitled";
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot < 9 ? name.substring(dot).toLowerCase() : "";
    }

    private static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private static JSONArray readIndex(File f) {
        try {
            if (!f.exists()) return new JSONArray();
            JSONArray arr = new JSONArray(new String(readBytes(f, 16 * 1024 * 1024), StandardCharsets.UTF_8));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null && e.optString("file").matches("^\\d+\\.json$")) out.put(e);
            }
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONArray prepend(JSONArray arr, JSONObject entry) {
        JSONArray out = new JSONArray();
        out.put(entry);
        for (int i = 0; i < arr.length(); i++) out.put(arr.opt(i));
        return out;
    }

    private static JSONArray without(JSONArray arr, int index) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) if (i != index) out.put(arr.opt(i));
        return out;
    }
}
