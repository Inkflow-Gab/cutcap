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

## The gallery

Adding media opens the device's own gallery, not a file dialog. A phone editor is expected to show
the phone's photos with thumbnails and albums; Android's document picker shows neither, which is the
main reason adding a clip felt worse here than it does in the app it imitates.

It is read straight from `MediaStore`, so it works with no network and shows what the phone actually
has:

- **Albums** come from `BUCKET_ID` / `BUCKET_DISPLAY_NAME`, with a count and a cover per album.
- **Items** are listed newest first, capped at 4000 rows, 3 per row on a phone and 5 on a tablet.
- **Thumbnails** are asked of the system (`loadThumbnail` on 29+, a scaled decode below that) and
  cached as 320 px JPEGs in `cache/thumbs`. The grid never decodes a full-size photo.
- **Sorting** cycles newest / oldest / A–Z, and an album narrows the grid.
- **Selection is ordered.** The number on a tile is the order it was tapped, and that is the order
  the clips land on the timeline. Tapping a chosen tile removes it and the numbers close up.

Only the chosen rows are copied in, through the same import path as any other file, so a gallery of
two thousand photos costs nothing until something is picked. The renderer addresses a row by its
MediaStore id, never by a `content://` URI, because the WebView cannot load one.

Reading the gallery needs `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` on Android 13+, and
`READ_EXTERNAL_STORAGE` (capped at API 32) below that. Access is requested the first time the gallery
opens.

The permissions have to be in `AndroidManifest.xml` as well as on the plugin's `@CapacitorPlugin`
annotation. Capacitor does not show a dialog for an undeclared permission — it rejects the call with
"Missing the following permissions in AndroidManifest.xml" — so a missing entry makes the gallery
fail to open with nothing in the UI to explain it. `scripts/check-permissions.mjs` compares the two,
and CI reads the built APK's manifest with `aapt`, which also catches the merger dropping an entry.

Each kind is granted separately on Android 13+. Allowing photos and declining videos still leaves
the Photos tab working. A refusal leaves the sheet saying which permission is missing, with a button
that opens this app's page in Settings and a Try again that re-reads without closing it; "Files"
remains available, so a clip from Downloads or a USB stick is still reachable.

## Storage

```
filesDir/openroll/
  media/       imported clips, recordings
  projects/    *.motion, plus a .thumb next to each
  cache/       generated sound effects and derived files (Clear cache keeps sfx-*.wav)
  cache/thumbs/  gallery thumbnails, 320 px JPEGs
  journal/     undo history, per project
  versions/    saved project versions, per project
  templates/   local templates
  state/       settings.json
  exports/     finished renders, shared out through FileProvider
```

Settings and history are app-private, so they survive an app update but not an uninstall. The only
thing read outside the app's own directory is the gallery, through `MediaStore` and only after the
read permission is granted; files are otherwise reached through the system picker.

## Layout

The editor is built for large screens with a pointer. Two things switch it for a phone, and both
must agree: the `@media (max-width: 820px)` rules in `src/renderer/styles/mobile.css`, and
`usePhoneLayout()` in `src/renderer/lib/layout.ts`, which decides which controls are rendered at
all. A phone is narrow **and** `platform === "android"`, so a narrow desktop window does not lose
controls it can still use.
