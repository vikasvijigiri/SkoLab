// Serves a built web app (dist/) with the production response headers and
// forwards API paths to a local gateway, so the app and the API share one
// origin. Used by the Preview workflow (.github/workflows/preview.yml) to
// put a branch's whole stack behind one temporary public URL. No packages.
//
//   node scripts/preview-server.mjs --port 4180 --api http://127.0.0.1:8080
import { createReadStream, readFileSync, statSync } from "node:fs";
import { createServer, request } from "node:http";
import { extname, join, normalize, resolve } from "node:path";
import { parseArgs } from "node:util";

const { values } = parseArgs({
  options: {
    port: { type: "string", default: "4180" },
    api: { type: "string", default: "http://127.0.0.1:8080" },
    dist: { type: "string", default: new URL("../dist", import.meta.url).pathname },
  },
});

const root = resolve(values.dist);
const api = new URL(values.api);
const headers = JSON.parse(readFileSync(new URL("../security-headers.json", import.meta.url), "utf8"));
delete headers["//"];

const API_PREFIXES = ["/api/", "/gateway-health", "/readyz"];
const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".json": "application/json",
  ".map": "application/json",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
};

function proxy(req, res) {
  const upstream = request(
    { host: api.hostname, port: api.port, method: req.method, path: req.url, headers: { ...req.headers, host: api.host } },
    (answer) => {
      res.writeHead(answer.statusCode ?? 502, answer.headers);
      answer.pipe(res);
    },
  );
  upstream.on("error", () => {
    if (!res.headersSent) res.writeHead(502, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "The preview API is not running", code: "preview_api_down" }));
  });
  req.pipe(upstream);
}

function file(path) {
  try {
    return statSync(path).isFile() ? path : null;
  } catch {
    return null;
  }
}

createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://preview");
  if (API_PREFIXES.some((prefix) => url.pathname.startsWith(prefix))) return proxy(req, res);
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405).end();
    return;
  }
  const requested = normalize(join(root, decodeURIComponent(url.pathname)));
  const target = (requested.startsWith(root) && file(requested)) || join(root, "index.html");
  const immutable = url.pathname.startsWith("/assets/");
  res.writeHead(200, {
    ...headers,
    "Content-Type": TYPES[extname(target)] ?? "application/octet-stream",
    "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-cache",
  });
  if (req.method === "HEAD") return res.end();
  createReadStream(target).pipe(res);
}).listen(Number(values.port), "127.0.0.1", () => {
  console.log(`Preview on http://127.0.0.1:${values.port} (API ${api.origin})`);
});
