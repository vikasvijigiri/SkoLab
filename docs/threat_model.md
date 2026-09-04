# Centralized Threat Model & Data Flow Document

This document maps all entry points, trust boundaries, data flows, malicious actor profiles, and corresponding mitigations for the SkoLab system.

---

## 1. System Architecture & Trust Boundaries

SkoLab operates across three distinct trust zones:
1. **Untrusted Public Zone:** Client mobile devices (Android) and public internet actors connecting to external domains.
2. **Perimeter Zone (FastAPI Backend API):** The public entry point for the SkoLab application, protected by WAF rules and rate limiters.
3. **Trusted Private Zone:** PostgreSQL databases, Firebase cloud document repositories, and SRE monitoring services.

```mermaid
graph TD
    subgraph Untrusted Public Zone
        Client[Android Mobile Client]
        Scraper[Automated Scraper / Bot]
        Attacker[Malicious Injector]
    end

    subgraph Perimeter Zone
        WAF[Cloudflare WAF / Rate Limiter]
        Backend[FastAPI Application Backend]
    end

    subgraph Trusted Private Zone
        DB[(PostgreSQL Database)]
        Firestore[(Firebase Firestore)]
        LLM[Groq & OpenRouter APIs]
    end

    Client -->|HTTPS / W3C Trace| WAF
    Scraper -.->|Dropped / Restricted| WAF
    Attacker -.->|Blocked / Sanitized| WAF
    WAF --> Backend
    Backend -->|Private Network / SSL| DB
    Backend -->|Firebase SDK / Auth| Firestore
    Backend -->|HTTPS + API Tokens| LLM
```

---

## 2. Threat Actor Profiles & Malicious Scenarios

| Threat Actor | Motivation | Attack Vector | Mitigation |
|---|---|---|---|
| **Data Scraper** | Steal copyrighted/licensed academic data from OpenAlex. | High-frequency queries to search endpoints. | Per-IP rate limiting at the Go API gateway (`internal/middleware`). The Python per-process token bucket and User-Agent blocking were removed as redundant/counterproductive. |
| **Script Kiddie / Attacker** | Disrupt service / Access other user accounts. | Session spoofing, SQL injection, API abuse. | Firebase ID-token verification (`auth.VerifyUser` / `get_verified_user`) + parameterised ORM queries. The `X-Device-Signature` HMAC check and the `/metrics` admin-IP gate were removed (`docs/plans/2026-09-04-retire-python-infra.md`) — the signature was keyed by a server-only secret and protected no real route. |
| **Prompt Injector** | Hijack LLM credits / Extract system prompts. | Prompt stuffing in search/chat fields. | XML tagging isolation + Max character constraints. |
| **Direct DB Tamperer** | Modify database values (e.g., complete quests/get credits). | Modifying SQLite on device or executing SQL directly on compromised DB. | Record integrity HMAC-SHA256 signatures validated on read. |

---

## 3. Threat Mitigation Summary (STRIDE Map)

* **Spoofing:** Write requests authenticate with a Firebase ID token (`Authorization: Bearer`), verified at the gateway (`auth.VerifyUser`) and in Python (`get_verified_user`). The former `X-Device-Signature` HMAC check was removed — it was keyed by `settings.database_encryption_key`, a server-only secret no client could hold, and guarded no real route.
* **Tampering:** Crucial quest state tables verify records against cryptographic HMAC-SHA256 signatures on database read operations.
* **Repudiation:** Telemetry logs record masked IDs, endpoints, and HTTP response codes within centralized W3C tracing spans.
* **Information Disclosure:** `GET /metrics` and its admin-IP / SRE-token gate were removed from the Python service; request metrics move to the Go gateway. `GET /ai_status` is public system metadata.
* **Denial of Service:** Per-IP rate limiting at the Go API gateway (`internal/middleware.NewRateLimiter`) throttles bursts in front of both services; the Python per-process token bucket was retired.
* **Elevation of Privilege:** Strict segregation between public and private Docker networks prevents direct external access to Postgres nodes on port 5432.
