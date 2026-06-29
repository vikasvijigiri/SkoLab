/**
 * soak.js — Soak test: 2× load sustained for 2 hours.
 *
 * Purpose: Detect memory leaks, SQLAlchemy session exhaustion, connection pool starvation,
 *          and database index degradation under sustained load.
 *
 * Expected result: error rate < 1%, p95 stable (not growing over time), memory stable.
 *
 * Usage:
 *   BASE_URL=https://api.your-domain.com k6 run soak.js
 *   # Full 2-hour run:
 *   BASE_URL=https://api.your-domain.com DURATION=2h k6 run soak.js
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend, Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8000";
const SOAK_DURATION = __ENV.DURATION || "2h";

const errorRate = new Rate("error_rate");
const soakLatency = new Trend("soak_latency", true);
const totalRequests = new Counter("total_soak_requests");

export const options = {
  stages: [
    { duration: "5m",          target: 50 },          // Warm-up
    { duration: SOAK_DURATION, target: 50 },           // Soak at 2× baseline (50 VUs)
    { duration: "5m",          target: 0  },           // Cool-down
  ],
  thresholds: {
    http_req_duration: ["p(95)<3000"],  // P95 must stay under 3s throughout
    http_req_failed: ["rate<0.01"],     // Error rate under 1% for the full duration
    error_rate: ["rate<0.01"],
  },
};

export default function () {
  const headers = {
    "X-Request-ID": `soak-${__VU}-${__ITER}`,
    "User-Agent": "k6-soak-test/1.0",
  };

  // Rotate through a realistic mix of endpoints to stress all code paths
  const scenario = __ITER % 4;

  if (scenario === 0) {
    // Author search
    const terms = ["reinforcement learning", "protein folding", "quantum mechanics", "climate model"];
    const q = terms[__VU % terms.length];
    const res = http.get(
      `${BASE_URL}/api/v1/authors/search?query=${encodeURIComponent(q)}`,
      { headers }
    );
    check(res, { "author search ok": (r) => r.status === 200 || r.status === 429 });
    soakLatency.add(res.timings.duration);
    errorRate.add(res.status >= 500);
    totalRequests.add(1);
    sleep(1.5);

  } else if (scenario === 1) {
    // Health check — should always be fast regardless of load
    const res = http.get(`${BASE_URL}/health`, { headers });
    check(res, { "health ok": (r) => r.status === 200 });
    errorRate.add(res.status >= 500);
    totalRequests.add(1);
    sleep(0.5);

  } else if (scenario === 2) {
    // Paper search
    const res = http.get(
      `${BASE_URL}/api/v1/papers/search?query=neural+network+architecture`,
      { headers }
    );
    check(res, { "paper search ok": (r) => r.status === 200 || r.status === 429 });
    soakLatency.add(res.timings.duration);
    errorRate.add(res.status >= 500);
    totalRequests.add(1);
    sleep(2);

  } else {
    // Root discovery endpoint (lightest possible request)
    const res = http.get(`${BASE_URL}/`, { headers });
    check(res, { "root ok": (r) => r.status === 200 });
    errorRate.add(res.status >= 500);
    totalRequests.add(1);
    sleep(0.5);
  }
}

export function handleSummary(data) {
  // Print a structured summary to stdout for archiving
  const p95 = data.metrics["http_req_duration"].values["p(95)"];
  const errRate = data.metrics["http_req_failed"] ? data.metrics["http_req_failed"].values["rate"] : 0;
  const total = data.metrics["total_soak_requests"] ? data.metrics["total_soak_requests"].values["count"] : 0;

  console.log("\n=== SOAK TEST SUMMARY ===");
  console.log(`Total requests  : ${total}`);
  console.log(`P95 latency     : ${p95?.toFixed(0) ?? "N/A"} ms`);
  console.log(`Error rate      : ${(errRate * 100)?.toFixed(2) ?? "N/A"}%`);
  console.log("\nSave this output to: /docs/load-test-results-YYYY-MM-DD.md");
  console.log("========================\n");

  return {};
}
