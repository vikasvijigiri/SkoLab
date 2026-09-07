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
const LIGHT = {
  "page-bg": "#f7f7f5",
  surface: "#ffffff",
  "surface-subtle": "#eeeeec",
  "text-primary": "#1b1d23",
  "text-secondary": "#565863",
  "text-muted": "#5f616d",
  "text-on-primary": "#ffffff",
  primary: "#3552cf",
  "primary-dark": "#2c46b8",
  "primary-deeper": "#233a99",
  "accent-signal": "#c9401f",
  "accent-signal-dark": "#af3819",
};

const DARK = {
  "page-bg": "#1a1b18",
  surface: "#232420",
  "surface-subtle": "#2b2c26",
  "text-primary": "#e9e5da",
  "text-secondary": "#b1ac9f",
  "text-muted": "#9c978a",
  "text-on-primary": "#1a1b18",
  primary: "#93a5ff",
  "primary-dark": "#7f92f5",
  "primary-deeper": "#6b80ea",
  "accent-signal": "#ff7a5c",
  "accent-signal-dark": "#ff6749",
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
