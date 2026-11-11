import { useSyncExternalStore } from "react";
import { DEFAULT_SETTINGS, sanitizeSettings, type Settings } from "../../shared/settings";
import { derived } from "./derived-media";

let current: Settings = DEFAULT_SETTINGS;
const listeners = new Set<() => void>();

function apply(next: Settings): void {
  // Never trust the platform to have sent a complete object. Android's settings file starts
  // empty, so a raw `{}` used to reach the Viewer, which then indexed QUALITY_SCALE with
  // undefined and asked for an OffscreenCanvas of NaN pixels.
  current = sanitizeSettings(next);
  document.documentElement.dataset.reduceMotion = String(current.reduceMotion);
  derived.setProxyMode(current.proxyMode);
  for (const fn of listeners) fn();
}

/** Loads once and then follows the main process, which owns the file. */
export function startSettings(): void {
  void window.openroll.settings.get().then(apply);
  window.openroll.settings.onChange(apply);
}

export function updateSettings(patch: Partial<Settings>): void {
  // Show the change at once; main confirms with the sanitised copy.
  apply({ ...current, ...patch });
  void window.openroll.settings.set(patch);
}

export function useSettings(): Settings {
  return useSyncExternalStore(
    fn => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    () => current,
  );
}
