import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import type { GalleryAlbum, GalleryItem, OpenRollApi } from "../../shared/api";
import type { Settings } from "../../shared/settings";
import type { UpdateState } from "../../shared/api";

/**
 * The one native plugin the Android build needs. Every method is answered by
 * MotionNativePlugin.java; it is registered by name rather than shipped as a separate
 * package because it belongs to this app alone.
 */
interface NativeBridge {
  pickMedia(options?: Record<string, unknown>): Promise<NativeFiles>;
  galleryPickAlbum(options: { kind: string }): Promise<{ albums: GalleryAlbum[] }>;
  galleryList(options: { kind: string }): Promise<{ items: GalleryItem[] }>;
  galleryImport(options: { ids: number[]; kind: string }): Promise<NativeFiles & { failed?: number }>;
  importShared(options: { uri: string }): Promise<NativeFiles>;
  pathsForDropped(options?: Record<string, unknown>): Promise<NativeFiles>;
  stat(options: { path: string }): Promise<{ file: NativeFile | null }>;
  pickLut(options?: Record<string, unknown>): Promise<{ lut: { name: string; text: string } | null }>;
  createWriter(options: { suggestedName: string; kind: string }): Promise<{ id: number; path: string; url: string }>;
  write(options: { id: number; position: number; data: string }): Promise<void>;
  resetWriter(options: { id: number }): Promise<void>;
  closeWriter(options: { id: number; abort: boolean }): Promise<{ result: NativeFile | null }>;
  projectsDir(): Promise<{ dir: string }>;
  projectsList(): Promise<{ projects: Array<{ path: string; name: string; modified: number; thumb?: string }> }>;
  projectsLoad(options: { path: string }): Promise<{ text: string | null }>;
  projectsSave(options: { path: string | null; name: string; json: string }): Promise<{ ok: boolean; path?: string; error?: string }>;
  projectsSaveThumb(options: { path: string; jpeg: string }): Promise<void>;
  projectsRemove(options: { path: string }): Promise<void>;
  cacheGet(options: { key: string }): Promise<{ data: string | null }>;
  cachePut(options: { key: string; data: string }): Promise<void>;
  cacheFile(options: { key: string }): Promise<{ file: NativeFile | null }>;
  cacheInfo(): Promise<{ dir: string; bytes: number }>;
  cacheClear(): Promise<void>;
  clearCache(): Promise<void>;
  saveRecording(options: { name: string; ext: string; data: string }): Promise<{ file: NativeFile | null }>;
  settingsGet(): Promise<{ settings: Partial<Settings> }>;
  settingsSet(options: { patch: Partial<Settings> }): Promise<{ settings: Partial<Settings> }>;
  settingsPickProjectsDir(): Promise<{ dir: string | null }>;
  historyList(options: { projectPath: string }): Promise<{ list: Array<{ file: string; time: number; bytes: number }> }>;
  historyRestore(options: { projectPath: string; file: string }): Promise<{ restored: boolean }>;
  snapshotsSave(options: { name: string; json: string }): Promise<{ entry: { file: string; name: string; time: number; bytes: number } | null }>;
  snapshotsList(options: { projectId: string }): Promise<{ list: Array<{ file: string; name: string; time: number; bytes: number }> }>;
  snapshotsRead(options: { projectId: string; file: string }): Promise<{ text: string | null }>;
  snapshotsRemove(options: { projectId: string; file: string }): Promise<{ removed: boolean }>;
  sessionTake(): Promise<{ notice: { reason: "crash" | "unclean"; path: string; name: string } | null }>;
  sessionOpened(options: { path: string; name: string }): Promise<void>;
  sessionClosed(): Promise<void>;
  shareExport(options: { path: string; mime?: string }): Promise<void>;
  revealInFolder(options: { path: string }): Promise<void>;
  systemInfo(): Promise<Record<string, unknown>>;
  addListener(event: string, handler: (data: Record<string, unknown>) => void): Promise<PluginListenerHandle>;
}

interface NativeFile {
  path: string;
  name: string;
  size: number;
  mtime: number;
}
type NativeFiles = { files: NativeFile[] };

const native = registerPlugin<NativeBridge>("MotionNative");

/**
 * Replaces `window.openroll` on the Android build.
 *
 * Every desktop module (src/main/*) has a counterpart here, built on one native plugin that
 * owns app storage; the page is the same renderer the desktop app runs. What a phone has no
 * honest equivalent for is left out rather than faked:
 *
 *   convert, ffexport  the desktop app's bundled ffmpeg. Without it, imports and exports use
 *                      the WebView's own decoders (WebCodecs), which covers the common
 *                      formats; ProRes, GIF and exotic files are hidden from the export list
 *                      because `convert.status()` reports no converter.
 *   agent              the control socket AI tools connect to on a desktop.
 *   news, updates      the team's feed and self-updating; an APK updates by installing a new one.
 *   relink             media is copied into the app's store, so nothing can go missing.
 *
 * Everything that is the editor itself - projects, the journal, named versions, the cache,
 * media import from the system picker or a share, settings, and finished exports - is real.
 */
export function installAndroidApi(): void {
  if (!Capacitor.isNativePlatform()) return;

  const call = <T>(method: keyof NativeBridge, options: Record<string, unknown> = {}): Promise<T> =>
    (native[method] as (o: Record<string, unknown>) => Promise<T>)(options);

  /** Turns a native event into the unsubscribe shape every listener here returns. */
  const listen = (event: string, handler: (data: Record<string, unknown>) => void): (() => void) => {
    const pending: Promise<PluginListenerHandle> = native.addListener(event, handler);
    return () => void pending.then(h => h.remove());
  };

  /** Files already imported through the native side, by path, so repeat imports stay cheap. */
  const files = new Map<string, { path: string; name: string; size: number; mtime: number }>();

  const remember = <T extends { path: string; name: string; size: number; mtime: number }>(f: T): T => {
    files.set(f.path, f);
    return f;
  };

  let settings: Partial<Settings> = {};
  const settingsListeners = new Set<(s: Settings) => void>();

  const api: OpenRollApi = {
    platform: "android",
    versions: { app: "0.2.1-android", electron: "", chrome: "webview" },

    async pickMedia() {
      const { files: picked } = await call<NativeFiles>("pickMedia");
      return (picked ?? []).map(remember);
    },

    gallery: {
      albums: async kind => (await call<{ albums: GalleryAlbum[] }>("galleryPickAlbum", { kind })).albums ?? [],
      items: async kind => (await call<{ items: GalleryItem[] }>("galleryList", { kind })).items ?? [],
      import: async (ids, kind) => {
        const out = await call<NativeFiles & { failed?: number }>("galleryImport", { ids, kind });
        // Chosen order is kept: the grid numbers the selection, so the first clip is first.
        return { files: (out.files ?? []).map(remember), failed: out.failed ?? 0 };
      },
    },

    async pathsForDropped() {
      return [];
    },

    mediaUrl(path: string) {
      // Mirrors MotionNativePlugin.mediaUrlFor: the absolute path without its leading
      // slash, per segment encoded, under the origin the plugin's client intercepts.
      const normal = path.startsWith("file://") ? path.replace(/^file:\/\//, "") : path;
      const rel = normal.replace(/^\/+/, "");
      return `https://localhost/motion-media/${rel.split("/").map(encodeURIComponent).join("/")}`;
    },

    async stat(path: string) {
      const { file } = await call<{ file: NativeFile | null }>("stat", { path });
      return file ? remember(file) : null;
    },

    async pickLut() {
      const { lut } = await call<{ lut: { name: string; text: string } | null }>("pickLut");
      return lut ?? null;
    },

    createWriter: (suggestedName, kind) => call<{ id: number; path: string; url: string }>("createWriter", { suggestedName, kind }),

    async write(id, position, data) {
      await call("write", { id, position, data: base64(data) });
    },

    async resetWriter(id) {
      await call("resetWriter", { id });
    },

    async closeWriter(id, abort = false) {
      await call<{ result: NativeFile | null }>("closeWriter", { id, abort });
    },

    async revealInFolder(path) {
      // No folder to open on a phone: the person gets the system share sheet instead.
      await call("shareExport", { path }).catch(() => {});
    },

    relink: {
      // Everything the editor reads is copied into app storage, which nothing else removes.
      missing: async () => [],
      auto: async () => [],
      folder: async () => null,
      file: async () => null,
    },

    projects: {
      dir: async () => (await call<{ dir: string }>("projectsDir")).dir,
      list: async () => {
        const { projects } = await call<{ projects: Array<{ path: string; name: string; modified: number; thumb?: string }> }>("projectsList");
        return projects ?? [];
      },
      load: async path => (await call<{ text: string | null }>("projectsLoad", { path })).text,
      save: (path, name, json) => call("projectsSave", { path, name, json }),
      async saveThumb(path, jpeg) {
        await call("projectsSaveThumb", { path, jpeg: base64(jpeg) });
      },
      async remove(path) {
        await call("projectsRemove", { path });
      },
    },

    cache: {
      async get(key) {
        const { data } = await call<{ data: string | null }>("cacheGet", { key });
        return data ? fromBase64(data) : null;
      },
      async put(key, data) {
        await call("cachePut", { key, data: base64(data) });
      },
      async file(key) {
        const { file } = await call<{ file: NativeFile | null }>("cacheFile", { key });
        return file ? remember(file) : null;
      },
    },

    async saveRecording(name, ext, data) {
      const { file } = await call<{ file: NativeFile | null }>("saveRecording", { name, ext, data: base64(data) });
      return file ? remember(file) : null;
    },

    settings: {
      async get() {
        const r = await call<{ settings: Partial<Settings> }>("settingsGet");
        settings = r.settings ?? {};
        return settings as Settings;
      },
      async set(patch) {
        const r = await call<{ settings: Partial<Settings> }>("settingsSet", { patch });
        settings = r.settings ?? settings;
        return settings as Settings;
      },
      pickProjectsDir: async () => null,
      onChange(handler) {
        settingsListeners.add(handler);
        return () => void settingsListeners.delete(handler);
      },
    },

    async cacheInfo() {
      return call<{ dir: string; bytes: number }>("cacheInfo");
    },

    async clearCache() {
      await call("clearCache");
    },

    updates: {
      // An APK updates by installing a newer one; there is nothing to check or swap in place.
      state: async (): Promise<UpdateState> => ({ status: "manual" }),
      check: async () => {},
      download: async () => {},
      installNow: async () => {},
      whatsNew: async () => null,
      onState: () => () => {},
    },

    history: {
      async list(projectPath) {
        const { list } = await call<{ list: Array<{ file: string; time: number; bytes: number }> }>("historyList", { projectPath });
        return list ?? [];
      },
      async restore(projectPath, file) {
        const { restored } = await call<{ restored: boolean }>("historyRestore", { projectPath, file });
        return restored;
      },
    },

    snapshots: {
      async save(name, json) {
        const { entry } = await call<{ entry: { file: string; name: string; time: number; bytes: number } | null }>("snapshotsSave", { name, json });
        return entry ?? null;
      },
      async list(projectId) {
        const { list } = await call<{ list: Array<{ file: string; name: string; time: number; bytes: number }> }>("snapshotsList", { projectId });
        return list ?? [];
      },
      async read(projectId, file) {
        return (await call<{ text: string | null }>("snapshotsRead", { projectId, file })).text;
      },
      async remove(projectId, file) {
        return (await call<{ removed: boolean }>("snapshotsRemove", { projectId, file })).removed;
      },
    },

    session: {
      async take() {
        const { notice } = await call<{ notice: { reason: "crash" | "unclean"; path: string; name: string } | null }>("sessionTake");
        return notice ?? null;
      },
      projectClosed() {
        void call("sessionClosed");
      },
    },

    systemInfo: () => call<Record<string, unknown>>("systemInfo") as never,

    // The desktop control socket has no phone counterpart.
    agent: undefined,

    news: undefined,

    templates: {
      // The starters ship inside the bundle (the Templates panel reads them directly). Yours
      // are not on the phone's home screen; a template saved on a desktop travels through
      // the panel's export, which the phone can import when it is shared to it.
      list: async () => [],
      async save(template) {
        return { ok: true as const, template };
      },
      async remove() {},
      async pick() {
        return { added: [], duplicates: 0, problems: [] };
      },
      async importDropped() {
        return { added: [], duplicates: 0, problems: [] };
      },
      export: async () => null,
      community: async () => ({ templates: [], updated: null }),
      refreshCommunity: async () => ({ templates: [], updated: null }),
    },

    onMenu() {
      // No application menu on a phone; the browser back gesture closes dialogs on its own.
      return () => {};
    },

    setTitle(title: string) {
      document.title = title;
    },

    setDirty() {
      // There is no window close button whose state a phone would show.
    },
  };

  window.openroll = api;

  // Settings written from the native side (there is no other writer today) reach the page here.
  listen("motion-settings", data => {
    settings = data as Partial<Settings>;
    for (const handler of settingsListeners) handler(settings as Settings);
  });

  // A document shared into the app from another app's share sheet becomes a new project.
  window.addEventListener("motion-share", e => {
    const uri = (e as CustomEvent<string>).detail;
    void importShared(uri);
  });
}

const importShared = async (uri: string): Promise<void> => {
  try {
    const { files: imported } = await native.importShared({ uri });
    if (!imported?.length) return;
    window.dispatchEvent(new CustomEvent("motion-open-media", { detail: imported }));
  } catch {
    // A share the app cannot read is dropped; the person can still pick the file.
  }
};

// Bare Uint8Array to base64, in chunks so a 60 MB frame does not blow the argument limit of
// String.fromCharCode.
export function base64(bytes: Uint8Array, offset = 0, length = bytes.length): string {
  const chunk = 0x8000;
  let out = "";
  for (let at = 0; at < length; at += chunk) {
    const part = bytes.subarray(offset + at, offset + Math.min(at + chunk, length));
    out += String.fromCharCode(...part);
  }
  return btoa(out);
}

function fromBase64(text: string): Uint8Array {
  const bin = atob(text);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export { fromBase64 };
export const isAndroid = (): boolean => Capacitor.isNativePlatform();
