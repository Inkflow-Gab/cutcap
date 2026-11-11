package app.openroll.mobile;

import android.webkit.MimeTypeMap;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Media types the WebView needs. The system table knows the common ones; the rest matter
 * because the editor writes projects, colour tables and subtitles too.
 */
final class MimeTypes {

    private static final Map<String, String> EXTRA = new HashMap<>();

    static {
        EXTRA.put("mp4", "video/mp4");
        EXTRA.put("m4v", "video/mp4");
        EXTRA.put("mov", "video/quicktime");
        EXTRA.put("webm", "video/webm");
        EXTRA.put("mkv", "video/x-matroska");
        EXTRA.put("avi", "video/x-msvideo");
        EXTRA.put("m4a", "audio/mp4");
        EXTRA.put("aac", "audio/aac");
        EXTRA.put("mp3", "audio/mpeg");
        EXTRA.put("wav", "audio/wav");
        EXTRA.put("ogg", "audio/ogg");
        EXTRA.put("opus", "audio/opus");
        EXTRA.put("flac", "audio/flac");
        EXTRA.put("png", "image/png");
        EXTRA.put("jpg", "image/jpeg");
        EXTRA.put("jpeg", "image/jpeg");
        EXTRA.put("webp", "image/webp");
        EXTRA.put("gif", "image/gif");
        EXTRA.put("avif", "image/avif");
        EXTRA.put("heic", "image/heic");
        EXTRA.put("json", "application/json");
        EXTRA.put("openroll", "application/json");
        EXTRA.put("cube", "text/plain");
        EXTRA.put("srt", "text/plain");
        EXTRA.put("vtt", "text/vtt");
        EXTRA.put("txt", "text/plain");
        EXTRA.put("woff2", "font/woff2");
        EXTRA.put("wasm", "application/wasm");
    }

    private MimeTypes() {}

    static String of(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        String known = EXTRA.get(ext);
        if (known != null) return known;
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return guessed != null ? guessed : "application/octet-stream";
    }
}
