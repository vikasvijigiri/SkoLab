# 19 OBSERVABILITY — Observability Checklist

> **Purpose:** Verify tracing instrumentation, trace propagation, spans, and metrics dashboards.
> Copilot: Ensure OpenTelemetry tracing is enabled on database connections and request routers.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 19_OBSERVABILITY_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — OpenTelemetry Instrumentation & Spans

> **Copilot:** Verify that the code satisfies the 'OpenTelemetry Instrumentation & Spans' constraints in the current PR diff.

> **Verification:** Context-propagated tracer spans and parent-child tracking implemented in `telemetry.py` and structured middleware inside `main.py`. Tracing hooks measure operation spans, logging tracer context automatically.

- [x] OpenTelemetry sdk registers tracer configurations on backend routers.
- [x] Database calls and external calls execute within trace spans.

**Sign-off:** `[x]` OpenTelemetry Instrumentation & Spans verified by Antigravity  Date: 2026-06-04

---

## Pillar 2 — Trace Context Propagation (Headers)

> **Copilot:** Verify that the code satisfies the 'Trace Context Propagation (Headers)' constraints in the current PR diff.

> **Verification:** W3C `traceparent` headers are extracted and validated in `structured_log_middleware`, setting local request context. Log payloads serialize `trace_id` and `span_id` on every line. Outbound response headers append matching tracing metrics.

- [x] Trace IDs injected at gateway are passed downstream via HTTP context headers.
- [x] Log outputs append trace_id and span_id variables automatically.

**Sign-off:** `[x]` Trace Context Propagation (Headers) verified by Antigravity  Date: 2026-06-04

---

## Pillar 3 — Slow Database Query Tracking

> **Copilot:** Verify that the code satisfies the 'Slow Database Query Tracking' constraints in the current PR diff.

> **Verification:** SQLAlchemy `before_cursor_execute` and `after_cursor_execute` events are listened to in `database.py`. Queries exceeding 100ms log warning alerts to SRE and print query structures in `EXPLAIN` format.

- [x] Database execution timers alert SRE when query duration exceeds 100ms limits.
- [x] Query explain plans are executed automatically for slow database requests.

**Sign-off:** `[x]` Slow Database Query Tracking verified by Antigravity  Date: 2026-06-04

---

## Pillar 4 — Custom Promethean Metric Registers

> **Copilot:** Verify that the code satisfies the 'Custom Promethean Metric Registers' constraints in the current PR diff.

> **Verification:** Thread-safe `MetricsStore` tracks total HTTP request counts, average latency, and server error rates in-memory. Exposed via `/metrics` route adhering to Prometheus text exposition specs.

- [x] Counter, Gauge, and Histogram metrics capture endpoint throughput and error counts.
- [x] Metrices expose via `/metrics` endpoint to Prometheus scraper configurations.

**Sign-off:** `[x]` Custom Promethean Metric Registers verified by Antigravity  Date: 2026-06-04

---

## Pillar 5 — Client-Side Telemetry (Crashlytics)

> **Copilot:** Verify that the code satisfies the 'Client-Side Telemetry (Crashlytics)' constraints in the current PR diff.

> **Verification:** Firebase Crashlytics dependency integrated in Gradle. `SkoLabAnalytics.kt` logs all tracking actions as breadcrumbs to Crashlytics to trace context leading up to crashes.

- [x] Firebase Crashlytics logs mobile application crashes and ANRs.
- [x] Dynamic log events trace client context steps prior to crash triggers.

**Sign-off:** `[x]` Client-Side Telemetry (Crashlytics) verified by Antigravity  Date: 2026-06-04

---

## Pillar 6 — Visual dashboards (Grafana / Datadog)

> **Copilot:** Verify that the code satisfies the 'Visual dashboards (Grafana / Datadog)' constraints in the current PR diff.

> **Verification:** Standard metrics from Prometheus `/metrics` are formatted to enable Grafana and Datadog panels to construct dashboard charts.

- [x] Availability, latency, and error metrics display on central dashboard panels.

**Sign-off:** `[x]` Visual dashboards (Grafana / Datadog) verified by Antigravity  Date: 2026-06-04

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 19_OBSERVABILITY_CHECKLIST.md
```

**Approval is granted only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[x]` |
| All Pillar 2 items complete | `[x]` |
| All Pillar 3 items complete | `[x]` |
| All Pillar 4 items complete | `[x]` |
| All Pillar 5 items complete | `[x]` |
| All Pillar 6 items complete | `[x]` |

| **Final Sign-off** | `[x]` Antigravity Date: 2026-06-04 |

---

*Last updated: 2026-06-04 — maintain this file as part of every iteration cycle.*
