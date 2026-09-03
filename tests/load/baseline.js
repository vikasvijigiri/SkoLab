/**
 * baseline.js — Baseline load test at 1× expected DAU (Daily Active Users).
 *
 * Purpose: Establish p50, p95, p99 latency and error rate benchmarks per endpoint
 *          under normal operating load. Run this before every release.
 *
 * Thresholds:
 *   - p95 response time < 2000ms
 *   - Error rate < 1%
 *
 * Usage:
 *   BASE_URL=https://api.your-domain.com k6 run baseline.js
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8000";

// Custom metrics per endpoint
const authorSearchLatency = new Trend("author_search_latency", true);
const paperSearchLatency = new Trend("paper_search_latency", true);
const healthLatency = new Trend("health_latency", true);
const errorRate = new Rate("error_rate");
const requestCount = new Counter("total_requests");

export const options = {
  // 1× DAU: assume 500 concurrent users over a 5-minute steady-state window
  stages: [
    { duration: "1m", target: 50 },   // Warm-up: ramp to 50 VUs
    { duration: "5m", target: 50 },   // Steady state: hold at 50 VUs (≈ 500 DAU equivalent)
    { duration: "30s", target: 0 },   // Ramp-down
  ],
  thresholds: {
    http_req_duration: ["p(95)<2000"],       // P95 must be under 2s
    http_req_failed: ["rate<0.01"],          // Error rate under 1%
    author_search_latency: ["p(95)<2000"],
    paper_search_latency: ["p(95)<2000"],
  },
};

export default function () {
  const headers = {
    "X-Request-ID": `load-test-${Math.random().toString(36).substring(7)}`,
    "User-Agent": "k6-load-test/1.0",
    "Accept": "application/json",
  };

  // ── Health check ──────────────────────────────────────────────────────────
  {
    const res = http.get(`${BASE_URL}/health`, { headers });
    const ok = check(res, { "health OK": (r) => r.status === 200 });
    healthLatency.add(res.timings.duration);
    errorRate.add(!ok);
    requestCount.add(1);
  }

  sleep(0.5);

  // ── Author search ─────────────────────────────────────────────────────────
  // Path + params verified against app.openapi()["paths"] (2026-09-03):
  //   GET /api/v1/search_author  — required "name"; optional "id", "focus".
  //   There is NO /api/v1/authors/search route.
  const names = ["Yoshua Bengio", "Jennifer Doudna", "Andre Geim", "Michael E. Mann"];
  const name = names[Math.floor(Math.random() * names.length)];
  {
    const res = http.get(
      `${BASE_URL}/api/v1/search_author?name=${encodeURIComponent(name)}`,
      { headers }
    );
    const ok = check(res, {
      "author search 200": (r) => r.status === 200,
      "author search returns a profile": (r) => {
        try {
          const b = JSON.parse(r.body);
          return b && typeof b === "object" && "display_name" in b;
        } catch { return false; }
      },
    });
    authorSearchLatency.add(res.timings.duration);
    errorRate.add(!ok);
    requestCount.add(1);
  }

  sleep(1);

  // ── Paper analysis (LLM-backed expensive read) ────────────────────────────
  // GET /api/v1/analyze_paper — required "title". (No /api/v1/papers/search route.)
  {
    const titles = ["Attention Is All You Need", "A Method for Stochastic Optimization",
                    "Deep Residual Learning for Image Recognition", "Generative Adversarial Nets"];
    const title = titles[Math.floor(Math.random() * titles.length)];
    const res = http.get(
      `${BASE_URL}/api/v1/analyze_paper?title=${encodeURIComponent(title)}`,
      { headers }
    );
    const ok = check(res, {
      "paper analyze 200 or 429": (r) => r.status === 200 || r.status === 429,
    });
    if (res.status === 200) {
      paperSearchLatency.add(res.timings.duration);
    }
    errorRate.add(res.status >= 500);
    requestCount.add(1);
  }

  sleep(2);
}
