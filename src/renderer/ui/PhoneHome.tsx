import { useMemo, useState } from "react";
import type { FileInfo, RecentProject } from "../../shared/api";
import { Icon, type IconName } from "./kit/Icon";
import { GalleryPicker } from "./GalleryPicker";

/**
 * The home screen for phones.
 *
 * A desktop home can afford two big choices and a project grid. A phone's is the first thing you
 * touch, so it leads with the thing you came to do — start a project with your clips — and puts
 * the tools underneath as a grid you can hit with a thumb. The bottom bar switches between the
 * three places worth being: your projects, the tool grid, and the app's own settings.
 *
 * Everything here routes into an action that already existed: the tools either start a project in
 * a given shape or open the editor with a panel selected. Nothing on this screen is a dead end.
 */

export type PhoneTab = "edit" | "tools" | "me";

interface Props {
  projects: RecentProject[] | null;
  onOpen(project: RecentProject): void;
  onNew(files?: FileInfo[], shape?: { width: number; height: number }): void;
  onSettings(): void;
  onInbox(): void;
  hasNews?: boolean;
}

interface Tool {
  id: string;
  label: string;
  icon: IconName;
  /** What the tool needs before it can run. */
  note?: string;
  run: () => void;
}

const SHAPES = {
  widescreen: { width: 1920, height: 1080 },
  vertical: { width: 1080, height: 1920 },
  square: { width: 1080, height: 1080 },
  portrait: { width: 1080, height: 1350 },
};

export function PhoneHome({ projects, onOpen, onNew, onSettings, onInbox, hasNews }: Props) {
  const [tab, setTab] = useState<PhoneTab>("edit");
  // What a gallery pick is for: a new project in a given shape, or nothing when the tools just
  // want clips of any shape.
  const [picking, setPicking] = useState<{ kind: "image" | "video"; shape?: { width: number; height: number } } | null>(null);

  /**
   * Starts a project from clips.
   *
   * When the device has a gallery, that is what opens: it shows the phone's own photos with
   * thumbnails and albums, which is what a person expects from a phone editor. The system picker
   * is still there for anything the gallery cannot see — a file in Downloads, a drive, a USB
   * stick — behind the "Files" button on the same screen.
   */
  const startWithClips = (shape?: { width: number; height: number }, kind: "image" | "video" = "video") => {
    if (window.openroll.gallery) setPicking({ kind, shape });
    else void fromSystemPicker(shape);
  };

  const fromSystemPicker = async (shape?: { width: number; height: number }) => {
    // Picking happens before the editor exists, so a cancelled picker is simply nothing.
    const files = await window.openroll.pickMedia();
    if (files.length) onNew(files, shape);
  };

  const tools = useMemo<Tool[]>(
    () => [
      {
        id: "trim",
        label: "Trim a video",
        icon: "crop",
        run: () => startWithClips(),
      },
      {
        id: "vertical",
        label: "Vertical video",
        icon: "shapes",
        note: "9:16",
        run: () => startWithClips(SHAPES.vertical),
      },
      {
        id: "square",
        label: "Square",
        icon: "shapes",
        note: "1:1",
        run: () => startWithClips(SHAPES.square),
      },
      {
        id: "wide",
        label: "Widescreen",
        icon: "shapes",
        note: "16:9",
        run: () => startWithClips(SHAPES.widescreen),
      },
      {
        id: "audio",
        label: "Record voice",
        icon: "mic",
        run: () => startWithClips(),
      },
      {
        id: "captions",
        label: "Captions",
        icon: "captions",
        run: () => startWithClips(),
      },
      {
        id: "empty",
        label: "Blank project",
        icon: "plus",
        run: () => onNew(undefined, SHAPES.vertical),
      },
      {
        id: "templates",
        label: "Templates",
        icon: "templates",
        run: () => startWithClips(),
      },
    ],
    // startWithClips closes over onNew only.
    [onNew],
  );

  return (
    <div className="phome">
      <header className="phome-bar">
        <div className="phome-brand">
          <Icon name="play" size={17} />
          <span>Motion</span>
        </div>
        <div className="phome-bar-actions">
          <button className="phome-icon" onClick={onInbox} aria-label="Inbox">
            <Icon name="bell" size={19} />
            {hasNews && <i className="phome-dot" />}
          </button>
          <button className="phome-icon" onClick={onSettings} aria-label="Settings">
            <Icon name="shapes" size={19} />
          </button>
        </div>
      </header>

      <main className="phome-main">
        {tab === "edit" && (
          <>
            <button className="phome-new" onClick={() => startWithClips()}>
              <span className="phome-new-plus">
                <Icon name="plus" size={24} />
              </span>
              <b>New project</b>
              <small>Pick clips, photos or audio</small>
            </button>

            <section className="phome-recent">
              <h2>
                Projects
                {projects?.length ? <span className="phome-count">{projects.length}</span> : null}
              </h2>
              {projects && !projects.length && (
                <p className="phome-empty">Your projects land here. They save as you work.</p>
              )}
              <div className="phome-list">
                {(projects ?? []).map(p => (
                  <button key={p.path} className="phome-card" onClick={() => onOpen(p)}>
                    <span className="phome-thumb">
                      {p.thumb ? <img src={window.openroll.mediaUrl(p.thumb)} alt="" draggable={false} /> : null}
                    </span>
                    <span className="phome-card-text">
                      <b>{p.name}</b>
                      <small>{relative(p.modified)}</small>
                    </span>
                    <Icon name="chevron" size={15} />
                  </button>
                ))}
              </div>
            </section>
          </>
        )}

        {tab === "tools" && (
          <section className="phome-tools">
            <h2>Tools</h2>
            <div className="phome-grid">
              {tools.map(t => (
                <button key={t.id} className="phome-tool" onClick={t.run}>
                  <span className="phome-tool-icon">
                    <Icon name={t.icon} size={21} />
                  </span>
                  <span className="phome-tool-label">{t.label}</span>
                  {t.note && <small>{t.note}</small>}
                </button>
              ))}
            </div>
            <p className="phome-hint">
              Tools that need clips ask for them first. Everything works offline — nothing is uploaded.
            </p>
          </section>
        )}

        {tab === "me" && (
          <section className="phome-me">
            <h2>Motion</h2>
            <p className="phome-version">A free video editor. No account, no uploads, no watermark.</p>
            <button className="phome-row" onClick={onSettings}>
              <Icon name="shapes" size={18} />
              <span>Settings</span>
              <Icon name="chevron" size={15} />
            </button>
            <button className="phome-row" onClick={onInbox}>
              <Icon name="bell" size={18} />
              <span>Inbox</span>
              <Icon name="chevron" size={15} />
            </button>
          </section>
        )}
      </main>

      {picking && (
        <GalleryPicker
          kind={picking.kind}
          onClose={() => setPicking(null)}
          onBrowseFiles={() => {
            const shape = picking.shape;
            setPicking(null);
            void fromSystemPicker(shape);
          }}
          onPicked={files => {
            setPicking(null);
            if (files.length) onNew(files, picking.shape);
          }}
        />
      )}

      <nav className="phome-tabs" aria-label="Sections">
        {(
          [
            { id: "edit", label: "Edit", icon: "video" },
            { id: "tools", label: "Tools", icon: "shapes" },
            { id: "me", label: "Me", icon: "lock" },
          ] as const
        ).map(t => (
          <button
            key={t.id}
            className={"phome-tab" + (tab === t.id ? " on" : "")}
            onClick={() => setTab(t.id)}
            aria-current={tab === t.id}
          >
            <Icon name={t.icon} size={20} />
            <span>{t.label}</span>
          </button>
        ))}
      </nav>
    </div>
  );
}

function relative(ms: number): string {
  const d = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  if (d < 60) return "Just now";
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
  if (d < 86400 * 7) return `${Math.floor(d / 86400)} d ago`;
  return new Date(ms).toLocaleDateString();
}
