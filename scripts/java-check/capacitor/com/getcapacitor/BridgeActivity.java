package com.getcapacitor;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

/** Stub. See ../../README.md. Signatures match Capacitor 8. */
public class BridgeActivity extends AppCompatActivity {
    protected Bridge bridge;
    protected List<Class<? extends Plugin>> initialPlugins = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {}

    protected void load() {}

    public Bridge getBridge() {
        return bridge;
    }

    public void registerPlugin(Class<? extends Plugin> pluginClass) {}

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {}

    @Override
    protected void onNewIntent(Intent intent) {}
}
