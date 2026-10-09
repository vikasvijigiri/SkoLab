// Every-URL load test (k6) for the endpoint metrics report. Runs against CI
// staging with OpenTelemetry export on (.github/workflows/endpoint-metrics.yml),
// never against production. Each URL gets its own steady arrival rate, so
// the per-URL numbers that Prometheus and Grafana show come from a known
// load. Signed in as the monitoring account; everything it creates is
// removed in teardown.
//
// Not driven here, by design: DELETE /api/v1/users/{id} (deletes the
// account) and POST /hooks/sentry (Sentry-signed). Routes that need a second
// account to succeed (role changes, ownership transfer, member removal) run
// on their refusal path; the Hurl scenarios cover their success paths.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const uid = __ENV.USER_ID;
const minutes = Number(__ENV.MINUTES || 3);
const scale = Number(__ENV.SCALE || 1);
const conflicts = new Counter('document_version_conflicts');

const auth = () => ({ Authorization: `Bearer ${__ENV.TOKEN}`, 'Content-Type': 'application/json' });
const req = (method, path, name, body, extra = {}) =>
  http.request(method, `${base}${path}`, body === undefined ? null : JSON.stringify(body), {
    headers: { ...auth(), ...extra },
    tags: { name },
    responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
  });

// name -> [requests per second, function]
const endpoints = {
  templates_list: [10, (d) => ok(req('GET', '/api/v1/templates', 'GET /api/v1/templates'), 200)],
  templates_get: [10, (d) => ok(req('GET', `/api/v1/templates/${pick(d.templates)}`, 'GET /api/v1/templates/:id'), 200)],
  document_get: [10, (d) => ok(req('GET', `/api/v1/workspaces/${pick(d.docs)}/document`, 'GET /api/v1/workspaces/:id/document'), 200)],
  document_put: [5, saveDocument],
  workspaces_list: [5, () => ok(req('GET', '/api/v1/workspaces?page_size=20', 'GET /api/v1/workspaces'), 200)],
  workspaces_create: [2, () => ok(req('POST', '/api/v1/workspaces', 'POST /api/v1/workspaces', { title: 'k6 replayed' }, { 'Idempotency-Key': `k6-${uid}` }), 201)],
  workspace_get: [5, (d) => ok(req('GET', `/api/v1/workspaces/${d.ws}`, 'GET /api/v1/workspaces/:id'), 200)],
  workspace_rename: [2, (d) => ok(req('PATCH', `/api/v1/workspaces/${d.ws}`, 'PATCH /api/v1/workspaces/:id', { title: 'k6 metrics workspace' }), 200)],
  members_list: [5, (d) => ok(req('GET', `/api/v1/workspaces/${d.ws}/members`, 'GET /api/v1/workspaces/:id/members'), 200)],
  member_role_refused: [1, (d) => ok(req('PATCH', `/api/v1/workspaces/${d.ws}/members/${uid}`, 'PATCH /api/v1/workspaces/:id/members/:user_id', { role: 'viewer' }), 409)],
  member_remove_refused: [1, (d) => ok(req('DELETE', `/api/v1/workspaces/${d.ws}/members/${uid}`, 'DELETE /api/v1/workspaces/:id/members/:user_id'), 409)],
  owner_transfer_refused: [1, (d) => ok(req('POST', `/api/v1/workspaces/${d.ws}/owner`, 'POST /api/v1/workspaces/:id/owner', { user_id: uid }), 409)],
  invite_options: [3, (d) => ok(req('GET', `/api/v1/workspaces/${d.ws}/invite-options`, 'GET /api/v1/workspaces/:id/invite-options'), 200)],
  invites_list: [2, (d) => ok(req('GET', `/api/v1/workspaces/${d.ws}/invites`, 'GET /api/v1/workspaces/:id/invites'), 200)],
  invite_lifecycle: [1, inviteLifecycle],
  ticket: [3, (d) => ok(req('POST', `/api/v1/ws/colab/${d.ws}/tickets`, 'POST /api/v1/ws/colab/:workspace_id/tickets'), 201)],
  profile_sync: [2, () => ok(req('POST', '/api/v1/users/profile/sync', 'POST /api/v1/users/profile/sync', { uid, name: 'SkoLab monitoring' }), 200)],
  liveness: [2, () => ok(http.get(`${base}/gateway-health`, { tags: { name: 'GET /gateway-health' } }), 200)],
  readiness: [2, () => ok(http.get(`${base}/readyz`, { tags: { name: 'GET /readyz' } }), 200)],
};

const scenarios = {};
for (const [name, [rate]] of Object.entries(endpoints)) {
  scenarios[name] = {
    executor: 'constant-arrival-rate', exec: 'hit', env: { ENDPOINT: name },
    rate: Math.max(1, Math.round(rate * scale)), timeUnit: '1s',
    duration: `${minutes}m`, preAllocatedVUs: 5, maxVUs: 40,
  };
}
// Compiles run one at a time per user (by design), back to back.
scenarios.compile = { executor: 'constant-vus', exec: 'compile', vus: 1, duration: `${minutes}m` };

// Latency gates for the editor's new endpoints; every other URL gets a
// loose gate so k6 reports its own client-side numbers per URL too.
const URLS = [
  'GET /api/v1/templates', 'GET /api/v1/templates/:id', 'GET /api/v1/workspaces/:id/document',
  'PUT /api/v1/workspaces/:id/document', 'GET /api/v1/workspaces', 'POST /api/v1/workspaces',
  'GET /api/v1/workspaces/:id', 'PATCH /api/v1/workspaces/:id', 'DELETE /api/v1/workspaces/:id',
  'GET /api/v1/workspaces/:id/members', 'PATCH /api/v1/workspaces/:id/members/:user_id',
  'DELETE /api/v1/workspaces/:id/members/:user_id', 'POST /api/v1/workspaces/:id/owner',
  'GET /api/v1/workspaces/:id/invite-options', 'GET /api/v1/workspaces/:id/invites',
  'POST /api/v1/workspaces/:id/invites', 'POST /api/v1/invites/preview', 'POST /api/v1/invites/accept',
  'DELETE /api/v1/workspaces/:id/invites/:invite_id', 'POST /api/v1/ws/colab/:workspace_id/tickets',
  'POST /api/v1/users/profile/sync', 'POST /api/v1/colab/compile', 'GET /gateway-health', 'GET /readyz',
];
const gates = {
  'GET /api/v1/templates': 'p(95)<150',
  'GET /api/v1/templates/:id': 'p(95)<150',
  'GET /api/v1/workspaces/:id/document': 'p(95)<250',
  'PUT /api/v1/workspaces/:id/document': 'p(95)<400',
  'POST /api/v1/colab/compile': 'p(95)<15000',
};
const thresholds = { checks: ['rate>0.99'], http_req_failed: ['rate<0.01'] };
for (const url of URLS) {
  thresholds[`http_req_duration{name:${url}}`] = [gates[url] || 'p(95)<2000'];
}

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ['count', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function hit(d) {
  endpoints[__ENV.ENDPOINT][1](d);
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function ok(res, status) {
  return check(res, { [`status is ${status}`]: (r) => r.status === status });
}

function saveDocument(d) {
  const ws = pick(d.docs);
  const current = req('GET', `/api/v1/workspaces/${ws}/document`, 'GET /api/v1/workspaces/:id/document');
  if (!ok(current, 200)) return;
  const doc = current.json();
  const source = d.source.replace('\\begin{document}', `\\begin{document}\n% k6 edit ${Date.now()}`);
  const res = req('PUT', `/api/v1/workspaces/${ws}/document`, 'PUT /api/v1/workspaces/:id/document',
    { source, base_version: doc.version });
  if (res.status === 409) conflicts.add(1); // another iteration saved first: correct, and counted
  check(res, { 'saved or conflict': (r) => r.status === 200 || r.status === 409 });
}

function inviteLifecycle(d) {
  const created = req('POST', `/api/v1/workspaces/${d.ws}/invites`, 'POST /api/v1/workspaces/:id/invites',
    { role: 'viewer', expires_in_hours: 24, max_uses: 1 });
  if (!ok(created, 201)) return;
  const invite = created.json();
  ok(req('POST', '/api/v1/invites/preview', 'POST /api/v1/invites/preview', { token: invite.token }), 200);
  ok(req('POST', '/api/v1/invites/accept', 'POST /api/v1/invites/accept', { token: invite.token }), 200);
  ok(req('DELETE', `/api/v1/workspaces/${d.ws}/invites/${invite.id}`, 'DELETE /api/v1/workspaces/:id/invites/:invite_id'), 204);
}

export function compile(d) {
  const res = req('POST', '/api/v1/colab/compile', 'POST /api/v1/colab/compile', { latex_source: d.compileSource, engine: 'pdflatex' });
  check(res, { 'compiled': (r) => r.status === 200 && r.json('status') === 'compiled' });
}

export function setup() {
  if (!__ENV.TOKEN || !uid) throw new Error('TOKEN and USER_ID are required');
  const templates = req('GET', '/api/v1/templates', 'setup').json('templates').map((t) => t.id);
  const source = req('GET', '/api/v1/templates/ams-article', 'setup').json('source');
  const ws = req('POST', '/api/v1/workspaces', 'setup', { title: 'k6 metrics workspace' }).json('id');
  const docs = [];
  for (let i = 0; i < 8; i++) {
    const id = req('POST', '/api/v1/workspaces', 'setup', { title: `k6 document ${i}` }).json('id');
    req('PUT', `/api/v1/workspaces/${id}/document`, 'setup', { source, base_version: 0, template_id: 'ams-article' });
    docs.push(id);
  }
  const replayed = req('POST', '/api/v1/workspaces', 'setup', { title: 'k6 replayed' }, { 'Idempotency-Key': `k6-${uid}` }).json('id');
  const compileSource = '\\documentclass{article}\\begin{document}Hello from the load test, $e^{i\\pi}+1=0$.\\end{document}';
  return { templates, source, ws, docs, replayed, compileSource };
}

export function teardown(d) {
  for (const id of [d.ws, d.replayed, ...d.docs]) {
    ok(req('DELETE', `/api/v1/workspaces/${id}`, 'DELETE /api/v1/workspaces/:id'), 204);
  }
}

export function handleSummary(data) {
  const rows = [];
  for (const [key, metric] of Object.entries(data.metrics)) {
    const m = key.match(/^http_req_duration\{name:(.+)\}$/);
    if (!m) continue;
    rows.push({ url: m[1], ...metric.values });
  }
  const out = { stdout: JSON.stringify({ k6: { rows, checks: data.metrics.checks.values, failed: data.metrics.http_req_failed.values, conflicts: (data.metrics.document_version_conflicts || { values: { count: 0 } }).values.count } }) + '\n' };
  if (__ENV.SUMMARY_JSON) out[__ENV.SUMMARY_JSON] = JSON.stringify(data, null, 2);
  return out;
}
