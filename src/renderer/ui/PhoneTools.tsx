import { useState } from "react";
import type { Editor } from "../state/editor";
import { useEditor } from "../lib/hooks";
import { freezeAtPlayhead, setSpeedFor, trimToPlayhead } from "../state/timeline-actions";
import { Icon } from "./kit/Icon";

/**
 * The editing toolbar for phones.
 *
 * On a desktop every one of these actions has a keyboard shortcut or a menu, and a phone has
 * neither. This is a bar of the six things a person actually reaches for while cutting — split,
 * trim, delete, duplicate, speed, freeze — laid out along the bottom the way phone editors do it,
 * with the destructive ones behind a selection so a mis-tap cannot delete a clip nobody chose.
 *
 * The actions are the editor's own, not a parallel implementation: splitting here is the same
 * call the "s" key makes.
 */
export function PhoneTools({ editor }: { editor: Editor }) {
  const selection = useEditor(editor, e => e.selection);
  const hasClip = useEditor(editor, e => e.project.tracks.some(t => t.items.some(i => i.type === "media")));
  const [speedOpen, setSpeedOpen] = useState(false);
  const [speeds] = useState([0.25, 0.5, 1, 1.5, 2, 3]);

  const nothingChosen = selection.length === 0;

  return (
    <div className="phone-tools" role="toolbar" aria-label="Edit">
      <button
        className="phone-tool"
        // splitAtPlayhead already says so when the playhead is not over a clip.
        onClick={() => editor.splitAtPlayhead()}
        disabled={!hasClip}
        aria-label="Split"
      >
        <Icon name="split" size={19} />
        <span>Split</span>
      </button>

      <button
        className="phone-tool"
        onClick={() => trimToPlayhead(editor, "start")}
        disabled={nothingChosen}
        aria-label="Trim start to playhead"
      >
        <Icon name="crop" size={19} />
        <span>Trim</span>
      </button>

      <button
        className="phone-tool"
        onClick={() => editor.duplicateSelection()}
        disabled={nothingChosen}
        aria-label="Duplicate"
      >
        <Icon name="duplicate" size={19} />
        <span>Copy</span>
      </button>

      <button
        className="phone-tool"
        onClick={() => setSpeedOpen(true)}
        disabled={nothingChosen}
        aria-label="Speed"
      >
        <Icon name="speed" size={19} />
        <span>Speed</span>
      </button>

      <button
        className="phone-tool"
        onClick={() => freezeAtPlayhead(editor)}
        disabled={!hasClip}
        aria-label="Freeze frame"
      >
        <Icon name="freeze" size={19} />
        <span>Freeze</span>
      </button>

      <button
        className="phone-tool phone-tool-danger"
        onClick={() => editor.deleteSelection()}
        disabled={nothingChosen}
        aria-label="Delete"
      >
        <Icon name="trash" size={19} />
        <span>Delete</span>
      </button>

      {speedOpen && (
        <div className="phone-sheet-backdrop" onClick={() => setSpeedOpen(false)}>
          <div className="phone-sheet" role="dialog" aria-label="Speed" onClick={e => e.stopPropagation()}>
            <h3>Speed</h3>
            <div className="phone-speed-grid">
              {speeds.map(s => (
                <button
                  key={s}
                  className="phone-speed"
                  onClick={() => {
                    setSpeedFor(editor, s);
                    setSpeedOpen(false);
                  }}
                >
                  {s}×
                </button>
              ))}
            </div>
            <p className="phone-sheet-note">Applies to the selected clip. 1× is normal speed.</p>
            <button className="phone-sheet-close" onClick={() => setSpeedOpen(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
