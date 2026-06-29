# consolidated_checklist.md — SkoLab Engineering & Product Checklist

A unified, simplified compilation of SkoLab's requirements, architectural standards, quality gates, and launch readiness checks.

---

## 1. Product & Requirements (Launch Readiness)
- `[ ]` **Core Metrics Verification:** Verify H-Index, i10-Index, Works count, and Citations load correctly on author search.
- `[ ]` **mDNS Auto-Discovery:** Android client must locate local FastAPI backend over local network without manual IP configuration.
- `[ ]` **In-Memory Cache Latency:** Cache lookup (`/author_suggestions`) must achieve sub-10ms warm response latencies.
- `[ ]` **Fallback Resilience:** OpenAlex queries must gracefully fall back to PostgreSQL local indexes if API/Firestore is rate-limited.

---

## 2. UI/UX & Design System
- `[ ]` **Contrast Compliance (WCAG AA):** Ensure text and borders meet minimum contrast ratio requirements (4.5:1 for normal text).
- `[ ]` **Design Token Theme Alignment:** Verify light theme uses HSL-tailored slate-gray-blue (`#F4F0E8`) backgrounds and elevated white (`#FEFCF7`) surfaces.
- `[ ]` **Radar Chart Integrity:** Verify innovation dimensions (8-axes) chart disruption, novelty, and future impact scales accurately.
- `[ ]` **Micro-animations & State Retention:** autocompletes, Connect toggles, and draft message popups must run smooth animations and retain state.

---

## 3. Architecture & Bounded Contexts
- `[ ]` **Domain Separation:** Go and Python services must keep package directories partitioned by domain context (author, user, quest) rather than layers (handlers, schemas, services).
- `[ ]` **Go Internal Package Security:** Private packages must live under `/internal/` to block external modules from accessing private routines.
- `[ ]` **Data Partitioning:** Hot data (sessions, connections, telemetry logs) stored in PostgreSQL; cold/large records (works arrays, LLM response caches) stored in Firestore.

---

## 4. Code Quality, Security & Privacy
- `[ ]` **PII Masking & Safety:** Logging systems must scan and redact email addresses, tokens, and phone numbers in production.
- `[ ]` **HMAC Device Signatures:** Write requests (POST, PUT, DELETE) must validate timestamped device signatures to prevent replay attacks.
- `[ ]` **GDPR Right to Be Forgotten:** User account deletions must execute complete purging of preferences and anonymization of logging data.
- `[ ]` **SRE Kill Switches:** SRE flags must immediately toggle off non-health paths under load/attack without service downtime.

---

## 5. Testing & SRE Operations
- `[ ]` **Automated Test Coverage:** Run unit tests and API gateway checks prior to merges.
- `[ ]` **Load Testing Benchmarks:** Validate gateways using `k6` to verify p95 latency stays under 2000ms.
- `[ ]` **Observability Dashboards:** Log streams must integrate with Loki/Promtail and trace requests via traceparents.
- `[ ]` **Disk Monitoring Alerts:** Disk capacity monitor task must fire critical alerts to logs when disk capacity crosses 80%.
