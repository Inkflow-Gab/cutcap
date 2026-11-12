import { useEffect, useMemo, useRef, useState } from "react";
import type { FileInfo, GalleryAlbum, GalleryItem } from "../../shared/api";
import { Icon } from "./kit/Icon";
import { nativeMessage } from "../platform/native-error";

/**
 * Picks photos and videos from the device's own gallery.
 *
 * Android's document picker is a file browser: no thumbnails, no albums, no sense of what is
 * recent. This is the thing a phone editor is expected to have instead — a grid of the phone's
 * pictures, a row of albums to narrow it, tabs for Photos and Videos, and sort order.
 *
 * Selection is ordered, not a set. The number on a tile is the order it will be added to the
 * timeline, so "the first one" is a fact the person can see rather than a guess about list order.
 * Tapping a chosen tile removes it and the numbers after it close up.
 */

export type Kind = "image" | "video";
export type Sort = "newest" | "oldest" | "name";

interface Props {
  onClose(): void;
  onPicked(files: FileInfo[]): void;
  /** Which tab to open on; the editor passes the one that matches what it was asked for. */
  kind?: Kind;
  /** Opens the system file browser, for media the gallery does not index. */
  onBrowseFiles?(): void;
}

const SORTS: { id: Sort; label: string }[] = [
  { id: "newest", label: "Newest" },
  { id: "oldest", label: "Oldest" },
  { id: "name", label: "A–Z" },
];

/**
 * What the grid shows: one album if one is chosen, in the chosen order.
 *
 * Pure and exported because the order is the part that has to be right — it decides which clip
 * lands first — and it is easier to hold to account away from the rendering around it.
 */
export function arrange(items: GalleryItem[], album: string | null, sort: Sort): GalleryItem[] {
  const list = album ? items.filter(i => i.album === album) : items;
  const copy = [...list];
  if (sort === "oldest") copy.sort((a, b) => a.added - b.added);
  else if (sort === "name") copy.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
  else copy.sort((a, b) => b.added - a.added);
  return copy;
}

/**
 * Tapping a tile: chosen ones leave the selection, new ones join at the end.
 *
 * Removing from the middle closes the gap, so the numbers always run 1..n and the clip that was
 * first stays first. This is the whole of "mark it by what was tapped first".
 */
export function toggle(picked: number[], id: number): number[] {
  return picked.includes(id) ? picked.filter(x => x !== id) : [...picked, id];
}

export function GalleryPicker({ onClose, onPicked, kind: initial = "image", onBrowseFiles }: Props) {
  const [kind, setKind] = useState<Kind>(initial);
  const [albums, setAlbums] = useState<GalleryAlbum[] | null>(null);
  const [items, setItems] = useState<GalleryItem[] | null>(null);
  const [album, setAlbum] = useState<string | null>(null);
  const [sort, setSort] = useState<Sort>("newest");
  const [picked, setPicked] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // A refusal needs an explanation and a way out, not a grid that stays empty.
  const [blocked, setBlocked] = useState(false);
  // Which rows the system could not give a picture for. A deleted photo is a normal thing to
  // meet while scrolling an old album; it should look like one, not like a bug in the grid.
  const [broken, setBroken] = useState<ReadonlySet<number>>(new Set());
  // Bumped by Try again, so the effect re-reads after a refusal has been answered in Settings.
  const [attempt, setAttempt] = useState(0);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    const gallery = window.openroll.gallery;
    if (!gallery) {
      setError("This device has no gallery to read.");
      return;
    }
    setAlbums(null);
    setItems(null);
    setAlbum(null);
    setPicked([]);
    setError(null);
    setBlocked(false);
    setBroken(new Set());
    void (async () => {
      try {
        const [a, i] = await Promise.all([gallery.albums(kind), gallery.items(kind)]);
        if (!alive.current) return;
        setAlbums(a);
        setItems(i);
      } catch (e) {
        if (!alive.current) return;
        const message = nativeMessage(e, "The gallery could not be read.");
        setError(message);
        // Both refusals name what is blocked; either one means the grid will never load.
        setBlocked(/allow access to/i.test(message));
      }
    })();
  }, [kind, attempt]);

  const shown = useMemo(() => (items ? arrange(items, album, sort) : null), [items, album, sort]);

  const tap = (id: number) => setPicked(p => toggle(p, id));

  const confirm = async () => {
    const gallery = window.openroll.gallery;
    if (!gallery || !picked.length || busy) return;
    setBusy(true);
    try {
      const { files, failed } = await gallery.import(picked, kind);
      // Handing off closes the sheet, so there is no screen left to complain on: import what was
      // readable and only stay put when that is nothing at all.
      if (files.length) return onPicked(files);
      setError(
        failed === 1
          ? "That one could not be read. It may have been deleted or be on a card that is gone."
          : `Those ${failed} could not be read. They may have been deleted, or be on a card that is gone.`
      );
    } catch (e) {
      setError(nativeMessage(e, "Those photos could not be imported."));
    } finally {
      setBusy(false);
    }
  };

  const albumName = album ? (albums?.find(a => a.id === album)?.name ?? "Album") : "All photos";

  return (
    <div className="gallery" role="dialog" aria-label="Choose from your gallery">
      <header className="gallery-bar">
        <button className="gallery-close" onClick={onClose} aria-label="Close">
          <Icon name="close" size={20} />
        </button>
        <div className="gallery-tabs" role="tablist">
          <button role="tab" aria-selected={kind === "image"} className={kind === "image" ? "on" : ""} onClick={() => setKind("image")}>
            Photos
          </button>
          <button role="tab" aria-selected={kind === "video"} className={kind === "video" ? "on" : ""} onClick={() => setKind("video")}>
            Videos
          </button>
        </div>
        <button
          className="gallery-sort"
          onClick={() => setSort(SORTS[(SORTS.findIndex(s => s.id === sort) + 1) % SORTS.length].id)}
          aria-label={`Sort: ${SORTS.find(s => s.id === sort)!.label}`}
        >
          <Icon name="layout" size={18} />
        </button>
      </header>

      {/* Albums, with All first so the default is the whole gallery. */}
      <div className="gallery-albums" role="tablist" aria-label="Albums">
        <button
          className={"gallery-album" + (album === null ? " on" : "")}
          onClick={() => setAlbum(null)}
          role="tab"
          aria-selected={album === null}
        >
          <span className="gallery-album-cover gallery-album-all">
            {items?.[0] ? (
              <img src={items[0].thumb} alt="" loading="lazy" draggable={false} />
            ) : (
              <Icon name="media" size={18} />
            )}
          </span>
          <span className="gallery-album-name">All</span>
          <small>{items?.length ?? "…"}</small>
        </button>
        {(albums ?? []).map(a => (
          <button
            key={a.id}
            className={"gallery-album" + (album === a.id ? " on" : "")}
            onClick={() => setAlbum(a.id)}
            role="tab"
            aria-selected={album === a.id}
          >
            <span className="gallery-album-cover">
              <img src={a.cover} alt="" loading="lazy" draggable={false} />
            </span>
            <span className="gallery-album-name">{a.name}</span>
            <small>{a.count}</small>
          </button>
        ))}
      </div>

      <p className="gallery-status">
        {albumName}
        {shown ? ` · ${shown.length}` : ""}
        {sort !== "newest" ? ` · ${SORTS.find(s => s.id === sort)!.label}` : ""}
      </p>

      {error && <p className="gallery-error">{error}</p>}

      {!shown && !error && <p className="gallery-loading">Reading your gallery…</p>}
      {shown && !shown.length && <p className="gallery-loading">Nothing here yet.</p>}
      {blocked && (
        <div className="gallery-blocked">
          <p>Motion needs to see your photos and videos to add them to a project. Nothing is uploaded — everything stays on this phone.</p>
          <div className="gallery-blocked-actions">
            {window.openroll.openAppSettings && (
              <button className="gallery-add" onClick={() => void window.openroll.openAppSettings?.()}>
                Open Settings
              </button>
            )}
            <button className="gallery-files" onClick={() => setAttempt(a => a + 1)}>
              Try again
            </button>
            {onBrowseFiles && (
              <button className="gallery-files" onClick={onBrowseFiles}>
                Pick a file instead
              </button>
            )}
          </div>
        </div>
      )}

      {/* The selection in tap order. Tiles carry the numbers too, but a chosen item can be scrolled
          away or live in another album, and the running order is the thing that matters here. */}
      {picked.length > 0 && (
        <div className="gallery-picked" aria-label="Selected, in order">
          {picked.map((id, i) => {
            const item = items?.find(x => x.id === id);
            return (
              <button key={id} className="gallery-chip" onClick={() => tap(id)} aria-label={`Remove ${item?.name ?? "this one"}`}>
                {item && (
                  <img
                    src={item.thumb}
                    alt=""
                    draggable={false}
                    onError={() => setBroken(b => new Set(b).add(id))}
                  />
                )}
                <span className="gallery-chip-n">{i + 1}</span>
              </button>
            );
          })}
        </div>
      )}

      <div className="gallery-grid">
        {(shown ?? []).map(item => {
          const order = picked.indexOf(item.id);
          return (
            <button
              key={item.id}
              className={"gallery-tile" + (order >= 0 ? " on" : "")}
              onClick={() => tap(item.id)}
              aria-pressed={order >= 0}
              aria-label={order >= 0 ? `${item.name}, number ${order + 1} in the selection` : item.name}
            >
              {broken.has(item.id) ? (
                // Deleted, on a card that is gone, or a format the phone cannot decode. A tile
                // that says so is better than a blank square that looks like a bug.
                <span className="gallery-broken">
                  <Icon name="warning" size={16} />
                </span>
              ) : (
                <img
                  src={item.thumb}
                  alt=""
                  loading="lazy"
                  decoding="async"
                  draggable={false}
                  onError={() => setBroken(b => new Set(b).add(item.id))}
                />
              )}
              {order >= 0 && <span className="gallery-order">{order + 1}</span>}
              {kind === "video" && <span className="gallery-play"><Icon name="play" size={12} /></span>}
            </button>
          );
        })}
      </div>

      <footer className="gallery-foot">
        <span className="gallery-count">
          {picked.length ? `${picked.length} selected` : "Pick photos or videos"}
        </span>
        {onBrowseFiles && (
          // Not everything is in the gallery: a clip in Downloads, a drive, a USB stick.
          <button className="gallery-files" onClick={onBrowseFiles}>
            Files
          </button>
        )}
        <button className="gallery-add" disabled={!picked.length || busy} onClick={() => void confirm()}>
          {busy ? "Adding…" : picked.length > 1 ? `Add ${picked.length}` : "Add"}
        </button>
      </footer>
    </div>
  );
}
