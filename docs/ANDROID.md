# Android

Motion (formerly OpenRoll) runs on Android as a Capacitor app. The renderer is the same React tree the desktop
build uses; what changes is the layer under `window.openroll`.

| | Desktop | Android |
|---|---|---|
| Shell | Electron | Capacitor 8 + WebView |
| `window.openroll` | IPC to `src/main` | `MotionNativePlugin` over the bridge |
| Media bytes | `file://`, with ffmpeg as a decoder | `MediaRangeClient` on `/motion-media/`, WebCodecs only |
| Convert / export | ffmpeg | not present; WebCodecs encode only |
| Projects | folder on disk | app-private `filesDir/motion/projects` |

## Building an APK

Locally, with a JDK 21 and the Android SDK 36:

```sh
npm ci --ignore-scripts
npm run android:sync          # web bundle + cap sync
cd android && ./gradlew assembleRelease
```

The package is `com.motion.official` and the launcher label is Motion; CI fails the build if the
packaged APK says anything else. The APK lands in `android/app/build/outputs/apk/release/`. It is signed with the debug keystore
unless `OPENROLL_KEYSTORE`, `OPENROLL_KEYSTORE_PASSWORD`, `OPENROLL_KEY_ALIAS` and
`OPENROLL_KEY_PASSWORD` are set; a debug-signed release installs fine but Play rejects it.

CI (`.github/workflows/android-apk.yml`) builds the same APK on every push that touches the
Android layer, unpacks it, fails if any worker or the audio worklet is missing, and uploads it as
the `motion-apk` artifact. A manual run also builds the unshrunk debug APK.

## Checks that run without the Android SDK

There is no reliable way to run Gradle in every environment, so two cheaper checks guard the parts
that break silently:

```sh
node scripts/check-bridge.mjs      # every call() in the renderer has a @PluginMethod
bash scripts/check-android-java.sh # compiles the Java against real API signatures
```

`check-android-java.sh` downloads a portable JRE and the Eclipse compiler, compiles against the
android-36 platform stubs, and uses the stubs in `scripts/java-check/` for AndroidX and for the
slice of Capacitor's API the app calls. It is not a substitute for Gradle — CI's build is the
authority — but it catches the errors that otherwise cost a full CI round trip.

## What the phone build leaves out

`scripts/build-android.mjs` builds the renderer with `phoneOnly`, which replaces the AI and speech
workers, and then prunes the runtime files that are no longer reachable: ONNX Runtime's WASM,
transformers, kokoro, rnnoise. That is most of the 18 MB it drops. AI features on Android are
captions, effects, keyframes, speed curves and audio mixing; anything that needs a model is not
offered.

There are no native libraries in the APK. Capacitor and the Filesystem plugin are pure Java and the
DSP runs as WebAssembly in the WebView, so a single APK installs on every architecture.

## Storage

```
filesDir/openroll/
  media/       imported clips, recordings
  projects/    *.motion, plus a .thumb next to each
  cache/       generated sound effects and derived files (Clear cache keeps sfx-*.wav)
  journal/     undo history, per project
  versions/    saved project versions, per project
  templates/   local templates
  state/       settings.json
  exports/     finished renders, shared out through FileProvider
```

Settings and history are app-private, so they survive an app update but not an uninstall. Nothing
outside the app's own directory is read without the system picker, which is why the app needs no
storage permission.

## Layout

The editor is built for large screens with a pointer. Two things switch it for a phone, and both
must agree: the `@media (max-width: 820px)` rules in `src/renderer/styles/mobile.css`, and
`usePhoneLayout()` in `src/renderer/lib/layout.ts`, which decides which controls are rendered at
all. A phone is narrow **and** `platform === "android"`, so a narrow desktop window does not lose
controls it can still use.
