import http from 'k6/http';
import { check, fail } from 'k6';
import encoding from 'k6/encoding';
import secrets from 'k6/secrets';
import { WebSocket } from 'k6/websockets';
import { setTimeout, clearTimeout } from 'k6/timers';

// One iteration is one complete user journey, never a load test.
export const options = {
  vus: 1, iterations: 1,
  thresholds: { checks: ['rate==1'] },
  systemTags: ['name', 'method', 'status', 'check', 'group', 'scenario'],
};

const gateway = __ENV.SKOLAB_GATEWAY_URL || 'https://skolab-gateway.onrender.com';
const authBase = __ENV.SKOLAB_FIREBASE_AUTH_URL || 'https://identitytoolkit.googleapis.com';
if (authBase !== 'https://identitytoolkit.googleapis.com' && !/^http:\/\/127\.0\.0\.1:\d+$/.test(authBase)) {
  throw new Error('Firebase authentication override is allowed only for local mock tests');
}
const socketBase = __ENV.SKOLAB_WS_URL || gateway.replace(/^http/, 'ws');
const latex = '\\documentclass{article}\n\\begin{document}\nSkoLab monitoring check.\n\\end{document}';

function requireCheck(value, name, predicate) {
  if (!check(value, { [name]: predicate })) fail(`Journey failed at: ${name}`);
}

function json(response) {
  try { return response.json(); } catch (_) { return {}; }
}

function params(name, token) {
  return {
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    tags: { name }, timeout: '25s', redirects: 0,
  };
}

export default async function () {
  const apiKey = await secrets.get('skolab-firebase-api-key');
  const email = await secrets.get('skolab-monitor-email');
  const password = await secrets.get('skolab-monitor-password');
  const workspace = await secrets.get('skolab-monitor-workspace');
  const auth = http.post(
    `${authBase}/v1/accounts:signInWithPassword?key=${encodeURIComponent(apiKey)}`,
    JSON.stringify({ email, password, returnSecureToken: true }), params('firebase-login'),
  );
  const identity = json(auth);
  requireCheck(auth, 'fresh Firebase login', r => r.status === 200 && !!identity.idToken && !!identity.localId);
  const token = identity.idToken;

  const profile = http.post(`${gateway}/api/v1/users/profile/sync`,
    JSON.stringify({ uid: identity.localId, name: 'SkoLab monitoring' }), params('profile-sync', token));
  requireCheck(profile, 'monitoring identity synchronized', r => r.status === 200 && json(r).status === 'synced');

  const compile = http.post(`${gateway}/api/v1/colab/compile`,
    JSON.stringify({ latex_source: latex, engine: 'pdflatex' }), params('compile', token));
  const result = json(compile);
  let pdf = '';
  try { pdf = encoding.b64decode(result.pdf_base64 || '', 'std', 's'); } catch (_) { /* assertion below */ }
  requireCheck(compile, 'valid PDF compiled through gateway', r =>
    r.status === 200 && result.status === 'compiled' && pdf.startsWith('%PDF-') && pdf.includes('%%EOF'));

  function ticket() {
    const response = http.post(`${gateway}/api/v1/ws/colab/${encodeURIComponent(workspace)}/tickets`,
      '{}', params('workspace-ticket', token));
    const body = json(response);
    requireCheck(response, 'authorized workspace ticket issued', r => r.status === 201 && !!body.ticket);
    return body.ticket;
  }
  // Ticket URLs and Firebase credentials must not become metric labels or logs.
  const wsBase = socketBase;
  const receiverTicket = ticket();
  const senderTicket = ticket();
  await new Promise(resolve => {
    let receiver, sender, opened = 0, received = false, finished = false;
    const marker = JSON.stringify({ type: 'monitoring-ping', nonce: `${Date.now()}-${Math.random()}` });
    const timeout = setTimeout(() => finish(), 8000);
    function finish() {
      if (finished) return;
      finished = true;
      clearTimeout(timeout);
      if (sender) sender.close();
      if (receiver) receiver.close();
      check(received, { 'collaboration message delivered between peers': value => value === true });
      resolve();
    }
    function onOpen() {
      opened += 1;
      if (opened === 2) sender.send(marker);
    }
    receiver = new WebSocket(`${wsBase}/ws/colab/${encodeURIComponent(workspace)}?ticket=${encodeURIComponent(receiverTicket)}`,
      [], { tags: { name: 'collaboration-receiver' } });
    sender = new WebSocket(`${wsBase}/ws/colab/${encodeURIComponent(workspace)}?ticket=${encodeURIComponent(senderTicket)}`,
      [], { tags: { name: 'collaboration-sender' } });
    receiver.onopen = onOpen;
    sender.onopen = onOpen;
    receiver.onmessage = event => {
      if (String(event.data).split('\n').includes(marker)) { received = true; finish(); }
    };
    receiver.onerror = finish;
    sender.onerror = finish;
    receiver.onclose = () => { if (!finished) finish(); };
    sender.onclose = () => { if (!finished) finish(); };
  });
}
