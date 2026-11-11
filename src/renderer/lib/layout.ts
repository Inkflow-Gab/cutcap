import { useSyncExternalStore } from "react";

/**
 * Which layout the app is running: the desktop console, or the phone one the Android build
 * uses. The stylesheet decides the shapes; this decides which controls exist at all, so a
 * phone never renders a button for a window it does not have.
 *
 * The phone check is the same width the stylesheet switches at, so the two never disagree.
 */
const QUERY = "(max-width: 820px)";

function subscribe(fn: () => void): () => void {
  const mq = window.matchMedia(QUERY);
  mq.addEventListener("change", fn);
  return () => mq.removeEventListener("change", fn);
}

const snapshot = (): boolean => window.matchMedia(QUERY).matches;

/** True when the narrow, touch-first layout is in charge. */
export function usePhoneLayout(): boolean {
  return useSyncExternalStore(subscribe, snapshot, () => false);
}

/** The desktop build is never a phone, even in a narrow window: it has a pointer and a keyboard. */
export function useLayout(): { phone: boolean } {
  const narrow = usePhoneLayout();
  return { phone: narrow && window.openroll.platform === "android" };
}
