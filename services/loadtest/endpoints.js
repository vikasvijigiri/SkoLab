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
// The same fixtures the Hurl scenarios upload and import.
const dotPng = open('../qa/hurl/fixtures/dot.png', 'b');
const projectZip = open('../qa/hurl/fixtures/project.zip', 'b');

const auth = () => ({ Authorization: `Bearer ${__ENV.TOKEN}`, 'Content-Type': 'application/json' });
const req = (method, path, name, body, extra = {}) =>
  http.request(method, `${base}${path}`, body === undefined ? null : JSON.stringify(body), {
    headers: { ...auth(), ...extra },
    tags: { name },
    responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
  });

// A multipart request: k6 sets the boundary, so no JSON Content-Type.
const upload = (path, name, fields) =>
  http.request('POST', `${base}${path}`, fields, {
    headers: { Authorization: `Bearer ${__ENV.TOKEN}` },
    tags: { name },
    responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
  });
const unique = () => `${__VU}-${__ITER}-${Date.now()}`;

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
  files_list: [5, (d) => ok(req('GET', `/api/v1/workspaces/${d.proj}/files`, 'GET /api/v1/workspaces/:id/files'), 200)],
  file_get: [5, (d) => ok(req('GET', `/api/v1/workspaces/${d.proj}/files/${d.textFile}`, 'GET /api/v1/workspaces/:id/files/:file_id'), 200)],
  file_raw: [5, (d) => ok(req('GET', `/api/v1/workspaces/${d.proj}/files/${d.image}/raw`, 'GET /api/v1/workspaces/:id/files/:file_id/raw'), 200)],
  file_save: [3, saveFile],
  file_lifecycle: [2, fileLifecycle],
  file_upload: [2, uploadLifecycle],
  archive: [2, (d) => ok(req('GET', `/api/v1/workspaces/${d.proj}/archive`, 'GET /api/v1/workspaces/:id/archive'), 200)],
  output_pdf: [2, (d) => ok(req('GET', `/api/v1/workspaces/${d.proj}/output.pdf`, 'GET /api/v1/workspaces/:id/output.pdf'), 200)],
  import_project: [1, importProject],
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
  'GET /api/v1/workspaces/:id/files', 'POST /api/v1/workspaces/:id/files', 'POST /api/v1/workspaces/:id/files/upload',
  'GET /api/v1/workspaces/:id/files/:file_id', 'GET /api/v1/workspaces/:id/files/:file_id/raw',
  'PUT /api/v1/workspaces/:id/files/:file_id', 'PATCH /api/v1/workspaces/:id/files/:file_id',
  'DELETE /api/v1/workspaces/:id/files/:file_id', 'GET /api/v1/workspaces/:id/archive',
  'POST /api/v1/workspaces/:id/compile', 'GET /api/v1/workspaces/:id/output.pdf', 'POST /api/v1/workspaces/import',
];
const gates = {
  'GET /api/v1/templates': 'p(95)<150',
  'GET /api/v1/templates/:id': 'p(95)<150',
  'GET /api/v1/workspaces/:id/document': 'p(95)<250',
  'PUT /api/v1/workspaces/:id/document': 'p(95)<400',
  'POST /api/v1/colab/compile': 'p(95)<15000',
  'POST /api/v1/workspaces/:id/compile': 'p(95)<15000',
  'GET /api/v1/workspaces/:id/files': 'p(95)<250',
  'PUT /api/v1/workspaces/:id/files/:file_id': 'p(95)<400',
  'POST /api/v1/workspaces/:id/files/upload': 'p(95)<1000',
  'GET /api/v1/workspaces/:id/archive': 'p(95)<1000',
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

function saveFile(d) {
  const current = req('GET', `/api/v1/workspaces/${d.proj}/files/${d.textFile}`, 'GET /api/v1/workspaces/:id/files/:file_id');
  if (!ok(current, 200)) return;
  const res = req('PUT', `/api/v1/workspaces/${d.proj}/files/${d.textFile}`, 'PUT /api/v1/workspaces/:id/files/:file_id',
    { content: `The introduction, k6 edit ${Date.now()}.`, base_version: current.json('version') });
  if (res.status === 409) conflicts.add(1);
  check(res, { 'saved or conflict': (r) => r.status === 200 || r.status === 409 });
}

function fileLifecycle(d) {
  const created = req('POST', `/api/v1/workspaces/${d.proj}/files`, 'POST /api/v1/workspaces/:id/files',
    { path: `scratch/${unique()}.tex`, kind: 'text', content: 'Scratch.' });
  if (!ok(created, 201)) return;
  const id = created.json('id');
  ok(req('PATCH', `/api/v1/workspaces/${d.proj}/files/${id}`, 'PATCH /api/v1/workspaces/:id/files/:file_id', { path: `moved/${unique()}.tex` }), 200);
  ok(req('DELETE', `/api/v1/workspaces/${d.proj}/files/${id}`, 'DELETE /api/v1/workspaces/:id/files/:file_id'), 204);
}

function uploadLifecycle(d) {
  const res = upload(`/api/v1/workspaces/${d.proj}/files/upload`, 'POST /api/v1/workspaces/:id/files/upload',
    { folder: 'uploads', file: http.file(dotPng, `${unique()}.png`, 'image/png') });
  if (!ok(res, 201)) return;
  ok(req('DELETE', `/api/v1/workspaces/${d.proj}/files/${res.json('files.0.id')}`, 'DELETE /api/v1/workspaces/:id/files/:file_id'), 204);
}

function importProject() {
  const res = upload('/api/v1/workspaces/import', 'POST /api/v1/workspaces/import',
    { title: 'k6 imported project', file: http.file(projectZip, 'project.zip', 'application/zip') });
  if (!ok(res, 201)) return;
  ok(req('DELETE', `/api/v1/workspaces/${res.json('id')}`, 'DELETE /api/v1/workspaces/:id'), 204);
}

// One compile at a time per user: the single-file and whole-project
// compiles take turns.
export function compile(d) {
  const res = __ITER % 2 === 0
    ? req('POST', '/api/v1/colab/compile', 'POST /api/v1/colab/compile', { latex_source: d.compileSource, engine: 'pdflatex' })
    : req('POST', `/api/v1/workspaces/${d.proj}/compile`, 'POST /api/v1/workspaces/:id/compile', {});
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
  // A multi-file project: \input, an image and a BibTeX bibliography.
  const proj = req('POST', '/api/v1/workspaces', 'setup', { title: 'k6 project' }).json('id');
  req('PUT', `/api/v1/workspaces/${proj}/document`, 'setup', {
    source: '\\documentclass{article}\\usepackage{graphicx}\\begin{document}\\input{chapters/intro}\n' +
      '\\includegraphics[width=1cm]{figures/dot.png} \\cite{knuth1984}\\bibliographystyle{plain}\\bibliography{refs}\\end{document}\n',
    base_version: 0,
  });
  const textFile = req('POST', `/api/v1/workspaces/${proj}/files`, 'setup', { path: 'chapters/intro.tex', kind: 'text', content: 'The introduction.' }).json('id');
  req('POST', `/api/v1/workspaces/${proj}/files`, 'setup', { path: 'refs.bib', kind: 'text', content: '@book{knuth1984, author={Donald Knuth}, title={The TeXbook}, publisher={Addison-Wesley}, year={1984}}\n' });
  const image = upload(`/api/v1/workspaces/${proj}/files/upload`, 'setup', { folder: 'figures', file: http.file(dotPng, 'dot.png', 'image/png') }).json('files.0.id');
  const compiled = req('POST', `/api/v1/workspaces/${proj}/compile`, 'setup', {});
  if (compiled.json('status') !== 'compiled') throw new Error(`project compile failed: ${compiled.body}`);
  return { templates, source, ws, docs, replayed, compileSource, proj, textFile, image };
}

export function teardown(d) {
  for (const id of [d.ws, d.replayed, d.proj, ...d.docs]) {
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
