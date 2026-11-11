import { Capacitor } from "@capacitor/core";

export interface Env {
  pickMedia(): Promise<FileInfo[]>;
  pathsForDropped(files: File[]): Promise<FileInfo[]>;
  mediaUrl(path: string): string;
  stat(path: string): Promise<FileInfo | null>;
  pickLut(): Promise<{ name: string; text: string } | null>;
  createWriter(suggestedName: string, kind: "video" | "audio" | "subtitles" | "image"): Promise<{ id: number; path: string; url?: string } | null>;
  write(id: number, position: number, data: Uint8Array): Promise<void>;
  resetWriter(id: number): Promise<void>;
  closeWriter(id: number, abort?: boolean): Promise<void>;
  revealInFolder(path: string): Promise<void>;

  /** Converts files the WebView cannot decode. Null when no converter ships with the app. */
  convert: {
    status(): Promise<{ available: boolean }>;
    inspect(path: string): Promise<InspectResult | null>;
    prepare(path: string, hints?: { videoDecodable?: boolean }): Promise<DerivedState | null>;
    proxy(path: string, req: ProxyRequest): Promise<DerivedState | null>;
    existing(paths: string[]): Promise<Record<string, DerivedState[]>>;
    cancel(path: string, kind: DerivedKind): void;
    setPlayback(active: boolean): void;
    onState(handler: (path: string, state: DerivedState) => void): () => void;
  } | null;

  /** Frame pipes into ffmpeg. Null when ffmpeg is not part of the app. */
  ffexport: {
    open(): Promise<string>;
    audio(id: string, data: Uint8Array): Promise<void>;
    begin(id: string, writerId: number, spec: PipeSpec): Promise<void>;
    frame(id: string, data: Uint8Array): Promise<void>;
    finish(id: string, spec: PipeSpec): Promise<number>;
    cancel(id: string): Promise<void>;
  } | null;

  relink: {
    missing(paths: string[]): Promise<string[]>;
    auto(missing: MissingFile[]): Promise<(RelinkMatch & { file: FileInfo })[]>;
    folder(missing: MissingFile[]): Promise<{ folder: string; matches: (RelinkMatch & { file: FileInfo })[] } | null>;
    file(missing: MissingFile[], targetId: string): Promise<{ matches: (RelinkMatch & { file: FileInfo })[] } | null>;
  };

  projects: {
    dir(): Promise<string>;
    list(): Promise<RecentProject[]>;
    load(path: string): Promise<string | null>;
    save(path: string | null, name: string, json: string): Promise<SaveResult>;
    saveThumb(path: string, jpeg: Uint8Array): Promise<void>;
    remove(path: string): Promise<void>;
  };
  cache: {
    get(key: string): Promise<Uint8Array | null>;
    put(key: string, data: Uint8Array): Promise<void>;
    file(key: string): Promise<FileInfo | null>;
  };
  saveRecording(name: string, ext: "wav", data: Uint8Array): Promise<FileInfo | null>;
  settings: {
    get(): Promise<Settings>;
    set(patch: Partial<Settings>): Promise<Settings>;
    pickProjectsDir(): Promise<string | null>;
    onChange(handler: (s: Settings) => void): () => void;
  };
  cacheInfo(): Promise<CacheInfo>;
  clearCache(): Promise<void>;
  updates: {
    state(): Promise<UpdateState>;
    check(): Promise<void>;
    download(): Promise<void>;
    installNow(): Promise<void>;
    whatsNew(): Promise<WhatsNew | null>;
    onState(handler: (s: UpdateState) => void): () => void;
  };
  history: {
    list(projectPath: string): Promise<HistoryEntry[]>;
    restore(projectPath: string, file: string): Promise<boolean>;
  };
  snapshots: {
    save(name: string, json: string): Promise<VersionEntry | null>;
    list(projectId: string): Promise<VersionEntry[]>;
    read(projectId: string, file: string): Promise<string | null>;
    remove(projectId: string, file: string): Promise<boolean>;
  };
  session: { take(): Promise<SessionNotice | null>; projectClosed(): void };
  systemInfo(): Promise<SystemInfo>;
  agent: {
    onCall(handler: (call: { callId: number; method: string; params: unknown; client: string }) => void): () => void;
    reply(callId: number, reply: { id: number; result?: unknown; error?: { message: string } }): void;
    clients(): Promise<AgentClient[]>;
    onClients(handler: (clients: AgentClient[]) => void): () => void;
    disconnect(id: number): Promise<void>;
    connectInfo(): Promise<{ executable: string; args: string[] }>;
    allow(paths: string[]): Promise<FileInfo[]>;
    writerAt(path: string | null, fallbackName: string): Promise<{ id: number; path: string }>;
  } | null;
  news: {
    list(): Promise<{ items: NewsItem[]; read: string[] }>;
    markRead(ids: string[]): Promise<void>;
    onChange(handler: (items: NewsItem[]) => void): () => void;
  } | null;
  templates: {
    list(): Promise<Template[]>;
    save(template: Template): Promise<SaveTemplateResult>;
    remove(id: string): Promise<void>;
    pick(): Promise<TemplateImport>;
    importDropped(files: File[]): Promise<TemplateImport>;
    export(template: Template): Promise<string | null>;
    community(): Promise<CommunityState>;
    refreshCommunity(): Promise<CommunityState>;
  };
  onMenu(handler: (command: string) => void): () => void;
  setTitle(title: string): void;
  setDirty(dirty: boolean): void;
  qa?: { allow(paths: string[]): Promise<FileInfo[]> };
}

import type { AgentClient } from "../../shared/agent-protocol";
import type { DerivedKind, DerivedState, InspectResult, ProxyRequest } from "../../shared/derived";
import type { PipeSpec } from "../../shared/ffexport";
import type { NewsItem } from "../../shared/news";
import type { MissingFile, RelinkMatch } from "../../shared/relink";
import type { Settings } from "../../shared/settings";
import type { CommunityState, SaveTemplateResult, TemplateImport } from "../../shared/templates";
import type { FileInfo, CacheInfo, HistoryEntry, RecentProject, SaveResult, SessionNotice, SystemInfo, UpdateState, VersionEntry, WhatsNew } from "../../shared/api";
import type { Template } from "../../core/template";

/** True on the Android build, where this module is the only implementation. */
export const IS_ANDROID = Capacitor.isNativePlatform();

/** Filled in by the Android layer once the native side answers. */
export const env = {
  /** "ffmpeg" when the app ships the converter (see native status), null otherwise. */
  converter: null as null | { available: boolean },
} as const;
