import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";

/**
 * Launcher icons from the same artwork the desktop app uses.
 *
 *  - Adaptive icon (Android 8+): the film reel on its own, transparent, sized to the 66dp safe
 *    circle inside the 108dp canvas, so no launcher crops it. The background is the app's
 *    graphite.
 *  - Legacy icon (Android 7): the artwork as drawn, in a rounded square.
 *  - Round icon: the same artwork, for launchers that ask for one.
 *
 * Run with `npm run icons:android`. Needs ImageMagick (`convert`), which CI has.
 */
const SRC = "build/icon.png";
const RES = "android/app/src/main/res";

/** Pixel sizes Android expects for each density bucket, at 108dp for adaptive icons. */
const ADAPTIVE = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };
/** Legacy launcher icons are 48dp. */
const LEGACY = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };

if (!existsSync(SRC)) {
  console.error(`Missing ${SRC}`);
  process.exit(1);
}

const run = (args) => execFileSync("convert", args, { stdio: "inherit" });
const tmp = (name) => join("/tmp", `openroll-icon-${name}.png`);

// The reel with the white page behind it removed, cropped to its own edges.
run([SRC, "-fuzz", "12%", "-transparent", "white", "-trim", "+repage", tmp("mark")]);

for (const [density, size] of Object.entries(ADAPTIVE)) {
  const dir = join(RES, `mipmap-${density}`);
  mkdirSync(dir, { recursive: true });
  // 66/108 of the canvas is the safe zone; the mark is centred inside it.
  const inner = Math.round(size * (66 / 108));
  run([tmp("mark"), "-resize", `${inner}x${inner}`, "-gravity", "center", "-background", "none", "-extent", `${size}x${size}`, join(dir, "ic_launcher_foreground.png")]);
}

for (const [density, size] of Object.entries(LEGACY)) {
  const dir = join(RES, `mipmap-${density}`);
  mkdirSync(dir, { recursive: true });
  // Legacy icons keep a small margin so a square mask still reads as the app's shape.
  const pad = Math.round(size * 0.06);
  run([SRC, "-resize", `${size - pad * 2}x${size - pad * 2}`, "-gravity", "center", "-background", "#141415", "-extent", `${size}x${size}`, join(dir, "ic_launcher.png")]);
  // A round one: the same artwork, masked to a circle.
  const r = size / 2 - 1;
  run([
    SRC,
    "-resize",
    `${size}x${size}`,
    "(",
    "-size",
    `${size}x${size}`,
    "xc:black",
    "-fill",
    "white",
    "-draw",
    `circle ${size / 2},${size / 2} ${size / 2},${size / 2 - r}`,
    ")",
    "-alpha",
    "off",
    "-compose",
    "CopyOpacity",
    "-composite",
    join(dir, "ic_launcher_round.png"),
  ]);
}

console.log("Android launcher icons written to", RES);
