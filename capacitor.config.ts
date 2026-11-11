import type { CapacitorConfig } from "@capacitor/cli";

/**
 * The Android build. The page is the same renderer the desktop app runs; only the layer under
 * `window.openroll` changes (src/renderer/platform), because a phone has no Node and no Electron.
 *
 * Scheme https with host `localhost` gives the WebView a secure origin, which WebCodecs,
 * WebGL2, AudioWorklet, WASM and the workers the editor spawns all require. The server behind
 * it is Capacitor's own in-process WebView request hook: nothing listens on a port and no
 * traffic leaves the device.
 */
const config: CapacitorConfig = {
  appId: "app.openroll.mobile",
  appName: "OpenRoll",
  webDir: "out/renderer",
  // Every asset is bundled: first paint is a disk read, and the editor must work offline.
  server: {
    androidScheme: "https",
    hostname: "localhost",
    cleartext: false,
  },
  android: {
    // The editor is built for large screens with a pointer. Letting it fit the whole project
    // frame on a phone would make every control thumb-sized, so the WebView keeps CSS pixels
    // at their normal density and the responsive layout in mobile.css does the work.
    // Default (unset) is what we want; spelled out so nobody "fixes" it later.
    allowMixedContent: false,
    // Text fields, the keyboard, and drag handles all need the standard zoom behaviour off.
    webContentsDebuggingEnabled: true,
    backgroundColor: "#141415",
  },
  plugins: {
    // Only used by the Android layer itself (temporary staging files); the app talks to
    // window.openroll, which routes media through the native plugin.
    Filesystem: {},
  },
};

export default config;
