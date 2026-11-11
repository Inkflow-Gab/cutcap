// Draws the Motion app icon.
//
// The icon is a play triangle that has been pulled into motion: three chevrons trailing it, each
// lighter and smaller, the way a fast object smears. It reads at 48 px (the smallest launcher
// size) because there is one bold shape and two colours, and it says nothing about letters —
// "Motion" as a wordmark does not survive a launcher, so the mark is the word.
//
// Everything is drawn as geometry in a normalised 108-unit square, which is the size Android's
// adaptive icon expects for the foreground; the safe zone is the middle 66 units, so the mark
// stays inside it and the launcher can mask corners without clipping anything.
//
//   node scripts/make-motion-icon.mjs
//
// Writes android/app/src/main/res/mipmap-*/ic_launcher*.png and build/icon-motion.png.

import { mkdir, writeFile, readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const RES = join(ROOT, "android/app/src/main/res");

const BG = "#0B0B0D";
const BG_TOP = "#1C1C22";
const ACCENT = "#4C8DFF";
const ACCENT_LIGHT = "#8FBCFF";

/** Densities Android asks for, and the legacy icon size at each. */
const LEGACY = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
/** The adaptive foreground is 108dp, so 108 px at mdpi. */
const ADAPTIVE = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };

/**
 * The mark, in a 108x108 box, centred inside the 66-unit safe zone.
 *
 * Three trails behind one solid triangle. The trails shrink and fade so the shape reads as
 * movement to the right, which is the one idea the icon has to carry.
 */
function mark(size) {
  const cx = 54;
  const cy = 54;
  // Scales the whole group; 1 fills the safe zone comfortably.
  const s = size / 108;

  const triangle = (x, half, alpha, fill) => {
    const height = half * 1.72;
    return `<path d="M ${x} ${cy - height} L ${x + half * 1.35} ${cy} L ${x} ${cy + height} Z"
              fill="${fill}" fill-opacity="${alpha}"/>`;
  };

  // Trails first so the solid one sits on top.
  return `
    <g>
      ${triangle(cx - 34 * s * 1, 8.5 * s, 0.26, ACCENT_LIGHT)}
      ${triangle(cx - 20 * s * 1, 11 * s, 0.45, ACCENT_LIGHT)}
      ${triangle(cx - 6 * s * 1, 13.5 * s, 0.72, ACCENT)}
      ${triangle(cx + 8 * s * 1, 16 * s, 1, ACCENT)}
    </g>`;
}

/** The icon at `size` px with rounded-corner background, for the legacy (pre-26) launcher. */
function legacySvg(size) {
  const r = size * 0.22;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
    <defs>
      <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="${BG_TOP}"/>
        <stop offset="1" stop-color="${BG}"/>
      </linearGradient>
      <linearGradient id="sheen" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.10"/>
        <stop offset="0.55" stop-color="#FFFFFF" stop-opacity="0"/>
      </linearGradient>
    </defs>
    <rect width="${size}" height="${size}" rx="${r}" fill="url(#bg)"/>
    <rect width="${size}" height="${size}" rx="${r}" fill="url(#sheen)"/>
    <g transform="scale(${size / 108})">${mark(108)}</g>
  </svg>`;
}

/** Round variant: the same thing, clipped to a circle. */
function roundSvg(size) {
  const c = size / 2;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
    <defs>
      <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="${BG_TOP}"/>
        <stop offset="1" stop-color="${BG}"/>
      </linearGradient>
      <clipPath id="c"><circle cx="${c}" cy="${c}" r="${c}"/></clipPath>
    </defs>
    <g clip-path="url(#c)">
      <rect width="${size}" height="${size}" fill="url(#bg)"/>
      <g transform="scale(${size / 108})">${mark(108)}</g>
    </g>
  </svg>`;
}

/**
 * The adaptive foreground: transparent, and the mark is already inside the safe zone, so no
 * extra inset is needed. It is drawn at 108 units regardless of the pixel size.
 */
function foregroundSvg(size) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 108 108">
    ${mark(108)}
  </svg>`;
}

const png = (svg, size) => sharp(Buffer.from(svg)).resize(size, size).png({ compressionLevel: 9 }).toBuffer();

await mkdir(join(RES, "mipmap-anydpi-v26"), { recursive: true });

const written = [];
for (const [density, size] of Object.entries(LEGACY)) {
  const dir = join(RES, `mipmap-${density}`);
  await mkdir(dir, { recursive: true });
  await writeFile(join(dir, "ic_launcher.png"), await png(legacySvg(size), size));
  await writeFile(join(dir, "ic_launcher_round.png"), await png(roundSvg(size), size));
  written.push(`mipmap-${density}/ic_launcher{,_round}.png @${size}`);
}

for (const [density, size] of Object.entries(ADAPTIVE)) {
  const dir = join(RES, `mipmap-${density}`);
  await mkdir(dir, { recursive: true });
  await writeFile(join(dir, "ic_launcher_foreground.png"), await png(foregroundSvg(size), size));
  written.push(`mipmap-${density}/ic_launcher_foreground.png @${size}`);
}

// A large copy for the store listing, the README and the task switcher.
await mkdir(join(ROOT, "build"), { recursive: true });
await writeFile(join(ROOT, "build/icon-motion.png"), await png(legacySvg(1024), 1024));
await writeFile(join(ROOT, "build/icon-motion-round.png"), await png(roundSvg(1024), 1024));
written.push("build/icon-motion{,-round}.png @1024");

// The launcher background colour, shared with the splash and the app theme.
const colorsPath = join(RES, "values/ic_launcher_background.xml");
await writeFile(
  colorsPath,
  `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">${BG}</color>\n</resources>\n`,
);

console.log(written.join("\n"));
