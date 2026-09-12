import { EventEmitter } from "node:events";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

// Next's dev server attaches one close listener per Playwright request. The
// default limit is a diagnostic threshold, not a runtime limit; raising it in
// this test-only child prevents noisy false-positive warnings without changing
// production listener behavior.
EventEmitter.defaultMaxListeners = 0;

const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
const preload = fileURLToPath(new URL("./playwright-listener-preload.cjs", import.meta.url));
const child = spawn(process.execPath, ["--require", preload, nextBin, "dev"], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    // Only the test runner can opt into this deterministic local session.
    ...(process.env.PLAYWRIGHT_AUTH === "1" ? { NEXT_PUBLIC_PLAYWRIGHT_AUTH: "1" } : {}),
  },
  stdio: "inherit",
});

const forward = (signal) => child.kill(signal);
process.once("SIGINT", () => forward("SIGINT"));
process.once("SIGTERM", () => forward("SIGTERM"));
child.once("exit", (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  else process.exit(code ?? 1);
});
