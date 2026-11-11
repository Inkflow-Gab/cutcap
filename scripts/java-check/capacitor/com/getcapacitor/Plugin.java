package com.getcapacitor;

import android.content.Context;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;

/** Stub. See ../../README.md. Signatures match Capacitor 8. */
public class Plugin {
    public void load() {}

    public void startActivityForResult(PluginCall call, Intent intent, String callbackName) {}

    public Context getContext() {
        return null;
    }

    public AppCompatActivity getActivity() {
        return null;
    }

    public void setBridge(Bridge bridge) {}

    public Bridge getBridge() {
        return null;
    }

    public String getAppId() {
        return "";
    }

    public void saveCall(PluginCall lastCall) {}

    public void freeSavedCall() {}

    public PluginCall getSavedCall() {
        return null;
    }

    public PluginConfig getConfig() {
        return null;
    }

    public boolean hasPermission(String permission) {
        return false;
    }

    public void pluginRequestPermission(String permission, int requestCode) {}

    public void pluginRequestPermissions(String[] permissions, int requestCode) {}
}
