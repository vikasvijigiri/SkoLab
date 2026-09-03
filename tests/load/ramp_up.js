/**
 * ramp_up.js — Ramp-up test: linear 0 → 2× peak concurrent users over 10 minutes.
 *
 * Purpose: Find the error inflection point — the CCU at which error rate exceeds 1%
 *          or P95 latency crosses 2s. Document this ceiling in /docs/scaling-decision.md.
 *
 * Usage:
 *   BASE_URL=https://api.your-domain.com k6 run ramp_up.js
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8000";

const p95Latency = new Trend("p95_latency", true);
const errorRate = new Rate("error_rate");

export const options = {
  // Linear ramp: 0 → 100 VUs over 10 minutes, then hold for 3 min, then down
  stages: [
    { duration: "10m", target: 100 },  // Linear ramp-up: 0 → 2× peak (100 VUs ≈ peak DAU)
    { duration: "3m",  target: 100 },  // Hold at peak to observe sustained behaviour
    { duration: "1m",  target: 0   },  // Ramp-down
  ],
  thresholds: {
    // These are informational — we expect them to breach near the ceiling
    http_req_duration: ["p(95)<5000"],
    http_req_failed: ["rate<0.05"],
  },
};

export default function () {
  const headers = {
    "X-Request-ID": `ramp-${__VU}-${__ITER}`,
    "User-Agent": "k6-ramp-test/1.0",
  };

  // Mixed workload to simulate realistic traffic distribution
  const roll = Math.random();

  if (roll < 0.5) {
    // 50%: Author search (most common operation).
    // GET /api/v1/search_author?name=... — verified route (no /authors/search).
    const searchNames = ["Geoffrey Hinton", "Emmanuelle Charpentier", "Kip Thorne", "Frances Arnold"];
    const nm = searchNames[__VU % searchNames.length];
    const res = http.get(
      `${BASE_URL}/api/v1/search_author?name=${encodeURIComponent(nm)}`,
      { headers }
    );
    const ok = check(res, { "status 200 or 429": (r) => r.status === 200 || r.status === 429 });
    p95Latency.add(res.timings.duration);
    errorRate.add(res.status >= 500);

  } else if (roll < 0.8) {
    // 30%: Health check
    const res = http.get(`${BASE_URL}/health`, { headers });
    check(res, { "health ok": (r) => r.status === 200 });
    errorRate.add(res.status >= 500);

  } else {
    // 20%: Personalised daily feed (more expensive — recommendation pipeline).
    // GET /api/v1/daily_feed?author_id=... (no /papers/search route).
    const res = http.get(
      `${BASE_URL}/api/v1/daily_feed?author_id=A5052223708`,
      { headers }
    );
    const ok = check(res, { "feed status ok": (r) => r.status === 200 || r.status === 429 });
    p95Latency.add(res.timings.duration);
    errorRate.add(res.status >= 500);
  }

  sleep(1);
}
