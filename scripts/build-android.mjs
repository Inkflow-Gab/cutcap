import { build } from "vite";
import { mkdir, readdir, rm, stat } from "node:fs/promises";
import { join, resolve } from "node:path";

/**
 * The renderer as the Android app ships it.
 *
 * It is the same editor, with the pieces a phone cannot or should not carry left out:
 *
 *   - source maps: 30 MB of .map files no APK ever reads;
 *   - the ONNX runtime (about 28 MB of WASM) and the three workers that load it - visual
 *     background removal and reframing, listening for captions, and read-aloud. They download
 *     model weights over the network anyway and their runtimes want threads a phone WebView
 *     often refuses, so shipping them would make every install slower and the features no
 *     better on the devices that fail. Everything that edits pictures and sound is still
 *     here: WebCodecs export, effects, filters, transitions, captions you type, stickers,
 *     keyframes, speed curves, audio mixing;
 *   - ES2022 output, split by feature so the phone loads the shell first and the exporters on
 *     first use.
 */
// Vite's root is src/renderer (see vite.config.ts), so paths are resolved from here instead.
const ROOT = new URL("..", import.meta.url).pathname;
const OUT = resolve(ROOT, "out/renderer");
const SKIP_RUNTIME = /node_modules[\\/](?:@huggingface[\\/]transformers|onnxruntime-web|kokoro-js|@jitsi[\\/]rnnoise-wasm)[\\/]/;

/**
 * The three workers the phone build leaves out. They are found where the page asks for them
 * (the `new URL("...", import.meta.url)` pattern below) rather than by module resolution, and
 * the worker sources themselves are emptied in case anything imports them directly. The host
 * code already treats a worker that fails to start as "this feature is off", with the message
 * written here.
 */
const SKIP_WORKER = /(?:^|[\\/])engine[\\/](?:ai\.worker|speech\.worker|tts\.worker)\.ts$/;
const WORKER_URL = /new\s+Worker\s*\(\s*new\s+URL\s*\(\s*["'`]([^"'`]+)["'`]\s*,\s*import\.meta\.url\s*\)(?:[^)]*)?\)/g;
const NOT_HERE = JSON.stringify("This feature is not part of the Android build.");
/** Bundled ONNX runtimes, model wiring and their WASM: never part of the phone build. */
const SKIP_ASSET = /onnxruntime-web|\.wasm(?:\?|$)|ai[\\/]superres|ai[\\/]models/;

const phoneOnly = {
  name: "openroll-android-build",
  enforce: "pre",

  // A page that asks for a worker the phone does not ship gets a call that throws, which is
  // exactly what a worker that failed to start looks like to the code around it.
  transform(code, id) {
    if (!WORKER_URL.test(code)) {
      WORKER_URL.lastIndex = 0;
      return null;
    }
    WORKER_URL.lastIndex = 0;
    const out = code.replace(WORKER_URL, (match, specifier) => {
      if (!SKIP_WORKER.test(specifier)) return match;
      return `(() => { throw new Error(${NOT_HERE}); })()`;
    });
    return out === code ? null : { code: out, map: null };
  },

  // Asset URLs (`new URL("...wasm", import.meta.url)`) are collected before a module's own
  // code is transformed, so they are turned into plain strings here. Nothing on this build
  // ever fetches them; the features that would have are not shipped.
  resolveId(source, importer) {
    if (!SKIP_ASSET.test(source)) return null;
    if (!SKIP_ASSET.test(importer ?? "") && !/[\\/]engine[\\/](?:ai[\\/]|speech\.worker|tts\.worker|ai\.worker)/.test(importer ?? "")) return null;
    return "\0openroll-skipped:" + source;
  },

  // The modules themselves: the skipped workers, the ONNX runtime, and the AI helpers that
  // only it needs. Replacing them here keeps their 28 MB of WASM out of the APK.
  load(id) {
    if (id.startsWith("\0openroll-skipped:")) return `export default "";`;
    if (SKIP_WORKER.test(id)) return `${NOT_HERE}; export {};`;
    if (/[\\/]onnxruntime-web[\\/]/.test(id)) return `${NOT_HERE}; export default undefined; export {};`;
    // Same shape as the real modules, so the panels that quote model sizes still build.
    if (/[\\/]ai[\\/]models\.ts$/.test(id)) return `export const MODELS = {}; export async function session() { throw new Error(${NOT_HERE}); } export function dropSession() {}`;
    if (/[\\/]ai[\\/]superres\.ts$/.test(id)) return `export async function upscale() { throw new Error(${NOT_HERE}); }`;
    return null;
  },
};

await rm(OUT, { recursive: true, force: true });
await mkdir(OUT, { recursive: true });

await build({
  configFile: "vite.config.ts",
  mode: "android",
  logLevel: "warn",
  plugins: [phoneOnly],
  build: {
    outDir: OUT,
    // Nothing produced here needs a map; they were 30 MB of the APK on the first try.
    sourcemap: false,
    // Paths in this config are ours, not relative to the renderer root.
    assetsDir: "assets",
    emptyOutDir: true,
    sourcemap: false,
    target: "es2022",
    minify: "esbuild",
    cssMinify: true,
    reportCompressedSize: false,
    chunkSizeWarningLimit: 4096,
    rollupOptions: {
      output: {
        // Big, independently loaded pieces get their own files so first paint does not wait.
        manualChunks(id) {
          if (!id.includes("node_modules")) return undefined;
          if (id.includes("mediabunny")) return "media-io";
          if (id.includes("react")) return "ui";
          return undefined;
        },
      },
    },
  },
});

// Anything the bundler still emitted for a runtime the phone build does not ship is dead
// weight in the APK. (The ONNX WASM and the two AI helper chunks can be referenced by a lazy
// import inside a worker before their modules are replaced; they are never fetched, because
// the code that would fetch them is replaced above.)
const assets = await readdir(join(OUT, "assets"));
let dropped = 0;
let bytes = 0;
for (const name of assets) {
  // `(?:^|[-_.])ort[-.]` and not plain `ort[-.]`: the latter also ate `export.worker-*.js`.
  if (/(?:^|[-_.])ort[-.]|onnx|transformers|kokoro|rnnoise|^superres-|^ai\.worker-/i.test(name)) {
    bytes += (await stat(join(OUT, "assets", name))).size;
    await rm(join(OUT, "assets", name), { force: true });
    dropped++;
  }
}

const total = await sizeOf(OUT);
console.log(
  `Android web assets are in ${OUT}/ (${(total / 1024 / 1024).toFixed(1)} MB on disk, ` +
    `${(dropped ? bytes / 1024 / 1024 : 0).toFixed(1)} MB of runtime files left out${dropped ? `, ${dropped} files` : ""})`,
);

async function sizeOf(dir) {
  let bytes = 0;
  for (const name of await readdir(dir)) {
    const p = join(dir, name);
    const s = await stat(p);
    bytes += s.isDirectory() ? await sizeOf(p) : s.size;
  }
  return bytes;
}
