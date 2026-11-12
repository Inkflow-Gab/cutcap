/**
 * A message from anything a call can fail with.
 *
 * Capacitor is the reason this exists: a native plugin rejects its promise with the error string
 * itself, not with an Error. `(e as Error).message` is therefore `undefined` for every failure
 * that comes from Java, which turns "Allow access to photos in Settings…" into a blank panel with
 * no explanation. The same code also has to survive a real Error, a thrown string, and the
 * occasional object.
 */
export function nativeMessage(e: unknown, fallback: string): string {
  if (typeof e === "string") return e.trim() || fallback;
  if (e && typeof e === "object") {
    const message = (e as { message?: unknown }).message;
    if (typeof message === "string" && message.trim()) return message;
    const error = (e as { error?: unknown }).error;
    if (typeof error === "string" && error.trim()) return error;
  }
  return fallback;
}
