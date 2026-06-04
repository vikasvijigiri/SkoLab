/**
 * spike.js — Spike test: instant 5× burst to simulate push-notification blast or viral event.
 *
 * Purpose: Verify the system handles a sudden massive traffic spike without cascading failures.
 *          Rate limiter should absorb the excess. Circuit breakers must not trip on legitimate traffic.
 *
 * Usage:
 *   BASE_URL=https://api.your-domain.com k6 run spike.js
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8000";

const errorRate = new Rate("error_rate");
const spikeLatency = new Trend("spike_latency", true);

export const options = {
  stages: [
    { duration: "30s", target: 10  },  // Pre-spike: establish baseline (10 VUs)
    { duration: "10s", target: 250 },  // SPIKE: instant jump to 5× peak (250 VUs)
    { duration: "2m",  target: 250 },  // Hold at spike for 2 minutes
    { duration: "30s", target: 10  },  // Recovery: drop back to baseline
    { duration: "1m",  target: 10  },  // Observe recovery behaviour
    { duration: "30s", target: 0   },  // Ramp-down
  ],
  thresholds: {
    // During a spike, the rate limiter will return 429s — that is correct behaviour.
    // We track 5xx errors (server failures) separately from 429s (rate-limited).
    error_rate: ["rate<0.05"],         // Allow up to 5% errors (most will be 429s)
    http_req_duration: ["p(95)<8000"], // P95 can be high during spike; track for analysis
  },
};

export default function () {
  const headers = {
    "X-Request-ID": `spike-${__VU}-${__ITER}`,
    "User-Agent": "k6-spike-test/1.0",
  };

  const res = http.get(
    `${BASE_URL}/api/v1/authors/search?query=quantum`,
    { headers }
  );

  // 429 is expected and correct during a spike — do NOT count as an error
  check(res, {
    "not a 5xx server error": (r) => r.status < 500,
    "rate limited or ok": (r) => r.status === 200 || r.status === 429,
  });

  spikeLatency.add(res.timings.duration);
  errorRate.add(res.status >= 500);  // Only count real server errors

  sleep(0.2);
}
