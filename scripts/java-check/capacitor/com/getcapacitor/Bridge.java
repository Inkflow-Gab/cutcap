package com.getcapacitor;

import android.content.Context;
import android.webkit.WebView;

/** Stub. See ../../README.md. Signatures match Capacitor 8. */
public class Bridge {
    public WebView getWebView() {
        return null;
    }

    public Context getContext() {
        return null;
    }

    public void triggerWindowJSEvent(String eventName) {}

    public void triggerWindowJSEvent(String eventName, String data) {}

    public BridgeWebViewClient getWebViewClient() {
        return null;
    }

    public void setWebViewClient(BridgeWebViewClient client) {}

    public void addWebViewListener(WebViewListener webViewListener) {}

    public void removeWebViewListener(WebViewListener webViewListener) {}

    public PluginConfig getConfig() {
        return null;
    }
}
