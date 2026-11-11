import { useEffect, useState } from "react";
import { Icon } from "./kit/Icon";

/**
 * Tells the person when the device has no network.
 *
 * The editor is entirely local — clips decode from app storage, audio is mixed in the WebView,
 * exports are written to a file — so losing the network changes nothing about editing. That is
 * worth saying out loud once, in a way that also makes clear nothing is being uploaded, rather
 * than leaving a spinner on a panel that will never load.
 */
export function OfflineBanner() {
  const [offline, setOffline] = useState(() => typeof navigator !== "undefined" && navigator.onLine === false);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    const down = () => {
      setOffline(true);
      setDismissed(false);
    };
    const up = () => setOffline(false);
    window.addEventListener("offline", down);
    window.addEventListener("online", up);
    return () => {
      window.removeEventListener("offline", down);
      window.removeEventListener("online", up);
    };
  }, []);

  if (!offline || dismissed) return null;
  return (
    <div className="offline-banner" role="status">
      <Icon name="warning" size={15} />
      <span>Offline. Editing, export and your projects all keep working.</span>
      <button onClick={() => setDismissed(true)} aria-label="Dismiss">
        <Icon name="close" size={14} />
      </button>
    </div>
  );
}
