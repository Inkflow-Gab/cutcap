package com.motion.official;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the editor's media to the WebView with real HTTP range support.
 *
 * The preview scrubs, the exporter seeks and the filmstrip walker reads middles of files. A
 * response to a Range request must start at the byte asked for and say where it sits, or the
 * media decoders read the wrong frames. Capacitor's built-in filesystem handler reports
 * 64-bit file sizes as 32-bit available() and always answers 206, which breaks both for the
 * multi-gigabyte files this editor exists for; this one answers honestly. Everything else is
 * handed straight to Capacitor's own client.
 */
public class MediaRangeClient extends BridgeWebViewClient {

    private static final String PREFIX = "/motion-media/";

    /** Kept because the base class holds its own copy privately. */
    private final Bridge bridge;

    MediaRangeClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        // getEncodedPath(), not getPath(): getPath() is already decoded, and decoding it again
        // corrupts any file whose name contains a percent sign (a share from another app can
        // easily be named "100% done.mp4"), which then reads as missing.
        String path = url.getEncodedPath();
        if (path == null || !path.startsWith(PREFIX)) {
            return super.shouldInterceptRequest(view, request);
        }
        String relative = path.substring(PREFIX.length());
        String decoded = Uri.decode(relative);
        // A gallery thumbnail is not a file yet: it names a MediaStore row, and the JPEG is made
        // here, the first time the WebView asks. Only tiles on screen ask, so a grid of thousands
        // decodes only the photos actually looked at.
        if (decoded.startsWith("thumb:")) return thumbnail(decoded);
        File file = new File(decoded);
        if (!file.isFile()) return notFound();

        String range = request.getRequestHeaders().get("Range");
        try {
            return range == null ? whole(file) : partial(file, range);
        } catch (IOException e) {
            return notFound();
        }
    }

    /**
     * Serves a gallery thumbnail, making it once.
     *
     * `thumb:<kind>:<MediaStore id>` becomes a cached 320 px JPEG. A row that has vanished — a
     * photo deleted between listing the album and looking at it — answers 404 rather than a
     * broken image, and the tile shows its placeholder.
     */
    private WebResourceResponse thumbnail(String spec) {
        String[] parts = spec.split(":", 3);
        if (parts.length != 3) return notFound();
        long id;
        try {
            id = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return notFound();
        }
        File cached = Thumbnails.cached(bridge.getContext(), parts[1], id);
        if (cached == null) cached = Thumbnails.make(bridge.getContext(), parts[1], id);
        if (cached == null) return notFound();
        try {
            return whole(cached);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse whole(File file) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-cache");
        // Length is passed as the stream length; WebResourceResponse has no size field, so the
        // decoders read until the stream ends, which is what a full-body response means.
        return new WebResourceResponse(MimeTypes.of(file), null, 200, "OK", headers, new FileInputStream(file));
    }

    private static WebResourceResponse partial(File file, String range) throws IOException {
        long total = file.length();
        long[] span = Range.parse(range, total);
        if (span == null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Range", "bytes */" + total);
            return new WebResourceResponse(MimeTypes.of(file), null, 416, "Range Not Satisfiable", headers, empty());
        }
        long start = span[0];
        long end = span[1];
        long length = end - start + 1;
        FileInputStream in = new FileInputStream(file);
        if (start > 0) in.getChannel().position(start);
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
        headers.put("Content-Length", Long.toString(length));
        headers.put("Cache-Control", "no-cache");
        java.io.InputStream body = new BoundedStream(in, length);
        return new WebResourceResponse(MimeTypes.of(file), null, 206, "Partial Content", headers, body);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), empty());
    }

    private static java.io.InputStream empty() {
        return new java.io.ByteArrayInputStream(new byte[0]);
    }

    /**
     * A slice of a file, read lazily so decoding frame 900 of a 4 GB clip costs nothing extra.
     * Built on FileInputStream because WebResourceResponse reads available() from it.
     */
    private static final class BoundedStream extends java.io.InputStream {

        private final FileInputStream in;
        private long left;

        BoundedStream(FileInputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public int available() {
            // An estimate is enough for the WebView; it reads until this says zero.
            return left > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) left;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
