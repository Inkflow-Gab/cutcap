/**
 * Every permission alias the plugin declares must be in the manifest.
 *
 * Capacitor does not show a dialog for a permission that is not declared: Bridge.validatePermissions
 * rejects the call with "Missing the following permissions in AndroidManifest.xml". That is silent
 * from the UI's side — the gallery just never opens — so it is checked here instead.
 *
 *   node scripts/check-permissions.mjs
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const plugin = readFileSync(
  `${root}/android/app/src/main/java/com/motion/official/MotionNativePlugin.java`,
  "utf8"
);
const manifest = readFileSync(`${root}/android/app/src/main/AndroidManifest.xml`, "utf8");

const declared = new Map(); // permission string -> maxSdkVersion, or null
for (const m of manifest.matchAll(/<uses-permission\b([^>]*)>/g)) {
  const attrs = m[1];
  const name = /android:name="([^"]+)"/.exec(attrs)?.[1];
  if (!name) continue;
  const max = /android:maxSdkVersion="(\d+)"/.exec(attrs)?.[1];
  declared.set(name, max === undefined ? null : Number(max));
}

const aliases = [
  ...plugin.matchAll(/@Permission\(\s*alias\s*=\s*"([^"]+)",\s*strings\s*=\s*\{\s*"([^"]+)"/g),
].map(([, alias, perm]) => ({ alias, perm }));

const problems = [];
if (!aliases.length) problems.push("no @Permission aliases found — has the annotation moved?");

for (const { alias, perm } of aliases) {
  if (!declared.has(perm)) {
    problems.push(`alias "${alias}" uses ${perm}, which is not declared in AndroidManifest.xml`);
    continue;
  }
  // READ_EXTERNAL_STORAGE is ignored from API 33 on. Capping it at 32 is correct; leaving it
  // uncapped means the app asks for a permission the system will never grant on a new phone.
  if (perm === "android.permission.READ_EXTERNAL_STORAGE" && declared.get(perm) !== 32) {
    problems.push(`${perm} should be capped at maxSdkVersion="32" (it is ignored from API 33 on)`);
  }
}

// The other direction: a media permission no alias can request is dead weight, and usually means
// an alias was renamed and the manifest was not.
const wanted = new Set(aliases.map(a => a.perm));
for (const perm of declared.keys()) {
  if (/READ_MEDIA_|READ_EXTERNAL_STORAGE/.test(perm) && !wanted.has(perm)) {
    problems.push(`${perm} is declared in the manifest but no alias requests it`);
  }
}

if (problems.length) {
  for (const p of problems) console.error("permissions: " + p);
  console.error("permissions check FAILED");
  process.exit(1);
}
console.log(`permissions ok: ${aliases.length} aliases, all declared`);
