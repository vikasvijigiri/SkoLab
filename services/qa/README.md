# QA suite

Production-level testing for the SkoLab API, built on established
open-source tools rather than custom test code:

| Layer | Tool | What it proves | Where it runs |
|---|---|---|---|
| Contract | [OpenAPI 3.1](../backend-go/api/openapi.yaml) | One written definition of every endpoint, status code and body | Source of truth for the tools below |
| Contract fuzzing | [Schemathesis](https://github.com/schemathesis/schemathesis) | Generated requests per operation; responses match the spec, no 5xx, auth enforced, undocumented methods refused | CI staging, every PR and merge |
| End-to-end scenarios | [Hurl](https://github.com/Orange-OpenSource/hurl) (`hurl/*.hurl`) | Real multi-user flows: owner, editor, viewer, outsider, a deleted account; invites, roles, isolation, error contract, security headers | CI staging, and production after every release |
| Real-time | [k6](https://github.com/grafana/k6) ([journey](../observability/journey.js)) | Sign-in, compile, tickets, two WebSocket peers receiving each other's messages | CI staging; Grafana probes every 30 min |
| Security (DAST) | [OWASP ZAP](https://github.com/zaproxy/zaproxy) API scan | Injection, headers, misconfiguration across every operation | Weekly on staging (`security-dast.yml`) |
| Load | k6 ([`loadtest/`](../loadtest/api.js)) | Throughput and latency gates | Weekly on staging (`load-test.yml`) |
| Unit and integration | Go `testing` (race, coverage floor), pytest (coverage floor) | Handlers, stores, auth, migrations against real Postgres | CI, every PR |

The one custom piece is [`users.py`](users.py): it creates temporary,
email-verified Firebase test users, which no tool provides, and deletes them
afterwards through the API's own account deletion, so a run leaves nothing
behind. Users from an aborted run (named `qa-…`, older than two hours) are
removed by the next run.

## Running

Everything runs in GitHub Actions; nothing needs installing locally.

- **Every PR and merge:** the `staging` job in `ci.yml` boots the production
  image and runs smoke checks, Hurl, the WebSocket journey and Schemathesis.
- **Every release:** `release.yml` runs the Hurl scenarios against
  production with temporary users.
- **Reports:** each run attaches `qa-reports-*` (Hurl JUnit and HTML,
  Schemathesis JUnit); the ZAP run attaches `zap-report`.

To run the scenarios against any deployment by hand (needs
`FIREBASE_SERVICE_ACCOUNT` and `SKOLAB_FIREBASE_API_KEY`, plus Hurl):

```sh
bash services/qa/e2e.sh https://skolab-api.onrender.com qa-reports
```

## Adding a test

- **New endpoint:** describe it in `openapi.yaml` (Schemathesis and ZAP pick
  it up), then add the flow to a `hurl/*.hurl` file.
- **New rule** (who may do what): add the allowed and the refused request to
  `hurl/04_sharing_and_roles.hurl`, asserting the error `code`.
- Hurl files run in name order; `06_account_deletion.hurl` must stay last.
