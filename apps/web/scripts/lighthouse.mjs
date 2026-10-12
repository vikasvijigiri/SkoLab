// Page-speed gate: Lighthouse (mobile, simulated slow 4G and a mid-range
// phone CPU) on the production build's public pages, served the way the CDN
// serves them (compressed, with the shipped security headers). Each page runs
// three times and the median counts, so one noisy run can't fail the build.
// Thresholds live in performance-budget.json under "lighthouse".
//
// Build it with VITE_API_BASE_URL=http://127.0.0.1:4180/__api so the pages'
// own API calls (Web Vitals reports) stay on this server, never production.
import { readFileSync, existsSync, appendFileSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import * as chromeLauncher from "chrome-launcher";
import lighthouse from "lighthouse";
import { chromium } from "playwright-core";

const root = fileURLToPath(new URL("../dist/", import.meta.url));
const budget = JSON.parse(readFileSync(new URL("../performance-budget.json", import.meta.url), "utf8")).lighthouse;
const headers = JSON.parse(readFileSync(new URL("../security-headers.json", import.meta.url), "utf8"));
delete headers["//"];

const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".png": "image/png", ".ico": "image/x-icon", ".woff2": "font/woff2", ".json": "application/json", ".map": "application/json", ".txt": "text/plain" };

// A static server like Render's: hashed assets cached for a year, unknown
// paths fall back to index.html, text compressed.
const server = createServer((req, res) => {
  if (req.url?.startsWith("/__api/")) {
    res.writeHead(204).end();
    return;
  }
  const path = normalize(decodeURIComponent(new URL(req.url ?? "/", "http://x").pathname)).replace(/^(\.\.[/\\])+/, "");
  let file = join(root, path);
  if (!file.startsWith(root) || !existsSync(file) || path.endsWith("/")) file = join(root, "index.html");
  let body = readFileSync(file);
  const type = types[extname(file)] ?? "application/octet-stream";
  const head = { ...headers, "Content-Type": type, "Cache-Control": file.includes("/assets/") ? "public, max-age=31536000, immutable" : "no-cache" };
  if (/^(text|application\/json|image\/svg)/.test(type) && /\bgzip\b/.test(req.headers["accept-encoding"] ?? "")) {
    body = gzipSync(body);
    head["Content-Encoding"] = "gzip";
    head.Vary = "Accept-Encoding";
  }
  res.writeHead(200, head).end(body);
});
await new Promise((resolve) => server.listen(4180, "127.0.0.1", resolve));

const chrome = await chromeLauncher.launch({ chromePath: process.env.CHROME_PATH ?? chromium.executablePath(), chromeFlags: ["--headless=new", "--no-sandbox"] });
const median = (values) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];

const rows = [];
let failed = false;
try {
  for (const page of budget.pages) {
    const runs = [];
    for (let i = 0; i < 3; i++) {
      const result = await lighthouse(`http://127.0.0.1:4180${page}`, { port: chrome.port, output: "json", logLevel: "error", onlyCategories: ["performance", "accessibility", "best-practices"] });
      const { categories, audits } = result.lhr;
      runs.push({
        performance: categories.performance.score * 100,
        accessibility: categories.accessibility.score * 100,
        bestPractices: categories["best-practices"].score * 100,
        lcp: audits["largest-contentful-paint"].numericValue,
        tbt: audits["total-blocking-time"].numericValue,
        cls: audits["cumulative-layout-shift"].numericValue,
      });
    }
    const m = Object.fromEntries(Object.keys(runs[0]).map((key) => [key, median(runs.map((run) => run[key]))]));
    const misses = [];
    if (m.performance < budget.minPerformance) misses.push(`performance ${m.performance.toFixed(0)} < ${budget.minPerformance}`);
    if (m.accessibility < budget.minAccessibility) misses.push(`accessibility ${m.accessibility.toFixed(0)} < ${budget.minAccessibility}`);
    if (m.bestPractices < budget.minBestPractices) misses.push(`best practices ${m.bestPractices.toFixed(0)} < ${budget.minBestPractices}`);
    if (m.lcp > budget.maxLcpMs) misses.push(`LCP ${m.lcp.toFixed(0)} ms > ${budget.maxLcpMs} ms`);
    if (m.tbt > budget.maxTbtMs) misses.push(`TBT ${m.tbt.toFixed(0)} ms > ${budget.maxTbtMs} ms`);
    if (m.cls > budget.maxCls) misses.push(`CLS ${m.cls.toFixed(3)} > ${budget.maxCls}`);
    for (const miss of misses) console.error(`::error::${page}: ${miss} (median of 3 Lighthouse runs)`);
    failed ||= misses.length > 0;
    rows.push(`| ${page} | ${m.performance.toFixed(0)} | ${m.accessibility.toFixed(0)} | ${m.bestPractices.toFixed(0)} | ${(m.lcp / 1000).toFixed(2)} s | ${m.tbt.toFixed(0)} ms | ${m.cls.toFixed(3)} | ${misses.length ? "**over**" : "ok"} |`);
  }
} finally {
  await chrome.kill();
  server.close();
}

const table = ["| Page | Performance | Accessibility | Best practices | LCP | TBT | CLS | |", "|---|---:|---:|---:|---:|---:|---:|---|", ...rows].join("\n");
console.log(table);
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Lighthouse (mobile, median of 3)\n\n${table}\n`);
if (failed) process.exit(1);
