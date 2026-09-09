/**
 * WCAG 2.1 relative-luminance contrast gate for the design tokens.
 *
 * `vitest` runs with `css: false`, so token colours never reach jsdom and their
 * contrast cannot be asserted in a component test. This standalone check is the
 * mechanism `DESIGN.md`'s accessibility floor names: every token pair that has
 * a contrast requirement is verified here, for both themes, on every run.
 *
 * No dependencies — pure Node. Exit 1 on any miss so it can gate CI / a hook.
 */

// --- token values — MUST mirror src/app/globals.css -------------------------
// Professional network theme (DESIGN.md / decision 0013): light is the base.
const LIGHT = {
  "page-bg": "#f3f2ef",
  surface: "#ffffff",
  "surface-subtle": "#f3f2ef",
  "text-primary": "#191919",
  "text-secondary": "#666666",
  "text-muted": "#666666",
  "text-on-primary": "#ffffff",
  primary: "#0a66c2",
  "primary-dark": "#004182",
  "primary-deeper": "#003a70",
  "accent-signal": "#0a66c2",
  "accent-signal-dark": "#004182",
};

const DARK = {
  "page-bg": "#0b0d10",
  surface: "#14171b",
  "surface-subtle": "#1c2026",
  "text-primary": "#e6e9ee",
  "text-secondary": "#aab1bd",
  "text-muted": "#8b93a0",
  "text-on-primary": "#0b0d10",
  primary: "#8f88ff",
  "primary-dark": "#a49dff",
  "primary-deeper": "#6b62f0",
  "accent-signal": "#ff6b4a",
  "accent-signal-dark": "#ff8163",
};

// [foreground token, background token, minimum ratio, note]
const PAIRS = [
  ["text-on-primary", "primary", 4.5, "button label on brand fill"],
  ["text-on-primary", "accent-signal", 4.5, "CTA label on signal fill"],
  ["primary", "surface", 3.0, "brand as UI element / link on card"],
  ["primary", "surface-subtle", 4.5, "brand as link text on inset"],
  ["accent-signal", "surface", 3.0, "signal as UI element on card"],
  ["text-primary", "surface", 4.5, "primary text on card"],
  ["text-primary", "surface-subtle", 4.5, "primary text on inset"],
  ["text-primary", "page-bg", 4.5, "primary text on page"],
  ["text-secondary", "surface", 4.5, "secondary text on card"],
  ["text-secondary", "surface-subtle", 4.5, "secondary text on inset"],
  ["text-muted", "surface", 4.5, "muted text / eyebrow on card"],
  ["text-muted", "surface-subtle", 4.5, "muted text / eyebrow on inset"],
  ["text-muted", "page-bg", 4.5, "muted text on page"],
];

// --- WCAG maths -------------------------------------------------------------
function channel(c) {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}
function luminance(hex) {
  const n = parseInt(hex.slice(1), 16);
  const r = (n >> 16) & 255;
  const g = (n >> 8) & 255;
  const b = n & 255;
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}
function ratio(hex1, hex2) {
  const l1 = luminance(hex1);
  const l2 = luminance(hex2);
  const [hi, lo] = l1 >= l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

// --- run -----------------------------------------------------------------
let fails = 0;
for (const [label, tokens] of [
  ["LIGHT", LIGHT],
  ["DARK", DARK],
]) {
  console.log(`\n${label}`);
  console.log(
    "  " +
      "pair".padEnd(38) +
      "ratio".padStart(7) +
      "  min   result",
  );
  for (const [fg, bg, min, note] of PAIRS) {
    const r = ratio(tokens[fg], tokens[bg]);
    const ok = r >= min;
    if (!ok) fails++;
    console.log(
      "  " +
        `${fg} on ${bg}`.padEnd(38) +
        r.toFixed(2).padStart(7) +
        `  ${min.toFixed(1)}   ${ok ? "PASS" : "FAIL"}   ${note}`,
    );
  }
}

console.log(
  fails === 0
    ? "\nAll token contrast pairs pass.\n"
    : `\n${fails} contrast pair(s) below threshold.\n`,
);
process.exit(fails === 0 ? 0 : 1);
