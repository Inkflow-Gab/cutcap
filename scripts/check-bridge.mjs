// Checks that the renderer and the Android plugin agree on the bridge's method names.
//
// The bridge is a string-keyed API: `call("clearCache")` on the JS side and
// `@PluginMethod public void clearCache` on the Java side. Nothing type-checks that pair, so a
// rename on one side leaves a button that throws at runtime, on a device, in a build that already
// passed. This catches it in a second.
//
//   node scripts/check-bridge.mjs
//
// Run by CI and by scripts/check-android-java.sh.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const JS = join(ROOT, "src/renderer/platform/android.ts");
const JAVA = join(ROOT, "android/app/src/main/java/com/motion/official/MotionNativePlugin.java");

const js = readFileSync(JS, "utf8");
const java = readFileSync(JAVA, "utf8");

/** Method names the plugin exposes. */
const exposed = new Set([...java.matchAll(/@PluginMethod\s+public void (\w+)\s*\(/g)].map(m => m[1]));

/** Method names the renderer calls: call("x"), call<T>("x"), call<{...}>("x"). */
const called = new Set([...js.matchAll(/\bcall(?:<[^>]*>)?\(\s*"(\w+)"/g)].map(m => m[1]));

// Methods the plugin has that the renderer reaches indirectly, or that exist for other callers.
const REACHED_ELSEWHERE = new Set([
  "importShared", // reached through the share-sheet event, not call()
  "settingsGet",
  "settingsSet",
  "projectsList",
  "historyList",
  "snapshotsList",
  "systemInfo",
  "pathsForDropped",
  "revealInFolder",
  "shareFile",
  "sessionOpened",
  "settingsPickProjectsDir",
]);

const missing = [...called].filter(name => !exposed.has(name)).sort();
const unused = [...exposed].filter(name => !called.has(name) && !REACHED_ELSEWHERE.has(name)).sort();

let bad = false;

if (missing.length) {
  bad = true;
  console.error("The renderer calls methods the Android plugin does not expose:");
  for (const name of missing) console.error(`  call("${name}") has no @PluginMethod ${name}(PluginCall)`);
  console.error("\nEach of these throws at runtime on a device.");
}

if (unused.length) {
  bad = true;
  console.error("The Android plugin exposes methods nothing calls:");
  for (const name of unused) console.error(`  ${name}()`);
  console.error(
    "\nEither the renderer forgot to call it, or it should move to REACHED_ELSEWHERE in " +
      "scripts/check-bridge.mjs if it is reached another way.",
  );
}

if (bad) process.exit(1);
console.log(`bridge ok: ${called.size} methods called, ${exposed.size} exposed`);
