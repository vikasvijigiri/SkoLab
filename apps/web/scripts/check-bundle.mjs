// Fails when the production build outgrows performance-budget.json, so the
// app can't quietly get heavier. Sizes are gzip-compressed, as served.
// "Initial" is what index.html loads before anything else can happen: its
// scripts, their static imports (modulepreload) and stylesheets. Everything
// else is a lazy chunk, loaded when a page needs it.
import { readdirSync, readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";

const dist = new URL("../dist/", import.meta.url);
const budget = JSON.parse(readFileSync(new URL("../performance-budget.json", import.meta.url), "utf8"));
const html = readFileSync(new URL("index.html", dist), "utf8");

const kb = (file) => gzipSync(readFileSync(new URL(file, dist)), { level: 9 }).length / 1024;
const initial = new Set([...html.matchAll(/(?:src|href)="\/(assets\/[^"]+\.(?:js|css))"/g)].map((m) => m[1]));
const assets = readdirSync(new URL("assets/", dist))
  .filter((name) => /\.(?:m?js|css)$/.test(name))
  .map((name) => {
    const file = `assets/${name}`;
    return { file, size: kb(file), initial: initial.has(file), css: name.endsWith(".css"), worker: /worker/i.test(name) };
  });

const sum = (list) => list.reduce((total, asset) => total + asset.size, 0);
const largest = (list) => list.reduce((top, asset) => (asset.size > (top?.size ?? -1) ? asset : top), null);
const js = assets.filter((a) => !a.css);
const lazy = js.filter((a) => !a.initial && !a.worker);
const checks = [
  ["initialJs", "JavaScript loaded up front", sum(js.filter((a) => a.initial))],
  ["initialCss", "CSS loaded up front", sum(assets.filter((a) => a.css && a.initial))],
  ["largestLazyJs", `largest lazy chunk${lazy.length ? ` (${largest(lazy).file})` : ""}`, largest(lazy)?.size ?? 0],
  ["largestWorker", "largest web worker", largest(js.filter((a) => a.worker))?.size ?? 0],
  ["totalJs", "all JavaScript", sum(js)],
];

let failed = false;
const lines = ["| Budget | Size (gzip) | Limit | |", "|---|---:|---:|---|"];
for (const [key, label, size] of checks) {
  const limit = budget[key];
  const ok = size <= limit;
  failed ||= !ok;
  lines.push(`| ${label} | ${size.toFixed(1)} kB | ${limit} kB | ${ok ? "ok" : "**over**"} |`);
  if (!ok) console.error(`::error::${label} is ${size.toFixed(1)} kB gzipped, over its ${limit} kB budget (performance-budget.json ${key})`);
}
console.log(lines.join("\n"));
if (process.env.GITHUB_STEP_SUMMARY) {
  const { appendFileSync } = await import("node:fs");
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Bundle size\n\n${lines.join("\n")}\n`);
}
if (failed) process.exit(1);
