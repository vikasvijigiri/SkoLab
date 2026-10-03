// SkoLab API load test (k6). Runs against CI staging -- the production image
// on a GitHub runner with its own Postgres -- never against production:
// Render's free instance is shared with real users and would only measure
// its own 0.1 CPU. See .github/workflows/load-test.yml.
//
// Signed in as the monitoring account, it ramps an open (arrival-rate) load
// over the authenticated read paths users hit most, so a slower handler or
// query shows up as a latency or error-rate regression. Writes are limited
// to the idempotent profile sync, so repeated runs leave the data unchanged.
import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const workspace = __ENV.WORKSPACE_ID;
const uid = __ENV.USER_ID;
const peak = Number(__ENV.PEAK_RPS || 200);
const params = (kind) => ({
  headers: { Authorization: `Bearer ${__ENV.TOKEN}`, 'Content-Type': 'application/json' },
  tags: { kind },
});

export const options = {
  scenarios: {
    api: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(peak / 10)),
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 400,
      stages: [
        { target: Math.round(peak / 4), duration: '30s' },
        { target: Math.round(peak / 2), duration: '30s' },
        { target: peak, duration: '60s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    // Regression gates for a GitHub runner (2 vCPU, local Postgres).
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<300'],
    'http_req_duration{kind:write}': ['p(95)<500'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!__ENV.TOKEN || !workspace || !uid) {
    throw new Error('TOKEN, WORKSPACE_ID and USER_ID are required');
  }
}

export default function () {
  const pick = Math.random();
  let res;
  if (pick < 0.35) {
    res = http.get(`${base}/api/v1/workspaces`, params('read'));
  } else if (pick < 0.6) {
    res = http.get(`${base}/api/v1/workspaces/${workspace}/members`, params('read'));
  } else if (pick < 0.8) {
    res = http.get(`${base}/api/v1/workspaces/${workspace}`, params('read'));
  } else if (pick < 0.95) {
    res = http.get(`${base}/api/v1/workspaces/${workspace}/invite-options`, params('read'));
  } else {
    res = http.post(`${base}/api/v1/users/profile/sync`,
      JSON.stringify({ uid, name: 'SkoLab monitoring' }), params('write'));
  }
  check(res, { 'status is 200': (r) => r.status === 200 });
}

function ms(metric, stat) {
  const value = metric && metric.values[stat];
  return value === undefined ? 'n/a' : `${value.toFixed(1)} ms`;
}

export function handleSummary(data) {
  const m = data.metrics;
  const read = m['http_req_duration{kind:read}'];
  const write = m['http_req_duration{kind:write}'];
  const failed = data.root_group && m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) : 'n/a';
  const verdict = Object.values(m).every((x) => !x.thresholds || Object.values(x.thresholds).every((t) => t.ok));
  const report = [
    `## Load test: ${verdict ? 'passed' : 'FAILED'} (peak ${peak} req/s)`,
    '',
    '| Metric | Value |',
    '|---|---|',
    `| Requests | ${m.http_reqs.values.count} (${m.http_reqs.values.rate.toFixed(1)} req/s average) |`,
    `| Failed requests | ${failed}% |`,
    `| Reads p50 / p95 / p99 | ${ms(read, 'med')} / ${ms(read, 'p(95)')} / ${ms(read, 'p(99)')} |`,
    `| Writes p50 / p95 / p99 | ${ms(write, 'med')} / ${ms(write, 'p(95)')} / ${ms(write, 'p(99)')} |`,
    `| Dropped iterations (load not reached) | ${m.dropped_iterations ? m.dropped_iterations.values.count : 0} |`,
    '',
  ].join('\n');
  const out = { stdout: report + '\n' };
  if (__ENV.SUMMARY_FILE) {
    out[__ENV.SUMMARY_FILE] = report;
  }
  return out;
}
