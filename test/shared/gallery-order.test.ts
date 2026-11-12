import { describe, expect, it } from "vitest";
import { arrange, toggle, type Sort } from "../../src/renderer/ui/GalleryPicker";
import type { GalleryItem } from "../../src/shared/api";

const item = (id: number, added: number, name: string, album: string): GalleryItem => ({
  id,
  added,
  name,
  album,
  size: 1024,
  thumb: `https://localhost/motion-media/x${id}.jpg`,
});

const items = [item(3, 300, "IMG_3.jpg", "cam"), item(1, 100, "IMG_1.jpg", "shots"), item(2, 200, "IMG_10.jpg", "cam")];
const ids = (list: GalleryItem[]) => list.map(i => i.id);

/**
 * Selection order is the feature, not a detail: the number on a tile is the order the clip lands
 * on the timeline, so "the first one I tapped" has to be first.
 */
describe("gallery selection order", () => {
  it("keeps the order things were tapped in", () => {
    let picked: number[] = [];
    picked = toggle(picked, 2);
    picked = toggle(picked, 3);
    picked = toggle(picked, 1);
    expect(picked).toEqual([2, 3, 1]);
  });

  it("closes the gap when something in the middle is removed", () => {
    expect(toggle([2, 3, 1], 3)).toEqual([2, 1]);
  });

  it("appends when an item is added again, rather than restoring its old slot", () => {
    const removed = toggle([1, 2, 3], 2);
    expect(removed).toEqual([1, 3]);
    expect(toggle(removed, 2)).toEqual([1, 3, 2]);
  });

  it("does not mutate the list it was given", () => {
    const picked = [1];
    toggle(picked, 2);
    expect(picked).toEqual([1]);
  });
});

describe("what the grid shows", () => {
  const sort = (s: Sort) => ids(arrange(items, null, s));

  it("is newest first by default", () => expect(sort("newest")).toEqual([3, 2, 1]));
  it("can be reversed", () => expect(sort("oldest")).toEqual([1, 2, 3]));
  it("sorts names numerically, so 10 does not come before 3", () => expect(sort("name")).toEqual([1, 3, 2]));
  it("narrows to one album", () => expect(ids(arrange(items, "cam", "newest"))).toEqual([3, 2]));
  it("shows nothing for an album with no items, rather than everything", () =>
    expect(ids(arrange(items, "missing", "newest"))).toEqual([]));

  it("does not reorder the source when sorting", () => {
    const source = [...items];
    arrange(items, null, "oldest");
    expect(items).toEqual(source);
  });
});
