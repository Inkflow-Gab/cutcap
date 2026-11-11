package app.openroll.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

/**
 * The phone shell.
 *
 * Three things happen here that the desktop app never needed: media arriving from another app
 * through the share sheet, the microphone prompt for the voiceover recorder, and the keyboard
 * resizing the editor instead of covering it. Everything else is Capacitor's own activity.
 */
public class MainActivity extends BridgeActivity {

    /** A document shared into OpenRoll, held until the page is ready to import it. */
    static Uri pendingShare;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(OpenRollNativePlugin.class);
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // Exports are long; keep the screen on while the app is in front.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        edgeToEdge();

        WebView web = getBridge().getWebView();
        // Timeline audio must start without a fresh gesture for every clip.
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // Media is served with real range support; Capacitor's own handler stays for assets.
        getBridge().setWebViewClient(new MediaRangeClient(getBridge()));
        web.setWebChromeClient(new ChromeClient(this));

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView view) {
                deliverShare();
            }
        });

        takeShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeShare(intent);
    }

    private void takeShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        pendingShare = uri;
        deliverShare();
    }

    /** One document at a time: importing also moves it into the project store. */
    private void deliverShare() {
        Uri uri = pendingShare;
        if (uri == null || getBridge() == null) return;
        WebView web = getBridge().getWebView();
        if (web == null) return;
        pendingShare = null;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // A share grant is for this session; the copy is what matters.
        }
        String script = "window.dispatchEvent(new CustomEvent('openroll-share',{detail:" + org.json.JSONObject.quote(uri.toString()) + "}))";
        web.post(() -> web.evaluateJavascript(script, null));
    }

    /** Draws under the status and navigation bars, which the stylesheet pads for. */
    private void edgeToEdge() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
        View root = findViewById(android.R.id.content);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
    }

    /** The microphone is the only permission the editor asks for, and only for voiceover. */
    private static final class ChromeClient extends WebChromeClient {

        private final MainActivity activity;

        ChromeClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            activity.runOnUiThread(() -> {
                boolean audio = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
                }
                if (audio) request.grant(request.getResources());
                else request.deny();
            });
        }
    }
}
